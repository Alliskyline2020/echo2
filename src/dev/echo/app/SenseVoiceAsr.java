package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;

/**
 * SenseVoice 中文 ASR（int8 量化 · 226MB）。
 * 专用 ASR 模型替代 Gemma E2B 的转写——精度和速度都远超通用 LLM。
 * Gemma 只做理解（抽取/纠错/回顾），不再干 ASR 活。
 */
public final class SenseVoiceAsr {

    private static final String TAG = "echo-asr";
    private static OfflineRecognizer recognizer;

    private SenseVoiceAsr() {
    }

    public static boolean modelAvailable(Context context) {
        return modelPath(context).isFile() && tokensPath(context).isFile();
    }

    public static File modelPath(Context context) {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), "sensevoice-model.onnx");
    }

    public static File tokensPath(Context context) {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), "sensevoice-tokens.txt");
    }

    private static synchronized void ensure(Context context) throws Exception {
        if (recognizer != null) return;
        if (!modelAvailable(context)) throw new Exception("SenseVoice 模型未安装");
        long start = System.currentTimeMillis();
        OfflineSenseVoiceModelConfig svConfig = new OfflineSenseVoiceModelConfig();
        svConfig.setModel(modelPath(context).getAbsolutePath());
        svConfig.setLanguage("zh");
        svConfig.setUseInverseTextNormalization(true);
        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setSenseVoice(svConfig);
        modelConfig.setTokens(tokensPath(context).getAbsolutePath());
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        FeatureConfig featConfig = new FeatureConfig();
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(featConfig);
        config.setModelConfig(modelConfig);
        recognizer = new OfflineRecognizer(null, config);
        Log.i(TAG, "SenseVoice 初始化完成 " + (System.currentTimeMillis() - start) + "ms");
    }

    /** 16k PCM → 中文文本 */
    public static synchronized String transcribe(Context context, short[] samples, int length) throws Exception {
        ensure(context);
        float[] pcm = new float[length];
        for (int i = 0; i < length; i++) pcm[i] = samples[i] / 32768f;
        OfflineStream stream = recognizer.createStream();
        stream.acceptWaveform(pcm, 16000);
        recognizer.decode(stream);
        return recognizer.getResult(stream).getText();
    }

    /** WAV 文件 → 中文文本（跳过 44 字节头，读 PCM） */
    public static synchronized String transcribeWav(Context context, File wavFile) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(wavFile.toPath());
        // WAV header 44 bytes → 16-bit PCM LE
        int pcmLen = (bytes.length - 44) / 2;
        short[] samples = new short[pcmLen];
        for (int i = 0; i < pcmLen; i++) {
            int lo = bytes[44 + i * 2] & 0xFF;
            int hi = bytes[44 + i * 2 + 1];
            samples[i] = (short) ((hi << 8) | lo);
        }
        return transcribe(context, samples, pcmLen);
    }
}
