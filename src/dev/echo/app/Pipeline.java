package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一批整理：把当前所有待转写的段跑完，再按会话合并做一次抽取。
 *
 * 和 Web 版（shared/transcribe/schedule.ts + watcher）同一套节奏：
 *  - 抽取按会话合并，一批只调一次模型；
 *  - 录音开始后不抢资源，等停了再跑。
 *
 * 调度策略原样移植：balanced / charging / manual。
 * 原生的好处是能直接读电池（WebView 里拿不到），充电策略这次是真的。
 */
public final class Pipeline {

    private static final String TAG = "echo-pipeline";
    private static final long COOLDOWN_MS = 3 * 60_000L;
    private static final long BATCH_AUDIO_MS = 8 * 60_000L;
    private static final int BATCH_COUNT = 4;
    private static final long MAX_WAIT_MS = 15 * 60_000L;

    public static boolean isCharging(Context context) {
        android.content.Intent battery = context.registerReceiver(
                null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0;
    }

    public static boolean shouldRun(Context context, Store store, boolean recording, String policy, long lastRunAt, long now) {
        if ("manual".equals(policy)) return false;
        if (recording) return false;
        if (store.pendingCount() == 0) return false;
        if (now - lastRunAt < COOLDOWN_MS) return false;
        if (isCharging(context)) return true;
        if (store.pendingAudioMs() >= BATCH_AUDIO_MS) return true;
        if (store.pendingCount() >= BATCH_COUNT) return true;
        return now - oldestPendingAt(store, now) >= MAX_WAIT_MS;
    }

    private static long oldestPendingAt(Store store, long now) {
        long oldest = Long.MAX_VALUE;
        for (Model.Session session : store.sessions()) {
            for (Model.Segment segment : session.segments) {
                if (Store.isPending(segment)) {
                    oldest = Math.min(oldest, segment.startedAt + segment.durationMs);
                }
            }
        }
        return oldest == Long.MAX_VALUE ? now : oldest;
    }

    /**
     * 跑完所有待转写段。返回处理的段数。
     * 每个会话的转写合并后只做一次抽取，待办/记忆在最后合并入库。
     */
    public static int sync(Context context, Store store, GemmaEngine engine,
                           String sessionId, GemmaEngine.Progress progress) throws Exception {
        List<Model.Session> sessions = store.sessions();
        int processed = 0;

        for (Model.Session session : sessions) {
            if (sessionId != null && !session.id.equals(sessionId)) continue;
            StringBuilder merged = null;
            for (Model.Segment segment : session.segments) {
                if (!Store.isPending(segment)) continue;
                if (progress != null) progress.onState("正在转写 " + (segment.durationMs / 1000) + " 秒的录音…");
                segment.state = "transcribing";
                store.updateSegment(segment);

                try {
                    File wav = new File(segment.path);
                    String text = SenseVoiceAsr.modelAvailable(context)
                            ? SenseVoiceAsr.transcribeWav(context, wav)
                            : engine.transcribe(context, wav, progress);
                    // LLM 转写纠错层：只修错字/去重复/规范标点，不改意思。
                    // 只对主人段生效（other/unknown 只存档，不值得花推理）。
                    if ("owner".equals(segment.speaker) && text != null && text.trim().length() > 4) {
                        try {
                            String corrected = engine.refine(context, text, progress);
                            if (corrected != null && !corrected.trim().isEmpty()
                                    && corrected.trim().length() >= text.trim().length() / 3) {
                                segment.rawTranscript = text;
                                text = corrected.trim();
                            }
                        } catch (Throwable refineError) {
                            Log.w(TAG, "转写纠错跳过: " + refineError);
                        }
                    }
                    segment.transcript = text;
                    segment.state = "done";
                    segment.attempts++;
                    store.updateSegment(segment);
                    processed++;
                    // 三层信息全部入库：主人=主线，他人/未识别=素材与受托任务的来源。
                    // 分层规则在抽取 prompt 里执行（主人承诺/受托任务/素材记忆）。
                    if (text != null && !text.trim().isEmpty()) {
                        String who = "owner".equals(segment.speaker) ? "主人"
                                : "other".equals(segment.speaker) ? "他人" : "未识别";
                        merged = merged == null ? new StringBuilder() : merged;
                        merged.append(merged.length() == 0 ? "" : "\n")
                                .append('[').append(TimeCnClock.format(segment.startedAt))
                                .append(']').append('[').append(who).append("] ")
                                .append(text.trim());
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "transcribe failed", error);
                    segment.state = "failed";
                    segment.attempts++;
                    segment.lastFailedAt = System.currentTimeMillis();
                    segment.error = String.valueOf(error.getMessage());
                    store.updateSegment(segment);
                }
            }

            // 一批结束，按会话做一次抽取
            if (merged != null && merged.length() > 0) {
                if (progress != null) progress.onState("正在提取待办和记忆…");
                try {
                    // Gemma E2B 上下文 2048 token：转写太长会直接 INVALID_ARGUMENT。
                    // 保留最近的 2400 字符（约 1900 token），prompt 模板和输出留足余量。
                    String briefing = merged.toString();
                    if (briefing.length() > 2400) {
                        briefing = briefing.substring(briefing.length() - 2400);
                    }
                    String raw = engine.extract(context, briefing, progress);
                    applyExtraction(context, store, session.id, raw);
                } catch (Throwable error) {
                    Log.w(TAG, "extract failed", error);
                }
            }
        }
        return processed;
    }

    /** 解析模型输出的 JSON 并合并进待办/记忆库 */
    static void applyExtraction(Context context, Store store, String sessionId, String raw) {
        String text = raw;
        Log.i(TAG, "extraction in: len=" + text.length()
                + " head=" + text.substring(0, Math.min(60, text.length())).replace('\n', ' ')
                + " tail=" + text.substring(Math.max(0, text.length() - 40)).replace('\n', ' '));
        // 从第一个 { 开始做括号深度扫描，取出第一个完整 JSON 对象。
        // 之前用 lastIndexOf('}') 会在模型输出被 token 上限截断时取到内层括号，解析炸掉。
        String json = extractJsonObject(text);
        if (json == null) {
            Log.w(TAG, "extraction: 没有完整的 JSON 对象（可能被截断），len=" + text.length()
                    + " tail=" + text.substring(Math.max(0, text.length() - 80)).replace('\n', ' '));
            return;
        }
        text = json;
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Throwable error) {
            Log.w(TAG, "extraction parse failed, raw=" + text.substring(0, Math.min(400, text.length())));
            return;
        }
        long now = System.currentTimeMillis();

        JsonArray todoArray = asArray(root.get("todos"));
        if (todoArray != null) {
            for (JsonElement element : todoArray) {
                JsonObject item = asObject(element);
                if (item == null) continue;
                String task = firstString(item, "task", "title", "text", "content");
                String timeText = firstString(item, "time", "timeText", "when", "date");
                String evidence = firstString(item, "evidence");
                // 模型偶尔把内容写进 evidence 而 task 留空——用 evidence 兜底，并清掉说话人标记
                if (task == null || task.trim().isEmpty()) task = evidence;
                if (task == null) continue;
                task = task
                        .replace("【主人】", "").replace("【其他人】", "")
                        .replace("[主人]", "").replace("[其他人]", "").trim();
                // [对方] 前缀是有用信号，保留
                if (task.isEmpty()) continue;
                if (store.findTodoByTask(task) != null) continue;
                // 黑名单：语音助手的提示语/系统播报不是用户的待办
                if (task.contains("逐字转写") || task.contains("这段音频")
                        || task.contains("转写这段") || task.contains("识别结果")) continue;
                Model.Todo todo = new Model.Todo();
                todo.id = UUID.randomUUID().toString();
                todo.task = task;
                todo.timeText = timeText == null ? "" : timeText.trim();
                todo.dueAt = TimeCn.parse(todo.timeText, now);
                String who = firstString(item, "who");
                todo.who = who == null ? "" : who.trim();
                todo.evidence = evidence == null ? "" : evidence
                        .replace("【主人】", "").replace("【其他人】", "")
                        .replace("[主人]", "").replace("[对方]", "")
                        .replace("[其他人]", "").trim();
                String priority = firstString(item, "priority");
                todo.priority = "high".equals(priority) ? "high" : "normal";
                todo.sourceSessionId = sessionId;
                todo.createdAt = now;
                store.addTodo(todo);
                if (todo.dueAt > 0) ReminderAlarm.schedule(context, todo); // P0：说到即排上
            }
        }

        JsonArray memoryArray = asArray(root.get("memories"));
        if (memoryArray != null) {
            for (JsonElement element : memoryArray) {
                String textValue = null;
                if (element.isJsonPrimitive()) {
                    textValue = element.getAsString();
                } else {
                    JsonObject item = asObject(element);
                    if (item != null) textValue = firstString(item, "text", "content", "fact");
                }
                JsonObject memoryObject = asObject(element);
                String kind = memoryObject != null && firstString(memoryObject, "kind") != null
                        ? firstString(memoryObject, "kind") : "fact";
                String subject = memoryObject != null ? firstString(memoryObject, "subject") : textValue;
                String value = memoryObject != null ? firstString(memoryObject, "value") : null;
                // 素材黑名单：语音助手提示语/系统交互不进素材库
                if ("material".equals(kind)) {
                    String check = (subject == null ? "" : subject) + (value == null ? "" : value);
                    if (check.contains("转写") || check.contains("识别") || check.contains("提示")
                            || check.contains("询问主人") || check.contains("音频")) continue;
                }
                if (subject == null || subject.trim().isEmpty()) continue;
                subject = subject.trim().replace(" ", "");
                if (value == null || value.trim().isEmpty()) value = textValue == null ? "" : textValue.trim();
                if (value.isEmpty()) continue;

                float confidence = 0.8f;
                if (memoryObject != null && memoryObject.get("confidence") != null) {
                    try {
                        confidence = memoryObject.get("confidence").getAsFloat();
                    } catch (Throwable ignored) {
                    }
                }
                String evidence = memoryObject != null && firstString(memoryObject, "evidence") != null
                        ? firstString(memoryObject, "evidence") : "";

                // ── 冲突检测的核心：同主题 ──
                Model.MemoryItem existing = store.findActiveBySubject(subject);
                if (existing != null && existing.value.equals(value)) {
                    // 同一件事再说一遍：刷新确认时间，不新增
                    existing.lastConfirmedAt = now;
                    existing.reviewAt = now + REVIEW_TTL_MS;
                    store.updateMemory(existing);
                    continue;
                }
                Model.MemoryItem memory = new Model.MemoryItem();
                memory.id = UUID.randomUUID().toString();
                memory.kind = kind;
                memory.subject = subject;
                memory.value = value;
                memory.confidence = confidence;
                memory.evidence = evidence == null ? "" : evidence.trim();
                memory.sourceSessionId = sessionId;
                memory.createdAt = now;
                memory.lastConfirmedAt = now;
                memory.reviewAt = now + REVIEW_TTL_MS;
                if (existing != null) {
                    // 取值变了 = 用户的情况变了：旧的标记被取代，新的生效
                    existing.status = "superseded";
                    existing.supersededBy = memory.id;
                    store.updateMemory(existing);
                }
                // 写入即嵌向量：搜索时只需算查询向量一次
                try {
                    float[] vec = MemoryEmbedder.embed(context, subject + " " + value, null);
                    memory.embedding = MemoryEmbedder.quantize(vec);
                } catch (Throwable embedError) {
                    Log.w(TAG, "embed failed: " + embedError);
                }
                store.addMemory(memory);
            }
        }
    }

    /** 从第一个 { 起做括号深度扫描（跳过字符串字面量），返回第一个完整 JSON 对象 */
    static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 长期记忆的复核周期：30 天没确认就提醒用户核实 */
    static final long REVIEW_TTL_MS = 30L * 24 * 3_600_000L;

    private static JsonArray asArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        // 模型偶尔把单项写成对象，宽容处理
        if (element.isJsonObject()) {
            JsonArray array = new JsonArray();
            array.add(element);
            return array;
        }
        return null;
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.trim().isEmpty() && !"null".equals(value)) return value;
            }
        }
        return null;
    }

    private static final class TimeCnClock {
        static String format(long timestamp) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);
            return String.format(java.util.Locale.CHINA, "%02d:%02d",
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
        }
    }
}
