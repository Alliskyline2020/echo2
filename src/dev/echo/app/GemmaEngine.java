package dev.echo.app;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.litertlm.ActivationDataType;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.Role;

import java.io.File;
import java.util.Collections;

/**
 * Gemma 4 E2B 的封装（LiteRT-LM）。
 *
 * 一个模型同时干两件事：把音频转成文字、把文字抽成待办/记忆。
 * 这样端上不需要「ASR 模型 + LLM 模型」两份权重，也不用在两者之间搬数据。
 *
 * 几个必须踩过的坑，写在这里免得以后重犯：
 *  - 音频前端被模型固定要求跑在 CPU（`Model requires one of [cpu] but Audio backend is ...`），
 *    所以主体用 GPU、音频后端必须传 CPU；
 *  - `Message` 的 toolCalls / channels 不接受 null；
 *  - `Conversation.sendMessage` 的 extraContext 不接受 null；
 *  - 引擎初始化要几秒，必须复用，不能每段重建。
 */
public final class GemmaEngine {

    private static final String TAG = "echo-engine";
    private static final String MODEL_FILE = "gemma-4-E2B-it.litertlm";

    private static GemmaEngine shared;

    private Engine engine;
    private String backendName = "cpu";

    /** 进程级单例：2.4GB 模型只加载一份，服务和界面共用 */
    public static synchronized GemmaEngine get() {
        if (shared == null) shared = new GemmaEngine();
        return shared;
    }

    public interface Progress {
        void onState(String text);
    }

    public synchronized boolean isReady() {
        return engine != null && engine.isInitialized();
    }

    public String backendName() {
        return backendName;
    }

    /** 初始化引擎；重复调用会直接返回 */
    public synchronized void ensureReady(Context context, Progress progress) throws Exception {
        if (isReady()) return;

        File model = new File(modelPath(context));
        if (!model.isFile() || model.length() < 100_000_000L) {
            throw new IllegalStateException("模型文件不在：" + model.getAbsolutePath());
        }

        String[] candidates = {"gpu", "cpu"};
        Exception last = null;
        for (String candidate : candidates) {
            try {
                if (progress != null) progress.onState("正在加载模型（" + candidate + "）…");
                long startedAt = System.currentTimeMillis();
                Backend main = "gpu".equals(candidate) ? new Backend.GPU() : new Backend.CPU(4, 4);
                EngineConfig config = new EngineConfig(
                        model.getAbsolutePath(),
                        main,
                        null,
                        new Backend.CPU(2, 2), // 音频前端只能是 CPU
                        2048,
                        1,
                        context.getCacheDir().getAbsolutePath(),
                        ActivationDataType.FLOAT16);
                Engine created = new Engine(config);
                created.initialize();
                engine = created;
                backendName = candidate;
                Log.i(TAG, "engine ready backend=" + candidate
                        + " in " + (System.currentTimeMillis() - startedAt) + " ms");
                return;
            } catch (Throwable error) {
                last = error instanceof Exception ? (Exception) error : new Exception(error);
                Log.w(TAG, "init on " + candidate + " failed: " + error);
            }
        }
        throw last != null ? last : new IllegalStateException("模型初始化失败");
    }

    /** 把一段 WAV 转成文字 */
    public synchronized String transcribe(Context context, File wav, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在转写…");
        long startedAt = System.currentTimeMillis();
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(
                        new Content.AudioFile(wav.getAbsolutePath()),
                        new Content.Text("逐字转写这段音频里的中文。只输出转写结果，不要解释、不要加标点以外的内容。")),
                Collections.emptyList(),
                Collections.emptyMap());
        String text = send(message, 1024);
        Log.i(TAG, "transcribe " + wav.getName() + " in "
                + (System.currentTimeMillis() - startedAt) + " ms: " + text);
        return text;
    }

    /** 把一批转写抽成结构化结果 */
    public synchronized String extract(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在整理…");
        String prompt = "下面是一段录音的转写，每行标注了时间和说话人："
                + "【主人】= 手机主人，【他人】= 周围的人或设备播放的内容，【未识别】= 不确定。请抽取三部分：\n"
                + "1) summary：一句话概括（涵盖主人自己的事与环境中值得关注的内容）。\n"
                + "2) todos：需要做的事。规则：\n"
                + "  - 【主人】自己承诺的事：照常收；\n"
                + "  - 【他人】或对话中明确指派给主人的任务（如\"麻烦你把样品寄过来\"、\"你明天交报告\"）：也收，task 开头加[受托]；\n"
                + "  - 【他人】自己的闲聊/自己做的事：不收；\n"
                + "  - 语音助手提示语、系统播报（如\"逐字转写这段音频\"）：绝不收。\n"
                + "每条 {\"task\":\"\",\"time\":\"\",\"who\":\"\",\"evidence\":\"\",\"priority\":\"normal\"}。"
                + "time 填原文时间短语（没有就空串）；who 是涉及的人；"
                + "evidence 是原文依据（不超过30字，标注说话人）；priority 只有 high/normal。\n"
                + "3) memories：两类都收：\n"
                + "  a) 关于【主人】的偏好/状态/事实 → kind 用 preference/state/fact/event；\n"
                + "  b) 【他人】或【未识别】中有价值的信息素材（话题、观点、新闻要点、用户正在听/参与的内容）"
                + "→ kind 固定为\"material\"，subject 格式\"素材.主题\"（如 素材.AI安全），value=一句话要点，confidence 0-1。\n"
                + "每条 {\"kind\":\"\",\"subject\":\"\",\"value\":\"\",\"confidence\":0.8,\"evidence\":\"\"}，"
                + "evidence 不超过30字。同一主题复用同一 subject。\n"
                + "只输出 JSON：{\"summary\":\"\",\"todos\":[],\"memories\":[]}。没有的项给空数组。\n\n"
                + "转写：\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 1024);
    }

    /**
     * LLM 转写纠错：修正 ASR 同音字错误、去重复、规范标点。
     * 约束模型只做最小改动——不确定就保留原样，防止"过度纠错"改变原意。
     */
    public synchronized String refine(Context context, String rawTranscript, Progress progress) throws Exception {
        ensureReady(context, progress);
        String prompt = "下面是一段语音转写原稿，可能有同音字错误、识别噪声和重复字。\n"
                + "只做最小限度的纠错：\n"
                + "1. 修正明显的同音字/错别字（如语音识别把\"提醒\"听成无关词）\n"
                + "2. 删除卡顿重复的字（如\"我怕我怕我怕\"→\"我怕\"）\n"
                + "3. 规范标点\n"
                + "场景提示：这是个人语音助理，内容多为日程、提醒、待办。"
                + "常见的同音纠错方向：\"西沃/席沃/T西沃\"→\"提醒\"、\"开会\"、\"给李四/给老四\"这类人名保留。\n"
                + "禁止：改写句式、增删信息、翻译、总结。不确定的地方保留原样。\n"
                + "直接输出纠正后的文本，不要任何解释和前后缀。\n\n"
                + "原稿：" + rawTranscript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 512);
    }

    /**
     * 通话转写抽取：双方对话，主人承诺照常收；对方对主人的明确承诺也收（task 前加[对方]）。
     */
    public synchronized String extractCall(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在整理通话…");
        String prompt = "下面是一通电话的转写（双方对话，[主人]=手机的主人）。请抽取：\n"
                + "1) todos：需要做的事。主人自己承诺的事照常收；对方明确承诺为主人做的事，"
                + "也收进 todos 并在 task 开头加[对方]。与主人无关的内容不收。\n"
                + "每条 {\"task\":\"\",\"time\":\"\",\"who\":\"\",\"evidence\":\"\",\"priority\":\"normal\"}。"
                + "time 填原文时间短语，没有就空串；evidence 是原文依据（不超过30字）。\n"
                + "2) memories：值得长期记住的关于主人的偏好/状态/事实，"
                + "每条 {\"kind\":\"\",\"subject\":\"\",\"value\":\"\",\"confidence\":0.8,\"evidence\":\"\"}。\n"
                + "只输出 JSON：{\"summary\":\"\",\"todos\":[],\"memories\":[]}。没有的项给空数组。\n\n"
                + "转写：\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 1024);
    }

    /**
     * 语义切分：把一段长转写按话题/活动边界切成多个语义段。
     * 返回 JSON 数组：[{"topic":"...","content":"..."}]
     */
    public synchronized String segmentTopics(Context context, String fullTranscript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("按话题切分…");
        String prompt = "下面是一段连续录音的转写文本。请按话题/活动边界把它切成若干段。"
                + "每段是一个完整的话题或活动，不要把不同话题混在一起。\n"
                + "输出 JSON 数组，每项 {\"topic\":\"话题名\",\"content\":\"该段的完整转写内容\"}。\n"
                + "如果整个转写都是同一个话题，返回只有一个元素的数组。\n"
                + "只输出 JSON 数组，不要其他内容。\n\n"
                + "转写文本：\n" + fullTranscript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 1024);
    }

    /** 开放式小结生成（今日回顾 / 记忆合并共用）：输入简报，输出纯文本 */
    public synchronized String summarize(Context context, String brief, Progress progress) throws Exception {
        ensureReady(context, progress);
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(brief)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 512);
    }

    private String send(Message message, int maxOutputToken) throws Exception {
        // 每条都开新会话：批处理之间不该互相污染上下文
        Conversation conversation = engine.createConversation(new ConversationConfig());
        try {
            Message reply = conversation.sendMessage(
                    message, Collections.emptyMap(), null, null, null, maxOutputToken, null, null);
            if (reply == null || reply.getContents() == null) return "";
            String raw = String.valueOf(reply.getContents());
            Log.i(TAG, "raw reply: " + raw.substring(0, Math.min(300, raw.length())).replace('\n', ' '));
            String cleaned = clean(raw);
            Log.i(TAG, "cleaned len=" + cleaned.length() + " head=" 
                    + cleaned.substring(0, Math.min(100, cleaned.length())).replace('\n', ' '));
            return cleaned;
        } finally {
            try {
                conversation.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Contents.toString() 会带上结构壳，这里把正文抠出来 */
    private String clean(String raw) {
        String text = raw;
        int marker = text.indexOf("Text(");
        if (marker >= 0) {
            text = text.substring(marker + 5);
            int end = text.lastIndexOf(')');
            if (end > 0) text = text.substring(0, end);
        }
        text = text.replace("text=", "").replace("\\n", "\n").trim();
        return stripFence(text);
    }

    /** 模型爱把 JSON 包在 ```json 围栏里，剥掉再落盘 */
    private String stripFence(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            int first = value.indexOf('\n');
            int last = value.lastIndexOf("```");
            if (first > 0 && last > first) {
                value = value.substring(first + 1, last);
            } else if (first > 0) {
                value = value.substring(first + 1);
            }
        }
        return value.trim();
    }

    public synchronized void close() {
        try {
            if (engine != null) engine.close();
        } catch (Throwable ignored) {
        }
        engine = null;
    }

    /** 模型放在 App 私有外部目录，方便 adb push，也不用申请存储权限 */
    public static String modelPath(Context context) {
        File dir = context.getExternalFilesDir(null);
        File base = dir != null ? dir : context.getFilesDir();
        return new File(base, MODEL_FILE).getAbsolutePath();
    }
}
