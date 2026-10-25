package dev.echo.app;

import java.util.ArrayList;
import java.util.List;

/** 全部数据模型。一个字段一个用途，JSON 直接落盘。 */
public final class Model {
    private Model() {
    }

    /** 一段有效人声（已落盘的 WAV）+ 它的转写 */
    public static final class Segment {
        public String id;
        public String path;
        public long startedAt;
        public long durationMs;
        public String transcript = "";
        /** LLM 纠错前的 ASR 原稿（留档可溯源） */
        public String rawTranscript;
        public String state = "pending"; // pending | transcribing | done | failed
        public String error;
        /** 说话人：owner（主人）/ other（他人）/ unknown（未注册或太短） */
        public String speaker;
        /** 已尝试转写次数（失败重试上限 3 次） */
        public int attempts;
        /** 最近一次失败时间（冷却用） */
        public long lastFailedAt;
    }

    /** 一次「开始记录 → 停止」的会话，由若干段组成 */
    public static final class Session {
        public String id;
        public long startedAt;
        public long endedAt;
        public List<Segment> segments = new ArrayList<>();
    }

    /** 待办：从录音里抽出来，可勾选完成 */
    public static final class Todo {
        public String id;
        public String task;
        /** 模型给的原始时间短语（"今天下午三点"），另存 dueAt 供提醒分级 */
        public String timeText = "";
        public long dueAt;
        /** high 的待办没有截止时间、搁置超过一天会进 stale-high 提醒 */
        public String priority = "normal";
        /** 这件事涉及的人，没有为空串 */
        public String who = "";
        /** 原文依据，≤30 字 */
        public String evidence = "";
        public boolean done;
        /** 逾期升级提醒时间（0 = 还没提醒过） */
        public long escalatedAt;
        public String sourceSessionId;
        public long createdAt;
    }

    /**
     * 长期记忆，echo 的结构化 schema。
     * subject 是归一化主题（「饮食.辣椒」）；同一主题上新旧取值会互相取代——
     * 这就是冲突检测的根基：subject 相同、value 不同 = 用户的情况变了。
     */
    public static final class MemoryItem {
        public String id;
        public String kind = "fact"; // preference | state | fact | event
        public String subject;
        public String value;
        public float confidence = 0.8f;
        public String evidence = "";
        /** 语义向量（int8 量化 + base64）；为空表示还没算，搜索时会懒补 */
        public String embedding;
        /** active | superseded（被更新取值取代）| deleted（用户否认） */
        public String status = "active";
        public String supersededBy;
        public String sourceSessionId;
        public long createdAt;
        public long lastConfirmedAt;
        /** 复核时间；到期后提示用户确认是否仍成立 */
        public long reviewAt;

        public String title() {
            return subject + "：" + value;
        }
    }

    /** 模型抽取出的结构化结果 */
    public static final class Extraction {
        public String summary = "";
        public final List<String[]> todos = new ArrayList<>(); // [task, timeText]
        public final List<String> memories = new ArrayList<>();
    }

    public static final class Settings {
        public String batchPolicy = "balanced"; // balanced | charging | manual
        /** 麦克风处理档位：voice-recognition(降噪) / voice-communication(回声消除+降噪) / unprocessed(原始) */
        public String micProfile = "voice-recognition";
        /** 聆听时段：always(全天) / day(仅白天 8-22) / manual(只跟磁贴开关) */
        public String listenMode = "always";
        /** 手动暂停（快捷磁贴） */
        public boolean pausedManual = false;
        /** 首次引导（声纹录入）是否已完成 */
        public boolean onboarded = false;
    }
}
