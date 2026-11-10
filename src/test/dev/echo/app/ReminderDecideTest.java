package dev.echo.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReminderDecideTest {

    private Model.Todo todo(String task, long dueAt, String priority) {
        Model.Todo t = new Model.Todo();
        t.task = task;
        t.dueAt = dueAt;
        t.priority = priority;
        t.done = false;
        return t;
    }

    @Test public void overdueWhenPast() {
        long now = System.currentTimeMillis();
        Model.Todo t = todo("test", now - 3_600_000, "normal"); // 1h ago
        assertEquals("overdue", Reminder.decide(t, now));
    }

    @Test public void dueSoonWhenWithin2Hours() {
        long now = System.currentTimeMillis();
        Model.Todo t = todo("test", now + 3_600_000, "normal"); // 1h from now
        assertEquals("due-soon", Reminder.decide(t, now));
    }

    @Test public void normalWhenFar() {
        long now = System.currentTimeMillis();
        Model.Todo t = todo("test", now + 2 * 86_400_000, "normal"); // 1 day from now
        assertNotEquals("overdue", Reminder.decide(t, now));
        assertNotEquals("due-soon", Reminder.decide(t, now));
    }

    @Test public void noDueAtReturnsNormal() {
        long now = System.currentTimeMillis();
        Model.Todo t = todo("test", 0, "normal");
        assertNotEquals("overdue", Reminder.decide(t, now));
    }
}
