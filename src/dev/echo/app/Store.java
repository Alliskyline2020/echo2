package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * 全部本地数据的读写。四个 JSON 文件，一一对应四类数据。
 *
 * 不上 SQLite：数据量是「一天几十段」，JSON 足够，也方便直接看文件排查。
 * 所有写都是整文件原子覆盖（先写临时再改名）。
 */
public final class Store {

    private static final String TAG = "echo-store";
    /** 进程内共享一个实例：避免每 3 秒重读文件，也避免多实例互相覆盖 */
    private static Store shared;

    private final File dir;
    private final Gson gson = new Gson();

    private List<Model.Session> sessions;
    private List<Model.Todo> todos;
    private List<Model.MemoryItem> memories;
    private List<Model.ActivityBlock> activities;
    private Model.Settings settings;
    private final java.util.Map<String, String> digests;

    public static String todayKey() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    public synchronized String getDigest(String dateKey) {
        return digests.get(dateKey);
    }

    public synchronized void saveDigest(String dateKey, String text) {
        digests.put(dateKey, text);
        write("digests.json", digests);
    }

    public static synchronized Store get(Context context) {
        if (shared == null) shared = new Store(context.getApplicationContext());
        return shared;
    }

    private Store(Context context) {
        dir = new File(context.getFilesDir(), "data");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "mkdir failed");
        sessions = read("sessions.json", new TypeToken<List<Model.Session>>() { }.getType());
        todos = read("todos.json", new TypeToken<List<Model.Todo>>() { }.getType());
        memories = read("memories.json", new TypeToken<List<Model.MemoryItem>>() { }.getType());
        activities = read("activities.json", new TypeToken<List<Model.ActivityBlock>>() { }.getType());
        // 自愈迁移：删除旧格式活动（没有 title 字段的是旧版生成的）
        if (activities != null) {
            boolean changed = activities.removeIf(a -> a.title == null || a.title.isEmpty());
            if (changed) Log.i("echo-store", "清理了旧格式活动");
        }
        settings = readObject("settings.json", Model.Settings.class);
        if (settings == null) settings = new Model.Settings();
        digests = readMap("digests.json",
                new TypeToken<java.util.Map<String, String>>() { }.getType());
        // 启动恢复：上次进程若在转写中途被杀，段会永远卡在 transcribing。
        // 进程首个 Store 创建时把这类段放回待整理队列。
        boolean recovered = false;
        for (Model.Session session : sessions) {
            for (Model.Segment segment : session.segments) {
                if ("transcribing".equals(segment.state)) {
                    segment.state = "pending";
                    recovered = true;
                }
            }
        }
        if (recovered) {
            Log.i(TAG, "恢复了上次中断的转写段");
            write("sessions.json", sessions);
        }
    }

    private <T> T readObject(String name, Class<T> type) {
        File file = new File(dir, name);
        if (!file.isFile()) return null;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, type);
        } catch (Exception error) {
            Log.w(TAG, "read " + name + " failed", error);
            return null;
        }
    }

    private java.util.Map<String, String> readMap(String name, Type type) {
        File file = new File(dir, name);
        if (!file.isFile()) return new java.util.HashMap<>();
        try {
            java.util.Map<String, String> value = gson.fromJson(new java.io.FileReader(file), type);
            return value != null ? value : new java.util.HashMap<>();
        } catch (Throwable error) {
            Log.w(TAG, "read failed " + name, error);
            return new java.util.HashMap<>();
        }
    }

    private <T> List<T> read(String name, Type type) {
        File file = new File(dir, name);
        if (!file.isFile()) return new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<T> value = gson.fromJson(reader, type);
            return value != null ? value : new ArrayList<>();
        } catch (Exception error) {
            Log.w(TAG, "read " + name + " failed", error);
            return new ArrayList<>();
        }
    }

    private <T> void write(String name, T value) {
        File file = new File(dir, name);
        File temp = new File(dir, name + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            gson.toJson(value, writer);
        } catch (IOException error) {
            Log.w(TAG, "write " + name + " failed", error);
            return;
        }
        if (!temp.renameTo(file)) {
            Log.w(TAG, "rename failed: " + name);
        }
    }

    /* ------------------------------------------------------------- sessions */

    public synchronized List<Model.Session> sessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized Model.Session currentSession() {
        for (Model.Session session : sessions) {
            if (session.endedAt == 0) return session;
        }
        return null;
    }

    public synchronized Model.Session startSession() {
        Model.Session session = new Model.Session();
        session.id = UUID.randomUUID().toString();
        session.startedAt = System.currentTimeMillis();
        sessions.add(session);
        write("sessions.json", sessions);
        return session;
    }

    /** 通话转写等"已完成的段"直接入库 */
    public synchronized void appendReadySegment(String sessionId, Model.Segment segment) {
        for (Model.Session session : sessions) {
            if (session.id.equals(sessionId)) {
                session.segments.add(segment);
                break;
            }
        }
        write("sessions.json", sessions);
    }

    public synchronized Model.Segment appendSegment(String sessionId, File wav, long startedAt,
                                                    long durationMs, String speaker) {
        Model.Segment segment = new Model.Segment();
        segment.id = UUID.randomUUID().toString();
        segment.speaker = speaker == null ? "unknown" : speaker;
        segment.path = wav.getAbsolutePath();
        segment.startedAt = startedAt;
        segment.durationMs = durationMs;
        for (Model.Session session : sessions) {
            if (session.id.equals(sessionId)) {
                session.segments.add(segment);
                break;
            }
        }
        write("sessions.json", sessions);
        return segment;
    }

    public synchronized void endSession() {
        for (Model.Session session : sessions) {
            if (session.endedAt == 0) session.endedAt = System.currentTimeMillis();
        }
        write("sessions.json", sessions);
    }

    public synchronized void updateSegment(Model.Segment segment) {
        write("sessions.json", sessions);
    }

    /** 当前待整理段数 */
    public synchronized int pendingCount() {
        int count = 0;
        for (Model.Session session : sessions) {
            for (Model.Segment segment : session.segments) {
                if (isPending(segment)) count++;
            }
        }
        return count;
    }

    public synchronized long pendingAudioMs() {
        long total = 0;
        for (Model.Session session : sessions) {
            for (Model.Segment segment : session.segments) {
                if (isPending(segment)) total += segment.durationMs;
            }
        }
        return total;
    }

    /**
     * 待整理 = 还没转写，或上次失败且未超过重试上限、冷却已过。
     * 与 echo-src 一致：最多重试 3 次，失败后冷却 5 分钟再试。
     */
    /**
     * 待整理 = 未转写，或失败后到达退避时间。
     * 转写失败对用户不可见：内部按 5 分钟起步、逐次翻倍、封顶 30 分钟自动重试。
     * （冻结、内存压力这类瞬时故障一两次就过；真正坏掉的段每 30 分钟试一次，开销可忽略）
     */
    public static boolean isPending(Model.Segment segment) {
        if (segment.path == null || segment.path.isEmpty()) return false;
        String status = segment.state;
        if ("pending".equals(status)) return true;
        if ("failed".equals(status)) {
            long backoff = Math.min(30 * 60_000L, RETRY_BASE_MS << Math.min(segment.attempts, 5));
            return System.currentTimeMillis() - segment.lastFailedAt >= backoff;
        }
        return false;
    }

    private static final long RETRY_BASE_MS = 5 * 60_000L;

    public synchronized long todayRecordedMs() {
        long start = startOfToday();
        long total = 0;
        for (Model.Session session : sessions) {
            for (Model.Segment segment : session.segments) {
                if (segment.startedAt >= start) total += segment.durationMs;
            }
        }
        return total;
    }

    private static long startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /* ---------------------------------------------------------------- todos */

    public synchronized List<Model.Todo> todos() {
        return new ArrayList<>(todos);
    }

    public synchronized void addTodo(Model.Todo todo) {
        todos.add(todo);
        write("todos.json", todos);
    }

    public synchronized void updateTodo(Model.Todo todo) {
        write("todos.json", todos);
    }

    public synchronized Model.Todo findTodoByTask(String task) {
        for (Model.Todo todo : todos) {
            if (todo.task.equals(task)) return todo;
        }
        return null;
    }

    /* -------------------------------------------------------------- memories */

    public synchronized List<Model.MemoryItem> memories() {
        return new ArrayList<>(memories);
    }

        public synchronized List<Model.ActivityBlock> activities() {
        return new ArrayList<>(activities);
    }

    public synchronized void addActivity(Model.ActivityBlock block) {
        activities.add(block);
        write("activities.json", activities);
    }

public synchronized void addMemory(Model.MemoryItem item) {
        memories.add(item);
        write("memories.json", memories);
    }

    /** 同一归一化主题上当前生效的记忆（冲突检测用） */
    public synchronized Model.MemoryItem findActiveBySubject(String subject) {
        for (Model.MemoryItem item : memories) {
            if ("active".equals(item.status) && item.subject != null && item.subject.equals(subject)) {
                return item;
            }
        }
        return null;
    }

    public synchronized void updateMemory(Model.MemoryItem item) {
        write("memories.json", memories);
    }

    /* -------------------------------------------------------------- settings */

    public synchronized Model.Settings settings() {
        return settings;
    }

    public synchronized void saveSettings(Model.Settings value) {
        settings = value;
        write("settings.json", settings);
    }

    public synchronized void clearAll() {
        sessions.clear();
        todos.clear();
        memories.clear();
        write("sessions.json", sessions);
        write("todos.json", todos);
        write("memories.json", memories);
    }
}
