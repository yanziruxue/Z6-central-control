package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 主页三栏（原型 apk-dashboard-prototype.html 的 section#pageMusic，第 492~532 行）的原生重写。
 *
 * <p>★ 与原型逐像素对齐的前提：所有尺寸 / 字号 / 圆角 / 间距都照抄 CSS 里的 calc(N * var(--u))，
 * 把 N 原样喂给 {@link U#px(float)}；u = min(w/1920, h/720)，1920x720 时 u=1。
 *
 * <p>★ 三栏等宽：原型用 grid minmax(0,1fr)，这里用 LinearLayout(HORIZONTAL) + 每栏
 * layout_width=0 / weight=1f。未勾选的栏 GONE（GONE 不占 weight）⇒ 自动变各占 1/2。
 *
 * <p>★ 每秒一次 {@link #onSys(JSONObject)} 只更新已有 TextView 的文本与可见性，**绝不重建视图树**
 * （每秒重建会闪烁、掉帧）。所有字段一律 optXxx + 默认值容错，任何缺失都不抛异常。
 */
public class HomePane {

    private final Shell sh;
    private final Context ctx;

    /** 整页根容器（原型 .page：padding 14u 18u、栏间 gap 14u） */
    private LinearLayout root;

    // ------------------------------------------------------------------ 三栏根视图（常驻，refresh 只调顺序/可见性）

    private LinearLayout stateCol;   // data-mod="state"  → .card.carpanel（透明、无边框）
    private LinearLayout musicCol;   // data-mod="music"  → .music

    // ------------------------------------------------------------------ 栏 1：车辆状态

    private CarBox carBox;                       // .car-wrap（车身底图坐标系）
    private Bitmap bmpBody, bmpFl, bmpFr, bmpRl, bmpRr, bmpTrk;
    private ImageView ovFl, ovFr, ovRl, ovRr, ovTrk;
    private WinDot dotFl, dotFr, dotRl, dotRr;
    private TextView trkMark;
    private TextView tpFl, tpFr, tpRl, tpRr;
    private Drawable tpcBgN, tpcBgLow, dlBgN, dlBgOpen;
    private static final int TPC_LOW_TXT = 0xFFFF9A9A;

    /** 车况小卡片：10 张固定卡（6 数值 + 4 车身），只改文本不动结构 */
    private final TextView[] cardVal = new TextView[10];
    private final TextView[] cardUnit = new TextView[10];
    private final LinearLayout[] cardBox = new LinearLayout[10];
    private Drawable cardBgN, cardBgW;

    // ------------------------------------------------------------------ 栏 3：歌词

    /** 固定 6 行歌词（原型 k=-2..3），不足补占位行，避免歌词区随行数回跳 */
    private final TextView[] lyrRows = new TextView[6];
    /** 各行的基础不透明度（对 CSS 上下渐隐 mask 的近似，见 setAlpha 注释） */
    private static final float[] LYR_BASE = {0.18f, 0.42f, 1.0f, 0.42f, 0.25f, 0.13f};
    private static final int LYR_TXT = 0xFFE6EDF7;
    private SeekBarView seek;
    private TextView tCur, tDur;
    private int lrcIdx = -99;                    // -99=未初始化，-2=空歌词，其它=当前行号

    // ==================================================================== 构造 / 构建

    public HomePane(Shell sh) {
        this.sh = sh;
        this.ctx = sh.ctx();
    }

    /** 构建整页；重复调用返回同一实例，不重建 */
    public View build() {
        if (root != null) return root;

        loadCarAssets();

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        // 原型 .page padding 14u 18u；栏间 gap 14u 用「每栏 leftMargin 14u」实现 ——
        // 于是左侧内边距只需 18-14=4u，任何子集下左右边缘都恰好落在 18u 处。
        root.setPadding(U.px(4), U.px(14), U.px(18), U.px(14));

        stateCol = buildStateCol();
        musicCol = buildMusicCol();

        refresh();          // 按 homeState() 摆位 + 可见性
        // 先铺一遍占位（真实数据由每秒 onSys 覆盖）
        updateCar(null);
        updateMedia(null, null);

        return root;
    }

    /**
     * 按最新 homeState()（LinkedHashMap 保序）重排三栏并切换可见性。
     * 只调顺序 / GONE，不重建栏内结构。
     */
    public void refresh() {
        if (root == null) return;
        LinkedHashMap<String, Boolean> st = sh.prefs().homeState();
        root.removeAllViews();
        for (Map.Entry<String, Boolean> e : st.entrySet()) {
            LinearLayout col = colFor(e.getKey());
            if (col == null) continue;
            boolean on = e.getValue() != null && e.getValue();
            col.setVisibility(on ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.MP, 1f);
            p.leftMargin = U.px(14);
            root.addView(col, p);
        }
    }

    private LinearLayout colFor(String key) {
        if ("state".equals(key)) return stateCol;
        if ("music".equals(key)) return musicCol;
        return null;
    }

    // ==================================================================== 栏 1：车辆状态

    private LinearLayout buildStateCol() {
        LinearLayout col = U.col(ctx);
        col.setBackground(null);                 // .carpanel：透明、无边框

        // 复用背景 Drawable，避免每秒 new（onSys 每秒一次）
        cardBgN = U.bg(U.PANEL2, 8, U.LINE, 1);
        cardBgW = U.bg(U.PANEL2, 8, U.WARN, 1);
        tpcBgN = U.bg(0x80060A10, 7, U.LINE, 1);
        tpcBgLow = U.bg(0x33FF6B6B, 7, U.WARN, 1);
        dlBgN = U.bg(0x8C060A10, 8, U.LINE, 1);
        GradientDrawable go = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{U.WARN, 0xFFFF6B6B});
        go.setCornerRadius(U.px(8));
        dlBgOpen = go;

        TextView head = U.text(ctx, "车辆状态", 13, U.TXT);   // .cp-head 13u / weight 600
        U.bold(head);
        col.addView(head, U.lp(U.MP, U.WC));

        LinearLayout model = U.col(ctx);         // .car-model：flex 1.32、纵向居中、gap 4u
        model.setGravity(Gravity.CENTER);
        // 纵向 LinearLayout 的加权维度是「高」⇒ 高 0 + weight
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(U.MP, 0, 1.32f);
        mp.topMargin = U.px(10);
        col.addView(model, mp);

        carBox = buildCarBox();
        model.addView(carBox, U.lp(U.WC, U.WC));

        LinearLayout legend = U.row(ctx);        // .legend：gap 14u / 11u
        TextView l0 = U.text(ctx, "车窗", 11, U.SUB);
        legend.addView(l0, U.lp(U.WC, U.WC));
        legend.addView(legendItem("关", U.ACC2), leftGap(U.WC, U.WC, 14));
        legend.addView(legendItem("开", U.WARN), leftGap(U.WC, U.WC, 14));
        LinearLayout.LayoutParams lgp = U.lp(U.WC, U.WC);
        lgp.topMargin = U.px(4);                 // .car-model gap 4u
        model.addView(legend, lgp);

        // .car-status：flex 1、可滚动、gap 9u
        ScrollView sc = new ScrollView(ctx);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout status = U.col(ctx);
        status.setPadding(0, 0, U.px(2), 0);
        sc.addView(status, U.lp(U.MP, U.WC));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(U.MP, 0, 1f);
        sp.topMargin = U.px(10);
        col.addView(sc, sp);

        // 动力 · 服务端（3 列网格，6 项 → 2 行）
        status.addView(sectionLabel("动力 · 服务端"), U.lp(U.MP, U.WC));
        String[] nk = {"油表", "电瓶", "里程", "负载", "温度", "延时"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = U.row(ctx);
            for (int c = 0; c < 3; c++) {
                int i = r * 3 + c;
                row.addView(buildCard(i, nk[i]), cardLP(c));
            }
            LinearLayout.LayoutParams rp = U.lp(U.MP, U.WC);
            if (r > 0) rp.topMargin = U.px(5);
            status.addView(row, rp);
        }
        // 车身（4 列网格）
        LinearLayout sec2 = sectionLabel("车身");
        LinearLayout.LayoutParams s2p = U.lp(U.MP, U.WC);
        s2p.topMargin = U.px(9);
        status.addView(sec2, s2p);
        LinearLayout row2 = U.row(ctx);
        String[] bk = {"大灯", "双闪", "空调", "门锁"};
        for (int c = 0; c < 4; c++) row2.addView(buildCard(6 + c, bk[c]), cardLP(c));
        LinearLayout.LayoutParams r2p = U.lp(U.MP, U.WC);
        r2p.topMargin = U.px(5);
        status.addView(row2, r2p);

        return col;
    }

    private LinearLayout.LayoutParams cardLP(int idxInRow) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.WC, 1f);
        if (idxInRow > 0) p.leftMargin = U.px(5);   // .cs-grid gap 5u
        return p;
    }

    private LinearLayout.LayoutParams leftGap(float w, float h, float gapD) {
        LinearLayout.LayoutParams p = U.lp(w, h);
        p.leftMargin = U.px(gapD);
        return p;
    }

    /** .cs-label：3x10 强调竖条 + 10u 次级字 + 1px 字距 */
    private LinearLayout sectionLabel(String s) {
        LinearLayout r = U.row(ctx);
        View bar = new View(ctx);
        bar.setBackground(U.bg(U.ACC, 2));
        r.addView(bar, U.lp(3, 10));
        TextView t = U.text(ctx, s, 10, U.SUB);
        t.setLetterSpacing(1f / 10f);
        LinearLayout.LayoutParams p = U.lp(U.WC, U.WC);
        p.leftMargin = U.px(6);
        r.addView(t, p);
        return r;
    }

    /** .cst：次级面板底 + 边框 + 圆角 8u；值(13u 粗 等宽) / 名(10u) / 单位(9u) */
    private LinearLayout buildCard(int i, String key) {
        LinearLayout c = U.col(ctx);
        c.setBackground(U.bg(U.PANEL2, 8, U.LINE, 1));
        U.pad(c, 2, 4, 2, 4);                    // .cst padding 4u 2u
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = U.text(ctx, PH, 13, U.TXT);
        U.bold(v);
        U.tabular(v);
        v.setGravity(Gravity.CENTER);
        TextView k = U.text(ctx, key, 10, U.SUB);
        k.setGravity(Gravity.CENTER);
        TextView u = U.text(ctx, "", 9, U.SUB);
        u.setGravity(Gravity.CENTER);
        c.addView(v, U.lp(U.MP, U.WC));
        c.addView(k, U.lp(U.MP, U.WC));
        c.addView(u, U.lp(U.MP, U.WC));
        cardVal[i] = v;
        cardUnit[i] = u;
        cardBox[i] = c;
        return c;
    }

    private LinearLayout legendItem(String txt, int dotColor) {
        LinearLayout r = U.row(ctx);
        View dot = new View(ctx);
        dot.setBackground(U.bg(dotColor, 99));   // 9u 圆点
        LinearLayout.LayoutParams dp = U.lp(9, 9);
        dp.rightMargin = U.px(4);
        r.addView(dot, dp);
        TextView t = U.text(ctx, txt, 11, U.SUB);
        r.addView(t, U.lp(U.WC, U.WC));
        return r;
    }

    private CarBox buildCarBox() {
        CarBox box = new CarBox(ctx);

        ImageView body = new ImageView(ctx);
        body.setScaleType(ImageView.ScaleType.FIT_XY);
        if (bmpBody != null) body.setImageBitmap(bmpBody);
        box.addFrac(body, 0f, 0f, 1f, 1f);

        // 5 张开启叠加图：位置逐字照抄原型 img 的 style 百分比（相对 .car-wrap）
        ovFl = makeOv(bmpFl); box.addFrac(ovFl, 0.00647f, 0.23556f, 0.29754f, 0.28000f);
        ovFr = makeOv(bmpFr); box.addFrac(ovFr, 0.73480f, 0.29111f, 0.25873f, 0.21000f);
        ovRl = makeOv(bmpRl); box.addFrac(ovRl, 0.01423f, 0.42778f, 0.28978f, 0.26222f);
        ovRr = makeOv(bmpRr); box.addFrac(ovRr, 0.73480f, 0.44222f, 0.24450f, 0.23889f);
        ovTrk = makeOv(bmpTrk); box.addFrac(ovTrk, 0.34411f, 0.74111f, 0.39586f, 0.16444f);

        // 4 个胎压标注（10u 深底胶囊、等宽数字）
        tpFl = makeTpc(); box.addFrac(tpFl, 0.225f, 0.195f);
        tpFr = makeTpc(); box.addFrac(tpFr, 0.805f, 0.195f);
        tpRl = makeTpc(); box.addFrac(tpRl, 0.200f, 0.750f);
        tpRr = makeTpc(); box.addFrac(tpRr, 0.825f, 0.750f);

        // 4 个车窗标记（11u 圆点，translate(-50%,-50%) 居中）
        dotFl = new WinDot(ctx); box.addFrac(dotFl, 0.295f, 0.375f);
        dotFr = new WinDot(ctx); box.addFrac(dotFr, 0.730f, 0.375f);
        dotRl = new WinDot(ctx); box.addFrac(dotRl, 0.292f, 0.580f);
        dotRr = new WinDot(ctx); box.addFrac(dotRr, 0.737f, 0.580f);

        // 后备箱文字胶囊（10u，深底 0x8C060A10，边框 U.LINE）
        trkMark = U.text(ctx, "后备箱", 10, U.TXT);
        U.bold(trkMark);
        trkMark.setBackground(dlBgN);
        U.pad(trkMark, 6, 1, 6, 1);
        box.addFrac(trkMark, 0.545f, 0.875f);

        return box;
    }

    private ImageView makeOv(Bitmap bm) {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        if (bm != null) v.setImageBitmap(bm);
        v.setVisibility(View.INVISIBLE);         // 默认不可见（对应 .car-ov opacity:0）
        return v;
    }

    private TextView makeTpc() {
        TextView t = U.text(ctx, PH, 10, U.TXT);
        U.bold(t);
        U.tabular(t);
        t.setBackground(tpcBgN);
        U.pad(t, 6, 0, 6, 0);
        return t;
    }

    /** 图片解码只做一次，缓存字段复用（绝不每帧解码） */
    private void loadCarAssets() {
        bmpBody = assetBitmap("car/body.webp");
        bmpFl = assetBitmap("car/fl.webp");
        bmpFr = assetBitmap("car/fr.webp");
        bmpRl = assetBitmap("car/rl.webp");
        bmpRr = assetBitmap("car/rr.webp");
        bmpTrk = assetBitmap("car/trk.webp");
    }

    private Bitmap assetBitmap(String path) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(path);
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    // ==================================================================== 栏 3：歌词

    private LinearLayout buildMusicCol() {
        LinearLayout music = U.row(ctx);          // .music：flex row
        LinearLayout lyrics = U.col(ctx);         // .lyrics（无卡片外框）
        U.pad(lyrics, 4, 2, 4, 2);
        music.addView(lyrics, new LinearLayout.LayoutParams(0, U.MP, 1f));

        LinearLayout lyrBox = U.col(ctx);         // .lyr：纵向居中、gap 10u、左内边距 30%
        lyrBox.setGravity(Gravity.CENTER);
        lyrics.addView(lyrBox, new LinearLayout.LayoutParams(U.MP, 0, 1f));
        // padding-left:30%（把文字中心右推）；宽度运行时才知道，故布局后回填
        lyrBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int pl = Math.round((r - l) * 0.30f);
            if (v.getPaddingLeft() != pl) v.setPadding(pl, 0, 0, 0);
        });

        for (int i = 0; i < 6; i++) {
            boolean cur = (i == 2);               // 当前句恒为中间行（k=0）
            TextView t = U.text(ctx, " ", cur ? 25 : 20, cur ? Color.WHITE : LYR_TXT);
            U.bold(t);
            t.setGravity(Gravity.CENTER);
            lyrRows[i] = t;
            LinearLayout.LayoutParams p = U.lp(U.MP, U.WC);
            if (i > 0) p.topMargin = U.px(10);    // gap 10u
            lyrBox.addView(t, p);
        }

        // .progress：margin-top 10u
        LinearLayout prog = U.col(ctx);
        seek = new SeekBarView(ctx);
        prog.addView(seek, U.lp(U.MP, 6));        // .bar height 6u
        LinearLayout ptime = U.row(ctx);          // .ptime：两端对齐
        tCur = U.text(ctx, "0:00", 12, U.SUB);
        U.tabular(tCur);
        tDur = U.text(ctx, "0:00", 12, U.SUB);
        U.tabular(tDur);
        ptime.addView(tCur, U.lp(U.WC, U.WC));
        ptime.addView(U.spacer(ctx), new LinearLayout.LayoutParams(0, 0, 1f));
        ptime.addView(tDur, U.lp(U.WC, U.WC));
        LinearLayout.LayoutParams pp = U.lp(U.MP, U.WC);
        pp.topMargin = U.px(6);                   // .ptime margin-top 6u
        prog.addView(ptime, pp);
        LinearLayout.LayoutParams prp = U.lp(U.MP, U.WC);
        prp.topMargin = U.px(10);                 // .progress margin-top 10u
        lyrics.addView(prog, prp);

        return music;
    }

    // ==================================================================== 每秒系统数据

    /**
     * 每秒一次。所有分支单独 try，任一字段缺失 / 类型不符都只跳过该块，绝不抛异常、绝不重建视图树。
     */
    public void onSys(JSONObject sys) {
        if (sys == null) sys = new JSONObject();
        try { updateCar(sys.optJSONObject("car")); } catch (Throwable ignored) { }
        try { updateMedia(sys.optJSONObject("media"), sys.optJSONObject("lyrics")); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ 车况

    private void updateCar(JSONObject car) {
        JSONObject doors = obj(car, "doors");
        JSONObject wins = obj(car, "windows");
        JSONObject tire = obj(car, "tire");
        JSONObject ac = obj(car, "ac");

        boolean trunk = car != null && car.optBoolean("trunk", false);

        setOv(ovFl, doors, "fl"); setDot(dotFl, wins, "fl");
        setOv(ovFr, doors, "fr"); setDot(dotFr, wins, "fr");
        setOv(ovRl, doors, "rl"); setDot(dotRl, wins, "rl");
        setOv(ovRr, doors, "rr"); setDot(dotRr, wins, "rr");
        setVis(ovTrk, trunk);
        styleTrunk(trunk);

        setTp(tpFl, tire, "fl");
        setTp(tpFr, tire, "fr");
        setTp(tpRl, tire, "rl");
        setTp(tpRr, tire, "rr");

        // 动力 · 服务端（数值卡）
        numCard(0, car, "fuel", "%");
        numCard(1, car, "batt", "V");
        numCard(2, car, "odo", "km");
        numCard(3, car, "load", "%");
        numCard(4, car, "temp", "°C");
        numCard(5, car, "lat", "ms");

        // 车身（布尔 / 空调卡）
        boolCard(6, car, "lights", "近光", "关", false);
        boolCard(7, car, "hazard", "开", "关", true);
        acCard(8, ac);
        boolCard(9, car, "lock", "已锁", "未锁", true);
    }

    private void setOv(ImageView ov, JSONObject doors, String k) {
        setVis(ov, doors != null && doors.optBoolean(k, false));
    }

    private void setDot(WinDot dot, JSONObject wins, String k) {
        boolean open = wins != null && wins.optBoolean(k, false);
        if (dot.open != open) {
            dot.open = open;
            dot.invalidate();
        }
    }

    private void styleTrunk(boolean open) {
        trkMark.setTextColor(open ? 0xFF22140A : U.TXT);
        trkMark.setBackground(open ? dlBgOpen : dlBgN);
    }

    private void setTp(TextView tp, JSONObject tire, String k) {
        if (missing(tire, k)) {
            tp.setText(PH);
            tp.setBackground(tpcBgN);
            tp.setTextColor(U.TXT);
            return;
        }
        double v = tire.optDouble(k, Double.NaN);
        if (Double.isNaN(v)) {
            tp.setText(PH);
            tp.setBackground(tpcBgN);
            tp.setTextColor(U.TXT);
            return;
        }
        tp.setText(String.format(Locale.US, "%.1f", v));
        boolean low = v < 2.0;
        tp.setBackground(low ? tpcBgLow : tpcBgN);
        tp.setTextColor(low ? TPC_LOW_TXT : U.TXT);
    }

    private void numCard(int i, JSONObject o, String k, String unit) {
        boolean miss = missing(o, k);
        setCard(i, miss ? PH : numStr(o, k), miss ? "" : unit, 0);
    }

    private void boolCard(int i, JSONObject o, String k, String t, String f, boolean warnOn) {
        boolean miss = missing(o, k);
        boolean on = !miss && o.optBoolean(k, false);
        setCard(i, miss ? PH : (on ? t : f), "", (!miss && on && warnOn) ? 1 : 0);
    }

    private void acCard(int i, JSONObject ac) {
        boolean miss = ac == null || missing(ac, "on");
        setCard(i, miss ? PH : acText(ac), "", miss ? 0 : 2);
    }

    /** mode：0=常规 1=warn 2=ok */
    private void setCard(int i, String val, String unit, int mode) {
        cardVal[i].setText(val);
        cardUnit[i].setText(unit == null ? "" : unit);
        boolean warn = mode == 1;
        cardVal[i].setTextColor(warn ? U.WARN : (mode == 2 ? U.ACC2 : U.TXT));
        cardBox[i].setBackground(warn ? cardBgW : cardBgN);
    }

    private static String acText(JSONObject ac) {
        if (ac == null || !ac.optBoolean("on", false)) return "关闭";
        StringBuilder b = new StringBuilder(ac.optString("mode", "AUTO"));
        double temp = ac.optDouble("temp", Double.NaN);
        if (!Double.isNaN(temp)) b.append(" · ").append(numFmt(temp)).append("°");
        if (!missing(ac, "fan")) {
            double fan = ac.optDouble("fan", Double.NaN);
            if (!Double.isNaN(fan)) b.append(" · 风").append(numFmt(fan));
        }
        String circ = s(ac, "circulate", "");
        if (!circ.isEmpty()) b.append(" · ").append(circ).append("循环");
        return b.toString();
    }

    // ------------------------------------------------------------------ 媒体 / 歌词

    private void updateMedia(JSONObject media, JSONObject lyrics) {
        long pos = lng(media, "pos");
        long dur = lng(media, "dur");
        float frac = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
        seek.setFrac(frac);
        tCur.setText(fmtMsStr(pos));
        tDur.setText(fmtMsStr(dur));
        updateLyrics(lyrics, pos);
    }

    private void updateLyrics(JSONObject lyrics, long pos) {
        JSONArray lines = lyrics == null ? null : lyrics.optJSONArray("lines");
        int n = lines == null ? 0 : lines.length();

        if (n == 0) {
            if (lrcIdx == -2) return;             // 行没变不重绘
            lrcIdx = -2;
            String err = s(lyrics, "error", "");
            lyrRows[2].setText(err.isEmpty() ? "暂无歌词" : "暂无歌词 · " + err);
            lyrRows[2].setAlpha(1f);
            for (int i = 0; i < 6; i++) {
                if (i == 2) continue;
                lyrRows[i].setText(" ");
                lyrRows[i].setAlpha(0f);
            }
            return;
        }

        JSONObject first = lines.optJSONObject(0);
        boolean timed = first != null && first.optLong("t", -1) >= 0;
        int idx;
        if (timed) {
            idx = 0;
            for (int i = 0; i < n; i++) {
                JSONObject o = lines.optJSONObject(i);
                long t = o == null ? -1 : o.optLong("t", -1);
                if (t >= 0 && t <= pos) idx = i; else if (t > pos) break;
            }
        } else {
            idx = (int) ((pos / 2600) % n);       // 纯文本歌词：按 2.6s 轮转
        }
        if (idx == lrcIdx) return;                // 行没变不重绘，避免每秒闪
        lrcIdx = idx;

        for (int k = -2; k <= 3; k++) {
            int i = k + 2;
            int j = idx + k;
            if (j < 0 || j >= n) {
                lyrRows[i].setText(" ");          // 占位行（原型 .ph）
                lyrRows[i].setAlpha(0f);
            } else {
                JSONObject o = lines.optJSONObject(j);
                lyrRows[i].setText(o == null ? " " : s(o, "s", " "));
                lyrRows[i].setAlpha(LYR_BASE[i]);
            }
        }
    }

    /** 毫秒 → m:ss（与原型 fmtMs 同款） */
    private static String fmtMsStr(long ms) {
        long v = Math.max(0, ms);
        long s = v / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" + (s % 60) : "" + (s % 60));
    }

    // ==================================================================== 小工具

    private static final String PH = "—";

    private static JSONObject obj(JSONObject o, String k) {
        return o == null ? null : o.optJSONObject(k);
    }

    private static String s(JSONObject o, String k, String def) {
        if (o == null || !o.has(k) || o.isNull(k)) return def;
        String v = o.optString(k, def);
        return v == null ? def : v;
    }

    private static boolean missing(JSONObject o, String k) {
        return o == null || !o.has(k) || o.isNull(k);
    }

    private static String numStr(JSONObject o, String k) {
        if (missing(o, k)) return PH;
        double d = o.optDouble(k, Double.NaN);
        if (Double.isNaN(d)) return PH;
        return numFmt(d);
    }

    /** 整数去掉小数；否则保留 1 位并去掉多余的 0 */
    private static String numFmt(double d) {
        if (Math.abs(d - Math.rint(d)) < 1e-9) return String.valueOf((long) Math.rint(d));
        String x = String.format(Locale.US, "%.1f", d);
        int dot = x.indexOf('.');
        if (dot >= 0) {
            while (x.length() > dot + 1 && x.charAt(x.length() - 1) == '0') x = x.substring(0, x.length() - 1);
            if (x.endsWith(".")) x = x.substring(0, x.length() - 1);
        }
        return x;
    }

    private static long lng(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return 0;
        double d = o.optDouble(k, 0);
        if (Double.isNaN(d) || d < 0) return 0;
        return (long) d;
    }

    private static void setVis(View v, boolean on) {
        if (v != null) v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ==================================================================== 内部视图

    /**
     * 车身显示框：高度 ≤ 274u，宽度 = 高 * 556/647（body.webp 原始尺寸 556x647）。
     * 子视图用**相对本框的百分比**定位 —— left=pct*W、top=pct*H、size=pct*W|H；
     * pct 宽/高传负数表示「按自身自然尺寸 + 居中定位（translate(-50%,-50%)）」。
     */
    static final class CarBox extends FrameLayout {
        static final float BODY_AR = 556f / 647f;
        private final java.util.ArrayList<float[]> fr = new java.util.ArrayList<>();
        private int bw, bh;

        CarBox(Context c) { super(c); }

        void addFrac(View v, float l, float t, float w, float h) {
            fr.add(new float[]{l, t, w, h});
            addView(v);
        }

        void addFrac(View v, float l, float t) {
            addFrac(v, l, t, -1f, -1f);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int availH = (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED)
                    ? U.px(274) : MeasureSpec.getSize(hSpec);
            int maxH = U.px(274);
            int h = availH > 0 ? Math.min(availH, maxH) : maxH;
            int w = Math.round(h * BODY_AR);
            bw = w;
            bh = h;

            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                float[] f = i < fr.size() ? fr.get(i) : null;
                if (f == null || f[2] < 0f) {
                    measureChild(c, wSpec, hSpec);
                } else {
                    int cw = Math.max(1, Math.round(f[2] * w));
                    int ch = Math.max(1, Math.round(f[3] * h));
                    c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
                }
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                float[] f = i < fr.size() ? fr.get(i) : null;
                if (f == null) continue;
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (f[2] >= 0f) {
                    int x = Math.round(f[0] * bw), y = Math.round(f[1] * bh);
                    c.layout(x, y, x + cw, y + ch);
                } else {
                    int cx = Math.round(f[0] * bw), cy = Math.round(f[1] * bh);
                    c.layout(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
                }
            }
        }
    }

    /** 车窗状态点：关 = 绿实心；开 = 橙实心 + 外发光圈（对应 CSS 的 box-shadow 光晕） */
    static final class WinDot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean open;

        WinDot(Context c) { super(c); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int d = U.px(11), glow = U.px(6);
            setMeasuredDimension(d + glow * 2, d + glow * 2);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float core = U.px(11) / 2f;
            if (open) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(U.alpha(0xFFF87171, 0.55f));
                cv.drawCircle(cx, cy, core + U.px(5), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(open ? U.WARN : U.ACC2);
            cv.drawCircle(cx, cy, core, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, U.px(1)));
            p.setColor(open ? 0x99FFFFFF : 0x66FFFFFF);
            cv.drawCircle(cx, cy, core, p);
        }
    }

    /** 细进度条：底 0x1AFFFFFF + 已播 135° 渐变 ACC→PUR + 端点白点 */
    static final class SeekBarView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float frac;

        SeekBarView(Context c) {
            super(c);
            track.setColor(0x1AFFFFFF);
        }

        void setFrac(float f) {
            float nf = Math.max(0f, Math.min(1f, f));
            if (Math.abs(nf - frac) < 0.0005f) return;
            frac = nf;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0 && h > 0) {
                fill.setShader(new LinearGradient(0, 0, w, h, U.ACC, U.PUR, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, track);

            float fw = frac * w;
            if (fw > 0) {
                rect.set(0, 0, Math.max(fw, h), h);
                cv.drawRoundRect(rect, r, r, fill);
            }
            float kd = U.px(12), kx = Math.min(Math.max(fw, kd / 2f), w - kd / 2f);
            knob.setColor(Color.WHITE);
            cv.drawCircle(kx, h / 2f, kd / 2f, knob);
        }
    }
}
