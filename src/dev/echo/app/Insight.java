package dev.echo.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 今日洞察（与 Web 版 shared/memory/insight.ts 对齐）。
 * 六类：逾期(100) / 今天要收尾(80) / 重要搁置(60) / 记忆冲突(55) / 记忆复核(40) / 今天还没记(10)。
 */
public final class Insight {

    public static final class Item {
        public String kind;
        public String text;
        public int weight;
        public String refId;
    }

    public static List<Item> build(Store store, long now) {
        List<Item> items = new ArrayList<>();
        List<Model.Todo> open = new ArrayList<>();
        for (Model.Todo todo : store.todos()) {
            if (!todo.done) open.add(todo);
        }

        List<Model.Todo> overdue = new ArrayList<>();
        List<Model.Todo> dueSoon = new ArrayList<>();
        List<Model.Todo> staleHigh = new ArrayList<>();
        for (Model.Todo todo : open) {
            String level = Reminder.decide(todo, now);
            if (Reminder.OVERDUE.equals(level)) overdue.add(todo);
            else if (Reminder.DUE_SOON.equals(level)) dueSoon.add(todo);
            else if (Reminder.STALE_HIGH.equals(level)) staleHigh.add(todo);
        }

        if (!overdue.isEmpty()) {
            overdue.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
            items.add(item("overdue",
                    overdue.size() == 1
                            ? "「" + overdue.get(0).task + "」已经过时间了"
                            : overdue.size() + " 件事已经过时间了，最急的是「" + overdue.get(0).task + "」",
                    100, overdue.get(0).id));
        }
        if (!dueSoon.isEmpty()) {
            dueSoon.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
            items.add(item("due-soon",
                    dueSoon.size() == 1
                            ? "今天要收尾「" + dueSoon.get(0).task + "」"
                            : "今天还有 " + dueSoon.size() + " 件事，最近的是「" + dueSoon.get(0).task + "」",
                    80, dueSoon.get(0).id));
        }
        if (!staleHigh.isEmpty()) {
            items.add(item("stale-high",
                    staleHigh.size() == 1
                            ? "「" + staleHigh.get(0).task + "」重要但一直没动，要不要今天处理"
                            : staleHigh.size() + " 件重要的事一直没动",
                    60, staleHigh.get(0).id));
        }

        // 记忆冲突：同主题上新旧取值不同（最近优先）
        Model.MemoryItem conflictNewer = null;
        Model.MemoryItem conflictOlder = null;
        List<Model.MemoryItem> memories = store.memories();
        for (Model.MemoryItem item : memories) {
            if (!"superseded".equals(item.status) || item.supersededBy == null) continue;
            Model.MemoryItem newer = findById(memories, item.supersededBy);
            if (newer == null) continue;
            if (conflictNewer == null || newer.lastConfirmedAt > conflictNewer.lastConfirmedAt) {
                conflictOlder = item;
                conflictNewer = newer;
            }
        }
        if (conflictNewer != null) {
            items.add(item("memory-conflict",
                    "关于「" + conflictNewer.subject + "」，之前记的是「" + conflictOlder.value
                            + "」，现在是「" + conflictNewer.value + "」",
                    55, conflictNewer.id));
        }

        // 记忆复核：到期没确认的长期记忆
        int reviewCount = 0;
        Model.MemoryItem firstReview = null;
        for (Model.MemoryItem item : memories) {
            if ("active".equals(item.status) && item.reviewAt > 0 && now > item.reviewAt) {
                reviewCount++;
                if (firstReview == null) firstReview = item;
            }
        }
        if (reviewCount > 0) {
            items.add(item("memory-review",
                    reviewCount == 1
                            ? "「" + firstReview.subject + "」还准吗？确认一下我就继续记着"
                            : reviewCount + " 条长期记忆该确认一下了",
                    40, firstReview.id));
        }

        if (store.todayRecordedMs() < 10 * 60_000L) {
            items.add(item("nothing-yet", "今天还几乎没记下什么，想到什么说一句也行", 10, null));
        }

        items.sort((a, b) -> Integer.compare(b.weight, a.weight));
        return items;
    }

    private static Model.MemoryItem findById(List<Model.MemoryItem> memories, String id) {
        for (Model.MemoryItem item : memories) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private static Item item(String kind, String text, int weight, String refId) {
        Item out = new Item();
        out.kind = kind;
        out.text = text;
        out.weight = weight;
        out.refId = refId;
        return out;
    }
}
