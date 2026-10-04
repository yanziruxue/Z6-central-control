package com.l6.carmedia.nat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生 UI 的「设计像素 / 主题 / 视图工厂」。
 *
 * ★ 尺寸基准与 WebView 版的 CSS 变量 --u 完全同源：
 *     u = min(视口宽 / 1920, 视口高 / 720)，夹到 [0.4, 3]
 *   取「更吃紧的那一维」⇒ 任意宽高比下内容都不会溢出错位，富余的一维由 flex 容器吸收。
 *   所有设计稿里的数字（字号 / 间距 / 圆角 / 尺寸）一律经 {@link #px(float)} 换算，
 *   写代码时**直接照抄 CSS 里的数字**，视觉就能对上。
 *
 * ★ 色值同样照抄 CSS :root 与各组件规则；panel/line 刻意用**半透明**，
 *   这样主页有壁纸时卡片底下能透出壁纸（与 CSS 的 rgba 行为一致）。
 */
public final class U {
    private U() {}

    /** 与 CSS 的 --u 对应的换算系数 */
    public static float u = 1f;
    public static float density = 1f;
    public static int screenW = 1920, screenH = 720;

    public static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---- 色板（照抄 CSS :root）----
    public static final int BG    = 0xFF0B0E14;
    public static final int TXT   = 0xFFE8EDF5;
    public static final int SUB   = 0xFF8B97AB;
    public static final int ACC   = 0xFF3DA9FC;
    public static final int ACC2  = 0xFF34D399;
    public static final int WARN  = 0xFFFBBF24;
    public static final int RED   = 0xFFF87171;
    public static final int PUR   = 0xFFA78BFA;
    /** rgba(255,255,255,.10) —— 边框 */
    public static final int LINE  = 0x1AFFFFFF;
    /** rgba(255,255,255,.06) —— 卡片面板 */
    public static final int PANEL = 0x0FFFFFFF;
    /** rgba(255,255,255,.04) —— 次级面板（按钮底色） */
    public static final int PANEL2 = 0x0AFFFFFF;
    /** rgba(61,169,252,.12) + 边框 rgba(61,169,252,.3) ——「当前源」胶囊 */
    public static final int ACC_SOFT   = 0x1F3DA9FC;
    public static final int ACC_SOFT_L = 0x4D3DA9FC;
    /** 强调按钮（渐变 135deg acc→pur）的深色前景字 */
    public static final int ON_ACC_TXT = 0xFF06121F;

    /** 依屏幕尺寸重算 u（onCreate / onConfigurationChanged / 尺寸变化时都要调） */
    public static void init(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        density = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        float k = Math.min(screenW / 1920f, screenH / 720f);
        u = Math.max(0.4f, Math.min(3f, k));
    }

    // ------------------------------------------------------------------ 尺寸

    public static int px(float design) {
        return Math.round(design * u);
    }

    /** 字号：CSS 里的 font-size 是 CSS px ⇒ 这里也用 PX 单位写，视觉 1:1 */
    public static void textSize(TextView v, float design) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(1f, design * u));
    }

    public static void pad(View v, float l, float t, float r, float b) {
        v.setPadding(px(l), px(t), px(r), px(b));
    }

    public static void pad(View v, float all) {
        pad(v, all, all, all, all);
    }

    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(px(l), px(t), px(r), px(b));
        }
    }

    /** 设计像素 → LayoutParams（负数原样透传，便于写 MP / WC） */
    public static LinearLayout.LayoutParams lp(float wD, float hD) {
        return new LinearLayout.LayoutParams(iz(wD), iz(hD));
    }

    public static LinearLayout.LayoutParams lp(float wD, float hD, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(iz(wD), iz(hD), weight);
        return p;
    }

    public static LinearLayout.LayoutParams lp(float wD, float hD, float weight, float gapD) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(iz(wD), iz(hD), weight);
        p.setMargins(0, 0, px(gapD), 0);
        return p;
    }

    private static int iz(float d) {
        return d < 0 ? (int) d : px(d);
    }

    // ------------------------------------------------------------------ 背景

    /** 纯色 + 圆角 */
    public static Drawable bg(int fill, float radiusD) {
        return round(fill, radiusD, 0, 0);
    }

    /** 纯色 + 圆角 + 描边 */
    public static Drawable bg(int fill, float radiusD, int strokeColor, float strokeD) {
        return round(fill, radiusD, strokeColor, strokeD);
    }

    private static GradientDrawable round(int fill, float radiusD, int strokeColor, float strokeD) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(px(radiusD));
        if (strokeD > 0) g.setStroke(Math.max(1, px(strokeD)), strokeColor);
        return g;
    }

    /** 强调按钮底：linear-gradient(135deg, acc, pur) */
    public static Drawable accGradient(float radiusD) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACC, PUR});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(radiusD));
        return g;
    }

    /** 给可点控件加水波纹（原生没有 CSS 的 :active scale，水波纹是最贴近的反馈） */
    public static void ripple(View v, int maskColor, float radiusD) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(px(radiusD));
        mask.setColor(maskColor);
        try {
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), v.getBackground(), mask));
        } catch (Throwable ignored) {
        }
    }

    /** 圆角 / 圆形的水波纹遮罩（圆形用于 dock 圆形按钮） */
    public static void rippleCircle(View v) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        try {
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), v.getBackground(), mask));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 工厂

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, CharSequence s, float sizeD, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        textSize(t, sizeD);
        t.setTextColor(color);
        return t;
    }

    public static TextView bold(TextView t) {
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** 数据行/数字用等宽数字，避免跳动（CSS font-variant-numeric: tabular-nums） */
    public static TextView tabular(TextView t) {
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    /** .set-label：左侧 3x11 强调竖条 + 12px 次级字 + 1px 字距 */
    public static LinearLayout label(Context c, String s) {
        LinearLayout r = row(c);
        View bar = new View(c);
        bar.setBackground(bg(ACC, 2));
        r.addView(bar, lp(3, 11));
        TextView t = text(c, s, 12, SUB);
        t.setLetterSpacing(1f / 12f);          // letter-spacing:1px 在 12px 字号下 = 1/12 em
        r.addView(t, lp(WC, WC, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).leftMargin = px(6);
        return r;
    }

    /** .set-hint：11px 次级字，行高 1.5 */
    public static TextView hint(Context c, CharSequence s) {
        TextView t = text(c, s, 11, SUB);
        t.setLineSpacing(px(11 * 0.5f), 1f);
        return t;
    }

    /** .ota-card / .card：面板底 + 边框 + 圆角 + 内边距（默认 14） */
    /**
     * 去掉发布正文里的 **sha256 校验行**（★ v2.0.7）。
     *
     * <p>发版正文末尾那行 `sha256: <64hex>` 是给排障看的，用户不需要也不该看到
     * （下载后仍然会按它校验完整性，只是 UI 不展示）。弹窗与设置页的更新内容共用本方法。
     */
    public static String hideShaLine(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "sha", 0, 3)
                    && (t.regionMatches(true, 0, "sha256", 0, 6) || t.regionMatches(true, 0, "sha-256", 0, 7))) {
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    public static LinearLayout card(Context c, float radiusD, float padD) {
        LinearLayout l = col(c);
        l.setBackground(bg(PANEL, radiusD, LINE, 1));
        pad(l, padD);
        return l;
    }

    public static LinearLayout card(Context c) {
        return card(c, 14, 14);
    }

    /** .ota-card：更淡的底 + 小内边距，用于设置页各分区里的「卡片」 */
    public static LinearLayout otaCard(Context c) {
        LinearLayout l = col(c);
        l.setBackground(bg(PANEL2, 10));
        pad(l, 12, 10, 12, 10);
        return l;
    }

    /** .ota-row：左右两端、可换行的行 */
    public static LinearLayout otaRow(Context c) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** 设置页按钮基座：圆角 12 + 边框 + 半透明底 */
    public static TextView btnRaw(Context c, CharSequence s, float sizeD) {
        TextView t = text(c, s, sizeD, TXT);
        t.setBackground(bg(PANEL2, 12, LINE, 1));
        pad(t, 16, 11, 16, 11);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        ripple(t, 0x33FFFFFF, 12);
        return t;
    }

    public static TextView btn(Context c, CharSequence s) {
        return btnRaw(c, s, 14);
    }

    /** .set-opt.pick：虚线强调边框 + 强调色字 */
    public static TextView pickBtn(Context c, CharSequence s) {
        TextView t = text(c, s, 14, ACC);
        GradientDrawable g = round(0x14FFFFFF, 12, ACC, 1);
        g.setStroke(Math.max(1, px(1)), ACC);
        t.setBackground(g);
        pad(t, 16, 11, 16, 11);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        ripple(t, 0x33FFFFFF, 12);
        return t;
    }

    /** .set-opt.ghost：透明底 + 边框 + 次级字 */
    public static TextView ghostBtn(Context c, CharSequence s) {
        TextView t = text(c, s, 14, SUB);
        t.setBackground(bg(Color.TRANSPARENT, 12, LINE, 1));
        pad(t, 16, 11, 16, 11);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        ripple(t, 0x22FFFFFF, 12);
        return t;
    }

    /** 选中态切换：on = 渐变强调底 + 深色字（.set-opt.on） */
    public static void setOn(TextView t, boolean on) {
        if (on) {
            t.setBackground(accGradient(12));
            t.setTextColor(ON_ACC_TXT);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            t.setBackground(bg(PANEL2, 12, LINE, 1));
            t.setTextColor(TXT);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.NORMAL);
        }
    }

    /** .set-opts 里的「小一号」按钮（源卡片行） */
    public static TextView smallBtn(Context c, CharSequence s) {
        TextView t = btnRaw(c, s, 13);
        pad(t, 12, 8, 12, 8);
        return t;
    }

    /** .set-cur：强调色胶囊（当前源） */
    public static LinearLayout curPill(Context c) {
        LinearLayout l = row(c);
        l.setBackground(bg(ACC_SOFT, 8, ACC_SOFT_L, 1));
        pad(l, 10, 6, 10, 6);
        return l;
    }

    // ------------------------------------------------------------------ 小工具

    public static void add(ViewGroup p, View v, float wD, float hD) {
        p.addView(v, lp(wD, hD));
    }

    public static void add(ViewGroup p, View v, float wD, float hD, float weight) {
        p.addView(v, lp(wD, hD, weight));
    }

    /** 竖排间距（等价于 flex gap） */
    public static void gapV(LinearLayout col, float gapD) {
        for (int i = 1; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            ViewGroup.LayoutParams p = c.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) p).topMargin = px(gapD);
            }
        }
    }

    public static void gapH(LinearLayout row, float gapD) {
        for (int i = 1; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            ViewGroup.LayoutParams p = c.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) p).leftMargin = px(gapD);
            }
        }
    }

    /** 空视图（撑开用） */
    public static View space(Context c, float wD, float hD) {
        View v = new View(c);
        v.setLayoutParams(lp(wD, hD));
        return v;
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        return v;
    }

    public static void visible(View v, boolean on) {
        if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    public static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
