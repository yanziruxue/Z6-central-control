package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 设置页（原型 #pageSet）的原生实现。
 *
 * ★ 为什么整页只认 {@link Shell} 不认 Activity：
 *   原生重写期要和 WebView 版并排对照，两边共用同一份 Prefs/Host；
 *   视图层一旦直接 new Activity 里的方法，对照期就得改两份。走 Shell 后，
 *   收尾删 WebView 时只需把 Shell 实现搬进 MainActivity，本页一行不用动。
 *
 * ★ 布局全部手写：尺寸/字号/间距一律经 U.px / U.textSize 换算，数字直接照抄原型 CSS 的 N。
 *   Java 源里绝不出现「反斜杠 + u」的写法（javac 的 Unicode 转义预处理在词法分析之前，
 *   连注释里的也会被当转义解析、直接编译失败）。
 *
 * ★ 动态内容（勾选态、当前源、手势绑定、壁纸网格、权限、采集、OTA）统一由 refresh() 重算，
 *   每次交互改完模型就重算一次，避免「模型变了界面没变」这类幽灵问题。
 */
public class SetPane {

    private final Shell sh;
    private final Context c;
    private final Prefs prefs;

    private View root;

    // ---- 主页模式 ----
    private LinearLayout hmRow;
    /** 原型 HM_LP_MS：按住 350ms 进拖动；未进拖动前横向位移 >10px 即取消长按（设置页可滚动） */
    private static final long HM_LP_MS = 350L;
    private static final int HM_MOVE_SLOP = 10;
    private final Handler hmH = new Handler(Looper.getMainLooper());
    private final Runnable hmLpRun = () -> hmFire();
    private View hmLpEl;
    private View hmDrag;
    private View hmGhost;
    private float hmDownX;
    private int hmFrom = -1;
    private int hmDrop = -1;
    private float hmOvX, hmOvY;
    private long hmSwallowUntil = 0L;

    // ---- 音乐源 / 导航源 ----
    private LinearLayout musicOpts;
    private LinearLayout musicCur;
    private LinearLayout navCur;

    // ---- 手势 ----
    private LinearLayout gesBox;

    // ---- 壁纸 ----
    private TextView wallStaticBtn, wallDynamicBtn, wallDimBtn;
    private LinearLayout wallGrid;
    private List<Prefs.WallItem> wallItems;
    private String wallCurId = "";
    private int lastWallW = -1;
    /** 缩略图缓存：同一张几十 MB 的原图只采样解码一次，否则主线程必卡 */
    private final Map<String, Bitmap> thumbs = new HashMap<>();

    // ---- 自动播放 ----
    private TextView autoPlayState, autoPlayBtn;

    // ---- 系统权限 ----
    private TextView sysAccVal, ovAccVal, homeStateVal;

    // ---- 信号采集 ----
    private TextView sigState, sigBtn, sigOut;

    // ---- 在线更新 ----
    private TextView otaCurVal, otaStatus, otaNewV, otaCLPre, chHead, otaProgTxt;
    private LinearLayout otaNew, otaProg;
    private TextView otaInstall, otaCancel;
    private View otaBarFill, otaBarRest;
    /** 忙/锁状态机：busy=下载中（可取消），locked=已拿到包（禁点），idle=可重试 */
    private boolean otaBusy = false;
    private String otaState = "idle";
    private String otaUrl = "", otaSha = "";
    private boolean otaClOpen = false;

    public SetPane(Shell sh) {
        this.sh = sh;
        this.c = sh.ctx();
        this.prefs = sh.prefs();
    }

    // ================================================================== 构建

    public View build() {
        if (root != null) return root;

        LinearLayout panel = U.card(c, 14, 14);
        panel.setLayoutParams(new ViewGroup.LayoutParams(U.MP, U.MP));

        TextView head = U.bold(U.text(c, "设置 · 音乐源 / 导航源 / 壁纸", 13, U.TXT));
        panel.addView(head, U.lp(U.WC, U.WC));

        ScrollView sc = new ScrollView(c);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        sc.setPadding(0, 0, U.px(4), 0);

        LinearLayout box = U.col(c);
        sc.addView(box, new FrameLayout.LayoutParams(U.MP, U.WC));
        panel.addView(sc, U.lp(U.MP, 0, 1f));

        box.addView(secHomeMode());
        box.addView(secMusic());
        box.addView(secNav());
        box.addView(secGestures());
        box.addView(secWall());
        box.addView(secAutoPlay());
        box.addView(secPerms());
        box.addView(secSignal());
        box.addView(secOta());

        // .set-panel gap:12（标题与滚动区）、.set-scroll gap:18（分区之间）
        U.gapV(panel, 12);
        U.gapV(box, 18);

        root = panel;
        refresh();
        return root;
    }

    /** 重算全部动态文案 / 勾选态 / 列表 */
    public void refresh() {
        refreshHomeMode();
        refreshMusicSrc();
        refreshNavSrc();
        refreshGestures();
        refreshWall();
        refreshAutoPlay();
        refreshPerms();
        refreshSignal();
        refreshOta();
    }

    // ================================================================== 分区 1 主页模式

    private LinearLayout secHomeMode() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "主页模式"));
        hmRow = U.row(c);
        s.addView(hmRow);
        s.addView(U.hint(c, "勾选主页要显示的模块（默认状态 + 音乐）；"
                + "按住任何位置左右拖动即可调顺序。全部取消时自动恢复默认。"));
        U.gapV(s, 8);
        return s;
    }

    private void refreshHomeMode() {
        if (hmRow == null) return;
        hmClearVisuals();
        hmRow.removeAllViews();
        LinkedHashMap<String, Boolean> st = prefs.homeState();
        for (Map.Entry<String, Boolean> e : st.entrySet()) {
            hmRow.addView(hmItem(e.getKey(), e.getValue()), U.lp(0, U.WC, 1f));
        }
        U.gapH(hmRow, 10);
    }

    private LinearLayout hmItem(final String k, boolean on) {
        LinearLayout it = U.row(c);
        U.pad(it, 12, 11, 12, 11);
        int fg = on ? U.ON_ACC_TXT : U.TXT;
        int fg2 = on ? U.ON_ACC_TXT : U.SUB;
        TextView ck = U.text(c, on ? "☑" : "☐", 14, fg2);
        TextView nm = U.text(c, Def.paneName(k), 14, fg);
        TextView gr = U.text(c, "⠿", 14, fg2);
        it.addView(ck, U.lp(U.WC, U.WC));
        it.addView(nm, U.lp(0, U.WC, 1f));
        it.addView(gr, U.lp(U.WC, U.WC));
        U.gapH(it, 8);
        if (on) {
            it.setBackground(U.accGradient(12));
            U.bold(nm);
        } else {
            it.setBackground(U.bg(U.PANEL2, 12, U.LINE, 1));
        }
        it.setClickable(true);
        U.ripple(it, 0x22FFFFFF, 12);
        it.setOnClickListener(v -> {
            // 拖动刚结束时紧随的那次 click 必须吃掉，否则松手会顺手把模块勾选状态翻过来
            if (System.currentTimeMillis() < hmSwallowUntil) return;
            toggleHome(k);
        });
        attachHmDrag(it);
        return it;
    }

    private void toggleHome(String k) {
        LinkedHashSet<String> on = new LinkedHashSet<>(prefs.homeOn);
        if (on.contains(k)) on.remove(k);
        else on.add(k);
        prefs.setHomeOn(on);          // 全取消时 Prefs 内部会兜回默认
        sh.refreshHome();
        refreshHomeMode();
    }

    // ---------------------------------------------------------------- 主页模式：拖动排序

    /**
     * 挂上「按住 350ms → 进拖动排序」。
     *
     * ★ 与 dock 那条的唯一差别：设置页整页可滚动 ⇒ 未进拖动之前横向位移超过
     *   {@link #HM_MOVE_SLOP} 就取消长按，否则用户想横向滚一下就会误进拖动。
     * ★ 拖动中**只改「其它芯片」的 translationX 把空档让出来**，被拖的那枚原地 alpha=0。
     *   绝不 removeView 重排 —— 那会把被拖的 View 从 mFirstTouchTarget 触摸链里摘掉，
     *   之后的 MOVE / UP 一个都收不到（dock 那次踩过的同一个坑）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private void attachHmDrag(View it) {
        it.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    hmLpEl = v;
                    hmDownX = e.getRawX();
                    hmH.removeCallbacks(hmLpRun);
                    hmH.postDelayed(hmLpRun, HM_LP_MS);
                    return false;                       // 不吃 DOWN，单击照旧能触发
                case MotionEvent.ACTION_MOVE:
                    if (hmDrag == null) {
                        if (hmLpEl == v && Math.abs(e.getRawX() - hmDownX) > U.px(HM_MOVE_SLOP)) {
                        hmH.removeCallbacks(hmLpRun);   // 先动了 ⇒ 不是长按，交给 ScrollView
                            hmLpEl = null;
                        }
                        return false;
                    }
                    hmMove(e.getRawX(), e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    hmH.removeCallbacks(hmLpRun);
                    hmLpEl = null;
                    if (hmDrag != null) {
                        hmEnd();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void hmFire() {
        if (hmLpEl == null || hmDrag != null || hmRow == null) return;
        final View el = hmLpEl;
        hmLpEl = null;
        int from = hmRow.indexOfChild(el);
        if (from < 0) return;
        hmDrag = el;
        hmFrom = from;
        hmDrop = from;
        hmSwallowUntil = System.currentTimeMillis() + 1500;

        // ★ requestDisallowInterceptTouchEvent 声明在 ViewParent 上，不在 View 上
        android.view.ViewParent par = el.getParent();
        if (par != null) par.requestDisallowInterceptTouchEvent(true);   // 别让 ScrollView 抢走
        el.setAlpha(0f);                                  // 原位留成空档（仍然占着布局位）

        hmGhost = hmBuildGhost(el);
        sh.overlay().addView(hmGhost, new FrameLayout.LayoutParams(el.getWidth(), el.getHeight()));
        int[] loc = new int[2];
        sh.overlay().getLocationOnScreen(loc);
        hmOvX = loc[0];
        hmOvY = loc[1];

        int[] e2 = new int[2];
        el.getLocationOnScreen(e2);
        hmMove(e2[0] + el.getWidth() / 2f, e2[1] + el.getHeight() / 2f);
    }

    /** ghost = 照抄该芯片的样子（图标/文案/颜色都取真身，视觉上像把它拎起来了） */
    private View hmBuildGhost(View el) {
        LinearLayout g = U.row(c);
        U.pad(g, 12, 11, 12, 11);
        android.graphics.drawable.Drawable bg = el.getBackground();
        g.setBackground(bg == null ? U.bg(U.PANEL2, 12, U.LINE, 1)
                : bg.getConstantState() == null ? bg : bg.getConstantState().newDrawable().mutate());
        g.setAlpha(0.95f);
        // ★ 故意不 setElevation：拖动态下 elevation 会让该 View 走离屏合成，
        //   实测在本模拟器上整屏被冲淡（swiftShader 合成 artifact），真机也不值得为阴影冒险。
        if (el instanceof ViewGroup) {
            ViewGroup src = (ViewGroup) el;
            for (int i = 0; i < src.getChildCount(); i++) {
                View ch = src.getChildAt(i);
                TextView tv = U.text(c, ch instanceof TextView ? ((TextView) ch).getText() : "",
                        14, ch instanceof TextView ? ((TextView) ch).getCurrentTextColor() : U.TXT);
                if (ch instanceof TextView && ((TextView) ch).getTypeface() != null
                        && ((TextView) ch).getTypeface().isBold()) {
                    U.bold(tv);
                }
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                float wt = lp instanceof LinearLayout.LayoutParams
                        ? ((LinearLayout.LayoutParams) lp).weight : 0f;
                // ★ 这里必须直接 new：lp.width/height 已经是**像素**，再走 U.lp() 会被
                //   iz() 当成设计单位又乘一次 u（U.lp 的入参语义是「N * var(--u)」）。
                g.addView(tv, lp == null ? U.lp(U.WC, U.WC)
                        : new LinearLayout.LayoutParams(lp.width, lp.height, wt));
            }
        }
        U.gapH(g, 8);
        return g;
    }

    private void hmMove(float x, float y) {
        if (hmDrag == null || hmRow == null) return;
        if (hmGhost != null) {
            hmGhost.setX(x - hmOvX - hmGhost.getWidth() / 2f);
            hmGhost.setY(y - hmOvY - hmGhost.getHeight() / 2f);
        }
        // 落点 = 「剔除被拖项之后」有多少项的中线在手指左边
        int to = 0;
        for (int i = 0; i < hmRow.getChildCount(); i++) {
            if (i == hmFrom) continue;
            View v = hmRow.getChildAt(i);
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (x > loc[0] + v.getWidth() / 2f) to++;
        }
        if (to != hmDrop) {
            hmDrop = to;
            hmGaps();
        }
    }

    /** 用 translationX 把空档让出来（不动布局、不重排子视图 ⇒ 触摸链完好） */
    private void hmGaps() {
        if (hmRow == null || hmFrom < 0 || hmDrop < 0) return;
        int n = hmRow.getChildCount();
        if (n < 2) return;
        float step = hmRow.getChildAt(0).getWidth() + U.px(10);   // 芯片宽 + gapH(10)
        for (int i = 0; i < n; i++) {
            View v = hmRow.getChildAt(i);
            if (i == hmFrom) {
                v.setAlpha(0f);
                continue;
            }
            int j = i > hmFrom ? i - 1 : i;                       // 剔除被拖项后的下标
            float tx = 0f;
            if (hmFrom < hmDrop) {
                if (j >= hmFrom && j < hmDrop) tx = -step;         // 往后拖：这一段整体左移
            } else if (hmFrom > hmDrop) {
                if (j >= hmDrop && j < hmFrom) tx = step;          // 往前拖：这一段整体右移
            }
            v.setTranslationX(tx);
        }
    }

    private void hmEnd() {
        View drag = hmDrag;
        final int from = hmFrom;
        final int to = hmDrop;
        if (drag != null && drag.getParent() != null) {
            drag.getParent().requestDisallowInterceptTouchEvent(false);
        }
        hmClearVisuals();
        hmSwallowUntil = System.currentTimeMillis() + 400;
        if (from < 0 || to < 0 || to == from) return;
        ArrayList<String> order = new ArrayList<>(prefs.homeOrder);
        if (from >= order.size()) return;
        String k = order.remove(from);
        order.add(Math.min(to, order.size()), k);
        prefs.setHomeOrder(order);
        sh.refreshHome();
        refreshHomeMode();
    }

    private void hmClearVisuals() {
        hmDrag = null;
        hmFrom = -1;
        hmDrop = -1;
        if (hmGhost != null) {
            try {
                sh.overlay().removeView(hmGhost);
            } catch (Throwable ignored) {
            }
            hmGhost = null;
        }
        if (hmRow != null) {
            for (int i = 0; i < hmRow.getChildCount(); i++) {
                View v = hmRow.getChildAt(i);
                v.setTranslationX(0f);
                v.setAlpha(1f);
            }
        }
    }

    // ================================================================== 分区 3 音乐源

    private LinearLayout secMusic() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "音乐源"));

        LinearLayout srcRow = U.row(c);
        musicOpts = U.row(c);
        srcRow.addView(musicOpts, U.lp(U.WC, U.WC));
        musicCur = U.curPill(c);
        srcRow.addView(musicCur, U.lp(U.WC, U.WC));
        srcRow.addView(U.spacer(c));
        TextView pick = U.pickBtn(c, "📂 选择应用");
        pick.setOnClickListener(v -> sh.openDrawer("music", "选择音乐应用", a -> {
            prefs.musicSrc = a.srcKey();
            prefs.save();
            refreshMusicSrc();
        }));
        srcRow.addView(pick, U.lp(U.WC, U.WC));
        U.gapH(srcRow, 8);
        s.addView(srcRow);

        s.addView(U.hint(c, "车机本地源（U盘 / 蓝牙）直接点选；要选具体音乐 App 请点右侧「选择应用」。"));
        U.gapV(s, 8);
        return s;
    }

    private void refreshMusicSrc() {
        if (musicOpts == null) return;
        musicOpts.removeAllViews();
        for (final String[] m : Def.LOCAL_MUSIC) {
            TextView b = U.smallBtn(c, m[2] + " " + m[1]);
            U.setOn(b, m[0].equals(prefs.musicSrc));
            b.setOnClickListener(v -> {
                prefs.musicSrc = m[0];
                prefs.save();
                refreshMusicSrc();
            });
            musicOpts.addView(b, U.lp(U.WC, U.WC));
        }
        U.gapH(musicOpts, 8);

        musicCur.removeAllViews();
        SrcInfo si = resolveSrc(prefs.musicSrc, "music");
        musicCur.addView(U.text(c, si.icon, 16, U.TXT), U.lp(U.WC, U.WC));
        musicCur.addView(U.bold(U.text(c, si.name, 12, U.TXT)), U.lp(U.WC, U.WC));
        U.gapH(musicCur, 6);
    }

    // ================================================================== 分区 4 导航源

    private LinearLayout secNav() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "导航源"));

        LinearLayout srcRow = U.row(c);
        navCur = U.curPill(c);
        srcRow.addView(navCur, U.lp(U.WC, U.WC));
        srcRow.addView(U.spacer(c));
        TextView pick = U.pickBtn(c, "📂 选择应用");
        pick.setOnClickListener(v -> sh.openDrawer("nav", "选择导航应用", a -> {
            prefs.navApp = a.srcKey();
            prefs.save();
            refreshNavSrc();
            refreshGestures();   // 「跟随导航源」的括号里要跟着变
        }));
        srcRow.addView(pick, U.lp(U.WC, U.WC));
        U.gapH(srcRow, 8);
        s.addView(srcRow);

        s.addView(U.hint(c, "点「选择应用」从已安装导航 App 中选取；dock 导航按钮或主页下滑直接拉起该 App。"));
        U.gapV(s, 8);
        return s;
    }

    private void refreshNavSrc() {
        if (navCur == null) return;
        navCur.removeAllViews();
        if (prefs.navApp == null || prefs.navApp.isEmpty()) {
            navCur.addView(U.text(c, "未选择导航应用", 12, U.SUB), U.lp(U.WC, U.WC));
            return;
        }
        SrcInfo si = resolveSrc(prefs.navApp, "nav");
        navCur.addView(U.text(c, si.icon, 16, U.TXT), U.lp(U.WC, U.WC));
        navCur.addView(U.bold(U.text(c, si.name, 12, U.TXT)), U.lp(U.WC, U.WC));
        U.gapH(navCur, 6);
    }

    // ================================================================== 分区 5 手势

    private static final String[] GES_KEYS = {"up", "down", "left", "right"};

    private LinearLayout secGestures() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "手势"));
        gesBox = U.col(c);
        s.addView(gesBox);
        s.addView(U.hint(c, "需从屏幕中心区域（约屏幕宽 79% / 高 72% 的那块）起手才生效，任意分辨率下自动适配。"
                + "默认「下滑」跟随导航源，点「选择应用」可换成任意已装应用。"));
        U.gapV(s, 8);
        return s;
    }

    private void refreshGestures() {
        if (gesBox == null) return;
        gesBox.removeAllViews();
        for (final String k : GES_KEYS) {
            LinearLayout row = U.row(c);
            row.addView(U.text(c, dirName(k), 12, U.SUB), U.lp(U.WC, U.WC));
            LinearLayout cur = U.curPill(c);
            SrcInfo gi = gestureInfo(k);
            cur.addView(U.text(c, gi.icon, 16, U.TXT), U.lp(U.WC, U.WC));
            cur.addView(U.bold(U.text(c, gi.name, 12, U.TXT)), U.lp(U.WC, U.WC));
            U.gapH(cur, 6);
            row.addView(cur, U.lp(0, U.WC, 1f));

            TextView bp = gesBtn("📂 选择应用");
            bp.setOnClickListener(v -> sh.openDrawer(null, "选择应用", a -> setGesture(k, a.srcKey())));
            TextView bd = gesBtn("默认");
            bd.setOnClickListener(v -> setGesture(k, gestureDefault(k)));
            row.addView(bp, U.lp(U.WC, U.WC));
            row.addView(bd, U.lp(U.WC, U.WC));
            U.gapH(row, 8);
            gesBox.addView(row);
        }
        U.gapV(gesBox, 6);
    }

    /** .ges-row .set-opt：12px、内边距 6/10，比常规 set-opt 小一号 */
    private TextView gesBtn(String s) {
        TextView t = U.text(c, s, 12, U.TXT);
        t.setBackground(U.bg(U.PANEL2, 12, U.LINE, 1));
        U.pad(t, 10, 6, 10, 6);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        U.ripple(t, 0x33FFFFFF, 12);
        return t;
    }

    private void setGesture(String k, String v) {
        String val = v == null ? "" : v;
        if ("up".equals(k)) prefs.gUp = val;
        else if ("down".equals(k)) prefs.gDown = val;
        else if ("left".equals(k)) prefs.gLeft = val;
        else prefs.gRight = val;
        prefs.save();
        refreshGestures();
        sh.toast(dirName(k) + "已设为" + gestureInfo(k).name);
    }

    private static String gestureDefault(String k) {
        if ("up".equals(k)) return "home";
        if ("down".equals(k)) return "nav";
        return "";
    }

    private static String dirName(String k) {
        if ("up".equals(k)) return "上滑";
        if ("down".equals(k)) return "下滑";
        if ("left".equals(k)) return "左滑";
        return "右滑";
    }

    private String bindVal(String k) {
        if ("up".equals(k)) return prefs.gUp;
        if ("down".equals(k)) return prefs.gDown;
        if ("left".equals(k)) return prefs.gLeft;
        return prefs.gRight;
    }

    private SrcInfo gestureInfo(String k) {
        SrcInfo r = new SrcInfo();
        String v = bindVal(k);
        if (v == null || v.isEmpty()) {
            r.name = "未绑定";
            r.icon = "🚫";
            return r;
        }
        if ("home".equals(v)) {
            r.name = "返回原车机桌面";
            r.icon = "🏠";
            return r;
        }
        if ("nav".equals(v)) {
            r.icon = "🧭";
            r.name = "跟随导航源";
            if (prefs.navApp != null && !prefs.navApp.isEmpty()) {
                r.name += "（" + resolveSrc(prefs.navApp, "nav").name + "）";
            }
            return r;
        }
        return resolveSrc(v, "other");
    }

    // ================================================================== 分区 6 壁纸

    private LinearLayout secWall() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "壁纸"));

        LinearLayout bar = U.row(c);
        LinearLayout opts = U.row(c);
        wallStaticBtn = U.btn(c, "🖼️ 静态壁纸");
        wallDynamicBtn = U.btn(c, "🌈 动态壁纸");
        wallStaticBtn.setOnClickListener(v -> switchWall(Prefs.KIND_STATIC));
        wallDynamicBtn.setOnClickListener(v -> switchWall(Prefs.KIND_DYNAMIC));
        opts.addView(wallStaticBtn, U.lp(U.WC, U.WC));
        opts.addView(wallDynamicBtn, U.lp(U.WC, U.WC));
        U.gapH(opts, 10);
        bar.addView(opts, U.lp(0, U.WC, 1f));

        TextView up = upBtn("⬆️ 上传壁纸");
        up.setOnClickListener(v -> sh.host().pickWallpaperFile(prefs.wall));
        bar.addView(up, U.lp(U.WC, U.WC));
        U.gapH(bar, 10);
        s.addView(bar);

        wallGrid = U.col(c);
        // 宽度要等第一次布局才知道，先用回调反算列数；宽度不变就跳过，避免布局抖动
        wallGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (v.getWidth() != lastWallW && v.getWidth() > 0) layoutWallItems();
        });
        s.addView(wallGrid);

        s.addView(U.hint(c, "上传后自动归档：图片 → Download/L6/壁纸/静态　视频 → Download/L6/壁纸/动态"));

        LinearLayout dimRow = otaRow(U.text(c, "壁纸压暗层（关闭后壁纸原图显示）", 12, U.SUB), null);
        wallDimBtn = U.btn(c, "● 开启");
        wallDimBtn.setOnClickListener(v -> {
            prefs.wallDim = !prefs.wallDim;
            prefs.save();
            refreshWall();
            sh.applyWall();
        });
        dimRow.addView(wallDimBtn, U.lp(U.WC, U.WC));
        s.addView(dimRow);

        s.addView(U.hint(c, "「⬆️ 上传壁纸」自动识别文件类型：图片归入静态壁纸、视频归入动态壁纸，上传后自动切到该类型并选中。"
                + "壁纸仅在主页生效，设置页保持深色底。"));
        U.gapV(s, 8);
        return s;
    }

    /** .up-btn：虚线边框上传按钮（GradientDrawable 画不出虚线，用实线近似） */
    private TextView upBtn(String s) {
        TextView t = U.text(c, s, 13, U.TXT);
        t.setBackground(U.bg(U.PANEL2, 12, U.LINE, 1));
        U.pad(t, 14, 10, 14, 10);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        U.ripple(t, 0x33FFFFFF, 12);
        return t;
    }

    private void switchWall(String kind) {
        prefs.wall = kind;
        prefs.save();
        refreshWall();
    }

    private void refreshWall() {
        if (wallStaticBtn == null) return;
        U.setOn(wallStaticBtn, Prefs.KIND_STATIC.equals(prefs.wall));
        U.setOn(wallDynamicBtn, Prefs.KIND_DYNAMIC.equals(prefs.wall));
        wallDimBtn.setText(prefs.wallDim ? "● 开启" : "○ 关闭");
        wallItems = prefs.walls(prefs.wall);
        wallCurId = prefs.pickId(prefs.wall);
        layoutWallItems();
    }

    private void layoutWallItems() {
        if (wallGrid == null) return;
        wallGrid.removeAllViews();
        if (wallItems == null || wallItems.isEmpty()) {
            lastWallW = wallGrid.getWidth();
            return;
        }
        int gap = U.px(10);
        int itemW = U.px(112);
        int avail = wallGrid.getWidth();
        if (avail <= 0) avail = U.px(1800);   // 尚未布局时的兜底，真宽到了会再排一次
        int cols = Math.max(1, (avail + gap) / (itemW + gap));

        LinearLayout row = null;
        for (int i = 0; i < wallItems.size(); i++) {
            if (i % cols == 0) {
                row = U.row(c);
                wallGrid.addView(row);
            }
            Prefs.WallItem it = wallItems.get(i);
            row.addView(wallItemView(it, it.id.equals(wallCurId)), U.lp(112, U.WC));
        }
        // 行内用固定 10u 外边距（首列不加），LinearLayout 没有 flex gap，只能逐项补
        for (int ri = 0; ri < wallGrid.getChildCount(); ri++) {
            LinearLayout r = (LinearLayout) wallGrid.getChildAt(ri);
            for (int ci = 0; ci < r.getChildCount(); ci++) {
                View ch = r.getChildAt(ci);
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                if (lp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).rightMargin = ci == 0 ? 0 : gap;
                }
            }
        }
        U.gapV(wallGrid, 10);
        lastWallW = wallGrid.getWidth();
    }

    private View wallItemView(final Prefs.WallItem it, boolean on) {
        LinearLayout item = U.col(c);
        item.setBackground(U.bg(U.PANEL2, 10, on ? U.ACC : U.LINE, 1));

        Bitmap bm = wallThumb(it);
        if (bm != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
            item.addView(iv, new LinearLayout.LayoutParams(U.MP, U.px(62)));
        } else {
            TextView ph = U.text(c, wallPlaceholder(it), 22, U.SUB);
            ph.setGravity(Gravity.CENTER);
            ph.setBackgroundColor(0xFF0C121B);
            item.addView(ph, new LinearLayout.LayoutParams(U.MP, U.px(62)));
        }

        String nm = (it.name == null || it.name.isEmpty()) ? "壁纸" : it.name;
        TextView nt = U.text(c, nm, 10, on ? U.ACC : U.SUB);
        nt.setSingleLine(true);
        nt.setEllipsize(TextUtils.TruncateAt.END);
        U.pad(nt, 6, 5, 6, 5);
        item.addView(nt, U.lp(U.MP, U.WC));

        item.setClickable(true);
        U.ripple(item, 0x22FFFFFF, 10);
        item.setOnClickListener(v -> {
            prefs.wall = Prefs.KIND_DYNAMIC.equals(prefs.wall) ? Prefs.KIND_DYNAMIC : Prefs.KIND_STATIC;
            prefs.selectWall(prefs.wall, it.id);
            sh.applyWall();
            refreshWall();
        });
        return item;
    }

    private String wallPlaceholder(Prefs.WallItem it) {
        if (Prefs.isVideo(it)) return "🎞️";
        return Prefs.KIND_DYNAMIC.equals(prefs.wall) ? "🌈" : "🖼️";
    }

    /** 采样解码到缩略图尺寸；解码失败/视频返回 null（调用方回落到 emoji 占位） */
    private Bitmap wallThumb(Prefs.WallItem it) {
        if (it == null || Prefs.isVideo(it)) return null;
        String p = it.path;
        if (p == null || p.isEmpty()) return null;
        if (thumbs.containsKey(p)) return thumbs.get(p);
        Bitmap b = null;
        try {
            int tw = U.px(112), th = U.px(62);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = openWall(p);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, tw, th);
            o.inJustDecodeBounds = false;
            in = openWall(p);
            b = BitmapFactory.decodeStream(in, null, o);
            in.close();
        } catch (Throwable t) {
            b = null;
        }
        thumbs.put(p, b);
        return b;
    }

    private InputStream openWall(String p) throws Exception {
        if (p.startsWith(Prefs.ASSET_PREFIX)) {
            return c.getAssets().open(p.substring(Prefs.ASSET_PREFIX.length()));
        }
        return new FileInputStream(p);
    }

    /** 求最大的 2 的幂采样率，使解码后仍不小于目标尺寸 */
    private static int sampleSize(int w, int h, int tw, int th) {
        int s = 1;
        if (w <= 0 || h <= 0) return s;
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2;
        return s;
    }

    // ================================================================== 分区 7 自动播放

    private LinearLayout secAutoPlay() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "启动后自动播放音乐"));
        LinearLayout card = U.otaCard(c);
        autoPlayState = U.bold(U.text(c, "关闭", 12, U.TXT));
        autoPlayBtn = U.btn(c, "○ 关闭");
        autoPlayBtn.setOnClickListener(v -> {
            prefs.autoPlay = !prefs.autoPlay;
            prefs.save();
            refreshAutoPlay();
        });
        card.addView(otaRow(curSpan("应用启动后自动开始播放：", autoPlayState), autoPlayBtn));
        card.addView(U.text(c, "开启后，每次打开本应用会自动后台唤醒当前音乐源播放，不拉起音乐 App 界面，也不会出现悬浮返回按钮。",
                12, U.SUB));
        U.gapV(card, 8);
        s.addView(card);
        U.gapV(s, 8);
        return s;
    }

    private void refreshAutoPlay() {
        if (autoPlayState == null) return;
        boolean on = prefs.autoPlay;
        autoPlayState.setText(on ? "开启" : "关闭");
        autoPlayBtn.setText(on ? "● 开启" : "○ 关闭");
        U.setOn(autoPlayBtn, on);
    }

    // ================================================================== 分区 8 系统权限

    private LinearLayout secPerms() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "系统权限"));
        LinearLayout card = U.otaCard(c);

        sysAccVal = U.bold(U.text(c, "读取中…", 12, U.TXT));
        TextView sysBtn = U.btn(c, "🔓 去授权");
        sysBtn.setOnClickListener(v -> sh.host().openNotifyAccess());
        card.addView(otaRow(curSpan("通知使用权：", sysAccVal), sysBtn));
        card.addView(U.text(c, "授权后自动读取系统媒体会话与导航通知", 12, U.SUB));

        ovAccVal = U.bold(U.text(c, "读取中…", 12, U.TXT));
        TextView ovBtn = U.btn(c, "🔓 去授权");
        ovBtn.setOnClickListener(v -> sh.host().openOverlay());
        card.addView(otaRow(curSpan("悬浮窗权限：", ovAccVal), ovBtn));
        card.addView(U.text(c, "可选的备用通道：导航 App 全屏后点左下角悬浮「返回」按钮回主页。已把本应用设为默认桌面时可不授权。",
                12, U.SUB));

        homeStateVal = U.bold(U.text(c, "未设置", 12, U.TXT));
        TextView homeBtn = U.btn(c, "🖥️ 设为默认桌面");
        homeBtn.setOnClickListener(v -> sh.host().openHomeSettings());
        card.addView(otaRow(curSpan("默认桌面：", homeStateVal), homeBtn));
        card.addView(U.text(c, "把「老六中控」设为车机默认桌面后，按 HOME 键即回本界面。", 12, U.SUB));

        LinearLayout acts = U.row(c);
        TextView rf = gesBtn("🔄 刷新");
        rf.setOnClickListener(v -> refreshPerms());
        acts.addView(rf, U.lp(U.WC, U.WC));
        U.gapH(acts, 8);
        card.addView(acts);

        U.gapV(card, 8);
        s.addView(card);
        U.gapV(s, 8);
        return s;
    }

    private void refreshPerms() {
        if (sysAccVal == null) return;
        setAcc(sysAccVal, sh.host().hasNotifyAccess());
        setAcc(ovAccVal, sh.host().hasOverlay());
        boolean dh = sh.host().isDefaultHome();
        homeStateVal.setText(dh ? "✅ 已设置" : "❌ 未设置");
        homeStateVal.setTextColor(dh ? U.ACC2 : U.RED);
    }

    private void setAcc(TextView v, boolean ok) {
        v.setText(ok ? "✅ 已授权" : "❌ 未授权");
        v.setTextColor(ok ? U.ACC2 : U.RED);
    }

    // ================================================================== 分区 9 信号采集

    private LinearLayout secSignal() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "车机信号采集"));
        LinearLayout card = U.otaCard(c);

        sigState = U.bold(U.text(c, "未采集", 12, U.TXT));
        sigBtn = U.btn(c, "▶ 开始采集");
        sigBtn.setOnClickListener(v -> {
            if (sh.host().isSignalCapturing()) sh.host().stopSignal();
            else sh.host().startSignal();
            refreshSignal();
        });
        card.addView(otaRow(curSpan("采集状态：", sigState), sigBtn));

        sigOut = U.text(c, "", 11, U.TXT);
        sigOut.setBackground(U.bg(U.PANEL, 8, U.LINE, 1));
        U.pad(sigOut, 8);
        sigOut.setMaxHeight(U.px(200));
        sigOut.setVerticalScrollBarEnabled(true);
        sigOut.setMovementMethod(new ScrollingMovementMethod());
        U.visible(sigOut, false);
        card.addView(sigOut);

        card.addView(U.text(c, "点「开始采集」后保持在页面，自行操作车辆功能（车门 / 车窗 / 车辆按钮等），完成后点「结束采集」；"
                + "结论写入本地日志（标签 L6Signal）并另存 Download/L6/signal-*.json。", 12, U.SUB));
        U.gapV(card, 8);
        s.addView(card);
        U.gapV(s, 8);
        return s;
    }

    private void refreshSignal() {
        if (sigState == null) return;
        boolean cap = sh.host().isSignalCapturing();
        sigState.setText(cap ? "采集中…" : "未采集");
        sigBtn.setText(cap ? "■ 结束采集" : "▶ 开始采集");
        if (cap) {
            sigOut.setText("采集中… 请保持在页面并操作车辆功能（车门 / 车窗 / 车辆按钮等），完成后点「结束采集」。\n"
                    + "结论写入本地日志（标签 L6Signal），并另存 Download/L6/signal-*.json。");
        }
        U.visible(sigOut, cap);
    }

    // ================================================================== 分区 10 在线更新

    private LinearLayout secOta() {
        LinearLayout s = U.col(c);
        s.addView(U.label(c, "在线更新"));
        LinearLayout card = U.otaCard(c);

        otaCurVal = U.bold(U.text(c, "--", 12, U.TXT));
        TextView check = U.btn(c, "🔄 检查更新");
        check.setOnClickListener(v -> {
            otaStatus.setText("检查中…");
            otaStatus.setTextColor(U.SUB);
            sh.host().checkOta();
        });
        card.addView(otaRow(curSpan("当前版本：", otaCurVal), check));

        otaStatus = U.text(c, "点击「检查更新」获取最新版本", 12, U.SUB);
        otaStatus.setMinHeight(U.px(18));
        card.addView(otaStatus);

        // 发现新版本才展开的区块
        otaNew = U.col(c);
        LinearLayout nv = U.row(c);
        nv.addView(U.text(c, "最新版本 ", 14, U.ACC2), U.lp(U.WC, U.WC));
        otaNewV = U.bold(U.text(c, "--", 14, U.ACC2));
        nv.addView(otaNewV, U.lp(U.WC, U.WC));
        otaNew.addView(nv);

        chHead = U.text(c, "▸ 更新内容", 12, U.SUB);
        chHead.setClickable(true);
        chHead.setPadding(0, U.px(4), 0, U.px(4));
        chHead.setOnClickListener(v -> {
            otaClOpen = !otaClOpen;
            chHead.setText((otaClOpen ? "▾ " : "▸ ") + "更新内容");
            U.visible(otaCLPre, otaClOpen);
        });
        otaNew.addView(chHead);
        otaCLPre = U.text(c, "（无更新说明）", 12, U.TXT);
        otaCLPre.setBackground(U.bg(U.PANEL, 6));
        U.pad(otaCLPre, 8);
        otaCLPre.setMaxHeight(U.px(160));
        otaCLPre.setVerticalScrollBarEnabled(true);
        otaCLPre.setMovementMethod(new ScrollingMovementMethod());
        U.visible(otaCLPre, false);
        otaNew.addView(otaCLPre);

        LinearLayout acts = U.row(c);
        otaInstall = primaryBtn("⬇ 立即更新");
        otaInstall.setOnClickListener(v -> startOta());
        otaCancel = U.ghostBtn(c, "✕ 取消下载");
        otaCancel.setOnClickListener(v -> sh.host().cancelOta());
        U.visible(otaCancel, false);
        acts.addView(otaInstall, U.lp(U.WC, U.WC));
        acts.addView(otaCancel, U.lp(U.WC, U.WC));
        U.gapH(acts, 8);
        otaNew.addView(acts);

        otaProg = U.row(c);
        LinearLayout bar = U.row(c);
        bar.setBackground(U.bg(0x1AFFFFFF, 3));
        otaBarFill = new View(c);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{U.ACC, U.PUR});
        gd.setCornerRadius(U.px(3));
        otaBarFill.setBackground(gd);
        otaBarRest = new View(c);
        bar.addView(otaBarFill, U.lp(0, U.MP, 0f));
        bar.addView(otaBarRest, U.lp(0, U.MP, 100f));
        otaProg.addView(bar, U.lp(0, U.px(6), 1f));
        otaProgTxt = U.text(c, "0%", 12, U.TXT);
        otaProg.addView(otaProgTxt, U.lp(U.WC, U.WC));
        U.gapH(otaProg, 8);
        U.visible(otaProg, false);
        otaNew.addView(otaProg);

        U.gapV(otaNew, 6);
        U.visible(otaNew, false);
        card.addView(otaNew);

        U.gapV(card, 8);
        s.addView(card);
        s.addView(U.hint(c, "签名密钥不同将无法直接覆盖安装，需先卸载旧版。"));
        U.gapV(s, 8);
        return s;
    }

    /** .set-opt.primary：平铺强调色底 + 深色粗体字 */
    private TextView primaryBtn(String s) {
        TextView t = U.text(c, s, 14, 0xFF00081A);
        t.setBackground(U.bg(U.ACC, 12));
        U.pad(t, 12, 6, 12, 6);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        U.bold(t);
        U.ripple(t, 0x33FFFFFF, 12);
        return t;
    }

    /** 点「立即更新」的瞬间就锁按钮：原生回调是异步的，晚一步就挡不住连点下多个包 */
    private void startOta() {
        if (otaBusy || "locked".equals(otaState)) return;
        if (otaUrl == null || otaUrl.isEmpty()) {
            otaStatus.setText("无下载链接");
            otaStatus.setTextColor(U.RED);
            return;
        }
        setOtaState("busy");
        otaStatus.setText("下载中…");
        otaStatus.setTextColor(U.SUB);
        try {
            sh.host().installOta(otaUrl, otaSha == null ? "" : otaSha);
        } catch (Throwable t) {
            setOtaState("idle");
            otaStatus.setText("原生桥未就绪：" + t.getMessage());
            otaStatus.setTextColor(U.RED);
        }
    }

    private void setOtaState(String st) {
        otaState = st;
        otaBusy = "busy".equals(st);
        boolean idle = "idle".equals(st);
        otaInstall.setEnabled(idle);
        otaInstall.setAlpha(idle ? 1f : 0.45f);
        U.visible(otaCancel, "busy".equals(st));
    }

    private void setOtaProgress(int p) {
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) otaBarFill.getLayoutParams();
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) otaBarRest.getLayoutParams();
        fp.weight = p;
        rp.weight = 100 - p;
        otaBarFill.setLayoutParams(fp);
        otaBarRest.setLayoutParams(rp);
        otaProgTxt.setText(p + "%");
    }

    private void refreshOta() {
        if (otaCurVal == null) return;
        String ver = "--";
        try {
            JSONObject cfg = sh.host().otaConfig();
            if (cfg != null) ver = cfg.optString("version", "--");
        } catch (Throwable ignored) {
        }
        otaCurVal.setText(ver);
    }

    /** OTA 事件入口（主线程）：kind 见 Host.otaEvent 的约定，字段一律 optXxx 容错 */
    public void onOta(String kind, JSONObject data) {
        if (otaStatus == null) return;
        if (data == null) data = new JSONObject();
        String msg = data.optString("message", "");

        if ("checking".equals(kind)) {
            otaStatus.setText("检查中…");
            otaStatus.setTextColor(U.SUB);
            if (!otaBusy) setOtaState("idle");

        } else if ("available".equals(kind)) {
            String v = data.optString("version", "--");
            otaUrl = data.optString("url", "");
            otaSha = data.optString("sha256", "");
            otaNewV.setText(v);
            otaCLPre.setText(data.optString("changelog", "（无更新说明）"));
            U.visible(otaNew, true);
            U.visible(otaProg, false);
            otaStatus.setText("发现新版本 " + v + "，可点「⬇ 立即更新」");
            otaStatus.setTextColor(U.ACC2);
            setOtaState("idle");

        } else if ("latest".equals(kind)) {
            U.visible(otaNew, false);
            otaStatus.setText("已是最新版本");
            otaStatus.setTextColor(U.ACC2);
            setOtaState("idle");

        } else if ("downloading".equals(kind)) {
            U.visible(otaProg, true);
            if (!otaBusy) setOtaState("busy");
            otaStatus.setText("下载中…");
            otaStatus.setTextColor(U.SUB);

        } else if ("progress".equals(kind)) {
            int p = data.optInt("progress", 0);
            U.visible(otaProg, true);
            if (!otaBusy) setOtaState("busy");
            setOtaProgress(p);
            otaStatus.setText("下载中 " + p + "%");
            otaStatus.setTextColor(U.SUB);

        } else if ("downloaded".equals(kind)) {
            U.visible(otaProg, true);
            setOtaProgress(100);
            otaStatus.setText("下载完成，正在启动安装器…");
            otaStatus.setTextColor(U.ACC2);
            setOtaState("locked");

        } else if ("installing".equals(kind)) {
            otaStatus.setText("已启动系统安装器，请在系统弹窗中确认安装");
            otaStatus.setTextColor(U.ACC2);
            setOtaState("locked");

        } else if ("needPermission".equals(kind)) {
            U.visible(otaProg, false);
            otaStatus.setText(msg.isEmpty() ? "请在设置中允许安装未知应用" : msg);
            otaStatus.setTextColor(U.RED);
            setOtaState("locked");

        } else if ("error".equals(kind)) {
            U.visible(otaProg, false);
            String e = msg.isEmpty() ? data.optString("error", "未知") : msg;
            otaStatus.setText("错误：" + e);
            otaStatus.setTextColor(U.RED);
            setOtaState("idle");

        } else if ("cancelled".equals(kind)) {
            U.visible(otaProg, false);
            otaStatus.setText("已取消下载");
            otaStatus.setTextColor(U.SUB);
            setOtaState("idle");
        }
    }

    // ================================================================== 小工具

    /** .ota-row：左内容 + 弹性空隙 + 右按钮 */
    private LinearLayout otaRow(View left, View right) {
        LinearLayout r = U.otaRow(c);
        r.addView(left, U.lp(U.WC, U.WC));
        r.addView(U.spacer(c));
        if (right != null) r.addView(right, U.lp(U.WC, U.WC));
        U.gapH(r, 8);
        return r;
    }

    /** 「前缀：」+ 加粗值 拼成一个可放进左端的内联组 */
    private LinearLayout curSpan(String prefix, TextView value) {
        LinearLayout r = U.row(c);
        r.addView(U.text(c, prefix, 12, U.SUB), U.lp(U.WC, U.WC));
        r.addView(value, U.lp(U.WC, U.WC));
        U.gapH(r, 6);
        return r;
    }

    /** 源/手势解析结果：显示名 + 占位 emoji */
    private static final class SrcInfo {
        String name = "";
        String icon = "📦";
    }

    /**
     * 把「源取值」（usb/bt/短 key/包名）解析成显示名与 emoji。
     * 认不出时退化成原值，绝不抛异常 —— 设置页宁可显示得丑也不能崩。
     */
    private SrcInfo resolveSrc(String v, String type) {
        SrcInfo r = new SrcInfo();
        r.icon = Def.typeEmoji(type);
        if (v == null || v.isEmpty()) {
            r.name = "未选择";
            return r;
        }
        for (String[] m : Def.LOCAL_MUSIC) {
            if (m[0].equals(v)) {
                r.name = m[1];
                r.icon = m[2];
                return r;
            }
        }
        String pkg = Def.keyToPkg(v);
        String name = null;
        if (pkg != null) name = sh.host().appName(pkg);
        if (name == null || name.isEmpty() || name.equals(pkg)) {
            String[] item = Def.byPkg(pkg == null ? v : pkg);
            if (item != null) name = item[2];
        }
        r.name = (name == null || name.isEmpty()) ? v : name;
        String[] item = Def.byPkg(pkg == null ? v : pkg);
        if (item != null) r.icon = item[3];
        return r;
    }
}
