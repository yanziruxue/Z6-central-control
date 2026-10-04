package com.l6.carmedia.nat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 强制更新蒙层（原型 #otaForce）。
 *
 * 全屏遮罩 rgba(0,0,0,.88) 纵向居中：标题（20u WARN）/ 更新内容（13u SUB，可滚动，最高 240u）/
 * 主按钮「⬇ 立即更新」（强调渐变底 + 深色字）/ 次按钮「✕ 取消下载」（ghost，仅下载中可见）/
 * 进度条（高 6u）+ 右侧百分比（11u 等宽数字）。
 *
 * 不可取消、不响应返回键：本类不注册任何 OnBackPressed 逻辑；遮罩自身吃掉所有触摸。
 *
 * 数值照抄原型 CSS：#otaForce 间距 gap 14u / 内边距 24u；h2 20u；
 * p 13u / 最大宽 520u / line-height 1.6 / max-height 240u / 左右内边距 8u；
 * .set-opt.primary 内边距 10u 22u / 14u。
 */
public class ForceUpdate {

    /** 遮罩 rgba(0,0,0,.88) */
    private static final int SCRIM = 0xE0000000;

    private final Shell sh;
    private FrameLayout root;
    private LinearLayout progRow;
    private LinearLayout bar;
    private View fill;
    private View remain;
    private TextView pctTv;
    private TextView installBtn;
    private TextView cancelBtn;
    private boolean started;

    public ForceUpdate(Shell sh) {
        this.sh = sh;
    }

    public void show(String changelog, final Runnable onInstall, final Runnable onCancel) {
        if (root != null) return;                  // 已在展示 → 不叠层
        FrameLayout ov = sh.overlay();
        if (ov == null) return;
        Context c = sh.ctx();

        root = new FrameLayout(c);
        root.setBackgroundColor(SCRIM);
        root.setClickable(true);                   // 吃掉所有触摸（含点击穿透）
        root.setOnClickListener(v -> { /* 不可取消，吞掉 */ });

        LinearLayout col = U.col(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        U.pad(col, 24, 24, 24, 24);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(U.WC, U.WC, Gravity.CENTER);
        root.addView(col, clp);

        TextView title = U.text(c, "⚠️ 必须更新到最新版本", 20, U.WARN);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(U.WC, U.WC));

        // 更新内容：可滚动、保留换行、等宽换行
        MaxScroll sc = new MaxScroll(c, U.px(240));
        TextView cl = U.text(c, changelog == null ? "" : changelog, 13, U.SUB);
        cl.setMaxWidth(U.px(520));
        cl.setLineSpacing(U.px(13 * 0.6f), 1f);
        cl.setGravity(Gravity.CENTER);
        U.pad(cl, 8, 0, 8, 0);
        sc.addView(cl, new FrameLayout.LayoutParams(U.WC, U.WC));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(U.WC, U.WC);
        slp.topMargin = U.px(14);
        col.addView(sc, slp);

        // 进度：细条 + 百分比（默认隐藏，progress() 后显示）
        progRow = U.row(c);
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(U.bg(U.PANEL, 3));
        fill = new View(c);
        fill.setBackground(U.bg(U.ACC, 3));
        remain = new View(c);
        bar.addView(fill, new LinearLayout.LayoutParams(0, U.MP, 0f));
        bar.addView(remain, new LinearLayout.LayoutParams(0, U.MP, 100f));
        progRow.addView(bar, new LinearLayout.LayoutParams(0, U.px(6), 1f));
        pctTv = U.tabular(U.text(c, "0%", 11, U.SUB));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(U.WC, U.WC);
        plp.leftMargin = U.px(10);
        progRow.addView(pctTv, plp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(U.MP, U.WC);
        rlp.topMargin = U.px(14);
        col.addView(progRow, rlp);
        progRow.setVisibility(View.GONE);

        // 主按钮：强调渐变底 + 深色字；点一次立刻禁用
        installBtn = U.text(c, "⬇ 立即更新", 14, U.ON_ACC_TXT);
        installBtn.setGravity(Gravity.CENTER);
        installBtn.setBackground(U.accGradient(12));
        U.bold(installBtn);
        U.pad(installBtn, 22, 10, 22, 10);
        installBtn.setClickable(true);
        U.ripple(installBtn, 0x33FFFFFF, 12);
        installBtn.setOnClickListener(v -> {
            if (started) return;                   // 防连点落多个安装包
            started = true;
            installBtn.setEnabled(false);
            installBtn.setClickable(false);
            installBtn.setAlpha(0.6f);
            if (onInstall != null) onInstall.run();
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(U.WC, U.WC);
        ilp.topMargin = U.px(14);
        col.addView(installBtn, ilp);

        // 次按钮：ghost，仅下载中可见
        cancelBtn = U.ghostBtn(c, "✕ 取消下载");
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(v -> {
            if (onCancel != null) onCancel.run();
        });
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(U.WC, U.WC);
        clp2.topMargin = U.px(14);
        col.addView(cancelBtn, clp2);

        ov.addView(root, new FrameLayout.LayoutParams(U.MP, U.MP));
    }

    /** 更新进度（同时显示进度条与「取消下载」按钮） */
    public void progress(int pct) {
        if (root == null) return;
        int p = Math.max(0, Math.min(100, pct));
        U.visible(progRow, true);
        U.visible(cancelBtn, true);                // progress() 被调用过之后才允许取消
        LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        flp.weight = p;
        fill.setLayoutParams(flp);
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) remain.getLayoutParams();
        rlp.weight = 100 - p;
        remain.setLayoutParams(rlp);
        pctTv.setText(p + "%");
    }

    public void hide() {
        if (root == null) return;
        FrameLayout ov = sh.overlay();
        if (ov != null) ov.removeView(root);
        root = null;
        progRow = null;
        bar = null;
        fill = null;
        remain = null;
        pctTv = null;
        installBtn = null;
        cancelBtn = null;
        started = false;
    }

    /** 限高 ScrollView（更新内容 max-height 240u 的实现手段） */
    private static class MaxScroll extends ScrollView {
        private final int maxH;

        MaxScroll(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        @Override
        protected void onMeasure(int w, int h) {
            if (maxH > 0) h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            super.onMeasure(w, h);
        }
    }
}
