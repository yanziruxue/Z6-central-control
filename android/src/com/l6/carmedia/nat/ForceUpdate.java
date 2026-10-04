package com.l6.carmedia.nat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 更新弹窗（v2.0.4 起两种模式共用本类）。
 *
 * <p><b>① 强制更新</b>（发布正文含 {@code [force]}）：标题「⚠️ 必须更新到最新版本」，
 * 全屏遮罩 rgba(0,0,0,.88)、<b>不可取消</b>（遮罩吃掉所有触摸、不响应返回键）；
 * 次按钮「✕ 取消下载」是 ghost 且<b>仅下载开始后</b>可见。
 *
 * <p><b>② 非强制提示</b>（v2.0.4 新增，启动自动检查发现新版时）：标题「发现新版本 vX.Y.Z」用强调色；
 * 一开始就多给两个出口 —— **[✕ 稍后再说]**（本次不更，<b>下次启动仍会提示</b>）与
 * **[跳过此版]**（记住该版本号，<b>此后再不提示</b>，由 {@code Ota} 持久化）；
 * 点遮罩 = 稍后再说。
 *
 * <p><b>③ 两种模式一旦点了「立即更新」</b>就都进入「下载中」：隐藏 稍后/跳过、显示「✕ 取消下载」，
 * 且<b>点遮罩不再关闭</b> —— 正在下包时静默关掉会让人以为取消了。
 *
 * <p>★ 「立即更新」在<b>点击瞬间</b>就禁用（v2.0.1 的教训：连点会落多个安装包）。
 *
 * <p>数值照抄原型 CSS：#otaForce 间距 gap 14u / 内边距 24u；h2 20u；
 * p 13u / 最大宽 520u / line-height 1.6 / max-height 240u；.set-opt.primary 内边距 10u 22u / 14u。
 */
public class ForceUpdate {

    /**
     * 遮罩深浅分模式（★ v2.0.6）。
     *
     * <p>原先两种模式都用 0xE0000000（88% 黑）⇒ **背景压得太黑**，主页几乎看不见，
     * 观感上不像「弹窗」更像「黑屏 + 一行字」。现在：强制更新是「不可逆的全屏接管」，
     * 保持较深（85%）；非强制提示是**常规弹窗**，遮罩只压到 54%，主页仍隐约可见。
     */
    private static final int SCRIM_FORCE = 0xD9000000;
    private static final int SCRIM_OPTIONAL = 0x8A000000;

    /** 弹窗卡片底：v2.0.6 新增，**不透明**且比页面底略亮（否则遮罩上文字会发飘）。 */
    private static final int CARD_BG = 0xFF151B29;

    private final Shell sh;
    private FrameLayout root;
    private LinearLayout progRow;
    private LinearLayout bar;
    private View fill;
    private View remain;
    private TextView pctTv;
    private TextView installBtn;
    private TextView cancelBtn;      // ✕ 取消下载（下载中可见）
    private TextView laterBtn;       // ✕ 稍后再说（非强制，初始可见）
    private TextView skipBtn;        //  跳过此版（非强制，初始可见）
    private boolean optional;        // 非强制模式
    private boolean started;         // 点过「立即更新」
    private Runnable onLater, onSkip;

    public ForceUpdate(Shell sh) {
        this.sh = sh;
    }

    /** 强制更新（保持 v2.0.0 起的原语义：不可取消、取消按钮仅下载中可见）。 */
    public void show(String changelog, final Runnable onInstall, final Runnable onCancel) {
        build("⚠️ 必须更新到最新版本", U.WARN, changelog, false,
                onInstall, onCancel, null, null);
    }

    /** 非强制的新版本提示（v2.0.4）。 */
    public void showOptional(String version, String changelog,
                             Runnable onInstall, Runnable onLater, Runnable onSkip) {
        build("发现新版本 v" + (version == null ? "" : version), U.ACC, changelog, true,
                onInstall, null, onLater, onSkip);
    }

    private void build(String titleText, int titleColor, String changelog, boolean optionalMode,
                       final Runnable onInstall, final Runnable onCancel,
                       Runnable onLaterCb, Runnable onSkipCb) {
        if (root != null) return;                  // 已在展示 → 不叠层
        FrameLayout ov = sh.overlay();
        if (ov == null) return;
        Context c = sh.ctx();
        optional = optionalMode;
        onLater = onLaterCb;
        onSkip = onSkipCb;

        root = new FrameLayout(c);
        root.setBackgroundColor(optionalMode ? SCRIM_OPTIONAL : SCRIM_FORCE);
        root.setClickable(true);                   // 吃掉所有触摸（含点击穿透）
        // ★ 只有非强制模式才允许「点遮罩 = 稍后再说」；下载中（started）一律不关。
        // ★ 必须先把回调捕获成局部变量：hide() 会把字段 onLater/onSkip 置 null，
        //   而 lambda 里读的是**字段** ⇒ 直接写在 hide() 之后会永远为 null、回调从不执行。
        final Runnable laterCb = onLaterCb, skipCb = onSkipCb, cancelCb = onCancel;
        root.setOnClickListener(v -> {
            if (!optional || started) return;
            hide();
            if (laterCb != null) laterCb.run();
        });

        // ★ v2.0.6：做成真正的「弹窗」—— 卡片底 + 1px 边框 + 圆角 18 + 投影，
        //   而不再是「裸文字直接压在遮罩上」。卡片背景不透明，文字才不会与遮罩混在一起。
        LinearLayout col = U.col(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackground(U.bg(CARD_BG, 18, U.LINE, 1));
        U.pad(col, 28, 22, 28, 22);
        try { col.setElevation(U.px(14)); } catch (Throwable ignored) { }
        // ★ v2.0.7：卡片给固定宽度（820u），横向按钮行才排得下、正文也不会被挤成一条窄柱
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(U.px(820), U.WC, Gravity.CENTER);
        root.addView(col, clp);

        TextView title = U.text(c, titleText, 20, titleColor);
        title.setGravity(Gravity.CENTER);
        col.addView(title, new LinearLayout.LayoutParams(U.WC, U.WC));

        // 更新内容：可滚动、保留换行、等宽换行
        MaxScroll sc = new MaxScroll(c, U.px(240));
        // ★ v2.0.7：正文里的 sha256 校验行不展示（下载后仍按它校验，只是 UI 不显示）
        TextView cl = U.text(c, U.hideShaLine(changelog), 13, U.SUB);
        cl.setMaxWidth(U.px(760));
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

        // 主按钮：强调渐变底 + 深色字；点一次立刻禁用（v2.0.1：连点会落多个安装包）
        installBtn = U.text(c, "⬇ 立即更新", 14, U.ON_ACC_TXT);
        installBtn.setGravity(Gravity.CENTER);
        installBtn.setBackground(U.accGradient(12));
        U.bold(installBtn);
        U.pad(installBtn, 22, 10, 22, 10);
        installBtn.setClickable(true);
        U.ripple(installBtn, 0x33FFFFFF, 12);
        installBtn.setOnClickListener(v -> {
            if (started) return;
            started = true;
            installBtn.setEnabled(false);
            installBtn.setClickable(false);
            installBtn.setAlpha(0.6f);
            // 进入下载中：收掉「稍后/跳过」，只剩「取消下载」（progress() 才点亮它）
            U.visible(laterBtn, false);
            U.visible(skipBtn, false);
            if (onInstall != null) onInstall.run();
        });
        // ★ v2.0.7：按钮改**横向一行**，顺序按用户指定 立即更新 / 跳过此版 / 稍后再说。
        //   主按钮用 weight=1 吃掉剩余宽度，两个 ghost 按文字自适应；行间 8u 间隙。
        LinearLayout btnRow = U.row(c);
        btnRow.addView(installBtn, U.lp(0, U.WC, 1f));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(U.MP, U.WC);
        rowLp.topMargin = U.px(14);
        if (optional) {
            U.gapH(btnRow, 8);
            skipBtn = U.ghostBtn(c, "跳过此版");
            skipBtn.setOnClickListener(v -> {
                hide();
                if (skipCb != null) skipCb.run();
            });
            btnRow.addView(skipBtn, U.lp(U.WC, U.WC));
            U.gapH(btnRow, 8);
            laterBtn = U.ghostBtn(c, "✕ 稍后再说");
            laterBtn.setOnClickListener(v -> {
                hide();
                if (laterCb != null) laterCb.run();
            });
            btnRow.addView(laterBtn, U.lp(U.WC, U.WC));
        } else {
            // 强制更新只有「立即更新」一个按钮：不能让它 weight 拉满 820u 居中撑开
            btnRow.setGravity(Gravity.CENTER);
            btnRow.removeView(installBtn);
            btnRow.addView(installBtn, U.lp(U.WC, U.WC));
            laterBtn = null;
            skipBtn = null;
        }
        col.addView(btnRow, rowLp);

        // 「✕ 取消下载」仍单独一行、仅下载中可见（两种模式都保留）
        cancelBtn = U.ghostBtn(c, "✕ 取消下载");
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(v -> {
            if (optional) hide();              // 非强制：明确取消就把弹窗收掉
            if (cancelCb != null) cancelCb.run();
        });
        col.addView(cancelBtn, gapTop());

        ov.addView(root, new FrameLayout.LayoutParams(U.MP, U.MP));
    }

    private LinearLayout.LayoutParams gapTop() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(U.WC, U.WC);
        p.topMargin = U.px(14);
        return p;
    }

    /** 更新进度（同时显示进度条与「取消下载」按钮）。 */
    public void progress(int pct) {
        if (root == null) return;
        int p = Math.max(0, Math.min(100, pct));
        U.visible(progRow, true);
        U.visible(cancelBtn, true);                // progress() 被调用过之后才允许取消下载
        // 下载中不再提供「稍后/跳过」—— 已经开始下了，此时改主意只能取消
        U.visible(laterBtn, false);
        U.visible(skipBtn, false);
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
        laterBtn = null;
        skipBtn = null;
        onLater = null;
        onSkip = null;
        optional = false;
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
