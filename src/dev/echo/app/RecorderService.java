package dev.echo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 前台录音服务：AudioRecord 常驻采样 → VAD 切有效人声段 → 落 WAV 进数据层。
 *
 * 批量整理不在录音线程里跑：整理挂在「停止录音后」和「打开 App」这两个时机
 * （MainActivity 驱动 Pipeline）。连续录音攒够 8 分钟也会触发一批——
 * 前台服务活着时进程不会被冻，录音期间攒着的段是安全的。
 */
public final class RecorderService extends Service implements Vad.Sink {

    private static final String TAG = "echo-rec";
    private static final String CHANNEL_ID = "echo-recording";
    private static final int NOTIFICATION_ID = 1;
    private static final long BATCH_TRIGGER_MS = 8 * 60_000L;
    /** 常驻聆听的攒批窗口：2 分钟语音即整理一批 */
    private static final long BATCH_TRIGGER_SHORT_MS = 2 * 60_000L;

    public static volatile boolean recording = false;
    public static volatile String status = "空闲";
    public static volatile Listener listener;

    public interface Listener {
        void onStatus(String text);
    }

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running = false;
    private volatile boolean syncing = false;

    private final Vad vad = new Vad();
    private Store store;
    private GemmaEngine engine;
    private String sessionId;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        alive = true;
        Notifier.ensureChannels(this);
        createChannel();
        store = Store.get(this);
        engine = GemmaEngine.get();
        ReminderAlarm.scheduleMaintenance(this);
        cleanOldRecordings();
        // 通话链路：通话中让出麦克风（StepOS 自己录），挂断后吸收它的转写
        CallLink.register(this, new CallLink.Callbacks() {
            @Override public void onCallStart() {
                if (running) {
                    running = false;
                    recording = false;
                    vad.finish(RecorderService.this, System.currentTimeMillis());
                    store.endSession();
                    try { if (captureThread != null) captureThread.join(2000); } catch (InterruptedException ignored) { }
                }
                startForeground(NOTIFICATION_ID, buildNotification("通话中，回声让麦"));
                try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Throwable ignored) { }
                audioRecord = null;
            }
            @Override public void onCallEnd() {
                // 恢复采集由 CallLink 发送 start 动作完成
            }
            @Override public String status() { return status; }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "start";
        if ("stop".equals(action)) {
            stopCapture();
            return START_NOT_STICKY;
        }
        startCapture();
        return START_STICKY;
    }

    private void startCapture() {
        if (running) return;
        // 聆听时段门：仅白天/手动暂停时，服务在但不采集（通知显示待机）
        if (!ReminderAlarm.shouldListen(this)) {
            startForeground(NOTIFICATION_ID, buildNotification("聆听已暂停（时段外）"));
            setStatus("聆听已暂停");
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                Vad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int audioSource;
        switch (store.settings().micProfile) {
            case "voice-communication":
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION; // AEC+NS（借扬声器参考消回声）
                break;
            case "unprocessed":
                audioSource = MediaRecorder.AudioSource.UNPROCESSED;        // 原始麦，不过 DSP 处理
                break;
            default:
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;  // 降噪图，最贴 ASR
                break;
        }
        try {
            audioRecord = new AudioRecord(audioSource,
                    Vad.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer * 2, Vad.FRAME_SAMPLES * 8));
        } catch (Throwable error) {
            setStatus("打不开麦克风：" + error.getMessage());
            return;
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("麦克风初始化失败");
            return;
        }

        running = true;
        recording = true;
        vad.reset();
        sessionId = store.startSession().id;
        startForeground(NOTIFICATION_ID, buildNotification("正在记录"));
        setStatus("正在记录");

        captureThread = new Thread(this::captureLoop, "echo-capture");
        captureThread.start();
    }

    // ── 监听节能档位 ──
    private static final long QUIET_SKIP_VAD_MS = 2 * 60_000;     // 2 分钟静默 → 跳 VAD，只查 RMS
    private static final long QUIET_SKIP_ALL_MS = 10 * 60_000;    // 10 分钟极静 → 连 RMS 都跳，只等声音恢复
    private static final double STANDBY_RMS_THRESHOLD = 500.0;    // RMS 超过此值 → 有声音，立即唤醒
    private long lastSpeechAtMs = System.currentTimeMillis();
    private long lastSegmentEndAtMs = 0;
    private static final long MERGE_GAP_MS = 3000; // 间隔 <3s 的段合并

    private static boolean batchRmsExceeds(short[] buf, int len, double threshold) {
        if (len <= 0) return false;
        double sum = 0;
        for (int i = 0; i < len; i++) {
            double v = buf[i] / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / len) > threshold / 32768.0;
    }

    private void captureLoop() {
        audioRecord.startRecording();
        final int BATCH_FRAMES = 16;
        short[] batch = new short[Vad.FRAME_SAMPLES * BATCH_FRAMES];
        short[] frame = new short[Vad.FRAME_SAMPLES];
        while (running) {
            int read = audioRecord.read(batch, 0, batch.length);
            if (read <= 0) continue;
            long now = System.currentTimeMillis();
            long quietMs = now - lastSpeechAtMs;

            if (quietMs > QUIET_SKIP_ALL_MS) {
                // 极静模式：只查 RMS 是否超过阈值，VAD 和帧处理全跳过。
                // 麦克风硬件仍开着（关掉就无法检测声音恢复），但 CPU 几乎零开销。
                if (batchRmsExceeds(batch, read, STANDBY_RMS_THRESHOLD)) {
                    Log.i(TAG, "极静→检测到声音，恢复全量处理");
                    vad.reset();
                    lastSpeechAtMs = now;  // 唤醒：下一轮进全量 VAD
                }
                continue;
            }

            if (quietMs > QUIET_SKIP_VAD_MS) {
                // 半待机：只查 RMS，不做 ZCR / VAD 状态机
                if (batchRmsExceeds(batch, read, STANDBY_RMS_THRESHOLD)) {
                    Log.i(TAG, "安静→检测到声音，恢复全量处理");
                    vad.reset();
                    lastSpeechAtMs = now;
                }
                continue;
            }

            // 全量处理：攒帧 + VAD
            int frames = read / Vad.FRAME_SAMPLES;
            for (int f = 0; f < frames && running; f++) {
                System.arraycopy(batch, f * Vad.FRAME_SAMPLES, frame, 0, Vad.FRAME_SAMPLES);
                try {
                    vad.push(frame, now - (frames - 1 - f) * 20L, this);
                } catch (Throwable error) {
                    Log.w(TAG, "vad failed", error);
                }
                lastSeenAudioAtMs = now;
            }
            // VAD 检测到人声段时更新 lastSpeechAtMs
            if (vad.isSpeechActive()) {
                lastSpeechAtMs = now;
            }
        }
        try {
            audioRecord.stop();
            audioRecord.release();
        } catch (Throwable ignored) {
        }
        audioRecord = null;
        Log.i(TAG, "capture ended");
    }

    private void stopCapture() {
        if (!running) {
            stopSelf();
            return;
        }
        running = false;
        recording = false;
        vad.finish(this, System.currentTimeMillis());
        store.endSession();
        // 关键：整理期间保持前台服务。StepOS 会冻结后台进程（实测把推理冻死在半路），
        // 只有"可感知"的前台通知能保住进程——所以通知从"正在记录"切到"正在整理"，
        // 整理完成后才停服务。
        startForeground(NOTIFICATION_ID, buildNotification("正在整理录音…"));
        setStatus("已停止，开始整理");
        try {
            if (captureThread != null) captureThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        runBatchAsync();
    }

    /** 守夜自检用：进程里是否已有一份采集服务 */
    private static volatile boolean alive = false;

    public static boolean serviceRunning() {
        return alive;
    }

    private long lastVoiceAtMs;
    private long lastSeenAudioAtMs;

    
    /** 清理 7 天前的已完成录音文件 */
    private void cleanOldRecordings() {
        try {
            File dir = new File(getFilesDir(), "segments");
            if (!dir.isDirectory()) return;
            long cutoff = System.currentTimeMillis() - 7 * 86400_000L;
            int n = 0;
            for (File f : dir.listFiles()) {
                if (f.isFile() && f.lastModified() < cutoff) { f.delete(); n++; }
            }
            if (n > 0) Log.i(TAG, "清理 " + n + " 个过期录音");
        } catch (Throwable e) { Log.w(TAG, "录音清理失败", e); }
    }

    public void onSegment(short[] samples, int length, long startedAtMs) {
        // 段间隔追踪（VAD 关段阈值 2.5s 已足够长，这里只做日志）
        long gap = lastSegmentEndAtMs > 0 ? startedAtMs - lastSegmentEndAtMs : -1;
        if (gap > 0 && gap < MERGE_GAP_MS) {
            Log.d(TAG, "段间隔 " + gap + "ms（VAD 已通过 2.5s 静默判定为自然停顿）");
        }
        lastSegmentEndAtMs = startedAtMs + (long) length / Vad.SAMPLE_RATE * 1000;

        File dir = new File(getFilesDir(), "segments");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "mkdir failed");
        File wav = new File(dir, java.util.UUID.randomUUID() + ".wav");
        try {
            writeWav(wav, samples, length);
        } catch (IOException error) {
            Log.w(TAG, "write wav failed", error);
            return;
        }
        // 声纹判定：这段话是主人说的还是别人说的（决定能否成为待办）
        lastVoiceAtMs = System.currentTimeMillis();
        String speaker = SpeakerId.judge(this, samples, length);
        store.appendSegment(sessionId, wav, startedAtMs, length * 1000L / Vad.SAMPLE_RATE, speaker);
        setStatus("已记录 " + (length / Vad.SAMPLE_RATE) + " 秒");
        // 常驻模式攒批：2 分钟语音或「已静默 10 秒且有积压」即整理一批
        if (!syncing && (store.pendingAudioMs() >= BATCH_TRIGGER_SHORT_MS
                || (store.pendingCount() > 0 && System.currentTimeMillis() - lastVoiceAtMs >= 10_000L))) {
            startForeground(NOTIFICATION_ID, buildNotification("正在整理录音…"));
            runBatchAsync();
        }
    }

    /** P2：夜间回顾 + 记忆整合（前台保活下跑模型，完成后若无录音则停服） */
    private void runDigestAsync() {
        Thread thread = new Thread(() -> {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("正在生成今日回顾…"));
                engine.ensureReady(this, this::setStatus);
                Consolidator.runToday(this, engine, this::setStatus);
                setStatus("今日回顾已生成");
            } catch (Throwable error) {
                Log.w(TAG, "digest failed", error);
                setStatus("回顾稍后会自动重试");
            } finally {
                if (!recording) {
                    stopForeground(false);
                    stopSelf();
                }
            }
        }, "echo-digest");
        thread.start();
    }

    private void runBatchAsync() {
        if (syncing) return;
        syncing = true;
        Thread thread = new Thread(() -> {
            try {
                engine.ensureReady(this, this::setStatus);
                int count = Pipeline.sync(this, store, engine, null, this::setStatus);
                setStatus(count > 0 ? "整理完成 " + count + " 段" : "没有要整理的");
            } catch (Throwable error) {
                Log.w(TAG, "batch failed", error);
                setStatus("部分录音稍后会自动重试");
            } finally {
                syncing = false;
                // 整理完成后：不在录音 → 服务使命结束；在录音 → 留着继续记录
                if (!recording) {
                    stopForeground(false);
                    stopSelf();
                }
            }
        }, "echo-batch");
        thread.start();
    }

    static void writeWav(File file, short[] samples, int length) throws IOException {
        int dataBytes = length * 2;
        byte[] header = new byte[44];
        ascii(header, 0, "RIFF");
        int32(header, 4, 36 + dataBytes);
        ascii(header, 8, "WAVEfmt ");
        int32(header, 16, 16);
        int16(header, 20, (short) 1);
        int16(header, 22, (short) 1);
        int32(header, 24, Vad.SAMPLE_RATE);
        int32(header, 28, Vad.SAMPLE_RATE * 2);
        int16(header, 32, (short) 2);
        int16(header, 34, (short) 16);
        ascii(header, 36, "data");
        int32(header, 40, dataBytes);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
            byte[] pcm = new byte[dataBytes];
            for (int i = 0; i < length; i++) {
                pcm[i * 2] = (byte) (samples[i] & 0xff);
                pcm[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
            }
            out.write(pcm);
        }
    }

    private static void ascii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) target[offset + i] = (byte) value.charAt(i);
    }

    private static void int32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
        target[offset + 2] = (byte) ((value >> 16) & 0xff);
        target[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void int16(byte[] target, int offset, short value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private void setStatus(String text) {
        status = text;
        Listener current = listener;
        if (current != null) current.onStatus(text);
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && recording) manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("回声正在记录")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        recording = false;
        vad.reset();
        // 等采集线程退出（最多 1s），防止泄漏
        try {
            if (captureThread != null) captureThread.join(1000);
        } catch (InterruptedException ignored) { }
        captureThread = null;
        try {
            if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
        } catch (Throwable ignored) { }
        audioRecord = null;
        // engine 是进程级单例，服务销毁不关闭
        super.onDestroy();
    }
}
