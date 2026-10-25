package dev.echo.app;

/**
 * 端侧人声检测 + 分段（与 Web 版 shared/audio/vad.ts 同一套判据）。
 * 20ms 帧、RMS+过零率、自适应底噪（起始 -55 dBFS）、预卷/尾音、单段上限 30 秒。
 */
public final class Vad {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 320; // 20 ms
    private static final float MIN_SPEECH_DB = -55f;
    private static final float FLOOR_MARGIN_DB = 9f;
    private static final float MIN_ZCR = 0.01f;
    private static final float MAX_ZCR = 0.6f;

    private static final int PREROLL_FRAMES = 10;
    private static final int MIN_SPEECH_FRAMES = 12;
    private static final int END_SILENCE_FRAMES = 25;
    private static final int MAX_SEGMENT_FRAMES = SAMPLE_RATE / FRAME_SAMPLES * 30;

    private float noiseFloorDb = MIN_SPEECH_DB;

    private final short[] preroll = new short[FRAME_SAMPLES * PREROLL_FRAMES];
    private int prerollFill = 0;

    private short[] segment = new short[FRAME_SAMPLES * (MAX_SEGMENT_FRAMES + PREROLL_FRAMES + 8)];
    private int segmentLength = 0;
    private int speechFrames = 0;
    private int silenceFrames = 0;
    private boolean inSpeech = false;

    public interface Sink {
        void onSegment(short[] samples, int length, long startedAtMs);
    }

    public void reset() {
        prerollFill = 0;
        segmentLength = 0;
        speechFrames = 0;
        silenceFrames = 0;
        inSpeech = false;
    }

    public void push(short[] samples, long frameAtMs, Sink sink) {
        float db = rmsDb(samples);
        float zcr = zeroCrossingRate(samples);
        boolean speech = db > Math.max(MIN_SPEECH_DB, noiseFloorDb + FLOOR_MARGIN_DB)
                && zcr >= MIN_ZCR && zcr <= MAX_ZCR;

        if (!speech) {
            if (db < noiseFloorDb) noiseFloorDb = db;
            else noiseFloorDb += (db - noiseFloorDb) * 0.02f;
            if (noiseFloorDb > MIN_SPEECH_DB) noiseFloorDb = MIN_SPEECH_DB;
        }

        if (!inSpeech) {
            pushPreroll(samples);
            if (speech) {
                inSpeech = true;
                speechFrames = 1;
                silenceFrames = 0;
                copyPrerollInto();
                append(samples);
            } else if (prerollFill == PREROLL_FRAMES) {
                System.arraycopy(preroll, FRAME_SAMPLES, preroll, 0,
                        (PREROLL_FRAMES - 1) * FRAME_SAMPLES);
                prerollFill = PREROLL_FRAMES - 1;
            }
            return;
        }

        append(samples);
        if (speech) {
            speechFrames++;
            silenceFrames = 0;
        } else {
            silenceFrames++;
        }
        boolean tooLong = segmentLength / FRAME_SAMPLES >= MAX_SEGMENT_FRAMES;
        if (tooLong || silenceFrames >= END_SILENCE_FRAMES) {
            flush(sink, frameAtMs);
        }
    }

    public void finish(Sink sink, long nowMs) {
        if (inSpeech && speechFrames >= MIN_SPEECH_FRAMES) flush(sink, nowMs);
        else reset();
    }

    private void flush(Sink sink, long frameAtMs) {
        if (speechFrames >= MIN_SPEECH_FRAMES && segmentLength > 0) {
            short[] out = new short[segmentLength];
            System.arraycopy(segment, 0, out, 0, segmentLength);
            long startedAt = frameAtMs - (segmentLength / FRAME_SAMPLES) * 20L;
            sink.onSegment(out, segmentLength, startedAt);
        }
        segmentLength = 0;
        speechFrames = 0;
        silenceFrames = 0;
        inSpeech = false;
        prerollFill = 0;
    }

    private void append(short[] samples) {
        if (segmentLength + samples.length > segment.length) return;
        System.arraycopy(samples, 0, segment, segmentLength, samples.length);
        segmentLength += samples.length;
    }

    private void pushPreroll(short[] samples) {
        if (prerollFill < PREROLL_FRAMES) {
            System.arraycopy(samples, 0, preroll, prerollFill * FRAME_SAMPLES, FRAME_SAMPLES);
            prerollFill++;
        } else {
            System.arraycopy(preroll, FRAME_SAMPLES, preroll, 0, (PREROLL_FRAMES - 1) * FRAME_SAMPLES);
            System.arraycopy(samples, 0, preroll, (PREROLL_FRAMES - 1) * FRAME_SAMPLES, FRAME_SAMPLES);
        }
    }

    private void copyPrerollInto() {
        int frames = Math.max(0, prerollFill - 1);
        segmentLength = 0;
        if (frames > 0) {
            System.arraycopy(preroll, 0, segment, 0, frames * FRAME_SAMPLES);
            segmentLength = frames * FRAME_SAMPLES;
        }
        prerollFill = 0;
    }

    static float rmsDb(short[] samples) {
        double sum = 0;
        for (short sample : samples) {
            double v = sample / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / Math.max(1, samples.length));
        if (rms < 1e-7) return -120f;
        return (float) (20 * Math.log10(rms));
    }

    static float zeroCrossingRate(short[] samples) {
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] >= 0) != (samples[i] >= 0)) crossings++;
        }
        return crossings / (float) Math.max(1, samples.length - 1);
    }
}
