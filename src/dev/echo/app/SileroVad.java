package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;

/**
 * Silero VAD：神经网络语音活动检测，替代手写 RMS+ZCR。
 * 643KB 模型，比声学规则精确得多——自然停顿、咳嗽、门响不会误切段。
 * 通过 sherpa-onnx 集成，和 ASR/声纹同一框架。
 */
public final class SileroVad {

    private static final String TAG = "echo-vad";
    private Vad vad;

    /** 初始化。模型文件放在 getExternalFilesDir("silero_vad.onnx") */
    public boolean init(Context context) {
        File modelFile = new File(context.getExternalFilesDir(null), "silero_vad.onnx");
        if (!modelFile.isFile()) {
            Log.w(TAG, "silero_vad.onnx 不存在，降级到手写 VAD");
            return false;
        }
        try {
            SileroVadModelConfig svConfig = new SileroVadModelConfig();
            svConfig.setModel(modelFile.getAbsolutePath());
            svConfig.setThreshold(0.5f);           // 语音概率阈值
            svConfig.setMinSilenceDuration(2.5f);   // 2.5s 静默关段
            svConfig.setMinSpeechDuration(1.0f);    // 1s 语音开段
            svConfig.setWindowSize(512);            // 512 样本 @16kHz = 32ms
            svConfig.setMaxSpeechDuration(300f);    // 5 分钟上限

            VadModelConfig config = new VadModelConfig();
            config.setSileroVadModelConfig(svConfig);
            config.setSampleRate(16000);

            vad = new Vad(null, config); // null AssetManager = 用文件路径
            Log.i(TAG, "Silero VAD 初始化完成");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Silero VAD 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /** 喂入音频。Silero 内部检测语音边界，段落通过 popSegment() 获取 */
    public void acceptWaveform(short[] samples, int length) {
        if (vad == null) return;
        float[] pcm = new float[length];
        for (int i = 0; i < length; i++) pcm[i] = samples[i] / 32768f;
        vad.acceptWaveform(pcm);
    }

    /** 是否有可取的语音段 */
    public boolean hasSegment() {
        return vad != null && !vad.empty();
    }

    /** 取出最早的语音段（取后自动移除） */
    public SpeechSegment nextSegment() {
        if (vad == null || vad.empty()) return null;
        SpeechSegment seg = vad.front();
        vad.pop();
        return seg;
    }

    /** 当前是否在语音中（节能判断用） */
    public boolean isSpeechDetected() {
        return vad != null && vad.isSpeechDetected();
    }

    /** 单帧快速判断有无语音概率（极静模式用，不做完整分段） */
    public float computeSpeechProbability(short[] samples, int length) {
        if (vad == null) return 0f;
        float[] pcm = new float[Math.min(length, 512)];
        for (int i = 0; i < pcm.length; i++) pcm[i] = samples[i] / 32768f;
        return vad.compute(pcm);
    }

    public void reset() {
        if (vad != null) vad.clear();
    }

    public void release() {
        if (vad != null) { vad.release(); vad = null; }
    }
}
