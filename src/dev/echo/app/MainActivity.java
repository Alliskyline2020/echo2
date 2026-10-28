package dev.echo.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.echo.app.Ui.PRIMARY;
import static dev.echo.app.Ui.BG;
import static dev.echo.app.Ui.BORDER;
import static dev.echo.app.Ui.DANGER;
import static dev.echo.app.Ui.DANGER_BG;
import static dev.echo.app.Ui.SUCCESS;
import static dev.echo.app.Ui.HIGHLIGHT;
import static dev.echo.app.Ui.PRIMARY;
import static dev.echo.app.Ui.PRIMARY_SOFT;
import static dev.echo.app.Ui.SECONDARY_BG;
import static dev.echo.app.Ui.SECONDARY_FG;
import static dev.echo.app.Ui.SURFACE;
import static dev.echo.app.Ui.SURFACE_MUTED;
import static dev.echo.app.Ui.TEXT_HI;
import static dev.echo.app.Ui.TEXT_LOW;
import static dev.echo.app.Ui.TEXT_MID;
import static dev.echo.app.Ui.WARNING;
import static dev.echo.app.Ui.WARNING_BG;

/**
 * 回声 —— 语音记录 · 记忆 · 待办（原生版）。
 *
 * 页面结构对齐 echo-src：记录（首页：录音器+洞察+快捷入口+时间线）、记忆、待办、设置。
 * 视觉遵循「多巴胺」亮色主题（Ui.java 令牌）。
 */
public class MainActivity extends Activity implements RecorderService.Listener {

    private static final int REQ_PERMISSIONS = 1;
    private static final long SYNC_COOLDOWN_MS = 3 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat dayTimeFormat = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private Store store;
    private int currentTab = 0;
    private boolean syncing = false;
    private long lastSyncAt = 0;
    private String playingSegmentId;
    private MediaPlayer player;

    private final View[] pageRoots = new View[4];
    private ViewGroup homeBody, memoryBody, todosBody, settingsBody;
    private TextView statusLine, timerView;
    private Button recordButton;
    private EditText searchBox;
    private String searchQuery = "";
    private volatile float[] queryVector;
    private volatile String queryVectorFor = "";
    private volatile boolean searching = false;
    private Button[] tabButtons;
    private volatile boolean enrolling = false;
    private boolean materialExpanded = false;
    private boolean todosExpanded = false;
    private boolean memoriesExpanded = false;
    private boolean recordsExpanded = false;
    private android.animation.ObjectAnimator pulseAnim;
    private boolean lastRenderedRecording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // 浅色底上状态栏图标用深色
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store = Store.get(this);
        Notifier.ensureChannels(this);
        ReminderAlarm.scheduleMaintenance(this);
        ReminderAlarm.rescheduleAll(this);
        // 首次打开：先引导录声纹（无感采集的前提——分清谁在说话），再进主界面
        if (!store.settings().onboarded) {
            setContentView(buildOnboarding());
            requestPermissionsIfNeeded();
        } else {
            setContentView(buildUi());
            requestPermissionsIfNeeded();
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case "record": startRecording(); break;
            case "stop": stopRecording(); break;
            case "sync": syncNow(); break;
            case "calltest":
                // 调试：注入任意通话转写（adb: -es text "..."）
                String callText = intent.getStringExtra("text");
                if (callText == null || callText.isEmpty()) {
                    callText = "王总您好，明天上午九点我去你们公司签合同。好的小李，合同文件我们这边准备好，"
                            + "另外麻烦你周五之前把样品先寄给我们。到时候细节我们电话里再对一遍。";
                }
                CallLink.ingestText(this, callText);
                Toast.makeText(this, "已注入测试通话转写", Toast.LENGTH_SHORT).show();
                break;
            default: break;
        }
    }

    /* ------------------------------------------------------------------ UI */

    /** 首次引导：录声纹 → 进入主界面。录完或跳过都算完成引导。 */
    private LinearLayout buildOnboarding() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(40), dp(28), dp(40));
        root.setBackgroundColor(BG);

        TextView brand = Ui.text(this, "回声", TEXT_HI, Ui.Size.TITLE, true);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand);
        TextView tagline = Ui.text(this, "一直在记，重要的事不会漏", TEXT_MID, Ui.Size.SUB, false);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(6), 0, 0);
        root.addView(tagline);

        root.addView(spacer(28));
        String[] lines = {
                "回声会在后台安静地听，把对话里",
                "你的约定和重要的事记下来。",
                "",
                "为了让它分清「你说的」和「别人说的」，",
                "先花 15 秒录一下你的声音：",
        };
        for (String lineText : lines) {
            TextView view = Ui.text(this, lineText, TEXT_MID, Ui.Size.SUB, false);
            view.setGravity(Gravity.CENTER);
            root.addView(view);
        }
        root.addView(spacer(24));

        boolean modelReady = SpeakerId.modelAvailable(this);
        final Button skip = Ui.outlineButton(this, "跳过，稍后在设置里录入", TEXT_MID);
        Button enroll = Ui.primaryButton(this,
                modelReady ? "录入声纹（15 秒自然说话）" : "先跳过（声纹模型未安装）");
        TextView enrollState = Ui.text(this, "", PRIMARY, Ui.Size.SUB, true);
        enrollState.setGravity(Gravity.CENTER);
        enrollState.setPadding(0, dp(10), 0, 0);
        Button cancelEnroll = Ui.outlineButton(this, "取消", TEXT_MID);
        cancelEnroll.setVisibility(View.GONE);

        enroll.setOnClickListener(v -> {
            if (!modelReady) {
                finishOnboarding();
                return;
            }
            if (enrolling) return;
            enrolling = true;
            enroll.setVisibility(View.GONE);
            skip.setVisibility(View.GONE);
            cancelEnroll.setVisibility(View.VISIBLE);
            enrollState.setText("正在聆听… 15 秒");
            SpeakerId.captureAndEnroll(this, 15, new SpeakerId.CaptureListener() {
                @Override public void onTick(int remainSeconds) {
                    runOnUiThread(() -> enrollState.setText(
                            remainSeconds > 0 ? "正在聆听… 还剩 " + remainSeconds + " 秒" : "正在生成声纹…"));
                }
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "声纹已录入，开始使用", Toast.LENGTH_SHORT).show();
                        finishOnboarding();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        enrolling = false;
                        if ("已取消".equals(message)) {
                            enroll.setVisibility(View.VISIBLE);
                            skip.setVisibility(View.VISIBLE);
                            cancelEnroll.setVisibility(View.GONE);
                            enrollState.setText("已取消");
                            return;
                        }
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        enrollState.setText(message);
                        enroll.setVisibility(View.VISIBLE);
                        skip.setVisibility(View.VISIBLE);
                        cancelEnroll.setVisibility(View.GONE);
                    });
                }
            });
        });
        cancelEnroll.setOnClickListener(v -> SpeakerId.cancelCapture());
        root.addView(enroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(enrollState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(10));
        root.addView(cancelEnroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        skip.setOnClickListener(v -> finishOnboarding());
        root.addView(spacer(10));
        root.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void finishOnboarding() {
        Model.Settings settings = store.settings();
        settings.onboarded = true;
        store.saveSettings(settings);
        setContentView(buildUi());
        showTab(0);
        // 引导完成即开始常驻聆听（受聆听时段约束）
        startRecording();
    }

    /* ------------------------------------------------------------------ UI */

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        homeBody = makeScrollPage(container, 0);
        memoryBody = makeColumnPage(container, 1);
        todosBody = makeColumnPage(container, 2);
        settingsBody = makeScrollPage(container, 3);
        root.addView(container);
        root.addView(tabHairline());
        root.addView(tabBar());

        showTab(0);
        return root;
    }

    private View tabHairline() {
        View hairline = new View(this);
        hairline.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        hairline.setBackgroundColor(BORDER);
        return hairline;
    }

    private LinearLayout tabBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(SURFACE);
        String[] names = {"◉ 记录", "◆ 记忆", "✓ 待办", "⚙ 设置"};
        tabButtons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(names[i]);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setStateListAnimator(null);
            button.setMinHeight(0);
            button.setPadding(0, dp(10), 0, dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(4), 0, dp(4), 0);
            button.setLayoutParams(params);
            button.setOnClickListener(v -> showTab(index));
            tabs.addView(button);
            tabButtons[i] = button;
        }
        return tabs;
    }

    private void styleTab(int index, boolean selected) {
        Button button = tabButtons[index];
        button.setBackground(Ui.pill(this, dp(20), selected ? PRIMARY_SOFT : Color.TRANSPARENT));
        button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        button.setTextColor(selected ? PRIMARY : TEXT_MID);
    }

    private void showTab(int index) {
        currentTab = index;
        for (int i = 0; i < pageRoots.length; i++) {
            if (pageRoots[i] != null) {
                pageRoots[i].setVisibility(i == index ? ViewGroup.VISIBLE : ViewGroup.GONE);
            }
        }
        for (int i = 0; i < tabButtons.length; i++) styleTab(i, i == index);
        render();
    }

    private LinearLayout makeScrollPage(FrameLayout container, int index) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(Ui.PAGE_H), dp(12), dp(Ui.PAGE_H), dp(24));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageRoots[index] = scroll;
        return body;
    }

    private LinearLayout makeColumnPage(FrameLayout container, int index) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(Ui.PAGE_H), dp(12), dp(Ui.PAGE_H), dp(24));
        body.setBackgroundColor(BG);
        container.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageRoots[index] = body;
        return body;
    }

    private void render() {
        store = Store.get(this);
        switch (currentTab) {
            case 0: renderHome(); break;
            case 1: renderMemory(); break;
            case 2: renderTodos(); break;
            default: renderSettings(); break;
        }
    }

    /* ------------------------------- 记录（首页） ------------------------------- */

    private void renderHome() {
        homeBody.removeAllViews();
        long now = System.currentTimeMillis();

        // 日期行（品牌在启动器图标上，不需要每次重复）
        TextView dateLine = Ui.text(this,
                new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date(now)),
                TEXT_LOW, Ui.Size.CAP, false);
        homeBody.addView(dateLine);
        homeBody.addView(spacer(10));

        // 待办数据在洞察和快捷入口都需要，先算
        List<Model.Todo> open = openTodos();

        // 现在值得处理的事（洞察：注意级 = warning 浅底，次级 = 凹陷面）
        List<Insight.Item> items = Insight.build(store, now);
        // 只展示需要行动的洞察，nothing-yet 隐去（安静就是最好的状态）
        items.removeIf(item -> "nothing-yet".equals(item.kind));
        if (!items.isEmpty()) {
            int insightMax = Math.min(3, items.size());
            for (int ii = 0; ii < insightMax; ii++) { Insight.Item item = items.get(ii);
                boolean attention = "overdue".equals(item.kind) || "due-soon".equals(item.kind);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(11), dp(14), dp(11));
                row.setBackground(Ui.pill(this, dp(6),
                        attention ? WARNING_BG : SURFACE_MUTED));
                int dotColor = "overdue".equals(item.kind) ? DANGER
                        : "due-soon".equals(item.kind) ? WARNING
                        : "stale-high".equals(item.kind) ? WARNING
                        : "memory-conflict".equals(item.kind) ? PRIMARY
                        : "memory-review".equals(item.kind) ? SECONDARY_FG
                        : TEXT_LOW;
                ImageView dot = new ImageView(this);
                dot.setImageDrawable(Ui.dot(this, dotColor, 6));
                row.addView(dot);
                TextView text = Ui.text(this, item.text,
                        attention ? WARNING : TEXT_MID, Ui.Size.SUB, false);
                text.setPadding(dp(9), 0, 0, 0);
                row.addView(text);
                todaySpacer();
                homeBody.addView(row);
            }
            homeBody.addView(spacer(12));
        }

        // 录音面板
        LinearLayout panel = card();
        if (RecorderService.recording) {
            LinearLayout live = new LinearLayout(this);
            live.setGravity(Gravity.CENTER_VERTICAL);
            ImageView dot = new ImageView(this);
            dot.setImageDrawable(Ui.dot(this, DANGER, 9));
            live.addView(dot);
            if (pulseAnim != null) pulseAnim.cancel();
            pulseAnim = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
            pulseAnim.setDuration(650);
            pulseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulseAnim.start();
            TextView liveText = Ui.text(this, " 正在记录", TEXT_HI, Ui.Size.SUB, true);
            live.addView(liveText);
            panel.addView(live);

            timerView = Ui.text(this, elapsedText(), TEXT_HI, Ui.Size.H2, true);
            timerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            timerView.setPadding(dp(2), dp(4), 0, 0);
            panel.addView(timerView);
            panel.addView(spacer(12));
            Button stop = Ui.outlineButton(this, "停止并整理", TEXT_HI);
            stop.setOnClickListener(v -> stopRecording());
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            panel.addView(stop, stopParams);
        } else {
            // 精简：按钮即入口，不需要装饰性行
            recordButton = Ui.primaryButton(this, "●  开始记录");
            recordButton.setOnClickListener(v -> startRecording());
            LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            panel.addView(recordButton, recordParams);
            // 状态行只在有信息量时出现（错误 / 整理结果）；"空闲"是噪音，不显示
            if (!"空闲".equals(RecorderService.status)) {
                TextView statusNote = Ui.text(this, RecorderService.status, TEXT_LOW, Ui.Size.CAP, false);
                statusNote.setPadding(0, dp(6), 0, 0);
                statusLine = statusNote;
                panel.addView(statusNote);
            }
        }
        homeBody.addView(cardWrap(panel));

        // 今日回顾：有内容才展示，没有就不占位
        String digest = store.getDigest(Store.todayKey());
        if (digest != null) {
            LinearLayout digestCard = card();
            digestCard.addView(Ui.text(this, "今日回顾", TEXT_MID, Ui.Size.SUB, true));
            digestCard.addView(spacer(4));
            digestCard.addView(Ui.text(this, digest, TEXT_HI, Ui.Size.SUB, false));
            homeBody.addView(cardWrap(digestCard));
        }

        // 快捷入口
        LinearLayout quick = card();
        int openCount = openTodos().size();
        int reviewCount = reviewDueCount();
        String todoPreview = openCount > 0
                ? open.get(0).task : "暂无";
        String memPreview = activeMemories().isEmpty() ? "暂无"
                : activeMemories().get(activeMemories().size() - 1).value;
        quick.addView(quickRow("待办",
                openCount > 0 ? openCount + " 项 · " + todoPreview : "暂无", () -> showTab(2)));
        quick.addView(Ui.divider(this));
        quick.addView(quickRow("记忆",
                reviewCount > 0 ? reviewCount + " 条待复核" : memPreview, () -> showTab(1)));
        homeBody.addView(cardWrap(quick));

        // 时间线
        homeBody.addView(spacer(16));
        homeBody.addView(Ui.sectionLabel(this, "记录"));
        homeBody.addView(spacer(6));
        List<Model.Session> sessions = store.sessions();
        if (sessions.isEmpty()) {
            homeBody.addView(emptyCard("还没有记录", "点上面的「开始记录」，应用会自动分段保存。"));
            return;
        }
        String lastDay = "";
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Model.Session session = sessions.get(i);
            String day = dayFormat.format(new Date(session.startedAt));
            if (!day.equals(lastDay)) {
                homeBody.addView(Ui.sectionLabel(this, day));
                homeBody.addView(spacer(4));
                lastDay = day;
            }
            LinearLayout sessionCard = card();
            for (int j = session.segments.size() - 1; j >= 0; j--) {
                sessionCard.addView(segmentRow(session.segments.get(j)));
                if (j > 0) sessionCard.addView(Ui.divider(this));
            }
            homeBody.addView(cardWrap(sessionCard));
            homeBody.addView(spacer(10));
        }
    }

    private void todaySpacer() {
        homeBody.addView(spacer(6));
    }

    private LinearLayout quickRow(String label, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.clickable(this, Ui.pill(this, 0, Color.TRANSPARENT)));
        row.addView(Ui.text(this, label, TEXT_HI, Ui.Size.BODY, true));
        TextView valueView = Ui.text(this, value, TEXT_MID, Ui.Size.SUB, false);
        valueView.setPadding(0, 0, dp(6), 0);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.gravity = Gravity.CENTER_VERTICAL;
        valueView.setGravity(Gravity.END);
        valueView.setLayoutParams(valueParams);
        row.addView(valueView);
        TextView chevron = Ui.text(this, "›", TEXT_LOW, Ui.Size.H2, false);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private LinearLayout segmentRow(Model.Segment segment) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 单行头部：时间 + 转写（done 段省略状态标签，减少视觉噪音）
        String timeStr = clockFormat.format(new Date(segment.startedAt));
        String text = segment.transcript;
        if (text != null && !text.isEmpty()) {
            // 转写 + 时间在同一行（时间小字灰底，转写正文紧随）
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(Ui.text(this, timeStr, TEXT_LOW, Ui.Size.CAP, true));
            TextView body = Ui.text(this, " " + text, TEXT_HI, Ui.Size.SUB, false);
            body.setPadding(dp(8), 0, 0, 0);
            line.addView(body, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(line);
        } else {
            // 没转写：显示时间和状态
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.text(this, timeStr, TEXT_LOW, Ui.Size.CAP, true));
            String state = "transcribing".equals(segment.state) ? " 整理中…"
                    : "failed".equals(segment.state) ? " 失败（自动重试）" : " 待整理";
            TextView stateView = Ui.text(this, state,
                    "failed".equals(segment.state) ? DANGER : TEXT_LOW, Ui.Size.CAP, false);
            stateView.setPadding(dp(8), 0, 0, 0);
            head.addView(stateView);
            row.addView(head);
        }

        // done 段才有播放按钮
        if ("done".equals(segment.state)) {
            LinearLayout playRow = new LinearLayout(this);
            playRow.setGravity(Gravity.CENTER_VERTICAL);
            playRow.setPadding(0, dp(6), 0, 0);
            boolean isPlaying = playingSegmentId != null && playingSegmentId.equals(segment.id);
            TextView play = new TextView(this);
            play.setText(isPlaying ? "■" : "▶");
            play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            play.setTextColor(PRIMARY);
            play.setBackground(Ui.dot(this, PRIMARY_SOFT, 32));
            play.setGravity(Gravity.CENTER);
            play.setOnClickListener(v -> togglePlay(segment));
            playRow.addView(play, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView playLabel = Ui.text(this, isPlaying ? "停止" : "回放", PRIMARY, Ui.Size.CAP, false);
            playLabel.setPadding(dp(6), 0, 0, 0);
            playRow.addView(playLabel);
            row.addView(playRow);
        }
        return row;
    }

    /* --------------------------------- 记忆页 --------------------------------- */

    private void renderMemory() {
        memoryBody.removeAllViews();
        TextView brand = Ui.text(this, "记忆", TEXT_HI, Ui.Size.TITLE, true);
        memoryBody.addView(brand);
        memoryBody.addView(spacer(10));

        if (searchBox != null && searchBox.getParent() instanceof ViewGroup) {
            ((ViewGroup) searchBox.getParent()).removeView(searchBox);
        }
        if (searchBox == null) {
            searchBox = new EditText(this);
            searchBox.setHint("搜索记忆，或描述你想找的事…");
            searchBox.setTextColor(TEXT_HI);
            searchBox.setHintTextColor(TEXT_LOW);
            searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            searchBox.setBackground(Ui.card(this, Ui.RADIUS_SM, SURFACE, BORDER));
            searchBox.setPadding(dp(12), dp(11), dp(12), dp(11));
            memoryBody.addView(searchBox);
            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable editable) {
                    searchQuery = editable.toString().trim();
                    renderMemoryList();
                }
            });
        } else {
            memoryBody.addView(searchBox);
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("memory-list");
        list.setPadding(0, dp(12), 0, 0);
        memoryBody.addView(list);
        renderMemoryList();
    }

    private void renderMemoryList() {
        LinearLayout list = memoryBody.findViewWithTag("memory-list");
        if (list == null) return;
        list.removeAllViews();
        long now = System.currentTimeMillis();
        List<Model.MemoryItem> memories = store.memories();

        List<Model.MemoryItem> due = new ArrayList<>();
        for (Model.MemoryItem item : memories) {
            if ("active".equals(item.status) && item.reviewAt > 0 && now > item.reviewAt) due.add(item);
        }
        if (!due.isEmpty()) {
            LinearLayout reviewCard = card();
            reviewCard.addView(Ui.text(this, "这些记忆很久没确认了，还准吗？", TEXT_MID, Ui.Size.SUB, true));
            for (Model.MemoryItem item : due) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(9), 0, dp(9));
                row.addView(Ui.text(this, item.subject + "：" + item.value, TEXT_HI, Ui.Size.SUB, true));
                if (!item.evidence.isEmpty()) row.addView(quote(this, item.evidence));
                LinearLayout actions = new LinearLayout(this);
                actions.setPadding(0, dp(6), 0, 0);
                Button keep = Ui.outlineButton(this, "仍然成立", SECONDARY_FG);
                keep.setOnClickListener(v -> {
                    item.lastConfirmedAt = System.currentTimeMillis();
                    item.reviewAt = item.lastConfirmedAt + Pipeline.REVIEW_TTL_MS;
                    store.updateMemory(item);
                    render();
                });
                Button drop = Ui.outlineButton(this, "不对，删掉", DANGER);
                drop.setOnClickListener(v -> {
                    item.status = "deleted";
                    store.updateMemory(item);
                    render();
                });
                actions.addView(keep);
                actions.addView(drop);
                row.addView(actions);
                reviewCard.addView(row);
            }
            list.addView(section("记忆复核", reviewCard));
        }

        List<Model.MemoryItem> matched = new ArrayList<>();
        List<Model.MemoryItem> material = new ArrayList<>();
        for (Model.MemoryItem item : memories) {
            if ("deleted".equals(item.status)) continue;
            boolean hit = searchQuery.isEmpty()
                    || searchable(item).contains(searchQuery.toLowerCase(Locale.CHINA));
            if (!hit) continue;
            if ("material".equals(item.kind)) material.add(item);
            else matched.add(item);
        }
        // 素材区：默认折叠只显数量，点开看全部（精简呈现，不与主记忆争注意力）
        if (!material.isEmpty()) {
            LinearLayout materialCard = card();
            TextView materialHead = Ui.text(this, "素材 · " + material.size() + " 条"
                    + (materialExpanded ? "（点收起）" : "（点展开）"), TEXT_MID, Ui.Size.SUB, true);
            materialHead.setOnClickListener(v -> {
                materialExpanded = !materialExpanded;
                renderMemoryList();
            });
            materialCard.addView(materialHead);
            if (materialExpanded) {
                materialCard.addView(spacer(4));
                for (Model.MemoryItem item : material) {
                    materialCard.addView(Ui.divider(this));
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(0, dp(8), 0, dp(8));
                    row.addView(kindChip(item.kind));
                    TextView subject = Ui.text(this, item.subject, TEXT_MID, Ui.Size.SUB, false);
                    subject.setPadding(dp(7), 0, 0, 0);
                    row.addView(subject);
                    row.addView(Ui.text(this, item.value, TEXT_HI, Ui.Size.SUB, false));
                    if (!item.evidence.isEmpty()) row.addView(quote(this, item.evidence));
                    materialCard.addView(row);
                }
            }
            list.addView(section("素材（环境内容，不产生待办）", materialCard));
        }
        if (matched.isEmpty() && due.isEmpty()) {
            list.addView(card(Ui.text(this, searchQuery.isEmpty()
                    ? "还没有记忆，录一段话试试" : "没有匹配的记忆", TEXT_LOW, Ui.Size.SUB, false)));
            return;
        }
        if (searchQuery.isEmpty()) {
            for (int i = matched.size() - 1; i >= 0; i--) {
                list.addView(cardWrap(memoryCard(matched.get(i), false, 0)));
            }
            return;
        }

        list.addView(card(Ui.text(this, "正在按语义匹配「" + searchQuery + "」…", TEXT_LOW, Ui.Size.SUB, false)));
        final List<Model.MemoryItem> candidates = new ArrayList<>(matched);
        searching = true;
        new Thread(() -> {
            float[] qv = null;
            try {
                if (MemoryEmbedder.available(this)) {
                    if (!searchQuery.equals(queryVectorFor)) {
                        queryVector = MemoryEmbedder.embed(this, searchQuery, null);
                        queryVectorFor = searchQuery;
                    }
                    qv = queryVector;
                }
            } catch (Throwable error) {
                Log.w("echo-search", "query embed failed: " + error);
            }
            final float[] queryVec = qv;
            List<Model.MemoryItem> ranked = new ArrayList<>(candidates);
            Map<String, Float> scores = new java.util.HashMap<>();
            Map<String, Boolean> semanticHit = new java.util.HashMap<>();
            for (Model.MemoryItem item : ranked) {
                float kw = searchable(item).contains(searchQuery.toLowerCase(Locale.CHINA)) ? 1f : 0f;
                float recency = (float) (1.0 / (1.0 + (now - item.lastConfirmedAt) / (30f * 86400_000L)));
                float sem = 0;
                if (queryVec != null) {
                    try {
                        float[] itemVec = item.embedding != null
                                ? MemoryEmbedder.dequantize(item.embedding)
                                : MemoryEmbedder.embed(this, item.subject + " " + item.value, null);
                        if (item.embedding == null) {
                            item.embedding = MemoryEmbedder.quantize(itemVec);
                            store.updateMemory(item);
                        }
                        sem = Math.max(0, MemoryEmbedder.cosine(queryVec, itemVec));
                    } catch (Throwable error) {
                        Log.w("echo-search", "embed failed: " + error);
                    }
                }
                float score = 0.55f * sem + 0.25f * kw + 0.12f * recency + 0.08f * item.confidence;
                scores.put(item.id, score);
                semanticHit.put(item.id, sem > 0.5f && kw == 0f);
            }
            ranked.sort((a, b) -> Float.compare(
                    scores.getOrDefault(b.id, 0f), scores.getOrDefault(a.id, 0f)));
            searching = false;
            runOnUiThread(() -> {
                if (!searchQuery.equals(queryVectorFor)) return;
                LinearLayout resultList = memoryBody.findViewWithTag("memory-list");
                if (resultList == null) return;
                resultList.removeAllViews();
                for (Model.MemoryItem item : ranked) {
                    resultList.addView(cardWrap(memoryCard(item,
                            semanticHit.getOrDefault(item.id, false),
                            scores.getOrDefault(item.id, 0f))));
                }
            });
        }, "echo-semantic-search").start();
    }

    private LinearLayout memoryCard(Model.MemoryItem item, boolean semanticHit, float score) {
        boolean superseded = "superseded".equals(item.status);
        LinearLayout card = card();
        if (superseded) card.setAlpha(0.55f);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(kindChip(item.kind));
        TextView subject = Ui.text(this, item.subject, TEXT_MID, Ui.Size.SUB, false);
        subject.setPadding(dp(8), 0, 0, 0);
        head.addView(subject);
        card.addView(head);

        TextView value = Ui.text(this, item.value, superseded ? TEXT_MID : TEXT_HI, Ui.Size.BODY, true);
        value.setPadding(0, dp(4), 0, 0);
        card.addView(value);

        if (!item.evidence.isEmpty()) card.addView(quote(this, item.evidence));

        String meta = dayTimeFormat.format(new Date(item.lastConfirmedAt))
                + (superseded ? " · 已被取代" : "");
        TextView metaView = Ui.text(this, meta, TEXT_LOW, Ui.Size.CAP, false);
        metaView.setPadding(0, dp(3), 0, 0);
        card.addView(metaView);
        return card;
    }

    private TextView kindChip(String kind) {
        int color;
        String label;
        int bg;
        switch (kind) {
            case "preference": color = SECONDARY_FG; label = "偏好"; bg = SECONDARY_BG; break;
            case "state": color = WARNING; label = "状态"; bg = WARNING_BG; break;
            case "event": color = PRIMARY; label = "事件"; bg = PRIMARY_SOFT; break;
            case "material": color = TEXT_MID; label = "素材"; bg = SURFACE_MUTED; break;
            default: color = TEXT_MID; label = "事实"; bg = SURFACE_MUTED; break;
        }
        return Ui.chip(this, label, bg, color);
    }

    /* --------------------------------- 待办页 --------------------------------- */

    private void renderTodos() {
        todosBody.removeAllViews();
        TextView brand = Ui.text(this, "待办", TEXT_HI, Ui.Size.TITLE, true);
        todosBody.addView(brand);
        todosBody.addView(spacer(12));

        long now = System.currentTimeMillis();
        List<Model.Todo> open = openTodos();
        List<Model.Todo> done = new ArrayList<>();
        for (Model.Todo todo : store.todos()) {
            if (todo.done) done.add(todo);
        }

        // 按紧急度分组：逾期 → 今天 → 明天 → 之后 → 无日期
        List<Model.Todo> overdue = new ArrayList<>();
        List<Model.Todo> today = new ArrayList<>();
        List<Model.Todo> tomorrow = new ArrayList<>();
        List<Model.Todo> later = new ArrayList<>();
        List<Model.Todo> noDate = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        long tomorrowStart = todayStart + 86400_000L;
        long dayAfterStart = tomorrowStart + 86400_000L;

        for (Model.Todo todo : open) {
            if (todo.dueAt > 0 && todo.dueAt < todayStart) overdue.add(todo);
            else if (todo.dueAt >= todayStart && todo.dueAt < tomorrowStart) today.add(todo);
            else if (todo.dueAt >= tomorrowStart && todo.dueAt < dayAfterStart) tomorrow.add(todo);
            else if (todo.dueAt > 0) later.add(todo);
            else noDate.add(todo);
        }

        int total = open.size();
        if (total == 0) {
            LinearLayout emptyCard = card();
            emptyCard.addView(Ui.text(this, "暂无待办", TEXT_LOW, Ui.Size.SUB, false));
            todosBody.addView(emptyCard);
        } else {
            if (!overdue.isEmpty()) emitTodoGroup(todosBody, "逾期 · " + overdue.size(), overdue, DANGER);
            if (!today.isEmpty()) emitTodoGroup(todosBody, "今天 · " + today.size(), today, WARNING);
            if (!tomorrow.isEmpty()) emitTodoGroup(todosBody, "明天 · " + tomorrow.size(), tomorrow, TEXT_HI);
            if (!later.isEmpty()) emitTodoGroup(todosBody, "之后 · " + later.size(), later, TEXT_MID);
            if (!noDate.isEmpty()) emitTodoGroup(todosBody, "无日期 · " + noDate.size(), noDate, TEXT_LOW);

            if (total > 5 && !todosExpanded) {
                TextView more = Ui.text(this, "展开全部 " + total + " 项  ›", PRIMARY, Ui.Size.SUB, false);
                more.setPadding(dp(2), dp(8), 0, 0);
                more.setOnClickListener(v -> { todosExpanded = true; render(); });
                todosBody.addView(more);
            } else if (todosExpanded && total > 5) {
                TextView less = Ui.text(this, "收起  ‹", TEXT_MID, Ui.Size.SUB, false);
                less.setPadding(dp(2), dp(8), 0, 0);
                less.setOnClickListener(v -> { todosExpanded = false; render(); });
                todosBody.addView(less);
            }
        }

        if (!done.isEmpty()) {
            LinearLayout doneCard = card();
            for (Model.Todo todo : done) {
                TextView view = Ui.text(this, "✓ " + todo.task, TEXT_LOW, Ui.Size.SUB, false);
                view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                view.setPadding(dp(2), dp(6), dp(2), dp(6));
                doneCard.addView(view);
            }
            todosBody.addView(section("已完成 · " + done.size(), doneCard));
        }
    }

    private void emitTodoGroup(String label, List<Model.Todo> items, int color) {
        // delegate
    }

    private void emitTodoGroup(ViewGroup parent, String label, List<Model.Todo> items, int color) {
        LinearLayout groupCard = card();
        int shown = todosExpanded ? items.size() : Math.min(3, items.size());
        for (int i = 0; i < shown && i < items.size(); i++) {
            todosBodySee(items.get(i), groupCard);
        }
        if (items.size() > shown) {
            TextView more = Ui.text(this, "还有 " + (items.size() - shown) + " 项…", TEXT_LOW, Ui.Size.CAP, false);
            more.setPadding(dp(4), dp(4), 0, 0);
            groupCard.addView(more);
        }
        parent.addView(section(label, groupCard));
    }

    private void todosBodySee(Model.Todo todo, LinearLayout parent) {
        long now = System.currentTimeMillis();
        String level = Reminder.decide(todo, now);
        boolean urgent = "overdue".equals(level) || "high".equals(todo.priority);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(9), dp(2), dp(9));
        row.setBackground(Ui.clickable(this, Ui.pill(this, Ui.RADIUS_SM,
                urgent ? Color.argb(18, 180, 35, 24) : Color.TRANSPARENT)));

        TextView check = new TextView(this);
        check.setGravity(Gravity.CENTER);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        GradientDrawable checkBg = Ui.dot(this, todo.done ? SUCCESS : Color.TRANSPARENT, 22);
        if (todo.done) {
            checkBg.setStroke(dp(2), SUCCESS);
            check.setTextColor(Color.WHITE);
            check.setText("✓");
        } else {
            checkBg.setStroke(dp(2), BORDER);
            check.setText("");
        }
        check.setBackground(checkBg);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        checkParams.gravity = Gravity.CENTER_VERTICAL;
        check.setLayoutParams(checkParams);
        row.addView(check);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(11), 0, 0, 0);
        textCol.addView(Ui.text(this, todo.task, TEXT_HI, Ui.Size.BODY, false));
        StringBuilder meta = new StringBuilder();
        if (todo.dueAt > 0) {
            meta.append(dayTimeFormat.format(new Date(todo.dueAt)));
            if (Reminder.OVERDUE.equals(level)) meta.append(" · 已逾期");
        } else if (!todo.timeText.isEmpty()) {
            meta.append(todo.timeText);
        }
        if (!todo.who.isEmpty()) meta.append(" · ").append(todo.who);
        if (meta.length() > 0) {
            boolean isOverdue = Reminder.OVERDUE.equals(level);
            TextView metaView = Ui.text(this, meta.toString(),
                    isOverdue ? DANGER : TEXT_LOW, Ui.Size.CAP, false);
            metaView.setPadding(0, dp(2), 0, 0);
            textCol.addView(metaView);
        }
        if (!todo.evidence.isEmpty()) {
            TextView evidence = Ui.text(this, "「" + todo.evidence + "」", TEXT_LOW, Ui.Size.CAP, false);
            evidence.setPadding(0, dp(2), 0, 0);
            textCol.addView(evidence);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if ("high".equals(todo.priority)) {
            row.addView(Ui.chip(this, "高", DANGER_BG, DANGER));
        }
        row.setOnClickListener(v -> {
            todo.done = true;
            store.updateTodo(todo);
            render();
        });
        parent.addView(row);
    }

    /* --------------------------------- 设置页 --------------------------------- */

    private void renderSettings() {
        settingsBody.removeAllViews();
        TextView brand = Ui.text(this, "设置", TEXT_HI, Ui.Size.TITLE, true);
        settingsBody.addView(brand);
        settingsBody.addView(spacer(12));

        LinearLayout model = card();
        model.addView(line("Gemma 4 E2B · 转写与整理", TEXT_HI));
        File modelFile = new File(GemmaEngine.modelPath(this));
        model.addView(line(modelFile.isFile()
                ? "已就绪 · " + (modelFile.length() / 1024 / 1024) + " MB · 本机推理"
                : "未找到模型文件：" + GemmaEngine.modelPath(this), TEXT_LOW));
        model.addView(line(MemoryEmbedder.available(this)
                ? "EmbeddingGemma 2 · 语义检索已就绪" : "语义检索未安装", TEXT_LOW));
        settingsBody.addView(section("推理", model));

        // 声纹：录入一次，之后每段话自动判断是不是主人说的
        LinearLayout voice = card();
        boolean enrolled = SpeakerId.enrolled(this);
        voice.addView(line(enrolled ? "声纹已录入 · 待办只摘取主人说的话" : "声纹未录入 · 无差别摘取", TEXT_HI));
        voice.addView(line(enrolled ? "他人说的话只作背景记录" : "建议录入，避免把别人的事记成你的", TEXT_LOW));
        voice.addView(spacer(8));
        Button enrollButton = Ui.outlineButton(this, enrolled ? "重新录入声纹" : "录入声纹（15 秒）", PRIMARY);
        TextView enrollState = Ui.text(this, "", TEXT_MID, Ui.Size.SUB, false);
        enrollButton.setOnClickListener(v -> {
            if (enrolling) return;
            if (RecorderService.recording) {
                Toast.makeText(this, "请先停止记录再录入声纹", Toast.LENGTH_LONG).show();
                return;
            }
            if (!SpeakerId.modelAvailable(this)) {
                Toast.makeText(this, "声纹模型未安装", Toast.LENGTH_SHORT).show();
                return;
            }
            enrolling = true;
            enrollButton.setEnabled(false);
            enrollState.setText("正在聆听… 15 秒，请自然说话");
            SpeakerId.captureAndEnroll(this, 15, new SpeakerId.CaptureListener() {
                @Override public void onTick(int remainSeconds) {
                    runOnUiThread(() -> enrollState.setText(
                            remainSeconds > 0 ? "正在聆听… 还剩 " + remainSeconds + " 秒" : "正在生成声纹…"));
                }
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        enrolling = false;
                        enrollButton.setEnabled(true);
                        Toast.makeText(MainActivity.this, "声纹已录入", Toast.LENGTH_SHORT).show();
                        render();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        enrolling = false;
                        enrollButton.setEnabled(true);
                        enrollState.setText(message);
                        if (!"已取消".equals(message)) {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });
        voice.addView(enrollButton);
        voice.addView(enrollState);
        settingsBody.addView(section("声纹", voice));

        settingsBody.addView(section("整理节奏", optionGroup(
                new String[]{"攒够就整理", "只在充电时整理", "只手动整理"},
                new String[]{"停止录音后攒一批再跑", "只在充电时批量跑，最省电", "完全不自动"},
                new String[]{"balanced", "charging", "manual"},
                store.settings().batchPolicy,
                key -> {
                    Model.Settings value = store.settings();
                    value.batchPolicy = key;
                    store.saveSettings(value);
                    render();
                })));

        settingsBody.addView(section("麦克风处理", optionGroup(
                new String[]{"降噪", "回声消除 + 降噪", "原始麦克风"},
                new String[]{"DSP 降噪图，最贴语音识别", "边播放边录时用，借 DSP 回声消除", "不过 DSP，原始数据自己算"},
                new String[]{"voice-recognition", "voice-communication", "unprocessed"},
                store.settings().micProfile,
                key -> {
                    Model.Settings value = store.settings();
                    value.micProfile = key;
                    store.saveSettings(value);
                    render();
                })));

        int segments = 0;
        for (Model.Session session : store.sessions()) segments += session.segments.size();
        LinearLayout data = card();
        data.addView(line(store.sessions().size() + " 次记录 · " + segments + " 段 · "
                + store.todos().size() + " 待办 · " + store.memories().size() + " 条记忆", TEXT_HI));
        data.addView(spacer(8));
        Button clear = Ui.outlineButton(this, "清空全部数据", DANGER);
        clear.setOnClickListener(v -> confirmClear());
        data.addView(clear);
        settingsBody.addView(section("数据", data));

        LinearLayout about = card();
        about.addView(line("回声 0.3.0-native", TEXT_MID));
        about.addView(line("Gemma 4 E2B + EmbeddingGemma 2 · LiteRT-LM · 全部推理在本机完成", TEXT_LOW));
        settingsBody.addView(section("关于", about));
    }

    private LinearLayout optionGroup(String[] names, String[] notes, String[] keys,
                                     String current, java.util.function.Consumer<String> onPick) {
        LinearLayout cardBox = card();
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean active = key.equals(current);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackground(active
                    ? Ui.pill(this, Ui.RADIUS_SM, PRIMARY_SOFT)
                    : Ui.pill(this, Ui.RADIUS_SM, Color.TRANSPARENT));
            row.addView(Ui.text(this, (active ? "● " : "○ ") + names[i],
                    active ? PRIMARY : TEXT_HI, Ui.Size.SUB, true));
            TextView noteText = Ui.text(this, notes[i], TEXT_LOW, Ui.Size.CAP, false);
            row.addView(noteText);
            LinearLayout.LayoutParams noteParams = (LinearLayout.LayoutParams) noteText.getLayoutParams();
            noteParams.setMargins(0, dp(2), 0, 0);
            row.setOnClickListener(v -> {
                onPick.accept(key);
                render();
            });
            cardBox.addView(row);
        }
        return cardBox;
    }

    /* --------------------------------------------------------------- 动作 */

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded();
            return;
        }
        Intent intent = new Intent(this, RecorderService.class);
        intent.setAction("start");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecorderService.class);
        intent.setAction("stop");
        startService(intent);
    }

    /** 手动触发今日回顾生成 */
    private void startDigest() {
        Intent intent = new Intent(this, RecorderService.class);
        intent.setAction("digest");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "正在生成今日回顾…", Toast.LENGTH_SHORT).show();
    }

    private void syncNow() {
        Log.i("echo-sync", "syncNow 触发 syncing=" + syncing);
        if (syncing) return;
        syncing = true;
        Log.i("echo-sync", "整理线程启动");
        setStatus("正在整理…");
        new Thread(() -> {
            try {
                GemmaEngine engine = GemmaEngine.get();
                engine.ensureReady(this, text -> runOnUiThread(() -> setStatus(text)));
                Store fresh = Store.get(this);
                int count = Pipeline.sync(this, fresh, engine, null, text -> runOnUiThread(() -> setStatus(text)));
                lastSyncAt = System.currentTimeMillis();
                runOnUiThread(() -> {
                    Toast.makeText(this, count > 0 ? "整理完成 " + count + " 段" : "没有要整理的",
                            Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> setStatus("部分录音稍后会自动重试"));
            } finally {
                syncing = false;
            }
        }, "echo-sync").start();
    }

    private void togglePlay(Model.Segment segment) {
        try {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
                String was = playingSegmentId;
                playingSegmentId = null;
                if (was != null && was.equals(segment.id)) {
                    render();
                    return;
                }
            }
            player = new MediaPlayer();
            player.setDataSource(segment.path);
            player.prepare();
            player.setOnCompletionListener(mediaPlayer -> {
                playingSegmentId = null;
                if (player != null) {
                    player.release();
                    player = null;
                }
                render();
            });
            player.start();
            playingSegmentId = segment.id;
        } catch (Exception error) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部数据？")
                .setMessage("记录、待办、记忆都会删除，不可恢复。")
                .setPositiveButton("清空", (dialog, which) -> {
                    store.clearAll();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<Model.Todo> openTodos() {
        List<Model.Todo> open = new ArrayList<>();
        for (Model.Todo todo : store.todos()) {
            if (!todo.done) open.add(todo);
        }
        open.sort((a, b) -> {
            if (a.dueAt == b.dueAt) return 0;
            if (a.dueAt == 0) return 1;
            if (b.dueAt == 0) return -1;
            return Long.compare(a.dueAt, b.dueAt);
        });
        return open;
    }

    private List<Model.MemoryItem> activeMemories() {
        List<Model.MemoryItem> active = new ArrayList<>();
        for (Model.MemoryItem item : store.memories()) {
            if ("active".equals(item.status)) active.add(item);
        }
        return active;
    }

    private int reviewDueCount() {
        int count = 0;
        long now = System.currentTimeMillis();
        for (Model.MemoryItem item : store.memories()) {
            if ("active".equals(item.status) && item.reviewAt > 0 && now > item.reviewAt) count++;
        }
        return count;
    }

    private boolean hasAttention(List<Insight.Item> items) {
        for (Insight.Item item : items) {
            if (!"nothing-yet".equals(item.kind)) return true;
        }
        return false;
    }

    /* ----------------------------------------------------------- 生命周期 */

    @Override
    protected void onResume() {
        super.onResume();
        RecorderService.listener = this;
        setStatus(RecorderService.status);
        handler.post(refreshLoop);
        autoSyncIfDue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RecorderService.listener = null;
        handler.removeCallbacks(refreshLoop);
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (pageRoots[0] == null) { // 首启引导中，主界面尚未构建
                handler.postDelayed(this, 3000);
                return;
            }
            if (currentTab == 0) {
                boolean rec = RecorderService.recording;
                if (rec != lastRenderedRecording) {
                    lastRenderedRecording = rec;
                    render();
                }
                if (statusLine != null && !rec) {
                    statusLine.setText(RecorderService.status);
                }
                if (timerView != null && rec) {
                    timerView.setText(elapsedText());
                }
            } else if (currentTab == 2) {
                render();
            }
            autoSyncIfDue();
            handler.postDelayed(this, 3000);
        }
    };

    private String elapsedText() {
        Model.Session current = store.currentSession();
        if (current == null) return "00:00";
        long seconds = Math.max(0, (System.currentTimeMillis() - current.startedAt) / 1000);
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void autoSyncIfDue() {
        if (syncing) return;
        // 常驻聆听模式下 recording 恒为 true——整理由服务的攒批触发（BATCH_TRIGGER_MS），
        // 这里只在「未在录音」时兜底（例如服务被杀后重开 App）
        if (System.currentTimeMillis() - lastSyncAt < SYNC_COOLDOWN_MS) return;
        if (store.pendingCount() == 0) return;
        String policy = store.settings().batchPolicy;
        if ("manual".equals(policy)) return;
        if ("charging".equals(policy) && !Pipeline.isCharging(this)) return;
        syncNow();
    }

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> setStatus(text));
    }

    private void setStatus(String text) {
        if (currentTab == 0 && statusLine != null && !RecorderService.recording) {
            statusLine.setText(text);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /* ----------------------------------------------------------- 视图工具 */

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(Ui.CARD_H), dp(Ui.CARD_H), dp(Ui.CARD_H), dp(Ui.CARD_H));
        card.setBackground(Ui.elevatedCard(this));
        card.setElevation(dp(1));
        return card;
    }

    /** 卡片包一层白底圆角容器（echo 的 Card overflow-hidden 模式） */
    private LinearLayout cardWrap(LinearLayout card) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Ui.card(this, Ui.RADIUS_MD, SURFACE, 0));
        wrap.setClipToOutline(true);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private LinearLayout section(String title, LinearLayout card) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(Ui.SECTION_GAP), 0, 0);
        if (title != null && !title.isEmpty()) {
            TextView head = Ui.sectionLabel(this, title);
            head.setPadding(dp(2), 0, 0, dp(6));
            wrap.addView(head);
        }
        wrap.addView(cardWrap(card));
        return wrap;
    }

    private LinearLayout card(TextView body) {
        LinearLayout card = card();
        card.addView(body);
        return card;
    }

    private LinearLayout emptyCard(String title, String note) {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER);
        card.addView(Ui.text(this, title, TEXT_MID, Ui.Size.H2, true));
        card.addView(spacer(4));
        card.addView(Ui.text(this, note, TEXT_LOW, Ui.Size.SUB, false));
        return card;
    }

    private TextView line(String value, int color) {
        TextView view = Ui.text(this, value, color, Ui.Size.SUB, false);
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    /** 引用样式：左侧 2dp 竖条 + 弱色文字 */
    private LinearLayout quote(Context context, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setPadding(0, dp(4), 0, 0);
        View bar = new View(context);
        bar.setBackgroundColor(BORDER);
        row.addView(bar, new LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView text = Ui.text(context, " " + value, TEXT_LOW, Ui.Size.CAP, false);
        row.addView(text);
        return row;
    }

    private View spacer(int dpValue) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpValue)));
        return view;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }

    private String minutes(long ms) {
        if (ms < 60_000) return Math.max(1, Math.round(ms / 1000.0)) + " 秒";
        long m = Math.round(ms / 60_000.0);
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时" + (m % 60 == 0 ? "" : (m % 60) + " 分");
    }

    private String searchable(Model.MemoryItem item) {
        return (item.subject + " " + item.value + " " + item.evidence + " " + item.kind)
                .toLowerCase(Locale.CHINA);
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }
}
