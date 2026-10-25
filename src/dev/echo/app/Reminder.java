package dev.echo.app;

/**
 * 待办提醒分级（阈值与 Web 版 shared/memory/reminder.ts 完全一致）：
 *  - overdue：已过截止时间
 *  - due-soon：24 小时内到期
 *  - stale-high：没有截止时间的高优先级事项，搁置超过 24 小时
 */
public final class Reminder {

    public static final String NONE = "none";
    public static final String OVERDUE = "overdue";
    public static final String DUE_SOON = "due-soon";
    public static final String STALE_HIGH = "stale-high";

    private static final long HOUR = 3_600_000L;
    private static final long SOON_WINDOW_MS = 24 * HOUR;
    private static final long STALE_HIGH_MS = 24 * HOUR;

    public static String decide(Model.Todo todo, long now) {
        if (todo.done) return NONE;
        if (todo.dueAt > 0) {
            if (todo.dueAt < now) return OVERDUE;
            if (todo.dueAt - now <= SOON_WINDOW_MS) return DUE_SOON;
            return NONE;
        }
        if ("high".equals(todo.priority) && now - todo.createdAt >= STALE_HIGH_MS) {
            return STALE_HIGH;
        }
        return NONE;
    }
}
