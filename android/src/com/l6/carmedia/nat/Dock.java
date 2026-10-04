package com.l6.carmedia.nat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部一行：左侧迷你音乐栏 + 居中 dock + 右侧应用栏（含长按拖动排序与垃圾桶）。
 * 对应原型 {@code #bottomRow} / {@code #musicBar} / {@code #dock} / {@code #dockApps} / {@code #dockTrash}。
 *
 * ★ 底栏用 {@link FrameLayout} 而不是 LinearLayout：原型里 #musicBar / #dockApps 是
 *   {@code position:absolute; left|right:24u; top:50%; translateY(-50%)}，只有 dock 药丸是靠
 *   flex 居中的 —— 用 FrameLayout + layout_gravity 才是同一语义（而且应用栏宽度随已钉数量变，
 *   手工算「居中」必错）。
 *
 * ★★ 拖动排序**绝不在拖动过程中搬动被拖的那个 View**：
 *   ViewGroup 在 removeView 时会把该 child 从 {@code mFirstTouchTarget} 触摸链里摘掉
 *   ⇒ 之后所有 MOVE/UP 都收不到 ⇒ 拖动态卡死（这正是 JS 版踩过的「事件必须挂 document」同款坑，
 *   在原生里换了个形式：不能把自己从父容器里摘出去）。
 *   所以拖动中只做两件事：移动 ghost + 算落点下标并把插入指示条挪过去；
 *   真正的重排等到 ACTION_UP 之后再用 {@link #refreshApps()} 整块重建（那时手势已结束，随便搬）。
 */
public class Dock {

    /** 长按进拖动的判定时长（与 JS 版 DOCK_LP_MS 一致） */
    private static final long LP_MS = 550L;
    /** 未进拖动前允许的手指抖动（超过就说明用户想滑动而不是长按） */
    private static final float MOVE_SLOP = 10f;

    private final Shell sh;
    private final Handler h = new Handler(Looper.getMainLooper());

    private FrameLayout row;               // bottomRow
    private LinearLayout pill;             // dock-pill
    private TextView btnHome, btnNav, btnSet;

    private FrameLayout appsBox;           // dockApps（药丸底 + 边框）
    private LinearLayout appsRow;          // 已钉应用 + 🏠 + 📋
    private TextView trash;                // 垃圾桶（应用栏正上方）
    private View insertBar;                // 拖动时的落点指示条

    private ImageView cover;
    private TextView coverEmoji, title, artist, srcTag, playBtn;

    // 拖动状态
    private View dragEl;
    private View ghost;
    private View lpEl;
    private final Runnable lpRun = this::lpFire;
    private long swallowUntil = 0L;
    private float downX;
    private int ovLocX, ovLocY;
    /** 松手时要落到的下标（在「已钉项」序列里） */
    private int dropIndex = -1;

    public Dock(Shell sh) {
        this.sh = sh;
    }

    // ================================================================== 构建

    public View build() {
        Context c = sh.ctx();
        row = new FrameLayout(c);
        row.setLayoutParams(new LinearLayout.LayoutParams(U.MP, U.WC));
        U.pad(row, 18, 10, 18, 14);
        // 垃圾桶要画到应用栏上方（超出应用栏边界）⇒ 整条链都不能裁子视图
        row.setClipChildren(false);
        row.setClipToPadding(false);

        // 左：迷你音乐栏
        FrameLayout.LayoutParams mbLp = new FrameLayout.LayoutParams(U.WC, U.WC);
        mbLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        mbLp.leftMargin = U.px(24);
        row.addView(buildMusicBar(), mbLp);

        // 中：dock 药丸
        pill = U.row(c);
        pill.setBackground(U.bg(0x0FFFFFFF, 40, U.LINE, 1));
        U.pad(pill, 18, 12, 18, 12);
        U.gapH(pill, 14);
        try {
            pill.setElevation(U.px(8));
        } catch (Throwable ignored) {
        }
        btnHome = dockBtn("\uD83C\uDFE0", "主页");
        btnNav = dockBtn("\uD83E\uDDED", "导航");
        btnSet = dockBtn("\u2699\uFE0F", "设置");
        btnHome.setOnClickListener(v -> {
            sh.gotoPage(Shell.PAGE_HOME);
            syncActive();
        });
        btnNav.setOnClickListener(v -> sh.host().launchSrc(sh.prefs().navApp));
        btnSet.setOnClickListener(v -> {
            sh.gotoPage(Shell.PAGE_SET);
            syncActive();
        });
        pill.addView(btnHome);
        pill.addView(btnNav);
        pill.addView(btnSet);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(U.WC, U.WC);
        pillLp.gravity = Gravity.CENTER;
        row.addView(pill, pillLp);

        // 右：应用栏（含垃圾桶）
        FrameLayout.LayoutParams abLp = new FrameLayout.LayoutParams(U.WC, U.WC);
        abLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        abLp.rightMargin = U.px(24);
        row.addView(buildAppsBox(), abLp);

        syncActive();
        refreshApps();
        return row;
    }

    private TextView dockBtn(String emoji, String label) {
        Context c = sh.ctx();
        TextView t = U.text(c, emoji + "\n" + label, 32, U.SUB);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(U.px(3), 1f);
        t.setLayoutParams(U.lp(80, 80));
        t.setClickable(true);
        U.rippleCircle(t);
        styleDockBtn(t, false);
        return t;
    }

    /** dock 圆按钮的两种态：选中 = 135° 渐变底 + 深色字；未选中 = 透明底 + 次级字 */
    private void styleDockBtn(TextView t, boolean on) {
        if (on) {
            t.setBackground(U.accGradient(40));
            t.setTextColor(U.ON_ACC_TXT);
            try {
                t.setElevation(U.px(6));
            } catch (Throwable ignored) {
            }
        } else {
            t.setBackground(U.bg(android.graphics.Color.TRANSPARENT, 40));
            t.setTextColor(U.SUB);
            try {
                t.setElevation(0f);
            } catch (Throwable ignored) {
            }
        }
    }

    private View buildMusicBar() {
        Context c = sh.ctx();
        LinearLayout bar = U.row(c);
        bar.setBackground(U.bg(0x12FFFFFF, 44, U.LINE, 1));
        U.pad(bar, 10, 8, 22, 8);
        U.gapH(bar, 16);

        FrameLayout cov = new FrameLayout(c);
        cov.setBackground(U.accGradient(33));
        cov.setLayoutParams(U.lp(66, 66));
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setVisibility(View.GONE);
        cov.addView(cover, new FrameLayout.LayoutParams(U.MP, U.MP));
        coverEmoji = U.text(c, "\uD83C\uDFB5", 30, U.TXT);
        coverEmoji.setGravity(Gravity.CENTER);
        cov.addView(coverEmoji, new FrameLayout.LayoutParams(U.MP, U.MP));
        bar.addView(cov);

        LinearLayout meta = U.col(c);
        U.gapV(meta, 5);
        title = U.text(c, "未播放", 20, U.TXT);
        U.bold(title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // ★ 这里是纵向 LinearLayout，weight 作用在**高度轴**上 —— 别照抄横向那套 width=0 + weight
        meta.addView(title, U.lp(U.WC, U.WC));

        LinearLayout srcRow = U.row(c);
        U.gapH(srcRow, 9);
        artist = U.text(c, "—", 16, U.SUB);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        srcRow.addView(artist);
        srcTag = U.text(c, "U盘", 15, U.ACC2);
        srcTag.setBackground(U.bg(0x2234D399, 20, 0x5534D399, 1));
        U.pad(srcTag, 10, 2, 10, 2);
        srcRow.addView(srcTag);
        meta.addView(srcRow);
        // 音乐栏最宽 620u（原型 max-width）—— 超出由 title/artist 的 ellipsize 收敛
        bar.addView(meta, new LinearLayout.LayoutParams(U.px(250), U.WC));

        LinearLayout ctrls = U.row(c);
        U.gapH(ctrls, 9);
        ctrls.addView(mbtn("\u23EE", false, () -> sh.host().mediaControl("prev")));
        playBtn = mbtn("\u23F8", true, () -> sh.host().mediaControl("toggle"));
        ctrls.addView(playBtn);
        ctrls.addView(mbtn("\u23ED", false, () -> sh.host().mediaControl("next")));
        bar.addView(ctrls);
        return bar;
    }

    private TextView mbtn(String glyph, boolean primary, Runnable onTap) {
        Context c = sh.ctx();
        TextView t = U.text(c, glyph, primary ? 22 : 19, U.TXT);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(U.lp(primary ? 58 : 50, primary ? 58 : 50));
        if (primary) {
            t.setBackground(U.accGradient(33));
            try {
                t.setElevation(U.px(4));
            } catch (Throwable ignored) {
            }
        } else {
            t.setBackground(U.bg(U.PANEL2, 33, U.LINE, 1));
        }
        t.setClickable(true);
        U.rippleCircle(t);
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    /**
     * 底栏右侧「应用栏」容器。
     *
     * ★★ 垃圾桶是**绝对定位**的装饰元素（原型 {@code #dockTrash} 是 position:absolute），
     * 它绝不能参与父容器的测量：它是 204×204 的热区，而应用栏本体只有 178×84
     * ⇒ 一旦参与测量，父容器被撑成 204×204，WRAP_CONTENT 的按钮行会被摆到**左上角**，
     * 看起来就是「药丸大了一倍、里面什么都没有」（实测踩到）。
     * 所以这里：测量时量它但不计入父尺寸，布局时按「水平居中 + 底边距本体 28u」绝对摆位。
     */
    private final class AppsBox extends FrameLayout {
        /** 绝对定位的装饰子视图（垃圾桶）；null = 无 */
        View abs;

        AppsBox(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int count = getChildCount();
            int maxW = 0, maxH = 0;
            for (int i = 0; i < count; i++) {
                View ch = getChildAt(i);
                if (ch == abs) {
                    measureChildWithMargins(ch, wSpec, 0, hSpec, 0);   // 量它，但不计入
                    continue;
                }
                measureChildWithMargins(ch, wSpec, 0, hSpec, 0);
                maxW = Math.max(maxW, ch.getMeasuredWidth());
                maxH = Math.max(maxH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(
                    resolveSize(Math.max(maxW, getSuggestedMinimumWidth()), wSpec),
                    resolveSize(Math.max(maxH, getSuggestedMinimumHeight()), hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (abs == null) {
                return;
            }
            // ★ 垃圾桶挂在**全屏 overlay** 里（见 buildAppsBox 的说明：放进 appsBox 会被
            //   父容器矩形截掉），所以这里要把「应用栏正上方 28u、水平居中」换算到 overlay 坐标系。
            //   post 一帧再取坐标：onLayout 执行中 getLocationOnScreen 可能还是上一帧的值。
            post(() -> {
                if (abs == null || getWidth() <= 0) {
                    return;
                }
                int[] a = new int[2];
                getLocationOnScreen(a);
                int[] o = new int[2];
                sh.overlay().getLocationOnScreen(o);
                int sz = abs.getWidth() > 0 ? abs.getWidth() : U.px(204);
                abs.setX(a[0] - o[0] + (getWidth() - sz) / 2f);
                abs.setY(a[1] - o[1] - sz - U.px(28));
            });
        }
    }

    private View buildAppsBox() {
        Context c = sh.ctx();
        appsBox = new AppsBox(c);
        appsBox.setBackground(U.bg(0x0FFFFFFF, 40, U.LINE, 1));
        // 垃圾桶挂在应用栏内部、但要画到它上方 ⇒ 关掉裁切（原型靠 overflow:visible 的等价物）
        appsBox.setClipChildren(false);
        appsBox.setClipToPadding(false);

        appsRow = U.row(c);
        U.pad(appsRow, 14, 8, 14, 8);
        U.gapH(appsRow, 14);
        appsBox.addView(appsRow, new FrameLayout.LayoutParams(U.WC, U.WC));

        // 落点指示条：拖动时插在「将要插入的位置」上，避免原地不动让人以为没反应
        insertBar = new View(c);
        insertBar.setBackground(U.bg(U.ACC, 2));
        insertBar.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams ibLp = new FrameLayout.LayoutParams(U.px(3), U.px(68));
        ibLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        appsBox.addView(insertBar, ibLp);

        // 垃圾桶：应用栏正上方 28u，水平居中；热区 = 应用尺寸(68u) × 3 = 204u
        trash = U.text(c, "\uD83D\uDDD1", 72, 0xFFFFB4B4);
        trash.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0x24FF4646);
        g.setStroke(Math.max(1, U.px(2)), 0xA6FF5A5A);
        trash.setBackground(g);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(U.px(204), U.px(204));
        // ★ 必须 TOP|START（而不是 CENTER_HORIZONTAL）：位置由 AppsBox.onLayout 用
        //   setX/setY 按 overlay 坐标系算好，setX 是「相对布局位置」的偏移量。
        //   若这里再让 FrameLayout 水平居中，布局位置已经是 (overlayW-sz)/2，
        //   setX 会在它上面再加一次居中量 ⇒ 垃圾桶横向偏到屏幕外。
        tp.gravity = Gravity.TOP | Gravity.START;
        trash.setAlpha(0f);
        trash.setVisibility(View.INVISIBLE);
        // ★★ 必须挂**全屏 overlay**、不能留在 appsBox 里：垃圾桶位于应用栏「正上方」，
        //   而 Android 不保证绘制「超出父容器边界」的子视图（即便 clipChildren=false，
        //   脏区与绘制范围仍以父容器矩形为准）⇒ 塞进只有几十像素高的 appsBox 里会被整块截掉，
        //   实测拖动态完全看不到垃圾桶，「拖到垃圾桶取消钉住」就成了盲操作。
        //   挂 overlay 后没有裁剪问题，位置由 AppsBox.onLayout 换算过去。
        //   ★ 依赖 NatShell.build() 里「overlay 先于 dock.build() 创建」的顺序；这里再兜一层，
        //     万一顺序又被人改回去，也只是垃圾桶晚一帧出现，**绝不抛 NPE** ——
        //     那个 NPE 会冒到 MainActivity.startNativeUi 的 catch，把整个原生界面
        //     静默换成网页版，排查成本极高（见 NatShell.build 里 overlay 处的长注释）。
        FrameLayout ov = sh.overlay();
        if (ov == null) {
            appsBox.post(() -> {
                FrameLayout o2 = sh.overlay();
                if (o2 != null) o2.addView(trash, tp);
            });
        } else {
            ov.addView(trash, tp);
        }
        ((AppsBox) appsBox).abs = trash;

        return appsBox;
    }

    // ================================================================== 数据

    /** 重画 dock 右侧：已钉应用 + 两个固定尾按钮（顺序即 prefs.dockList 的顺序） */
    public void refreshApps() {
        if (appsRow == null) return;
        appsRow.removeAllViews();
        Prefs p = sh.prefs();
        List<Def.App> all = sh.host().allApps();
        for (String pkg : new ArrayList<>(p.dockList)) {
            Def.App a = find(all, pkg);
            String label = a == null ? pkg : a.name;
            Bitmap ic = sh.host().icon(pkg);
            View cell;
            if (ic != null) {
                FrameLayout f = new FrameLayout(sh.ctx());
                ImageView iv = new ImageView(sh.ctx());
                iv.setImageBitmap(ic);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(U.px(34), U.px(34));
                ip.gravity = Gravity.CENTER;
                f.addView(iv, ip);
                cell = f;
            } else {
                TextView t = appGlyph(a == null ? "\u25A6" : a.emoji, label);
                cell = t;
            }
            cell.setBackground(U.bg(android.graphics.Color.TRANSPARENT, 33));
            cell.setClickable(true);
            U.rippleCircle(cell);
            attachApp(cell, pkg);
            appsRow.addView(cell, U.lp(68, 68));
        }

        // 「🏠 返回原桌面」与「📋 应用列表」是固定尾按钮，结构上不参与换位
        TextView home = appGlyph("\uD83C\uDFE0", "返回原桌面");
        home.setOnClickListener(v -> sh.host().goHome());
        appsRow.addView(home, U.lp(68, 68));

        TextView more = appGlyph("\uD83D\uDCCB", "打开应用列表");
        more.setBackground(U.bg(0x1AFFFFFF, 33));
        more.setOnClickListener(v -> sh.openDrawer(null, "\uD83D\uDCCB 应用列表", null));
        appsRow.addView(more, U.lp(68, 68));
    }

    private TextView appGlyph(String emoji, String name) {
        TextView t = U.text(sh.ctx(), emoji, 30, U.TXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setContentDescription(name);
        t.setBackground(U.bg(android.graphics.Color.TRANSPARENT, 33));
        t.setClickable(true);
        U.rippleCircle(t);
        return t;
    }

    private static Def.App find(List<Def.App> l, String pkg) {
        if (l == null) return null;
        for (Def.App a : l) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    // ================================================================== 拖动

    /**
     * 给一个「已钉应用」格子挂上：单击启动 / 长按进拖动。
     *
     * ★ 长按判定用 ACTION_DOWN 起表、位移超过 {@link #MOVE_SLOP} 就取消 —— 否则用户想横向滑动
     *   必然误进拖动（原型设置页那条手势拖动也用同样的阈值）。
     *
     * ★ 事件挂在格子自己身上是可行的：Android 会把 ACTION_DOWN 之后的事件全都送给同一个
     *   View，手指拖到屏幕另一头也收得到 MOVE/UP（前提是**别把它从父容器摘出去**，见类注释）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private void attachApp(View target, String pkg) {
        target.setTag(pkg);
        target.setClickable(true);
        target.setOnClickListener(v -> {
            // 长按刚结束时紧随的那次 click 必须吃掉，否则松手会顺带把 App 拉起来
            if (System.currentTimeMillis() < swallowUntil) return;
            sh.host().launchPkg(pkg);
        });
        target.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lpEl = v;
                    downX = e.getRawX();
                    h.removeCallbacks(lpRun);
                    h.postDelayed(lpRun, LP_MS);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (dragEl == null) {
                        if (lpEl == v && Math.abs(e.getRawX() - downX) > U.px(MOVE_SLOP)) {
                            h.removeCallbacks(lpRun);        // 先移动 ⇒ 不是长按
                            lpEl = null;
                        }
                        return false;
                    }
                    dragMove(e.getRawX(), e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(lpRun);
                    lpEl = null;
                    if (dragEl != null) {
                        dragEnd(e.getRawX(), e.getRawY());
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void lpFire() {
        if (lpEl == null || dragEl != null) return;
        final View el = lpEl;
        lpEl = null;
        dragEl = el;
        dropIndex = -1;
        swallowUntil = System.currentTimeMillis() + 1500;

        ghost = buildGhost(el);
        sh.overlay().addView(ghost, new FrameLayout.LayoutParams(U.px(68), U.px(68)));
        // overlay 与 rawY 的坐标原点不同，先取一次 overlay 在屏幕上的位置
        int[] loc = new int[2];
        sh.overlay().getLocationOnScreen(loc);
        ovLocX = loc[0];
        ovLocY = loc[1];

        int[] el2 = new int[2];
        el.getLocationOnScreen(el2);
        dragMove(el2[0] + el.getWidth() / 2f, el2[1] + el.getHeight() / 2f);

        trash.setVisibility(View.VISIBLE);
        trash.animate().alpha(1f).setDuration(180).start();
    }

    /** ghost = 跟随手指的圆形图标（复用真实图标，视觉上像把图标拎起来了） */
    private View buildGhost(View el) {
        FrameLayout g = new FrameLayout(sh.ctx());
        g.setBackground(U.bg(0xF21E2636, 33, U.ACC, 1));
        try {
            g.setElevation(U.px(12));
        } catch (Throwable ignored) {
        }
        if (el instanceof FrameLayout) {
            ImageView src = null;
            for (int i = 0; i < ((FrameLayout) el).getChildCount(); i++) {
                if (((FrameLayout) el).getChildAt(i) instanceof ImageView) {
                    src = (ImageView) ((FrameLayout) el).getChildAt(i);
                    break;
                }
            }
            ImageView iv = new ImageView(sh.ctx());
            if (src != null) iv.setImageDrawable(src.getDrawable());
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(U.px(34), U.px(34));
            p.gravity = Gravity.CENTER;
            g.addView(iv, p);
        } else if (el instanceof TextView) {
            TextView tv = U.text(sh.ctx(), ((TextView) el).getText(), 30, U.TXT);
            tv.setGravity(Gravity.CENTER);
            g.addView(tv, new FrameLayout.LayoutParams(U.MP, U.MP));
        }
        return g;
    }

    private void dragMove(float x, float y) {
        if (dragEl == null) return;
        int sz = U.px(68);
        if (ghost != null) {
            ghost.setX(x - ovLocX - sz / 2f);
            ghost.setY(y - ovLocY - sz / 2f);
        }
        boolean hot = trashHit(x, y);
        trash.setScaleX(hot ? 1.12f : 1f);
        trash.setScaleY(hot ? 1.12f : 1f);
        if (ghost != null) {
            // 悬在垃圾桶上时缩小：否则跟着手指的图标正好盖住垃圾桶，用户看不到「要往哪扔」
            ghost.setScaleX(hot ? 0.5f : 1f);
            ghost.setScaleY(hot ? 0.5f : 1f);
            ghost.setAlpha(hot ? 0.8f : 1f);
        }
        showInsertBar(x, hot);
    }

    /**
     * 算落点下标并把指示条挪过去。
     * ★ 只挪指示条、**不搬被拖的 View**（见类注释：搬了会断触摸链）。
     */
    private void showInsertBar(float x, boolean inTrash) {
        if (appsBox == null) return;
        if (inTrash) {
            insertBar.setVisibility(View.INVISIBLE);
            dropIndex = -1;
            return;
        }
        List<View> pinned = pinnedViews();
        int at = pinned.size();
        float barX = 0f;
        boolean found = false;
        for (int i = 0; i < pinned.size(); i++) {
            View v = pinned.get(i);
            if (v == dragEl) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (x < loc[0] + v.getWidth() / 2f) {
                at = i;
                barX = loc[0] - appsBoxLoc()[0] - U.px(7);
                found = true;
                break;
            }
        }
        if (!found) {
            int[] last = new int[2];
            if (!pinned.isEmpty()) {
                View v = pinned.get(pinned.size() - 1);
                v.getLocationOnScreen(last);
                barX = last[0] - appsBoxLoc()[0] + v.getWidth() - U.px(4);
            } else {
                barX = U.px(14);
            }
        }
        // 已钉项序列的下标 → 去掉被拖项之后的位置
        int from = pinned.indexOf(dragEl);
        if (from >= 0 && at > from) at--;
        dropIndex = Math.max(0, at);
        insertBar.setVisibility(View.VISIBLE);
        insertBar.setX(barX);
        insertBar.setY((appsBox.getHeight() - U.px(48)) / 2f);
    }

    private int[] appsBoxLoc() {
        int[] l = new int[2];
        if (appsBox != null) appsBox.getLocationOnScreen(l);
        return l;
    }

    private void dragEnd(float x, float y) {
        final View el = dragEl;
        if (el == null) return;
        boolean inTrash = trashHit(x, y);
        final int idx = dropIndex;
        dragEl = null;
        dropIndex = -1;
        if (ghost != null) {
            try {
                sh.overlay().removeView(ghost);
            } catch (Throwable ignored) {
            }
            ghost = null;
        }
        swallowUntil = System.currentTimeMillis() + 400;
        trash.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            trash.setVisibility(View.INVISIBLE);
            trash.setScaleX(1f);
            trash.setScaleY(1f);
        }).start();
        insertBar.setVisibility(View.INVISIBLE);

        String pkg = (String) el.getTag();
        Prefs p = sh.prefs();
        if (inTrash) {
            if (pkg != null && p.isDockPinned(pkg)) {
                p.toggleDockPin(pkg);
                sh.toast("已从快捷方式移除");
            }
            refreshApps();
            return;
        }
        if (pkg == null || idx < 0) return;
        ArrayList<String> order = new ArrayList<>(p.dockList);
        int from = order.indexOf(pkg);
        if (from < 0) return;
        order.remove(from);
        order.add(Math.max(0, Math.min(idx, order.size())), pkg);
        if (order.equals(p.dockList)) return;
        p.dockList = order;
        p.save();
        refreshApps();                 // 手势已结束，这时整块重建是安全的
        sh.toast("已保存快捷方式顺序");
    }

    /** 已钉项 = 带 pkg tag 的格子（🏠 / 📋 两个固定尾按钮没有 tag，永不参与换位） */
    private List<View> pinnedViews() {
        List<View> out = new ArrayList<>();
        if (appsRow == null) return out;
        for (int i = 0; i < appsRow.getChildCount(); i++) {
            View v = appsRow.getChildAt(i);
            if (v.getTag() != null) out.add(v);
        }
        return out;
    }

    private boolean trashHit(float x, float y) {
        if (trash == null || trash.getVisibility() != View.VISIBLE) return false;
        // 无布局（异常）→ 永不判命中，宁可什么都不做也不误删
        if (trash.getWidth() <= 0 && trash.getHeight() <= 0) return false;
        int[] loc = new int[2];
        trash.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + trash.getWidth()
                && y >= loc[1] && y <= loc[1] + trash.getHeight();
    }

    // ================================================================== 状态同步

    /** 页面切换后刷新 dock 高亮（主页 / 设置）；导航按钮永不「选中」（它是直接拉起外部 App） */
    public void syncActive() {
        int pg = sh.page();
        styleDockBtn(btnHome, pg == Shell.PAGE_HOME);
        styleDockBtn(btnSet, pg == Shell.PAGE_SET);
        styleDockBtn(btnNav, false);
    }

    /** 每秒的系统数据 → 迷你音乐栏 */
    public void onSys(JSONObject sys) {
        if (sys == null) return;
        JSONObject m = sys.optJSONObject("media");
        String t = m == null ? "" : m.optString("title", "");
        String a = m == null ? "" : m.optString("artist", "");
        boolean playing = m != null && m.optBoolean("playing", false);
        boolean active = m != null && m.optBoolean("active", false);

        title.setText(active && !t.isEmpty() ? t : "未播放");
        artist.setText(a.isEmpty() ? "—" : a);
        playBtn.setText(playing ? "\u23F8" : "\u25B6");
        srcTag.setText(musicSrcName());

        String art = m == null ? "" : m.optString("art", "");
        Bitmap b = art.startsWith("data:image") ? decodeDataUrl(art) : null;
        if (b != null) {
            cover.setImageBitmap(b);
            cover.setVisibility(View.VISIBLE);
            coverEmoji.setVisibility(View.GONE);
        } else {
            cover.setVisibility(View.GONE);
            coverEmoji.setVisibility(View.VISIBLE);
            coverEmoji.setText(musicSrcEmoji());
        }
    }

    private String musicSrcName() {
        String v = sh.prefs().musicSrc;
        for (String[] m : Def.LOCAL_MUSIC) if (m[0].equals(v)) return m[1];
        for (Def.App a : sh.host().appsOfType("music")) {
            if (a.srcKey().equals(v) || a.pkg.equals(v)) return a.name;
        }
        return "U盘";
    }

    private String musicSrcEmoji() {
        String v = sh.prefs().musicSrc;
        for (String[] m : Def.LOCAL_MUSIC) if (m[0].equals(v)) return m[2];
        for (Def.App a : sh.host().appsOfType("music")) {
            if (a.srcKey().equals(v) || a.pkg.equals(v)) return a.emoji;
        }
        return "\uD83C\uDFB5";
    }

    private static Bitmap decodeDataUrl(String url) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) return null;
            byte[] raw = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }
}
