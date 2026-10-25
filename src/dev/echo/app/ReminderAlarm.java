package dev.echo.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.List;

/**
 * 提醒与维护调度（全部系统级，App 被杀/重启后依然生效）：
 *
 * P0  即时道：待办创建时注册 dueAt 精准闹钟 → 到点响提醒
 * P1  周期道：每 3 小时轻梳理（逾期升级为提醒、复核到期标记），规则计算，不用模型
 * 守夜：每 15 分钟自检一次采集服务是否还活着（StepOS 冻结兜底），死了就拉起
 * 开机：重排所有未到期提醒 + 视聆听时段拉起采集
 */
public final class ReminderAlarm {

    private static final String TAG = "echo-alarm";
    public static final String ACTION_REMIND = "dev.echo.app.REMIND";
    public static final String ACTION_MAINTAIN = "dev.echo.app.MAINTAIN";
    public static final String ACTION_BOOT = "dev.echo.app.BOOT";
    public static final String ACTION_RESCHED = "dev.echo.app.RESCHED";
    public static final String ACTION_DIGEST = "dev.echo.app.DIGEST";
    private static final String EXTRA_TODO = "todoId";

    private ReminderAlarm() {
    }

    /* ------------------------------------------------------------ P0 提醒 */

    public static void schedule(Context context, Model.Todo todo) {
        if (todo.dueAt <= 0 || todo.done) return;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pending = reminderPending(context, todo.id, true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, todo.dueAt, 5 * 60_000L, pending);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, todo.dueAt, pending);
            }
            Log.i(TAG, "提醒已排 " + new java.util.Date(todo.dueAt) + " " + todo.task);
        } catch (SecurityException error) {
            am.setWindow(AlarmManager.RTC_WAKEUP, todo.dueAt, 5 * 60_000L, pending);
        }
    }

    public static void cancel(Context context, Model.Todo todo) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(reminderPending(context, todo.id, true));
    }

    private static PendingIntent reminderPending(Context context, String todoId, boolean forAlarm) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_REMIND);
        intent.putExtra(EXTRA_TODO, todoId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, todoId.hashCode(), intent, flags);
    }

    /** 开机 / 数据变动后：重排所有未到期待办 */
    public static void rescheduleAll(Context context) {
        Store store = Store.get(context);
        long now = System.currentTimeMillis();
        for (Model.Todo todo : store.todos()) {
            if (todo.done || todo.dueAt <= 0) continue;
            if (todo.dueAt > now) schedule(context, todo);
        }
    }

    /* -------------------------------------------------- P1 周期梳理（规则） */

    /** 每 3 小时：逾期升级 + 复核到期标记。纯规则，不跑模型。 */
    public static void runMaintenance(Context context) {
        Store store = Store.get(context);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Model.Todo todo : store.todos()) {
            if (todo.done || todo.dueAt <= 0) continue;
            // 逾期且尚未升级过 → 提一次（只提一次，不轰炸）
            if (todo.dueAt < now && todo.escalatedAt == 0 && now - todo.dueAt < 24 * 3600_000L) {
                todo.escalatedAt = now;
                store.updateTodo(todo);
                Notifier.notifyOverdue(context, todo);
                changed = true;
            }
        }
        if (changed) Log.i(TAG, "周期梳理：逾期升级完成");
    }

    /** 守夜：采集服务不在了就拉起来（受聆听时段约束） */
    public static void ensureCapture(Context context) {
        if (RecorderService.recording || RecorderService.serviceRunning()) return;
        if (!shouldListen(context)) return;
        Intent intent = new Intent(context, RecorderService.class);
        intent.setAction("start");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable error) {
            Log.w(TAG, "自检拉起失败: " + error);
        }
    }

    /** 聆听时段判定：always=全天；day=8:00-22:00；manual=只跟磁贴 */
    public static boolean shouldListen(Context context) {
        Model.Settings settings = Store.get(context).settings();
        if (settings.pausedManual) return false;
        if ("manual".equals(settings.listenMode)) return true; // manual 档由磁贴决定
        if ("day".equals(settings.listenMode)) {
            Calendar calendar = Calendar.getInstance();
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            return hour >= 8 && hour < 22;
        }
        return true;
    }

    /* ------------------------------------------------------------ 定时器 */

    private static final int MAINTAIN_REQUEST = 9001;
    private static final int WATCHDOG_REQUEST = 9002;
    private static final int DIGEST_REQUEST = 9003;

    /** 每晚 21:30 生成今日回顾（精准闹钟，到点经服务跑，模型加载不怕慢） */
    public static void scheduleDigest(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 21);
        calendar.set(Calendar.MINUTE, 30);
        calendar.set(Calendar.SECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), 10 * 60_000L,
                        digestPending(context));
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        calendar.getTimeInMillis(), digestPending(context));
            }
        } catch (SecurityException error) {
            am.setWindow(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), 10 * 60_000L,
                    digestPending(context));
        }
    }

    private static PendingIntent digestPending(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_DIGEST);
        return PendingIntent.getBroadcast(context, DIGEST_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 维护闹钟：3 小时梳理 + 15 分钟守夜（非精准，省电） */
    public static void scheduleMaintenance(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + 3 * 3600_000L,
                3 * 3600_000L, maintainPending(context));
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + 15 * 60_000L,
                15 * 60_000L, watchdogPending(context));
    }

    private static PendingIntent maintainPending(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_MAINTAIN);
        return PendingIntent.getBroadcast(context, MAINTAIN_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent watchdogPending(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_BOOT);
        return PendingIntent.getBroadcast(context, WATCHDOG_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 接收器：提醒到点 / 维护 / 开机 */
    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            Notifier.ensureChannels(context);
            if (ACTION_REMIND.equals(action)) {
                String todoId = intent.getStringExtra(EXTRA_TODO);
                Store store = Store.get(context);
                for (Model.Todo todo : store.todos()) {
                    if (todo.id.equals(todoId)) {
                        if (!todo.done) Notifier.notifyReminder(context, todo);
                        break;
                    }
                }
            } else if (ACTION_MAINTAIN.equals(action)) {
                runMaintenance(context);
            } else if (ACTION_BOOT.equals(action)) {
                // 守夜心跳：确认采集活着
                ensureCapture(context);
            } else if (ACTION_DIGEST.equals(action)) {
                // 回顾由采集服务跑（前台通知保活，模型加载不怕慢）；跑完排明天
                try {
                    Intent service = new Intent(context, RecorderService.class);
                    service.setAction("digest");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(service);
                    } else {
                        context.startService(service);
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "digest 启动失败: " + error);
                }
                scheduleDigest(context);
            } else if (ACTION_RESCHED.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                rescheduleAll(context);
                scheduleMaintenance(context);
                scheduleDigest(context);
                ensureCapture(context);
            }
        }
    }
}
