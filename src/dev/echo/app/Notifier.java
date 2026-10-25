package dev.echo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知渠道与提醒通知。承诺：锁屏安静，只有到点的提醒和每日回顾会现身。 */
public final class Notifier {

    public static final String CH_CAPTURE = "capture";   // 常驻"在听"（低存在感）
    public static final String CH_REMIND = "remind";     // 到点提醒（高优先）
    public static final String CH_DIGEST = "digest";     // 每日回顾（低优先，静默）

    private Notifier() {
    }

    public static void ensureChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        nm.createNotificationChannel(new NotificationChannel(CH_CAPTURE, "正在倾听",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_REMIND, "事项提醒",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_DIGEST, "每日回顾",
                NotificationManager.IMPORTANCE_LOW));
    }

    private static Notification.Builder base(Context context, String channel, String title, String text) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 1001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channel)
                : new Notification.Builder(context);
        builder.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pending).setAutoCancel(true);
        return builder;
    }

    /** 到点提醒（P0，唯一会响的一类） */
    public static void notifyReminder(Context context, Model.Todo todo) {
        ensureChannels(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String when = todo.dueAt > 0
                ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(new java.util.Date(todo.dueAt))
                : "";
        nm.notify(2000 + todo.id.hashCode(), base(context, CH_REMIND,
                "该做：" + todo.task, when.isEmpty() ? "现在处理" : "约 " + when).build());
    }

    /** 逾期升级（P1 周期梳理发现，只提一次） */
    public static void notifyOverdue(Context context, Model.Todo todo) {
        ensureChannels(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(3000 + todo.id.hashCode(), base(context, CH_REMIND,
                "还没做：" + todo.task, "已过约定时间，今天处理掉？").build());
    }

    /** 每日回顾（P2，低优先静默） */
    public static void notifyDigest(Context context, String text) {
        ensureChannels(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(4001, base(context, CH_DIGEST, "今日回顾", text).build());
    }
}
