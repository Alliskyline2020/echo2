package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * P2 每日整合（夜间 / 充电时）：
 * ① 生成今日回顾（三五行人话，通知栏静默送达）
 * ② 记忆合并：让 Gemma 找出重复/被取代的旧记忆，合并到规范主题上。
 *    合并只认「模型回出的、且本机真实存在的 subject」，对不上的一律忽略——宁可不并，不能并错。
 */
public final class Consolidator {

    private static final String TAG = "echo-consolidate";

    private Consolidator() {
    }

    /** 今天是否已有回顾 */
    public static boolean hasToday(Context context) {
        return Store.get(context).getDigest(Store.todayKey()) != null;
    }

    /** 生成今日回顾 + 顺手做记忆合并。没有可回顾的内容时静默跳过。 */
    public static void runToday(Context context, GemmaEngine engine, GemmaEngine.Progress progress) throws Exception {
        Store store = Store.get(context);
        String dateKey = Store.todayKey();
        if (store.getDigest(dateKey) != null) return;

        // ① 收集今天的素材
        long dayStart = dayStart();
        StringBuilder transcripts = new StringBuilder();
        int segCount = 0;
        for (Model.Session session : store.sessions()) {
            if (session.startedAt < dayStart) continue;
            for (Model.Segment segment : session.segments) {
                if (segment.transcript == null || segment.transcript.isEmpty()) continue;
                transcripts.append("- ").append(segment.transcript, 0,
                        Math.min(120, segment.transcript.length())).append('\n');
                segCount++;
            }
        }
        List<Model.Todo> todos = store.todos();
        int openToday = 0, doneToday = 0, overdue = 0;
        StringBuilder todoLines = new StringBuilder();
        for (Model.Todo todo : todos) {
            if (todo.dueAt > 0 && todo.dueAt < System.currentTimeMillis() && !todo.done) overdue++;
            if (todo.done && todo.dueAt >= dayStart) doneToday++;
            if (!todo.done && todo.createdAt >= dayStart) {
                openToday++;
                todoLines.append("- ").append(todo.task).append('\n');
            }
        }
        if (segCount == 0 && openToday == 0 && doneToday == 0) return; // 今天没用回声

        if (progress != null) progress.onState("正在生成今日回顾…");
        StringBuilder brief = new StringBuilder();
        brief.append("日期：").append(dateKey).append('\n');
        brief.append("今天的转写片段（").append(segCount).append(" 段）：\n").append(
                transcripts.length() > 3600 ? transcripts.substring(transcripts.length() - 3600) : transcripts);
        if (todoLines.length() > 0) {
            brief.append("\n今天新增的待办：\n").append(todoLines);
        }
        // 素材话题（L3）：环境里听到/看到的内容，织进回顾故事线
        StringBuilder materialLines = new StringBuilder();
        int materialCount = 0;
        for (Model.MemoryItem item : store.memories()) {
            if (!"material".equals(item.kind) || !"active".equals(item.status)) continue;
            if (item.createdAt < dayStart) continue;
            materialLines.append("- ").append(item.subject.replace("素材.", ""))
                    .append("：").append(item.value).append('\n');
            if (++materialCount >= 8) break;
        }
        if (materialCount > 0) {
            brief.append("\n今天周围环境里出现的信息素材：\n").append(materialLines);
        }
        brief.append("\n逾期未做：").append(overdue).append(" 件，已完成：").append(doneToday).append(" 件\n");
        brief.append("请用中文写 3-5 行的今日回顾，像便签一样简洁：记了什么、待办概况、一两句贴心提醒。不要标题和格式符号。");

        String digest = engine.summarize(context, brief.toString(), progress);
        if (digest != null && !digest.trim().isEmpty()) {
            store.saveDigest(dateKey, digest.trim());
            Notifier.notifyDigest(context, digest.trim().replace('\n', ' '));
        }

        // ② 记忆夜间合并（有模型在手，顺手做掉）
        consolidateMemories(context, engine, progress);
    }

    /** 找重复记忆并合并到规范 subject 上（严格校验，防模型乱并） */
    static void consolidateMemories(Context context, GemmaEngine engine, GemmaEngine.Progress progress) {
        try {
            Store store = Store.get(context);
            List<Model.MemoryItem> active = new java.util.ArrayList<>();
            for (Model.MemoryItem item : store.memories()) {
                if ("active".equals(item.status)) active.add(item);
            }
            if (active.size() < 4 || active.size() > 30) return; // 太少不用并，太多先不让模型碰

            if (progress != null) progress.onState("正在整理长期记忆…");
            StringBuilder list = new StringBuilder();
            for (Model.MemoryItem item : active) {
                list.append(item.subject).append(" = ").append(item.value)
                        .append("（").append(item.kind).append("）\n");
            }
            String prompt = "下面是用户的长期记忆列表。找出「同一类事被记成了两个不同 subject」的重复项，"
                    + "输出 JSON：{\"merges\":[{\"keep\":\"规范subject\",\"merge\":[\"被合并的subject\"]}]}。"
                    + "只输出确定是重复的；没有就输出 {\"merges\":[]}。最多 10 组。不要编造列表里没有的 subject。\n\n"
                    + list;
            String raw = engine.summarize(context, prompt, progress);
            String json = Pipeline.extractJsonObject(raw == null ? "" : raw);
            if (json == null) return;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray merges = root.getAsJsonArray("merges");
            if (merges == null) return;
            int applied = 0;
            for (JsonElement element : merges) {
                if (applied >= 10) break;
                JsonObject merge = element.getAsJsonObject();
                String keep = merge.has("keep") ? merge.get("keep").getAsString() : "";
                JsonArray mergeList = merge.getAsJsonArray("merge");
                if (keep.isEmpty() || mergeList == null) continue;
                Model.MemoryItem keepItem = findBySubject(active, keep);
                if (keepItem == null) continue; // 模型编造的 keep，忽略
                for (JsonElement m : mergeList) {
                    String subject = m.getAsString();
                    if (subject.equals(keep)) continue;
                    Model.MemoryItem victim = findBySubject(active, subject);
                    if (victim == null || victim.id.equals(keepItem.id)) continue;
                    victim.status = "superseded";
                    victim.supersededBy = keepItem.id;
                    store.updateMemory(victim);
                    applied++;
                }
                // 合并后刷新保留项的确认时间
                keepItem.lastConfirmedAt = System.currentTimeMillis();
                store.updateMemory(keepItem);
            }
            Log.i(TAG, "记忆合并完成：" + applied + " 条被合并");
        } catch (Throwable error) {
            Log.w(TAG, "记忆合并跳过: " + error);
        }
    }

    private static Model.MemoryItem findBySubject(List<Model.MemoryItem> items, String subject) {
        for (Model.MemoryItem item : items) {
            if (item.subject.equals(subject) && "active".equals(item.status)) return item;
        }
        return null;
    }

    private static long dayStart() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
