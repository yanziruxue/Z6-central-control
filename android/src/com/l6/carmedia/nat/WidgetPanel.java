package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 小部件选择面板（原型 #wgScrim + #wgSheet）。
 *
 * 复用应用抽屉的浮层骨架：遮罩 + 底部面板（手柄 / 头部 / 内容），顶部滑入 300ms。
 * 内容 = 搜索框 + 行列表；数据来自 host().widgetList()（每项 String[]{pkg, cls, 名称}）。
 *
 * 数值照抄原型 CSS：搜索内边距 10/16/2u、输入框 12u 圆角 / 内边距 9/12u / 13u；
 * 行列表内边距 8/14/18u、gap 6u；行内边距 10/12u、12u 圆角；名称 13u、右侧包名 11u SUB。
 */
public class WidgetPanel {

    private static final Interpolator EASE = new PathInterpolator(0.2f, 0.85f, 0.25f, 1f);
    private static final int PANEL_FILL = 0xFA10141E;
    private static final int SCRIM = 0x80000000;

    private final Shell sh;
    private FrameLayout scrim;
    private LinearLayout panel;
    private MaxScroll scroll;
    private LinearLayout rows;
    private EditText search;
    private List<String[]> all;
    private boolean open;

    public WidgetPanel(Shell sh) {
        this.sh = sh;
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
        TextView titleTv = U.text(c, "🧩 选择小部件", 15, U.TXT);
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

        // 搜索框
        LinearLayout searchWrap = U.col(c);
        U.pad(searchWrap, 16, 10, 16, 2);
        search = new EditText(c);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        U.textSize(search, 13);
        search.setTextColor(U.TXT);
        search.setHintTextColor(U.SUB);
        search.setHint("搜索小部件");
        search.setBackground(U.bg(U.PANEL, 12, U.LINE, 1));
        U.pad(search, 12, 9, 12, 9);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int cc) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int cc) {}

            @Override
            public void afterTextChanged(Editable s) {
                render(s == null ? "" : s.toString());
            }
        });
        searchWrap.addView(search, new LinearLayout.LayoutParams(U.MP, U.WC));
        panel.addView(searchWrap, new LinearLayout.LayoutParams(U.MP, U.WC));

        // 行列表
        scroll = new MaxScroll(c, U.px(605));
        rows = U.col(c);
        U.pad(rows, 14, 8, 14, 18);
        scroll.addView(rows, new FrameLayout.LayoutParams(U.MP, U.WC));
        panel.addView(scroll, new LinearLayout.LayoutParams(U.MP, U.WC));

        all = sh.host().widgetList();
        render("");

        final LinearLayout pnl = panel;
        final MaxScroll sc = scroll;
        final View gripV = grip;
        pnl.post(() -> {
            int cap = U.px(605) - gripV.getHeight() - head.getHeight()
                    - Math.max(1, U.px(1)) - searchWrap.getHeight();
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
        rows = null;
        search = null;
        if (ov == null || pnl == null) return;
        if (scr != null) scr.animate().alpha(0f).setDuration(250).start();
        pnl.animate().translationY(pnl.getHeight() > 0 ? pnl.getHeight() : U.px(720))
                .setDuration(300).setInterpolator(EASE)
                .withEndAction(() -> {
                    ov.removeView(pnl);
                    if (scr != null) ov.removeView(scr);
                }).start();
    }

    // ------------------------------------------------------------------ 列表

    private void render(String kw) {
        if (rows == null) return;
        Context c = sh.ctx();
        rows.removeAllViews();
        if (all == null || all.isEmpty()) {
            rows.addView(empty(c, "未读到可用小部件"));
            return;
        }
        String k = kw == null ? "" : kw.trim().toLowerCase();
        List<String[]> view = new ArrayList<>();
        for (String[] it : all) {
            if (it == null || it.length < 2) continue;
            if (k.isEmpty()) {
                view.add(it);
            } else {
                String label = it.length > 2 && it[2] != null ? it[2] : "";
                String pkg = it[0] == null ? "" : it[0];
                if ((label + " " + pkg).toLowerCase().contains(k)) view.add(it);
            }
        }
        if (view.isEmpty()) {
            rows.addView(empty(c, "没有匹配「" + kw + "」的小部件"));
            return;
        }
        for (String[] it : view) {
            rows.addView(makeRow(it));
        }
        U.gapV(rows, 6);
    }

    private View makeRow(final String[] it) {
        Context c = sh.ctx();
        final String pkg = it[0] == null ? "" : it[0];
        final String cls = it[1] == null ? "" : it[1];
        String name = it.length > 2 && !TextUtils.isEmpty(it[2]) ? it[2] : "小部件";

        LinearLayout row = U.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        U.pad(row, 12, 10, 12, 10);
        row.setBackground(U.bg(U.PANEL2, 12, Color.TRANSPARENT, 1));
        row.setClickable(true);
        U.ripple(row, 0x33FFFFFF, 12);

        TextView nm = U.text(c, name, 13, U.TXT);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nm, new LinearLayout.LayoutParams(0, U.WC, 1f));

        TextView meta = U.text(c, pkg, 11, U.SUB);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(U.WC, U.WC);
        mlp.leftMargin = U.px(10);
        row.addView(meta, mlp);

        row.setOnClickListener(v -> {
            sh.host().widgetBind(pkg, cls);
            sh.refreshWidget();
            sh.toast("已嵌入小部件");
            close();
        });
        return row;
    }

    private TextView empty(Context c, String s) {
        TextView e = U.text(c, s, 13, U.SUB);
        e.setGravity(Gravity.CENTER);
        U.pad(e, 30, 30, 30, 30);
        return e;
    }

    private Drawable panelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(PANEL_FILL);
        float r = U.px(24);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(Math.max(1, U.px(1)), U.LINE);
        return g;
    }

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
