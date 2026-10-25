package dev.echo.app;

import java.util.Calendar;

/**
 * 中文时间短语 → 具体时刻。
 *
 * 只覆盖记录场景里高频的几种：X 分钟/小时后、今天/明天/后天、周X、下周X、
 * 上午/下午/晚上 X 点(半)。解析不出返回 0（页面按「没给时间」处理）。
 * 目的不是做全量 NLP，是让「今天该干嘛」这一屏排序够用。
 */
public final class TimeCn {

    private TimeCn() {
    }

    public static long parse(String raw, long now) {
        if (raw == null || raw.isEmpty()) return 0;
        String text = raw.trim()
                .replace(" ", "").replace("礼拜", "周")
                .replace("早上", "上午").replace("傍晚", "晚上");
        // 「下午三点」→「下午3点」：钟点里的中文数字先归一化，后面的正则只认阿拉伯数字
        java.util.regex.Matcher cnClock = java.util.regex.Pattern
                .compile("([一二两三四五六七八九十]{1,3})点").matcher(text);
        StringBuilder normalized = new StringBuilder();
        int copied = 0;
        while (cnClock.find()) {
            normalized.append(text, copied, cnClock.start())
                    .append(cnToInt(cnClock.group(1))).append("点");
            copied = cnClock.end();
        }
        normalized.append(text.substring(copied));
        text = normalized.toString();
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(now);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        // 相对时长：N分钟后 / 半小时后 / N小时后
        java.util.regex.Matcher rel = java.util.regex.Pattern
                .compile("(半小时|(\\d{1,3}|[一二两三四五六七八九十]+)(?:分|分钟|小时))后").matcher(text);
        if (rel.find()) {
            long minutes = rel.group(1).startsWith("半") ? 30
                    : cnNumber(rel.group(2)) * (rel.group(0).contains("小时") ? 60 : 1);
            return now + minutes * 60_000L;
        }

        int dayShift = -1;
        if (text.contains("今天") || text.contains("今日")) dayShift = 0;
        else if (text.contains("明天") || text.contains("次日")) dayShift = 1;
        else if (text.contains("后天")) dayShift = 2;
        else if (text.contains("大后天")) dayShift = 3;

        boolean hasDay = dayShift >= 0;
        if (!hasDay) {
            // 周X / 下周X
            java.util.regex.Matcher week = java.util.regex.Pattern
                    .compile("(下下周?|下?)周([一二三四五六日天])").matcher(text);
            if (week.find()) {
                int extraWeeks = week.group(1).startsWith("下下") ? 2 : week.group(1).startsWith("下") ? 1 : 0;
                int target = "一二三四五六日天".indexOf(week.group(2)) + 1; // Calendar: 周日=1
                int today = base.get(Calendar.DAY_OF_WEEK);
                int shift = (target - today + 7) % 7;
                if (shift == 0) shift = 7;
                dayShift = shift + extraWeeks * 7;
                hasDay = true;
            }
        }

        // 钟点
        java.util.regex.Matcher clock = java.util.regex.Pattern
                .compile("(上午|中午|下午|晚上)?(\\d{1,2})[点时:：](半|\\d{1,2}分?)?")
                .matcher(text);
        boolean hasClock = clock.find();
        int hour = -1;
        int minute = 0;
        if (hasClock) {
            hour = Integer.parseInt(clock.group(2));
            String tail = clock.group(3);
            if (tail != null) {
                minute = tail.startsWith("半") ? 30
                        : Integer.parseInt(tail.replace("分", "").isEmpty() ? "0" : tail.replace("分", ""));
            }
            String part = clock.group(1);
            if ("下午".equals(part) || "晚上".equals(part)) {
                if (hour < 12) hour += 12;
            } else if ("中午".equals(part)) {
                if (hour < 12) hour = 12;
            } else if (part == null && hour <= 6) {
                hour += 12; // 裸「3点」更像下午三点
            }
            if (hour > 23) hour = 23;
        }

        if (!hasClock) {
            // 「明天下午」这类只给时段的：按时段惯例给一个时刻
            if (text.contains("凌晨")) { hour = 4; hasClock = true; }
            else if (text.contains("上午")) { hour = 9; hasClock = true; }
            else if (text.contains("中午")) { hour = 12; hasClock = true; }
            else if (text.contains("下午")) { hour = 15; hasClock = true; }
            else if (text.contains("晚上")) { hour = 20; hasClock = true; }
        }

        if (!hasDay && !hasClock) return 0;

        Calendar out = Calendar.getInstance();
        out.setTimeInMillis(now);
        out.set(Calendar.SECOND, 0);
        out.set(Calendar.MILLISECOND, 0);
        if (hasDay) {
            out.add(Calendar.DAY_OF_YEAR, dayShift);
        }
        if (hasClock) {
            out.set(Calendar.HOUR_OF_DAY, hour);
            out.set(Calendar.MINUTE, minute);
        } else {
            out.set(Calendar.HOUR_OF_DAY, 9);
            out.set(Calendar.MINUTE, 0);
        }
        if (!hasDay && out.getTimeInMillis() < now) {
            out.add(Calendar.DAY_OF_YEAR, 1); // 只给了钟点且已过 → 算明天
        }
        return out.getTimeInMillis();
    }

    private static int cnToInt(String text) {
        if (text.equals("十")) return 10;
        if (text.equals("两")) return 2;
        int ten = text.indexOf('十');
        if (ten < 0) return "零一二三四五六七八九".indexOf(text.charAt(0));
        int head = ten == 0 ? 1 : Math.max(1, "零一二三四五六七八九".indexOf(text.charAt(0)));
        int tail = ten + 1 < text.length() ? Math.max(0, "零一二三四五六七八九".indexOf(text.charAt(ten + 1))) : 0;
        return head * 10 + tail;
    }

    private static int cnNumber(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        String digits = "零一二三四五六七八九";
        if (text.equals("十")) return 10;
        if (text.equals("两")) return 2;
        int ten = text.indexOf('十');
        if (ten < 0) return Math.max(0, digits.indexOf(text.charAt(0)));
        int head = ten == 0 ? 1 : Math.max(1, digits.indexOf(text.charAt(0)));
        int tail = ten + 1 < text.length() ? Math.max(0, digits.indexOf(text.charAt(ten + 1))) : 0;
        return head * 10 + tail;
    }
}
