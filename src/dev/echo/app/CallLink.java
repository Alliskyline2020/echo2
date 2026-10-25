package dev.echo.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 通话链路复用：通话中让出麦克风（StepOS 自己录，零冲突）；
 * 挂断后用 root 读取 StepOS 通话记录库（RecorderDb）里【它已生成的转写文本】，
 * 直接进我们的抽取管线 → 待办/提醒。本 app 全程不碰通话期间的麦克风。
 */
public final class CallLink {

    private static final String TAG = "echo-call";
    private static final String DB_SRC = "/data/data/com.android.soundrecorder/databases/RecorderDb";
    private static final Set<String> ingestedIds = new HashSet<>();

    private static volatile long callStartedAt;
    private static volatile boolean inCall = false;
    private static volatile boolean autoPausedByCall = false;
    private static volatile boolean registered = false;

    private CallLink() {
    }

    public interface Callbacks {
        void onCallStart();

        void onCallEnd();

        String status();
    }

    public static synchronized void register(Context context, Callbacks callbacks) {
        if (registered) return;
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                class CallStateCallback extends TelephonyCallback
                        implements TelephonyCallback.CallStateListener {
                    @Override
                    public void onCallStateChanged(int state) {
                        handle(context, callbacks, state);
                    }
                }
                tm.registerTelephonyCallback(context.getMainExecutor(), new CallStateCallback());
            } else {
                tm.listen(new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handle(context, callbacks, state);
                    }
                }, PhoneStateListener.LISTEN_CALL_STATE);
            }
            registered = true;
            Log.i(TAG, "通话监听已注册");
        } catch (Throwable error) {
            Log.w(TAG, "通话监听注册失败: " + error);
        }
    }

    private static void handle(Context context, Callbacks callbacks, int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && !inCall) {
            inCall = true;
            callStartedAt = System.currentTimeMillis();
            Log.i(TAG, "通话开始，让出麦克风");
            autoPausedByCall = RecorderService.recording;
            callbacks.onCallStart();
        } else if (state == TelephonyManager.CALL_STATE_IDLE && inCall) {
            inCall = false;
            final long started = callStartedAt;
            final Context appContext = context.getApplicationContext();
            Log.i(TAG, "通话结束，恢复采集并安排吸收通话转写");
            callbacks.onCallEnd();
            if (autoPausedByCall) {
                autoPausedByCall = false;
                Intent intent = new Intent(appContext, RecorderService.class);
                intent.setAction("start");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent);
                } else {
                    appContext.startService(intent);
                }
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> ingest(appContext, started, callbacks), 25_000L);
            handler.postDelayed(() -> ingest(appContext, started, callbacks), 90_000L);
        }
    }

    /** 读取 RecorderDb 里这段时间的通话行，吸收它的转写文本 → 抽取 */
    public static synchronized void ingest(Context context, long sinceMs, Callbacks callbacks) {
        if (!RootCmd.available()) {
            Log.i(TAG, "无 root，跳过通话转写复用");
            return;
        }
        File copy = new File(context.getFilesDir(), "RecorderDb.copy");
        if (!RootCmd.copyToAppData(DB_SRC, copy.getAbsolutePath())) return;
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    copy.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            long since = Math.max(sinceMs, System.currentTimeMillis() - 24 * 3600_000L);
            Cursor cursor = db.rawQuery(
                    "SELECT _id, name, ended_time, phone_number, recognition_file "
                            + "FROM recorder WHERE ended_time > ? ORDER BY _id DESC",
                    new String[]{String.valueOf(since)});
            int ingested = 0;
            while (cursor.moveToNext()) {
                String rowId = String.valueOf(cursor.getLong(0));
                String audioName = cursor.getString(1);
                String phone = cursor.getString(3) == null ? "" : cursor.getString(3);
                String recognitionFile = cursor.getString(4);
                if (ingestedIds.contains(rowId)) continue;

                String transcript = "";
                if (recognitionFile != null && !recognitionFile.isEmpty()) {
                    String text = RootCmd.exec("cat '" + recognitionFile + "'", 10);
                    if (text != null) transcript = text.trim();
                }
                Log.i(TAG, "通话行 " + rowId + " audio=" + audioName
                        + " phone=" + phone + " 转写" + (transcript.isEmpty() ? "未生成" : "已就绪"));
                if (transcript.isEmpty()) continue;

                ingestedIds.add(rowId);
                Store store = Store.get(context);
                Model.Session session = store.startSession();
                Model.Segment segment = new Model.Segment();
                segment.id = java.util.UUID.randomUUID().toString();
                segment.startedAt = System.currentTimeMillis();
                segment.speaker = "call";
                segment.transcript = "[通话" + (phone.isEmpty() ? "" : " " + phone) + "] " + transcript;
                segment.state = "done";
                store.appendReadySegment(session.id, segment);
                ingestText(context, transcript);
                ingested++;
            }
            cursor.close();
            db.close();
            if (ingested > 0 && callbacks != null) callbacks.status();
            Log.i(TAG, "通话吸收完成：" + ingested + " 通");
        } catch (Throwable error) {
            Log.w(TAG, "通话吸收失败: " + error);
        }
    }

    /** 抽取一通电话的转写（真实吸收与调试注入共用） */
    public static synchronized void ingestText(Context context, String transcript) {
        Store store = Store.get(context);
        Model.Session session = store.startSession();
        Model.Segment segment = new Model.Segment();
        segment.id = java.util.UUID.randomUUID().toString();
        segment.startedAt = System.currentTimeMillis();
        segment.speaker = "call";
        segment.transcript = "[通话] " + transcript;
        segment.state = "done";
        store.appendReadySegment(session.id, segment);

        try {
            GemmaEngine engine = GemmaEngine.get();
            engine.ensureReady(context, null);
            String raw = engine.extractCall(context, segment.transcript, null);
            Pipeline.applyExtraction(context, store, session.id, raw);
        } catch (Throwable error) {
            Log.w(TAG, "通话抽取失败: " + error);
        }
    }
}
