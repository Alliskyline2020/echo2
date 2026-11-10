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
    
    // ── prompt 外置：从 assets/prompts/ 加载 ──
    private static String loadPrompt(android.content.Context context, String name) {
        try {
            java.io.InputStream is = context.getAssets().open("prompts/" + name + ".txt");
            java.util.Scanner scanner = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : null;
        } catch (Throwable e) { return null; }
    }

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
        String prompt = "你是一个严格的信息提取助手。只输出 JSON，全程简体中文。\n"
                + "\n== 你的任务 ==\n"
                + "从下面的录音转写中提取待办（todos）和记忆（memories）。\n"
                + "\n== 转写内容分类（先判断再提取）==\n"
                + "拿到转写后，先判断内容属于哪种类型：\n"
                + "A. 主人参与的对话或独白 → 正常提取 todos + memories\n"
                + "B. 讲座/播客/视频等单向内容（主人在听，没有对话）→ 只提取 memories（kind=material），todos 给空数组\n"
                + "C. 环境噪声/无意义内容（脚步声、扶梯声、语音助手提示、无法理解的碎片）→ todos 和 memories 都给空数组\n"
                + "\n== 待办准入标准 ==\n"
                + "一条待办必须同时满足：\n"
                + "1. 有具体的动作（做某件事，不是想法或讨论）\n"
                + "2. 是主人的责任（主人自己说的要做，或别人明确指派主人做）\n"
                + "3. 有时间维度（有 deadline，或者不做会有后果）\n"
                + "\n合格示例：\n"
                + "✓ {\"task\":\"给李四打电话\",\"time\":\"明天下午三点\"}（主人承诺的，有时间）\n"
                + "✓ {\"task\":\"[受托] 周五前寄样品\",\"time\":\"周五前\"}（别人指派主人的）\n"
                + "\n不合格示例（不要输出）：\n"
                + "✗ {\"task\":\"考虑AI安全\"}（没有具体动作，是话题不是待办）\n"
                + "✗ {\"task\":\"整理厨房\"}（如果只是聊到别人在整理，不是主人的事）\n"
                + "✗ {\"task\":\"\"}（空 task 绝对不许出现）\n"
                + "✗ 语音助手提示语（\"逐字转写这段音频\"）\n"
                + "\n== 记忆准入标准 ==\n"
                + "记忆 = 对了解这个人有长期价值的信息。问自己：\u201c三个月后这条信息还能帮我更好地服务这个人吗？\u201d\n"
                + "\n四类记忆（kind 字段）：\n"
                + "1. preference（偏好）：主人喜欢/不喜欢/避免什么 → {\"subject\":\"饮食.辣\",\"value\":\"不能吃\"}\n"
                + "   ✓ 门槛：必须是持久的偏好，不是一时的感受\n"
                + "   ✗ \"今天天气不错\" 不是偏好\n"
                + "2. state（状态）：主人当前正在进行的事 → {\"subject\":\"装修.进度\",\"value\":\"正在选材\"}\n"
                + "   ✓ 门槛：会影响近期计划或决策的进行中状态\n"
                + "   ✗ \"刚吃完饭\" 不是有价值的状态\n"
                + "3. fact（事实）：关于主人的稳定事实 → {\"subject\":\"工作.公司\",\"value\":\"在字节跳动\"}\n"
                + "   ✓ 门槛：三个月内不会变的客观事实\n"
                + "4. event（事件）：主人经历的重要事件 → {\"subject\":\"行程.出差\",\"value\":\"下周去上海\"}\n"
                + "   ✓ 门槛：有明确时间和影响的事件\n"
                + "5. material（素材）：环境中主人正在接触的信息 → {\"subject\":\"素材.AI安全\",\"value\":\"了解了某认证框架\"}\n"
                + "   ✓ 门槛：主人主动关注或讨论的话题，不是碰巧听到的\n"
                + "\n不合格记忆示例（不要输出）：\n"
                + "✗ \"有人提到了超多的情况\"（内容空洞，没有具体信息）\n"
                + "✗ \"对方表示这边有\"（碎片，没有含义）\n"
                + "✗ \"确认了某处地点是基地\"（不可理解）\n"
                + "✗ \"有人要求主人对某人说波\"（无意义的语音片段）\n"
                + "\n== 输出格式 ==\n"
                + "{\"summary\":\"一句中文概括\",\"todos\":[{\"task\":\"\",\"time\":\"\",\"who\":\"\",\"evidence\":\"\",\"priority\":\"normal\"}],\"memories\":[{\"kind\":\"\",\"subject\":\"\",\"value\":\"\",\"confidence\":0.8,\"evidence\":\"\"}]}\n"
                + "没有的项给空数组。不确定的不要输出——宁可漏，不可错。\n"
                + "\n转写：\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 1024);
    }

    /**
     * 独立提取记忆（不与待办混合）。
     * 只输出 memories 数组，准入标准独立控制。
     */
    public synchronized String extractMemories(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在沉淀记忆…");
        String prompt = loadPrompt(context, "extract_memories");
        if (prompt == null) prompt = "你是中文助手。从转写中提取关于主人的长期记忆。只输出JSON数组。";
        prompt = prompt + "\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 512);
    }

    /**
     * 会议边界检测：按话题语义切分长转写。
     */
    public synchronized String detectMeetingBoundaries(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在检测活动边界…");
        String prompt = loadPrompt(context, "meeting_boundary_sentinel");
        if (prompt == null) prompt = "你是中文助手。按话题边界切分转写，输出JSON数组。";
        prompt = prompt + "\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 1024);
    }

    /**
     * 提取人物：识别转写中提到的人名、关系、场景。
     */
    public synchronized String extractPeople(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在识别人物…");
        String prompt = loadPrompt(context, "extract_people");
        if (prompt == null) prompt = "你是中文助手。提取转写中提到的人名，输出JSON数组。";
        prompt = prompt + "\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 256);
    }

    /**
     * LLM 转写纠错：修正 ASR 同音字错误、去重复、规范标点。
     * 约束模型只做最小改动——不确定就保留原样，防止"过度纠错"改变原意。
     */
    public synchronized String refine(Context context, String rawTranscript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在纠错…");
        String prompt = "请修正这段转写文本中的错字和标点。规则：\n"
                + "1. 只修明显的同音字错误，不确定就保留原文\n"
                + "2. 去掉无意义的重复词\n"
                + "3. 规范标点符号\n"
                + "4. 不要改变原意，不要添加内容\n"
                + "5. 只输出修正后的文本，不要解释\n\n"
                + "原文：\n" + rawTranscript;
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
        String prompt = "你是中文助手。只输出 JSON，全程简体中文。\n"
                + "下面是一通电话的转写记录（双方对话）。\n"
                + "todos：主人自己承诺的事照常收；对方明确承诺为主人做的事也收，task 开头加[对方]。\n"
                + "与主人无关的内容不收。空 task 不许出现。\n"
                + "memories：关于主人的偏好/状态/事实照常收。\n"
                + "输出：{\"summary\":\"\",\"todos\":[],\"memories\":[]}\n\n"
                + "转写内容：" + transcript;
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


    /** 活动摘要：生成短标题 + 一句话描述 */
    public synchronized String activitySummary(Context context, String transcript, Progress progress) throws Exception {
        ensureReady(context, progress);
        if (progress != null) progress.onState("正在总结…");
        String prompt = "你是中文助手。只输出 JSON，禁止 markdown 代码块。必须使用简体中文。\n"
                + "根据下面的转写内容，判断并生成活动摘要：\n"
                + "\n第一步——先判断内容类型：\n"
                + "如果内容属于以下任何一种，返回 {\"skip\":true}，不要生成摘要：\n"
                + "- 环境声响（脚步、扶梯、关门、咳嗽等非语音）\n"
                + "- 背景播放（电视、播客、视频、音乐等单向输出，不是和主人对话）\n"
                + "- 语音助手提示语或系统播报\n"
                + "- 无法理解或没有实际内容的碎片\n"
                + "- 和主人无关的他人对话（主人没有参与）\n"
                + "\n第二步——如果是主人参与的对话或独白，生成：\n"
                + "{\"title\":\"短标题（4-8个字，如：装修讨论、项目会议、整理厨房）\","
                + "\"description\":\"一句话描述做了什么、关键决定或结论（30-60字）\"}\n"
                + "\n只输出 JSON。\n\n转写内容：\n" + transcript;
        Message message = new Message(
                Role.USER,
                Contents.Companion.of(new Content.Text(prompt)),
                Collections.emptyList(),
                Collections.emptyMap());
        return send(message, 256);
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
