package com.l6.carmedia.nat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.l6.carmedia.Ota;

import org.json.JSONObject;

import java.util.List;

/**
 * 应用内软件仓库面板（v2.0.39）。
 *
 * <p>结构仿 {@link AppDrawer}：遮罩 + 底部面板（手柄 / 标题 / 列表），入场 translationY。
 * 打开即拉取本 App 的 GitHub Release 列表（{@link Ota#listReleases}），每项显示版本名、
 * 发布日期、APK 大小、更新说明预览与「下载安装」按钮；点击即走 {@link Ota#downloadAndInstall}
 * 下载并拉起系统安装器。全程原生 UI，不打开任何网页、不依赖浏览器。
 */
public class AppCenterPane {

    /** 入场动画曲线 cubic-bezier(.2,.85,.25,1)，与 CSS transition 对齐 */
    private static final Interpolator EASE = new PathInterpolator(0.2f, 0.85f, 0.25f, 1f);
    /** 面板底色 rgba(16,20,30,.98) */
    private static final int PANEL_FILL = 0xFA10141E;
    /** 遮罩 rgba(0,0,0,.5) */
    private static final int SCRIM = 0x80000000;

    private final Shell sh;
    private final Activity act;
    private FrameLayout scrim;
    private LinearLayout panel;
    private MaxScroll scroll;
    private LinearLayout listBox;
    private boolean open;

    public AppCenterPane(Shell sh) {
        this.sh = sh;
        this.act = (Activity) sh.ctx();
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        if (panel != null) return;                 // 已在展示（含关闭动画中）→ 不叠层
        FrameLayout ov = sh.overlay();
        if (ov == null) return;
        Context c = sh.ctx();

        scrim = new FrameLayout(c);
        scrim.setBackgroundColor(SCRIM);
        scrim.setAlpha(0f);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> close());
        ov.addView(scrim, new FrameLayout.LayoutParams(U.MP, U.MP));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBg());
        panel.setClickable(true);
        panel.setElevation(U.px(12));
        panel.setTranslationY(U.px(720));
        ov.addView(panel, new FrameLayout.LayoutParams(U.MP, U.WC, Gravity.BOTTOM));

        View grip = new View(c);
        grip.setBackground(U.bg(0x4DFFFFFF, 3));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(U.px(42), U.px(5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = U.px(9);
        glp.bottomMargin = U.px(3);
        panel.addView(grip, glp);

        LinearLayout head = U.row(c);
        U.pad(head, 18, 6, 18, 12);
        TextView titleTv = U.text(c, "📦 软件仓库", 15, U.TXT);
        U.bold(titleTv);
        head.addView(titleTv, new LinearLayout.LayoutParams(0, U.WC, 1f));
        TextView closeBtn = U.text(c, "✕", 16, U.SUB);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(U.bg(Color.TRANSPARENT, 17, U.LINE, 1));
        closeBtn.setClickable(true);
        U.rippleCircle(closeBtn);
        closeBtn.setOnClickListener(v -> close());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(U.px(34), U.px(34));
        clp.leftMargin = U.px(10);
        head.addView(closeBtn, clp);
        panel.addView(head, new LinearLayout.LayoutParams(U.MP, U.WC));
        View headLine = new View(c);
        headLine.setBackgroundColor(U.LINE);
        panel.addView(headLine, new LinearLayout.LayoutParams(U.MP, Math.max(1, U.px(1))));

        scroll = new MaxScroll(c, U.px(605));
        listBox = U.col(c);
        U.pad(listBox, 16, 12, 16, 16);
        scroll.addView(listBox, new FrameLayout.LayoutParams(U.MP, U.WC));
        panel.addView(scroll, new LinearLayout.LayoutParams(U.MP, U.WC));

        TextView loading = U.text(c, "读取软件仓库中…", 13, U.SUB);
        loading.setGravity(Gravity.CENTER);
        U.pad(loading, 30, 30, 30, 30);
        listBox.addView(loading, new LinearLayout.LayoutParams(U.MP, U.WC));

        open = true;
        sh.setSwipeLocked(true);                   // v2.0.40：弹窗打开期间禁主页滑动手势
        scrim.animate().alpha(1f).setDuration(250).start();
        panel.animate().translationY(0f).setDuration(300).setInterpolator(EASE).start();

        load();
    }

    public void close() {
        if (!open) return;
        open = false;
        sh.setSwipeLocked(false);                  // v2.0.40：与 open 对称解锁
        final FrameLayout ov = sh.overlay();
        final LinearLayout pnl = panel;
        final FrameLayout scr = scrim;
        panel = null;
        scrim = null;
        scroll = null;
        listBox = null;
        if (ov == null || pnl == null) return;
        if (scr != null) scr.animate().alpha(0f).setDuration(250).start();
        pnl.animate().translationY(pnl.getHeight() > 0 ? pnl.getHeight() : U.px(720))
                .setDuration(300).setInterpolator(EASE)
                .withEndAction(() -> {
                    ov.removeView(pnl);
                    if (scr != null) ov.removeView(scr);
                }).start();
    }

    /** 拉取并填充发布列表。 */
    private void load() {
        Ota.listReleases(act, (items, error) -> {
            if (listBox == null) return;
            listBox.removeAllViews();
            Context c = sh.ctx();
            if (error != null && !error.isEmpty()) {
                TextView e = U.text(c, "读取失败：" + error, 13, U.SUB);
                e.setGravity(Gravity.CENTER);
                U.pad(e, 24, 24, 24, 24);
                listBox.addView(e, new LinearLayout.LayoutParams(U.MP, U.WC));
                return;
            }
            if (items == null || items.isEmpty()) {
                TextView e = U.text(c, "没有可用的安装包", 13, U.SUB);
                e.setGravity(Gravity.CENTER);
                U.pad(e, 24, 24, 24, 24);
                listBox.addView(e, new LinearLayout.LayoutParams(U.MP, U.WC));
                return;
            }
            // 列表按版本号从高到低已在 GitHub 返回顺序中（最新在前），这里原样展示
            for (JSONObject it : items) {
                listBox.addView(makeRow(it));
            }
        });
    }

    /** 一行：版本名 + 日期/大小 + 更新说明预览 + 下载安装按钮。 */
    private View makeRow(final JSONObject it) {
        final Context c = sh.ctx();
        final String url = it.optString("downloadUrl", "");
        final String sha = it.optString("sha256", "");
        final boolean hasSha = sha != null && sha.length() == 64;

        final LinearLayout row = U.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(U.px(64));
        U.pad(row, 10, 8, 10, 8);
        row.setBackground(U.bg(U.PANEL2, 10, Color.TRANSPARENT, 1));
        U.ripple(row, 0x33FFFFFF, 10);

        LinearLayout txt = U.col(c);
        TextView t = U.text(c, it.optString("versionName", "?"), 14, U.TXT);
        U.bold(t);
        txt.addView(t);
        String meta = formatDate(it.optString("publishedAt", "")) + "  ·  " + formatSize(it.optLong("size", 0));
        TextView sub = U.text(c, meta, 11, U.SUB);
        txt.addView(sub);
        String chg = it.optString("changelog", "").trim();
        if (!chg.isEmpty()) {
            TextView ch = U.text(c, chg, 11, U.SUB);
            ch.setMaxLines(2);
            ch.setEllipsize(android.text.TextUtils.TruncateAt.END);
            U.pad(ch, 0, 4, 0, 0);
            txt.addView(ch);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, U.WC, 1f);
        tlp.leftMargin = U.px(4);
        tlp.rightMargin = U.px(10);
        row.addView(txt, tlp);

        final TextView dl = U.btn(c, "下载安装");
        dl.setMinimumHeight(U.px(36));
        row.addView(dl, new LinearLayout.LayoutParams(U.WC, U.WC));

        final Runnable fire = () -> {
            if (url.isEmpty()) return;
            dl.setEnabled(false);
            dl.setText("下载中…");
            Ota.downloadAndInstall(act, url, hasSha ? sha : "", otaCb(dl));
        };
        row.setOnClickListener(v -> fire.run());
        dl.setOnClickListener(v -> fire.run());
        return row;
    }

    /** 下载/安装进度回调：只播关键状态，不每 1% 刷 toast。 */
    private Ota.EventCb otaCb(final TextView dl) {
        return json -> {
            try {
                JSONObject j = new JSONObject(json);
                String kind = j.optString("kind", "");
                if ("error".equals(kind)) {
                    sh.toast("⚠️ " + j.optString("message", "下载失败"));
                    if (dl != null) { dl.setEnabled(true); dl.setText("下载安装"); }
                } else if ("cancelled".equals(kind)) {
                    sh.toast("已取消");
                    if (dl != null) { dl.setEnabled(true); dl.setText("下载安装"); }
                } else if ("downloaded".equals(kind)) {
                    sh.toast("下载完成，准备安装…");
                } else if ("needPermission".equals(kind)) {
                    sh.toast(j.optString("message", "需要授权安装未知应用"));
                    if (dl != null) { dl.setEnabled(true); dl.setText("下载安装"); }
                } else if ("installing".equals(kind)) {
                    sh.toast("正在安装…");
                    close();
                }
            } catch (Throwable ignored) {
            }
        };
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "未知日期";
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return (bytes / 1024) + " KB";
    }

    private Drawable panelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(PANEL_FILL);
        float r = U.px(24);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});   // 仅顶角圆角 24u
        g.setStroke(Math.max(1, U.px(1)), U.LINE);               // 上边框 U.LINE
        return g;
    }

    /** 限高 ScrollView（面板 max-height 605u 的实现手段）。 */
    private static class MaxScroll extends ScrollView {
        private int maxH;

        MaxScroll(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        void setMaxH(int h) {
            maxH = h;
        }

        @Override
        protected void onMeasure(int w, int h) {
            if (maxH > 0) h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            super.onMeasure(w, h);
        }
    }
}
