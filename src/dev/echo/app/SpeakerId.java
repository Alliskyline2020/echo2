package dev.echo.app;

import android.content.Context;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;

import java.io.File;

/**
 * 声纹识别（CAM++ 中文 · 16k）：分清「这段话是主人说的，还是别人说的」。
 *
 * 注册一次主人的声纹向量（15 秒自然说话），之后每段人声算一次余弦相似度：
 *   ≥ 0.55 → 主人（可产生待办/提醒）
 *   < 0.55 → 其他人（只作上下文，不产生待办）
 * 声纹向量属于生物特征，只存在本机 data 目录，永不出设备。
 */
public final class SpeakerId {

    private static final String TAG = "echo-voice";
    private static final String MODEL_FILE = "campplus_zh.onnx";
    private static final String CENTROID_FILE = "voiceprint.bin";
    private static final float OWNER_THRESHOLD = 0.62f;

    private static SpeakerEmbeddingExtractor extractor;
    private static SpeakerEmbeddingManager manager;
    private static float[] centroid;
    private static final java.util.Map<String, float[]> namedSpeakers = new java.util.LinkedHashMap<>();

    private SpeakerId() {
    }

    public static boolean modelAvailable(Context context) {
        return modelPath(context).isFile();
    }

    public static File modelPath(Context context) {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), MODEL_FILE);
    }

    public static boolean enrolled(Context context) {
        return centroidFile(context).isFile();
    }

    private static File centroidFile(Context context) {
        return new File(context.getFilesDir(), CENTROID_FILE);
    }

    private static synchronized void ensure(Context context) throws Exception {
        if (extractor != null) return;
        if (!modelAvailable(context)) throw new Exception("声纹模型未安装");
        SpeakerEmbeddingExtractorConfig config =
                new SpeakerEmbeddingExtractorConfig(modelPath(context).getAbsolutePath(), 2, false, "cpu");
        // 从 SD 卡加载模型必须传 null 的 AssetManager（sherpa-onnx 明确要求），否则原生层直接 abort
        extractor = new SpeakerEmbeddingExtractor(null, config);
        manager = new SpeakerEmbeddingManager(extractor.dim());
        // 恢复已注册的声纹
        File file = centroidFile(context);
        if (file.isFile()) {
            try {
                String base64 = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                String[] parts = base64.trim().split(",");
                centroid = new float[parts.length];
                for (int i = 0; i < parts.length; i++) centroid[i] = Float.parseFloat(parts[i]);
                manager.add("owner", centroid);
                Log.i(TAG, "声纹已恢复 dim=" + centroid.length);
                restoreNamedSpeakers(context);
            } catch (Throwable error) {
                Log.w(TAG, "声纹恢复失败: " + error);
            }
        }
    }

    /** 16k PCM → 说话人向量 */
    public static synchronized float[] embed(Context context, short[] samples, int length) throws Exception {
        ensure(context);
        float[] pcm = toFloat(samples, length);
        OnlineStream stream = extractor.createStream();
        stream.acceptWaveform(pcm, 16000);
        stream.inputFinished();
        while (!extractor.isReady(stream)) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
        }
        return extractor.compute(stream);
    }

    /** 注册/重置主人声纹（用一段 15 秒左右的自然说话） */
    public static synchronized void enroll(Context context, short[] samples, int length) throws Exception {
        float[] vector = embed(context, samples, length);
        centroid = vector;
        manager.remove("owner");
        manager.add("owner", vector);
        StringBuilder base64 = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) base64.append(',');
            base64.append(vector[i]);
        }
        java.nio.file.Files.write(centroidFile(context).toPath(),
                base64.toString().getBytes());
        Log.i(TAG, "声纹已注册 dim=" + vector.length);
    }

    /** 该段是谁说的："owner" / "other" / "unknown"（未注册、太短、出错） */
    public static synchronized String judge(Context context, short[] samples, int length) {
        try {
            if (!enrolled(context)) return "unknown";
            if (length < 16000 * 1.5) return "unknown"; // 短于 1.5 秒不可靠
            float[] vector = embed(context, samples, length);
            float ownerSim = cosine(vector, centroid);
            Log.i(TAG, "声纹 owner 相似度=" + String.format(java.util.Locale.US, "%.3f", ownerSim));
            if (ownerSim >= OWNER_THRESHOLD) return "owner";
            // 遍历已注册的家人/团队成员
            for (java.util.Map.Entry<String, float[]> entry : namedSpeakers.entrySet()) {
                float sim = cosine(vector, entry.getValue());
                if (sim >= OWNER_THRESHOLD) {
                    Log.i(TAG, "匹配到 " + entry.getKey() + " 相似度=" + String.format(java.util.Locale.US, "%.3f", sim));
                    return entry.getKey();
                }
            }
            return "other";
        } catch (Throwable error) {
            Log.w(TAG, "声纹判定失败: " + error);
            return "unknown";
        }
    }

    /* -------------------------------------------------- 采集注册（带超时/取消） */

    private static volatile boolean capturing = false;
    private static volatile boolean cancelRequested = false;
    private static android.media.AudioRecord captureRecord;

    public interface CaptureListener {
        void onTick(int remainSeconds);

        void onSuccess();

        void onError(String message);
    }

    /** 取消当前录入（UI 取消按钮 / 超时看门狗共用） */
    public static synchronized void cancelCapture() {
        cancelRequested = true;
        capturing = false;
        try {
            if (captureRecord != null) {
                captureRecord.stop();
                captureRecord.release();
            }
        } catch (Throwable ignored) {
        }
        captureRecord = null;
    }

    /**
     * 录 seconds 秒话并注册声纹。
     * - 每秒回调 onTick 剩余秒数（UI 倒计时）
     * - 连续 1 秒读不到数据 → 重开一次麦克风；再失败 → 明确报"麦克风被系统语音助手占用"
     * - 总时长 +10 秒的硬看门狗，任何情况下不会无限挂起
     */
    public static void captureAndEnroll(Context context, int seconds, CaptureListener listener) {
        if (capturing) {
            listener.onError("已有录入在进行");
            return;
        }
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Log.i(TAG, "captureAndEnroll 开始 seconds=" + seconds);
        new Thread(() -> {
            capturing = true;
            cancelRequested = false;
            Log.i(TAG, "采集线程进入");
            android.media.AudioRecord record = null;
            try {
                ensure(context);
                main.post(() -> listener.onTick(seconds));
                int minBuf = android.media.AudioRecord.getMinBufferSize(16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                record = new android.media.AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                captureRecord = record;
                record.startRecording();
                Log.i(TAG, "AudioRecord 已启动 state=" + record.getRecordingState());

                int total = 16000 * seconds;
                short[] all = new short[total];
                short[] buffer = new short[3200]; // 200ms 一读
                int filled = 0;
                int emptyReads = 0;
                boolean reopened = false;
                long deadline = System.currentTimeMillis() + (seconds + 10) * 1000L;

                while (filled < total) {
                    if (cancelRequested || !capturing) throw new Exception("已取消");
                    if (System.currentTimeMillis() > deadline) throw new Exception("麦克风无响应");
                    int n = record.read(buffer, 0, buffer.length);
                    if (n <= 0) {
                        emptyReads++;
                        if (emptyReads == 5 && !reopened) {
                            // 采集被系统语音助手搅局：释放重开一次，多数情况能抢回
                            reopened = true;
                            try { record.stop(); record.release(); } catch (Throwable ignored) { }
                            record = new android.media.AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                                    android.media.AudioFormat.CHANNEL_IN_MONO,
                                    android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                            captureRecord = record;
                            record.startRecording();
                            continue;
                        }
                        if (emptyReads > 15) throw new Exception("麦克风被系统语音助手占用，请关闭手机的 AI 聆听功能后重试");
                        Thread.sleep(100);
                        continue;
                    }
                    emptyReads = 0;
                    int copy = Math.min(n, total - filled);
                    System.arraycopy(buffer, 0, all, filled, copy);
                    filled += copy;
                    int remain = seconds - filled / 16000;
                    main.post(() -> listener.onTick(Math.max(0, remain)));
                }

                enroll(context, all, filled);
                capturing = false;
                main.post(listener::onSuccess);
            } catch (Throwable error) {
                capturing = false;
                boolean cancelled = cancelRequested || "已取消".equals(error.getMessage());
                String message = cancelled ? "已取消" : error.getMessage();
                main.post(() -> listener.onError(message == null ? "录入失败" : message));
            } finally {
                try { if (record != null) { record.stop(); record.release(); } } catch (Throwable ignored) { }
                captureRecord = null;
            }
        }, "echo-voice-enroll").start();
    }

    public static synchronized void reset(Context context) {
        centroid = null;
        if (manager != null) manager.remove("owner");
        //noinspection ResultOfMethodCallIgnored
        centroidFile(context).delete();
    }

    private static float[] toFloat(short[] samples, int length) {
        int n = Math.min(length, samples.length);
        float[] pcm = new float[n];
        for (int i = 0; i < n; i++) pcm[i] = samples[i] / 32768f;
        return pcm;
    }
    private static float cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            dot += a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9));
    }

    /** 注册一个新说话人（录入声纹后调用） */
    public static void registerSpeaker(Context context, String name, float[] vector) {
        namedSpeakers.put(name, vector);
        try {
            org.json.JSONObject speakers = new org.json.JSONObject();
            File sf = new File(context.getFilesDir(), "data/speakers.json");
            if (sf.isFile()) {
                try {
                    speakers = new org.json.JSONObject(
                            new String(java.nio.file.Files.readAllBytes(sf.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable ignored) { }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(vector[i]);
            }
            speakers.put(name, sb.toString());
            sf.getParentFile().mkdirs();
            java.nio.file.Files.write(sf.toPath(),
                    speakers.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i(TAG, "说话人已注册: " + name);
        } catch (Throwable e) {
            Log.w(TAG, "save speaker failed", e);
        }
    }

    /** 启动时恢复已注册的家人声纹 */
    private static void restoreNamedSpeakers(Context context) {
        try {
            File sf = new File(context.getFilesDir(), "data/speakers.json");
            if (!sf.isFile()) return;
            org.json.JSONObject speakers = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(sf.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
            java.util.Iterator<String> keys = speakers.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                String[] parts = speakers.getString(name).split(",");
                float[] vec = new float[parts.length];
                for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
                namedSpeakers.put(name, vec);
            }
            Log.i(TAG, "恢复了 " + namedSpeakers.size() + " 个家人声纹");
        } catch (Throwable e) {
            Log.w(TAG, "restore speakers failed", e);
        }
    }

}