package dev.echo.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.ai.edge.litertlm.ActivationDataType;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.EmbeddingEngine;
import com.google.ai.edge.litertlm.EmbeddingEngineConfig;
import com.google.ai.edge.litertlm.EmbeddingOptions;
import com.google.ai.edge.litertlm.EmbeddingResponse;
import com.google.ai.edge.litertlm.InputData;

import java.util.Collections;
import java.io.File;

/**
 * 记忆语义嵌入（EmbeddingGemma 2 · 768 维）。
 *
 * 实测：CPU 上单条约 35ms，加载秒级。写入记忆时算一次向量（int8 量化存储），
 * 搜索时只算查询向量一次，然后余弦排序——「意思相近也能搜到」就靠这个。
 */
public final class MemoryEmbedder {

    private static final String TAG = "echo-embed";
    private static final String MODEL_FILE = "embeddinggemma-2-740m.litertlm";

    private static EmbeddingEngine engine;

    private MemoryEmbedder() {
    }

    public interface Progress {
        void on(String text);
    }

    public static synchronized boolean available(Context context) {
        return new File(modelPath(context)).isFile();
    }

    public static String modelPath(Context context) {
        File dir = context.getExternalFilesDir(null);
        return new File(dir != null ? dir : context.getFilesDir(), MODEL_FILE).getAbsolutePath();
    }

    private static synchronized EmbeddingEngine ensure(Context context, Progress progress) throws Exception {
        if (engine != null && engine.isInitialized()) return engine;
        if (!available(context)) throw new Exception("未找到嵌入模型");
        if (progress != null) progress.on("正在加载语义模型…");
        EmbeddingEngineConfig config = new EmbeddingEngineConfig(
                modelPath(context), new Backend.CPU(2, 2), null, null,
                "", null, null, null, ActivationDataType.FLOAT16);
        EmbeddingEngine created = new EmbeddingEngine(config);
        created.initialize();
        engine = created;
        return engine;
    }

    /** 文本 → 768 维向量（未归一化原始输出，已归一化选项开着时可直接点积） */
    public static synchronized float[] embed(Context context, String text, Progress progress) throws Exception {
        EmbeddingEngine embeddingEngine = ensure(context, progress);
        EmbeddingResponse response = embeddingEngine.computeEmbedding(
                Collections.singletonList(new InputData.Text(text)),
                new EmbeddingOptions(true, true, null, null));
        return response.getEmbedding();
    }

    /** int8 量化 + base64：一条 768 维记忆只占约 1KB 存储 */
    public static String quantize(float[] vector) {
        byte[] bytes = new byte[vector.length];
        for (int i = 0; i < vector.length; i++) {
            bytes[i] = (byte) Math.max(-127, Math.min(127, Math.round(vector[i] * 127f)));
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static float[] dequantize(String base64) {
        byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
        float[] vector = new float[bytes.length];
        for (int i = 0; i < bytes.length; i++) vector[i] = bytes[i] / 127f;
        return vector;
    }

    public static float cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9));
    }

    public static synchronized void close() {
        try {
            if (engine != null) engine.close();
        } catch (Throwable ignored) {
        }
        engine = null;
    }
}
