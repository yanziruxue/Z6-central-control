package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 应用抽屉（原型 #appScrim + #appList）。
 *
 * 结构：遮罩 + 底部面板（手柄 / 头部 / 网格），入场 translationY 300ms。
 * 两种用法：
 * ① pick != null —— 选择模式（音乐源 / 导航源），点一项回调并关闭；
 * ② pick == null —— 纯浏览模式（dock 的 📋）：单击直接拉起，长按钉入 / 移除 dock 快捷方式。
 *
 * 数值全部照抄原型 CSS：
 *   面板 max-height 605u、顶角半径 24u、底 rgba(16,20,30,.98)；
 *   手柄 42x5u 半径 3u；头部内边距 6/18/12u、标题 15u；关闭按钮 34u 圆形；
 *   网格内边距 16u、gap 12u、每格固定宽 92u；图标 34u 圆角 9u；名称 11u 两行。
 */
public class AppDrawer {

    /** 打开动画曲线 cubic-bezier(.2,.85,.25,1)，与 CSS transition 对齐 */
    private static final Interpolator EASE = new PathInterpolator(0.2f, 0.85f, 0.25f, 1f);
    /** 面板底色 rgba(16,20,30,.98) */
    private static final int PANEL_FILL = 0xFA10141E;
    /** 遮罩 rgba(0,0,0,.5) */
    private static final int SCRIM = 0x80000000;
    /** 已钉格子底 rgba(61,169,252,.14) */
    private static final int PIN_FILL = 0x243DA9FC;

    private final Shell sh;
    private FrameLayout scrim;
    private LinearLayout panel;
    private MaxScroll scroll;
    private LinearLayout grid;
    private String curType;
    private Shell.DrawerPick pick;
    private boolean open;

    public AppDrawer(Shell sh) {
        this.sh = sh;
    }

    public boolean isOpen() {
        return open;
    }

    /** type = "music" / "nav" / null（全部）；title 为空时按 type 取默认标题 */
    public void open(String type, String title, Shell.DrawerPick pick) {
        if (panel != null) return;                 // 已在展示（含关闭动画中）→ 不叠层
        FrameLayout ov = sh.overlay();
        if (ov == null) return;
        Context c = sh.ctx();
        this.curType = type;
        this.pick = pick;

        // 遮罩：点击关闭
        scrim = new FrameLayout(c);
        scrim.setBackgroundColor(SCRIM);
        scrim.setAlpha(0f);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> {
            close();
        });
        ov.addView(scrim, new FrameLayout.LayoutParams(U.MP, U.MP));

        // 面板：固定底部、顶角圆角、上边框
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBg());
        panel.setClickable(true);
        panel.setElevation(U.px(12));
        panel.setTranslationY(U.px(720));
        ov.addView(panel, new FrameLayout.LayoutParams(U.MP, U.WC, Gravity.BOTTOM));

        // 手柄
        View grip = new View(c);
        grip.setBackground(U.bg(0x4DFFFFFF, 3));    // rgba(255,255,255,.3) 半径 3u
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(U.px(42), U.px(5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = U.px(9);
        glp.bottomMargin = U.px(3);
        panel.addView(grip, glp);

        // 头部：标题 + 圆形关闭按钮
        LinearLayout head = U.row(c);
        U.pad(head, 18, 6, 18, 12);
        String tt = title != null && !title.isEmpty() ? title : defaultTitle(type);
        TextView titleTv = U.text(c, tt, 15, U.TXT);
        U.bold(titleTv);
        head.addView(titleTv, new LinearLayout.LayoutParams(0, U.WC, 1f));
        TextView closeBtn = U.text(c, "✕", 16, U.SUB);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(U.bg(Color.TRANSPARENT, 17, U.LINE, 1));
        closeBtn.setClickable(true);
        U.rippleCircle(closeBtn);
        closeBtn.setOnClickListener(v -> close());
        head.addView(closeBtn, new LinearLayout.LayoutParams(U.px(34), U.px(34)));
        panel.addView(head, new LinearLayout.LayoutParams(U.MP, U.WC));
        View headLine = new View(c);
        headLine.setBackgroundColor(U.LINE);
        panel.addView(headLine, new LinearLayout.LayoutParams(U.MP, Math.max(1, U.px(1))));

        // 网格（放进可限高的 ScrollView）
        scroll = new MaxScroll(c, U.px(605));
        grid = U.col(c);
        U.pad(grid, 16, 16, 16, 16);
        scroll.addView(grid, new FrameLayout.LayoutParams(U.MP, U.WC));
        panel.addView(scroll, new LinearLayout.LayoutParams(U.MP, U.WC));

        fillGrid();
        final LinearLayout pnl = panel;
        final MaxScroll sc = scroll;
        final View gripV = grip;
        panel.post(() -> {                          // 面板 max-height 605u：扣除手柄 + 头部占高
            int cap = U.px(605) - gripV.getHeight() - head.getHeight() - Math.max(1, U.px(1));
            if (cap > 0) sc.setMaxH(cap);
            pnl.requestLayout();
        });

        open = true;
        scrim.animate().alpha(1f).setDuration(250).start();
        panel.animate().translationY(0f).setDuration(300).setInterpolator(EASE).start();
    }

    public void close() {
        if (!open) return;
        open = false;
        final FrameLayout ov = sh.overlay();
        final LinearLayout pnl = panel;
        final FrameLayout scr = scrim;
        panel = null;
        scrim = null;
        scroll = null;
        grid = null;
        pick = null;
        if (ov == null || pnl == null) return;
        if (scr != null) scr.animate().alpha(0f).setDuration(250).start();
        pnl.animate().translationY(pnl.getHeight() > 0 ? pnl.getHeight() : U.px(720))
                .setDuration(300).setInterpolator(EASE)
                .withEndAction(() -> {
                    ov.removeView(pnl);
                    if (scr != null) ov.removeView(scr);
                }).start();
    }

    /** 外部改动了 dock 钉住状态后调用：按当前数据重建网格 */
    public void refresh() {
        if (panel != null && grid != null) fillGrid();
    }

    // ------------------------------------------------------------------ 构建

    private void fillGrid() {
        if (grid == null) return;
        Context c = sh.ctx();
        grid.removeAllViews();
        List<Def.App> list = curType == null ? sh.host().allApps() : sh.host().appsOfType(curType);
        if (list == null || list.isEmpty()) {
            TextView e = U.text(c, "未读到已安装的应用", 13, U.SUB);
            e.setGravity(Gravity.CENTER);
            U.pad(e, 30, 30, 30, 30);
            grid.addView(e, new LinearLayout.LayoutParams(U.MP, U.WC));
            return;
        }
        int W = sh.overlay() != null && sh.overlay().getWidth() > 0
                ? sh.overlay().getWidth() : U.screenW;
        int cols = Math.max(1, (int) Math.floor((W - 20 * U.u) / (104 * U.u)));
        LinearLayout row = newRow(c);
        int inRow = 0;
        for (Def.App a : list) {
            row.addView(makeCell(a));
            if (++inRow == cols) {
                flushRow(row);
                row = newRow(c);
                inRow = 0;
            }
        }
        if (inRow > 0) flushRow(row);
    }

    private LinearLayout newRow(Context c) {
        LinearLayout r = U.row(c);
        r.setGravity(Gravity.TOP);
        return r;
    }

    private void flushRow(LinearLayout row) {
        U.gapH(row, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.MP, U.WC);
        if (grid.getChildCount() > 0) lp.topMargin = U.px(12);
        grid.addView(row, lp);
    }

    /** 一格：图标（真实图标 / emoji 回落）+ 名称 + 可选「已钉」角标 */
    private View makeCell(final Def.App a) {
        final Context c = sh.ctx();
        final boolean browse = pick == null;
        final FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(U.px(92), U.WC));
        U.pad(box, 6, 10, 6, 10);

        LinearLayout col = U.col(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        Bitmap bm = sh.host().icon(a.pkg);
        if (bm != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bm);
            iv.setClipToOutline(true);              // 34u 圆角 9u
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), U.px(9));
                }
            });
            col.addView(iv, new LinearLayout.LayoutParams(U.px(34), U.px(34)));
        } else {
            String em = a.emoji == null || a.emoji.isEmpty() ? Def.typeEmoji(a.type) : a.emoji;
            TextView ic = U.text(c, em, 26, U.TXT);
            ic.setGravity(Gravity.CENTER);
            col.addView(ic, new LinearLayout.LayoutParams(U.px(34), U.px(34)));
        }

        String nm = "nav".equals(curType) ? Def.navLabelOf(a.pkg, a.name) : a.name;
        TextView t = U.text(c, nm == null ? "" : nm, 11, U.SUB);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setLineSpacing(U.px(11 * 0.3f), 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(U.MP, U.WC);
        tlp.topMargin = U.px(6);
        col.addView(t, tlp);
        box.addView(col, new FrameLayout.LayoutParams(U.MP, U.WC));

        final TextView badge = U.text(c, "已钉", 9, U.ON_ACC_TXT);
        badge.setBackground(U.bg(U.ACC, 8));
        U.pad(badge, 5, 1, 5, 1);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(U.WC, U.WC, Gravity.TOP | Gravity.END);
        blp.rightMargin = U.px(2);
        box.addView(badge, blp);

        final Runnable paint = () -> {
            boolean p = browse && sh.prefs().isDockPinned(a.pkg);
            box.setBackground(U.bg(p ? PIN_FILL : U.PANEL2, 14, p ? U.ACC : Color.TRANSPARENT, 1));
            U.visible(badge, p);
            U.ripple(box, 0x33FFFFFF, 14);
        };
        paint.run();

        if (browse) {
            // 长按一旦触发过，随后的单击必须被吃掉（下一次按下时复位）
            final boolean[] fired = {false};
            box.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) fired[0] = false;
                return false;
            });
            box.setLongClickable(true);
            box.setOnLongClickListener(v -> {
                fired[0] = true;
                boolean was = sh.prefs().isDockPinned(a.pkg);
                boolean now = sh.prefs().toggleDockPin(a.pkg);
                if (!now && !was) {
                    sh.toast("快捷方式最多 " + sh.prefs().dockMax + " 个");
                } else {
                    sh.toast(now ? "已添加到快捷方式" : "已从快捷方式移除");
                }
                paint.run();
                sh.refreshDock();          // ★ 不通知的话，底栏要重启 App 才会更新
                return true;
            });
            box.setOnClickListener(v -> {
                if (fired[0]) { fired[0] = false; return; }
                sh.host().launchPkg(a.pkg);
                close();
            });
        } else {
            box.setOnClickListener(v -> {
                if (pick != null) pick.onPick(a);
                close();
            });
        }
        return box;
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

    private static String defaultTitle(String type) {
        if ("music".equals(type)) return "📋 选择音乐应用";
        if ("nav".equals(type)) return "📋 选择导航应用";
        return "📋 应用列表";
    }

    /** 限高 ScrollView（面板 max-height 605u 的实现手段） */
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
