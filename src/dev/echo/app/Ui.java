package dev.echo.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计令牌 + 视图工厂 —— 与 echo-src「多巴胺」主题对齐的亮色系统。
 * 冷白底 + 纯白卡片 + 靛蓝主色；青绿承载次级信息，明黄只做少量重点。
 */
public final class Ui {

    public static final int BG = Color.parseColor("#F6F7FB");
    public static final int SURFACE = Color.WHITE;
    public static final int SURFACE_MUTED = Color.parseColor("#ECEFF5");
    public static final int TEXT_HI = Color.parseColor("#1C2029");
    public static final int TEXT_MID = Color.parseColor("#626A76");
    public static final int TEXT_LOW = Color.parseColor("#9AA1AD");
    public static final int BORDER = Color.parseColor("#E1E4EB");

    public static final int PRIMARY = Color.parseColor("#4055D6");
    public static final int PRIMARY_SOFT = Color.parseColor("#E8ECFC");
    public static final int SECONDARY_BG = Color.parseColor("#E3F2F1");
    public static final int SECONDARY_FG = Color.parseColor("#11605C");
    public static final int HIGHLIGHT = Color.parseColor("#F1C62C");

    public static final int SUCCESS = Color.parseColor("#267A52");
    public static final int WARNING = Color.parseColor("#A85D10");
    public static final int WARNING_BG = Color.parseColor("#FCF3DC");
    public static final int DANGER = Color.parseColor("#B42318");
    public static final int DANGER_BG = Color.parseColor("#FBEDEB");

    public static final int RADIUS_SM = 6;
    public static final int RADIUS_MD = 10;
    public static final int RADIUS_LG = 14;
    /** 页面左右边距 */
    public static final int PAGE_H = 16;
    /** 卡片内边距 */
    public static final int CARD_H = 14;
    /** 区块间垂直间距 */
    public static final int SECTION_GAP = 12;

    private Ui() {
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable card(Context c, int radiusDp, int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(c, 1), strokeColor);
        return drawable;
    }

    /** 带轻微阴影的卡片（用 outline 模拟，无实际 shadow 性能开销） */
    public static GradientDrawable elevatedCard(Context c) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(SURFACE);
        drawable.setCornerRadius(dp(c, RADIUS_MD));
        drawable.setStroke(dp(c, 1), Color.argb(20, 0, 0, 0)); // 极淡阴影描边
        return drawable;
    }

    public static GradientDrawable pill(Context c, int radiusDp, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(c, radiusDp));
        return drawable;
    }

    public static GradientDrawable dot(Context c, int color, int sizeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setSize(dp(c, sizeDp), dp(c, sizeDp));
        return drawable;
    }

    public static ColorStateList colorStateList(int color) {
        return new ColorStateList(new int[][]{new int[]{0}}, new int[]{color});
    }

    public static RippleDrawable clickable(Context c, GradientDrawable content) {
        return new RippleDrawable(colorStateList(Color.argb(30, 64, 85, 214)), content, null);
    }

    public enum Size {
        TITLE(22), H2(17), BODY(15), SUB(13), CAP(11);
        final int sp;

        Size(int sp) {
            this.sp = sp;
        }
    }

    public static TextView text(Context c, String value, int color, Size size, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.sp);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return view;
    }

    public static TextView sectionLabel(Context c, String value) {
        TextView view = text(c, value, TEXT_MID, Size.CAP, false);
        view.setLetterSpacing(0.06f);
        return view;
    }

    public static Button primaryButton(Context c, String value) {
        Button button = new Button(c);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = pill(c, dp(c, RADIUS_SM), PRIMARY);
        button.setBackground(new RippleDrawable(colorStateList(Color.argb(40, 255, 255, 255)), background, null));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        return button;
    }

    public static Button outlineButton(Context c, String value, int textColor) {
        Button button = new Button(c);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(textColor);
        GradientDrawable background = card(c, RADIUS_SM, SURFACE, BORDER);
        button.setBackground(new RippleDrawable(colorStateList(Color.argb(30, 28, 32, 41)), background, null));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        return button;
    }

    public static TextView chip(Context c, String value, int bgColor, int textColor) {
        TextView view = text(c, value, textColor, Size.CAP, true);
        view.setBackground(pill(c, dp(c, 7), bgColor));
        view.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return view;
    }

    public static View divider(Context c) {
        View view = new View(c);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1) / 2));
        params.setMargins(dp(c, 14), 0, dp(c, 14), 0);
        view.setLayoutParams(params);
        view.setBackgroundColor(BORDER);
        return view;
    }
}
