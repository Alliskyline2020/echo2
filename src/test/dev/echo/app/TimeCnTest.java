package dev.echo.app;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Calendar;

public class TimeCnTest {

    private static long MIN = 60_000L;
    private static long HOUR = 3_600_000L;
    private static long DAY = 86_400_000L;

    private void assertNear(long actual, long expected, String label) {
        long diff = Math.abs(actual - expected);
        assertTrue(label + ": expected ~" + expected + " but got " + actual + " (diff=" + diff + ")",
                diff < 60_000); // 1 minute tolerance
    }

    @Test public void emptyStringReturnsZero() {
        assertEquals(0, TimeCn.parse(null, 1000));
        assertEquals(0, TimeCn.parse("", 1000));
    }

    @Test public void tomorrowAfternoon3pm() {
        long now = fixedTodayAt(10, 0);
        long result = TimeCn.parse("明天下午三点", now);
        long expected = now + DAY + (15 - 10) * HOUR;
        assertNear(result, expected, "明天下午三点");
    }

    @Test public void todayEvening() {
        long now = fixedTodayAt(10, 0);
        long result = TimeCn.parse("今天晚上八点", now);
        long expected = now + 10 * HOUR;
        assertNear(result, expected, "今天晚上八点");
    }

    @Test public void specificDate() {
        long now = fixedTodayAt(10, 0);
        long result = TimeCn.parse("十月二十六号", now);
        assertTrue("十月二十六号 should parse to nonzero", result > 0);
    }

    @Test public void nextMonday() {
        long now = fixedTodayAt(10, 0);
        long result = TimeCn.parse("下周一", now);
        assertTrue("下周一 should parse to nonzero", result > 0);
    }

    @Test public void inOneHour() {
        long now = fixedTodayAt(10, 0);
        long result = TimeCn.parse("一个小时后", now);
        assertNear(result, now + HOUR, "一个小时后");
    }

    @Test public void meaninglessReturnsZero() {
        assertEquals(0, TimeCn.parse("随便说的一句话没有时间", 1000));
    }

    private long fixedTodayAt(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
