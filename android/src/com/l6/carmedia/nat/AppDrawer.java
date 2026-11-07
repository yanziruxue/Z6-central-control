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
 * <p>v2.0.32：右下角 📋 由「图标网格」改为「纵向列表 + 首字母分组」。
 * 排序与分组由纯逻辑类 {@link AppSorter} 完成（中文按拼音首字母，英文 A–Z，其余归 #）。
 * 每行 = 真实图标（或 emoji 回落）+ 应用名 + 包名小字 + 可选「已钉」角标，
 * 1920x720 横屏下比网格更易读、长应用名不再被压成两行。
 *
 * <p>v2.0.40：弹出方式改为<b>右侧抽屉</b> —— 遮罩 + 右缘面板（高满屏、宽 min(560u, 屏宽*0.46)、
 * 左缘圆角），入场 translationX 300ms 从右滑入，一列显示（音乐/导航选择模式同样生效）。
 *
 * <p>结构：遮罩 + 右侧面板（手柄 / 头部 / 列表）。
 * 两种用法：
 * ① pick != null —— 选择模式（音乐源 / 导航源），点一项回调并关闭；
 * ② pick == null —— 纯浏览模式（dock 的 📋）：单击直接拉起，长按钉入 / 移除 dock 快捷方式。
 *
 * <p>交互一律保留：长按后的单击必须被吃掉、钉住状态实时刷新底栏、限高 605u 滚动。
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
    /** v2.0.40：右侧抽屉面板宽（open 时按屏宽算，close 收场动画复用） */
    private int panelW;
    /** v2.0.32：纵向列表容器（原网格容器，交互不变，仅布局从「每行 N 格」改为「每行 1 项」）。 */
    private LinearLayout listBox;
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

        // 面板：v2.0.40 由「底部面板」改为「右侧抽屉」—— 固定右缘、高满屏、左缘圆角，
        // 入场 translationX 从 +面板宽 → 0 滑入（类似从右侧弹出的一列）。
        // ★ 宽度取 min(560u, 屏宽*0.46)：1920×720 下是 560u，窄屏也不会顶满整屏。
        panelW = Math.min(U.px(560), Math.round(U.screenW * 0.46f));
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBg());
        panel.setClickable(true);
        panel.setElevation(U.px(12));
        panel.setTranslationX(panelW);
        ov.addView(panel, new FrameLayout.LayoutParams(panelW, U.MP, Gravity.RIGHT));

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
        TextView hint = U.text(c, "按首字母排序", 11, U.SUB);
        head.addView(hint, new LinearLayout.LayoutParams(U.WC, U.WC));
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

        // 列表（放进可限高的 ScrollView）
        scroll = new MaxScroll(c, U.px(605));
        listBox = U.col(c);
        U.pad(listBox, 16, 12, 16, 16);
        scroll.addView(listBox, new FrameLayout.LayoutParams(U.MP, U.WC));
        panel.addView(scroll, new LinearLayout.LayoutParams(U.MP, U.WC));

        fillList();
        final LinearLayout pnl = panel;
        final MaxScroll sc = scroll;
        final View gripV = grip;
        panel.post(() -> {                          // v2.0.40 右侧抽屉高满屏：列表限高 = 屏高 − 手柄 − 头部
            int cap = U.screenH - gripV.getHeight() - head.getHeight() - Math.max(1, U.px(1));
            if (cap > 0) sc.setMaxH(cap);
            pnl.requestLayout();
        });

        open = true;
        sh.setSwipeLocked(true);                   // v2.0.40：弹窗打开期间禁主页滑动手势
        scrim.animate().alpha(1f).setDuration(250).start();
        panel.animate().translationX(0f).setDuration(300).setInterpolator(EASE).start();
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
        pick = null;
        if (ov == null || pnl == null) return;
        if (scr != null) scr.animate().alpha(0f).setDuration(250).start();
        // v2.0.40：右侧抽屉 → 向右滑出（宽度优先用实测值，fallback 到计算值）
        pnl.animate().translationX(pnl.getWidth() > 0 ? pnl.getWidth() : panelW)
                .setDuration(300).setInterpolator(EASE)
                .withEndAction(() -> {
                    ov.removeView(pnl);
                    if (scr != null) ov.removeView(scr);
                }).start();
    }

    /** 外部改动了 dock 钉住状态后调用：按当前数据重建列表 */
    public void refresh() {
        if (panel != null && listBox != null) fillList();
    }

    // ------------------------------------------------------------------ 构建

    /** v2.0.32：按首字母分组的纵向列表，排序不修改 Host 的应用缓存。 */
    private void fillList() {
        if (listBox == null) return;
        Context c = sh.ctx();
        listBox.removeAllViews();
        List<Def.App> src = curType == null ? sh.host().allApps() : sh.host().appsOfType(curType);
        List<Def.App> list = AppSorter.sort(src);
        if (list.isEmpty()) {
            TextView e = U.text(c, "未读到已安装的应用", 13, U.SUB);
            e.setGravity(Gravity.CENTER);
            U.pad(e, 30, 30, 30, 30);
            listBox.addView(e, new LinearLayout.LayoutParams(U.MP, U.WC));
            return;
        }
        String last = null;
        for (Def.App a : list) {
            String ini = AppSorter.initialOf(AppSorter.label(a));
            if (!ini.equals(last)) {
                listBox.addView(makeGroup(c, ini));
                last = ini;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.MP, U.WC);
            lp.topMargin = U.px(4);
            listBox.addView(makeRow(a), lp);
        }
    }

    /** 分组标题行：A / B / … / Z / # 其他。 */
    private View makeGroup(Context c, String letter) {
        TextView t = U.text(c, AppSorter.OTHER.equals(letter) ? "# 其他" : letter, 12, U.ACC);
        U.bold(t);
        U.pad(t, 6, 10, 6, 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.MP, U.WC);
        lp.topMargin = U.px(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 列表一行：图标 + 应用名/包名 + 可选「已钉」角标。交互与旧网格格一致。 */
    private View makeRow(final Def.App a) {
        final Context c = sh.ctx();
        final boolean browse = pick == null;
        final LinearLayout row = U.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(U.px(52));
        U.pad(row, 10, 6, 10, 6);
        row.setLayoutParams(new LinearLayout.LayoutParams(U.MP, U.WC));

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
            row.addView(iv, new LinearLayout.LayoutParams(U.px(34), U.px(34)));
        } else {
            String em = a.emoji == null || a.emoji.isEmpty() ? Def.typeEmoji(a.type) : a.emoji;
            TextView ic = U.text(c, em, 24, U.TXT);
            ic.setGravity(Gravity.CENTER);
            row.addView(ic, new LinearLayout.LayoutParams(U.px(34), U.px(34)));
        }

        LinearLayout txt = U.col(c);
        String nm = "nav".equals(curType) ? Def.navLabelOf(a.pkg, a.name) : AppSorter.label(a);
        TextView t = U.text(c, nm, 14, U.TXT);
        U.bold(t);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        TextView sub = U.text(c, a.pkg == null ? "" : a.pkg, 10, U.SUB);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(sub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, U.WC, 1f);
        tlp.leftMargin = U.px(10);
        row.addView(txt, tlp);

        final TextView badge = U.text(c, "已钉", 9, U.ON_ACC_TXT);
        badge.setBackground(U.bg(U.ACC, 8));
        U.pad(badge, 5, 1, 5, 1);
        row.addView(badge, new LinearLayout.LayoutParams(U.WC, U.WC));

        final Runnable paint = () -> {
            boolean p = browse && sh.prefs().isDockPinned(a.pkg);
            row.setBackground(U.bg(p ? PIN_FILL : U.PANEL2, 10, p ? U.ACC : Color.TRANSPARENT, 1));
            U.visible(badge, p);
            U.ripple(row, 0x33FFFFFF, 10);
        };
        paint.run();

        if (browse) {
            // 长按一旦触发过，随后的单击必须被吃掉（下一次按下时复位）
            final boolean[] fired = {false};
            row.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) fired[0] = false;
                return false;
            });
            row.setLongClickable(true);
            row.setOnLongClickListener(v -> {
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
            row.setOnClickListener(v -> {
                if (fired[0]) { fired[0] = false; return; }
                sh.host().launchPkg(a.pkg);
                close();
            });
        } else {
            row.setOnClickListener(v -> {
                if (pick != null) pick.onPick(a);
                close();
            });
        }
        return row;
    }

    private Drawable panelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(PANEL_FILL);
        float r = U.px(24);
        // v2.0.40：右侧抽屉 → 仅左上/左下圆角 24u（radii 顺序 TL,TR,BR,BL）
        g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        g.setStroke(Math.max(1, U.px(1)), U.LINE);               // 边框 U.LINE
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
