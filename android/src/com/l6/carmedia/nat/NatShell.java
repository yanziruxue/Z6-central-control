package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;

/**
 * 原生主页容器 —— 对应原型 {@code #stage} + {@code #app} + {@code #viewport/#track} + 各浮层。
 *
 * 层级（照搬原型，顺序不能换）：
 * <pre>
 *  root(整屏 FrameLayout)
 *   ├─ wallpaper        壁纸层（纯色底 / 静态图 / 视频 / 压暗层）  ← 原型 z-index:0
 *   ├─ content          界面层                               ← 原型 #app z-index:1
 *   │   └─ column
 *   │       ├─ statusbar 顶部状态栏（原型里是空 header，只有一层渐变底）
 *   │       ├─ viewport  翻页区（weight 1）→ track 里叠着「主页 / 设置」两页
 *   │       └─ bottomRow 底栏（音乐栏 + dock + 应用栏）
 *   └─ overlay          浮层（抽屉 / 强制更新蒙层 / 拖动 ghost / Toast）
 * </pre>
 *
 * ★★ 翻页**不用 ViewPager**，而是「两页叠放 + track 整体位移」—— 与原型
 *   {@code track.style.transform=translateY(-Npx)} 完全同构。好处是页面里的滚动容器
 *   （车辆状态列表、设置页长列表）各自独立滚动，不会被翻页手势抢走；换成 ViewPager
 *   反而要在「竖向滚动 vs 竖向翻页」之间做冲突仲裁。
 *
 * ★★ 手势监听挂在**根容器的 dispatchTouchEvent 上且只观察不消费**：
 *   这是 JS 版「事件必须挂 document」那条教训的原生形态 —— 挂在某一栏上，手指滑到栏外
 *   就再也收不到后续事件；而在这里 consume 掉会让卡片/按钮全都点不动。
 */
public class NatShell implements Shell {

    /** 页面切换动画时长（原型 transition .38s） */
    private static final int PAGE_MS = 380;

    /** 手指移动超过这个距离才算滑动：max(24, 屏高*40/720)，与 JS 版 SWIPE_MIN 同式 */
    private int swipeMin = 40;

    private final Host host;
    private final Prefs prefs;

    private FrameLayout root;
    private FrameLayout overlay;
    private LinearLayout column;
    private FrameLayout viewport;
    private FrameLayout track;
    private Wallpaper wallpaper;
    private HomePane home;
    private SetPane set;
    private Dock dock;
    private NavMini miniNav;

    private AppDrawer drawer;
    private ForceUpdate forceUpdate;

    private View toastView;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable toastHide = () -> hideToast();
    private final Runnable sysRun = new Runnable() {
        @Override
        public void run() {
            pollSys();
            if (polling) h.postDelayed(this, 1000);
        }
    };

    private int idx = PAGE_HOME;
    /** 翻页动画（UI 线程驱动，见 {@link #slideTrack()}） */
    private android.animation.ValueAnimator slide;
    /** 位移重算是否已排队（见 {@link #postTrackOffset()}） */
    private boolean offsetPosted;
    private int lastViewportH = -1;
    private boolean polling = false;
    private boolean toastShowing = false;

    // 手势状态（观察式，不消费事件）
    private float dnX, dnY;
    private boolean dnHot;

    /** 应用图标缓存：Icon 解码不便宜，抽屉/dock/设置页来回切会反复要 */
    private final LruCache<String, Bitmap> icons = new LruCache<>(64);

    public NatShell(Host host) {
        this.host = host;
        this.prefs = Prefs.get(host.ctx());
        U.init(host.ctx());
    }

    // ================================================================== 构建

    /** 建整棵树。返回的 View 直接 setContentView */
    public View build() {
        Context c = host.ctx();
        U.init(c);

        root = new RootView(c);

        wallpaper = new Wallpaper(this);
        root.addView(wallpaper.view(), new FrameLayout.LayoutParams(U.MP, U.MP));

        FrameLayout content = new FrameLayout(c);
        content.setLayoutParams(new FrameLayout.LayoutParams(U.MP, U.MP));
        root.addView(content);

        // ★★ 浮层（抽屉 / 强制更新蒙层 / 拖动 ghost / 垃圾桶 / Toast 都挂这里）
        //   必须在**所有子面板 build() 之前**建好，而且必须紧跟 content 加到 root
        //   （root 的子序 = wallpaper → content → overlay，这样 overlay 才画在最上）。
        //
        //   为什么不能像原来那样放到 build() 末尾：`Dock.build()` 里的垃圾桶要挂到 overlay
        //   （见 Dock.buildAppsBox 的说明），而 dock 在末尾之前就被 build 了 ⇒ sh.overlay()
        //   返回 null ⇒ NPE。这个 NPE 会一路冒到 MainActivity.startNativeUi 的 catch，
        //   被当成「原生界面起不来」而**静默回退整个网页版** —— 症状极具误导性：
        //   界面看起来正常（那是 HTML 原型在 WebView 里渲染的，两者是忠实移植），
        //   但原生侧所有 android.util.Log 一条都不出，任何原生交互改动都「测不出效果」。
        //   实测证据：/sdcard/Download/L6/l6-<date>.log 里
        //   「原生界面启动失败，回退网页版: java.lang.NullPointerException: ... addView ... on a null object reference」。
        overlay = new FrameLayout(c);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(U.MP, U.MP));
        root.addView(overlay);

        column = U.col(c);
        content.addView(column, new FrameLayout.LayoutParams(U.MP, U.MP));

        // 顶部状态栏：原型里是空的 <header>，视觉上只提供一层顶部渐变
        View statusbar = new View(c);
        statusbar.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x12FFFFFF, 0x00000000}));
        column.addView(statusbar, new LinearLayout.LayoutParams(U.MP, U.px(33)));

        // 翻页区
        viewport = new FrameLayout(c);
        viewport.setClipChildren(true);
        viewport.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncPageSize());
        column.addView(viewport, new LinearLayout.LayoutParams(U.MP, 0, 1f));

        track = new FrameLayout(c);
        // ★★ 裁剪只能由 viewport 这一层负责（原型 #viewport{overflow:hidden}，#track 不裁）。
        //   FrameLayout 默认 clipChildren=true ⇒ 若不关掉，track 会把「落在自己矩形之外」的
        //   第二页整页裁掉（设置页在 track 局部坐标 559..1118，track 只有 0..559）——
        //   表现为切页后视口一片空白且不刷新，屏幕上始终是主页的旧像素。
        track.setClipChildren(false);
        track.setClipToPadding(false);
        viewport.addView(track, new FrameLayout.LayoutParams(U.MP, U.MP));

        home = new HomePane(this);
        set = new SetPane(this);
        addPage(home.build());
        addPage(set.build());

        // 迷你导航卡片（仅在真实导航时显示）
        miniNav = new NavMini(c);
        FrameLayout.LayoutParams mnLp = new FrameLayout.LayoutParams(U.MP, U.WC);
        mnLp.gravity = Gravity.TOP;
        mnLp.leftMargin = U.px(18);
        mnLp.rightMargin = U.px(18);
        mnLp.topMargin = U.px(8);
        viewport.addView(miniNav, mnLp);

        // 底栏
        dock = new Dock(this);
        column.addView(dock.build(), new LinearLayout.LayoutParams(U.MP, U.WC));

        wallpaper.reload();
        syncPageSize();
        syncActive();
        return root;
    }

    private void addPage(View page) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(U.MP, U.MP);
        page.setLayoutParams(p);
        track.addView(page);
    }

    private void syncActive() {
        if (dock != null) dock.syncActive();
    }

    /**
     * 视口尺寸变了 → 把两页各撑成一屏高并重算位移。
     * ★ 原型是老 WebView 百分比位移的坑（relative 到 #track 自身高度，一旦两者不等就静默错位），
     *   这里改成**实测高度累加**：页面高度直接等于视口高度，位移 = idx × 视口高，不留任何想象空间。
     */
    private void syncPageSize() {
        if (viewport == null || track == null) return;
        cancelSlide();      // 尺寸重算期间不要再有动画在改 translationY
        int vh = viewport.getHeight();
        if (vh <= 0) return;
        if (vh != lastViewportH) {
            lastViewportH = vh;
            // 手势阈值跟着屏高走（与 JS 版 syncSwipeMin 同式）
            swipeMin = Math.max(24, Math.round(U.screenH * 40f / 720f));
            int n = track.getChildCount();
            for (int i = 0; i < n; i++) {
                View p = track.getChildAt(i);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p.getLayoutParams();
                lp.height = vh;
                lp.topMargin = i * vh;
                p.setLayoutParams(lp);
            }
            // ★★ 轨道自己也得有「n 页那么高」：它是以 MATCH_PARENT 加进 viewport 的、只有一屏高，
            //   而第二页摆在轨道局部 559..1118 —— 整页落在轨道矩形之外。
            //   Android 不保证绘制「超出父容器边界」的子视图（即便 clipChildren=false，
            //   脏区与绘制范围仍以父容器矩形为准）⇒ 第二页永远画不出来。
            android.view.ViewGroup.LayoutParams tlp = track.getLayoutParams();
            if (tlp != null && tlp.height != n * vh) {
                tlp.height = n * vh;
                track.setLayoutParams(tlp);
            }
        }
        // ★★ 位移**不能在布局流程里写**：本方法是 viewport 的 OnLayoutChangeListener 回调，
        //   正处在 layout() 内部，此时 setTranslationY 引发的 invalidate 会被这次布局收尾吃掉
        //   ⇒ 视口整片不进脏区 ⇒ 切页后屏幕上一直是上一页的旧像素（实测踩到，极难定位）。
        //   post 到下一轮 UI 循环，布局已结束，invalidate 才作数。
        postTrackOffset();
    }

    /** 把「按当前 idx 重算位移」推迟到布局结束之后执行（同一帧内多次调用只排一次） */
    private void postTrackOffset() {
        if (offsetPosted) return;
        View v = viewport != null ? (View) viewport : track;
        if (v == null) return;
        offsetPosted = true;
        v.post(() -> {
            offsetPosted = false;
            applyTrackOffset();
        });
    }

    /** 真正写位移（只在布局流程之外调用） */
    private void applyTrackOffset() {
        if (track == null || lastViewportH <= 0) return;
        float want = -idx * lastViewportH;
        if (track.getTranslationY() != want) {
            track.setTranslationY(want);
        }
        // 双保险：位移变化后把视口整片标脏（含被 viewport 裁掉的那部分）
        if (viewport != null) viewport.invalidate();
    }


    /**
     * 翻页位移。
     *
     * ★ 刻意**不用 {@code track.animate()}**：ViewPropertyAnimator 在硬件加速视图上会把
     * translationY 交给 RenderNodeAnimator（RenderThread）直接改 RenderNode，而
     * {@link #syncPageSize()} 同时在 UI 线程 {@code setTranslationY} —— 两个写入者抢同一属性，
     * 实测在模拟器上表现为「主页内容在视口里纵向重影 N 份」（视图树完全正常，纯属绘制错乱）。
     * 改成 ValueAnimator 每帧在 UI 线程写一次，与原型那条 CSS transition 是同一机制，只有一个写入者。
     */
    private void slideTrack() {
        if (track == null || lastViewportH <= 0) return;
        cancelSlide();
        final float from = track.getTranslationY();
        final float to = -idx * lastViewportH;
        if (from == to) return;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration(PAGE_MS);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        va.addUpdateListener(a -> track.setTranslationY((Float) a.getAnimatedValue()));
        va.start();
        slide = va;
    }

    private void cancelSlide() {
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
    }

    /** 屏幕尺寸变了（旋转 / 分屏）：重算 u、重解码壁纸、重摆页面 */
    public void onScreenChanged() {
        U.init(host.ctx());
        lastViewportH = -1;
        icons.evictAll();
        if (wallpaper != null) wallpaper.onScreenChanged();
        syncPageSize();
    }

    // ================================================================== Shell

    @Override
    public Host host() {
        return host;
    }

    @Override
    public Prefs prefs() {
        return prefs;
    }

    @Override
    public Context ctx() {
        return host.ctx();
    }

    @Override
    public FrameLayout overlay() {
        return overlay;
    }

    @Override
    public int page() {
        return idx;
    }

    @Override
    public void gotoPage(int i) {
        idx = Math.max(0, Math.min(1, i));
        slideTrack();
        if (wallpaper != null) wallpaper.setHomeVisible(idx == PAGE_HOME);
        syncActive();
        if (idx == PAGE_SET && set != null) set.refresh();
        final android.view.View t2 = track;
        if (t2 != null) {
        }
    }


    @Override
    public void refreshHome() {
        if (home != null) home.refresh();
    }

    @Override
    public void refreshDock() {
        if (dock != null) dock.refreshApps();
    }

    /** 设置页数据变了（例如刚导入一张壁纸）→ 重铺它自己的缩略图网格。 */
    public void refreshSet() {
        if (set != null) set.refresh();
    }

    @Override
    public boolean paneVisible(String pane) {
        return prefs.homeOn.contains(pane);
    }

    @Override
    public void applyWall() {
        if (wallpaper != null) {
            wallpaper.setHomeVisible(idx == PAGE_HOME);
            wallpaper.reload();
        }
    }

    @Override
    public void pushSys(JSONObject sys) {
        if (home != null) home.onSys(sys);
        if (dock != null) dock.onSys(sys);
        if (miniNav != null) miniNav.onSys(sys);
    }

    /**
     * 事件驱动刷新：SysHub 有新数据推来时立刻刷一次，不必干等下一次 1s 轮询
     * （换歌 / 导航状态跳变这类时刻，1s 的延迟肉眼可见）。
     * 同一帧内的多次事件只跑一次。
     */
    public void sysWake() {
        h.removeCallbacks(sysWakeRun);
        h.post(sysWakeRun);
    }

    private final Runnable sysWakeRun = new Runnable() {
        @Override
        public void run() {
            pollSys();
        }
    };

    @Override
    public void openDrawer(String type, String title, DrawerPick pick) {
        if (drawer == null) drawer = new AppDrawer(this);
        drawer.open(type, title, pick);
    }

    @Override
    public void onOta(String kind, JSONObject data) {
        if (set != null) set.onOta(kind, data);
    }

    @Override
    public void forceUpdate(String changelog, Runnable onInstall, Runnable onCancel) {
        if (forceUpdate == null) forceUpdate = new ForceUpdate(this);
        forceUpdate.show(changelog, onInstall, onCancel);
    }

    // ================================================================== Toast

    /** 页面内轻提示（对应原型 .l6-toast：底部居中、圆角药丸、深底描边） */
    @Override
    public void toast(String msg) {
        if (overlay == null || msg == null) return;
        Context c = ctx();
        if (toastView == null) {
            android.widget.TextView t = U.text(c, "", 13, U.TXT);
            t.setBackground(U.bg(0xEB080C14, 20, U.LINE, 1));
            U.pad(t, 16, 9, 16, 9);
            toastView = t;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(U.WC, U.WC);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = U.px(96);
            overlay.addView(toastView, lp);
        } else if (toastView.getParent() == null) {
            overlay.addView(toastView);
        }
        ((android.widget.TextView) toastView).setText(msg);
        toastView.setVisibility(View.VISIBLE);
        toastView.animate().alpha(1f).translationY(0f).setDuration(220).start();
        toastShowing = true;
        h.removeCallbacks(toastHide);
        h.postDelayed(toastHide, 1800);
    }

    private void hideToast() {
        if (!toastShowing || toastView == null) return;
        toastShowing = false;
        toastView.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    if (toastView != null) toastView.setVisibility(View.GONE);
                }).start();
    }

    // ================================================================== 手势（观察式）

    /**
     * 根容器：只**观察**事件，不改动/不拦截它们。这样卡片、按钮、dock 的点击全都照常工作，
     * 而滑动手势也能在任何位置起手（等价于 JS 版把监听挂在 document 上）。
     *
     * ★★ 必须返回 true —— 这一步是「告诉父容器：这次手势归我，后续事件继续派发过来」。
     *   只观察不消费的写法（return super.dispatchTouchEvent）在**主页中心区**会彻底失效：
     *   那里没有任何可点控件，子树不消费 ACTION_DOWN ⇒ super 返回 false ⇒ 根容器落选
     *   触摸目标 ⇒ 之后的 MOVE / UP 一个都不会再派发到这里。
     *   实测症状：主页上滑完全静默（只收到 ACTION_DOWN），而设置页有可点控件所以看着正常。
     *   返回 true 不影响子视图拿事件（super 先把事件派发给它们）。
     */
    private final class RootView extends FrameLayout {
        RootView(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            try {
                observe(ev);
            } catch (Throwable ignored) {
            }
            super.dispatchTouchEvent(ev);
            return true;
        }
    }

    private void observe(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dnX = e.getRawX();
                dnY = e.getRawY();
                dnHot = inNavHot(dnX, dnY);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float dx = e.getRawX() - dnX;
                float dy = e.getRawY() - dnY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > swipeMin) {
                    dnX = e.getRawX();
                    dnY = e.getRawY();
                    handleSwipe(dx, dy, dnHot);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) dnHot = false;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) dnHot = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                dnHot = false;
                break;
            default:
                break;
        }
    }

    /**
     * 下滑调导航的命中热区：屏幕中心 79.167% × 72.222%（原型 #navHot）。
     * 原设计是「1520×520 居中于 1920×720 画布（左上 200,100）」，换算成相对整屏的百分比。
     * ★ 必须按真实屏宽高算百分比 ⇒ 任意分辨率下都是「屏幕中心那一块」，不依赖固定画布。
     */
    private boolean inNavHot(float x, float y) {
        int w = root == null ? U.screenW : root.getWidth();
        int hh = root == null ? U.screenH : root.getHeight();
        if (w <= 0 || hh <= 0) return false;
        float l = w * 10.417f / 100f;
        float t = hh * 13.889f / 100f;
        return x >= l && x <= l + w * 79.167f / 100f
                && y >= t && y <= t + hh * 72.222f / 100f;
    }

    /** 与原型 handleSwipe 同构：设置页禁滑、非主页纵向翻页、主页必须命中热区 */
    private void handleSwipe(float dx, float dy, boolean hot) {
        if (idx == 1) return;                       // 设置页：只能用 dock 返回
        if (idx != 0) {
            gotoPage(idx + (dy < 0 ? 1 : -1));
            return;
        }
        if (!hot) return;                           // 主页：热区外一律不响应（防误触）
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (ax == 0 && ay == 0) return;             // 无位移不响应（防止 dy=0 被判成「上滑」）
        String dir = (ax > ay) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
        runGesture(dir);
    }

    /** 手势分发："" 未绑定什么都不做、"home" 回原桌面、"nav" 跟随当前导航源、其余 = 应用 key/包名 */
    private void runGesture(String dir) {
        String v;
        switch (dir) {
            case "up":    v = prefs.gUp;    break;
            case "down":  v = prefs.gDown;  break;
            case "left":  v = prefs.gLeft;  break;
            default:      v = prefs.gRight; break;
        }
        if (v == null || v.isEmpty()) return;
        if ("home".equals(v)) {
            host.goHome();
        } else if ("nav".equals(v)) {
            String nav = prefs.navApp;
            if (nav == null || nav.isEmpty()) {
                // 还没识别出导航源 → 给个明确提示，而不是静默什么都不做
                toast("未设置导航源，请到「设置 → 导航源」选择");
                return;
            }
            host.launchSrc(nav);
        } else {
            host.launchSrc(v);
        }
    }

    // ================================================================== 每秒系统数据

    /** 由 Activity 在 onResume 调 */
    public void start() {
        if (polling) return;
        polling = true;
        h.removeCallbacks(sysRun);
        h.post(sysRun);
    }

    /** 由 Activity 在 onPause 调（页面不可见时别再轮询，车机上也省电） */
    public void stop() {
        polling = false;
        h.removeCallbacks(sysRun);
        cancelSlide();
    }

    private void pollSys() {
        JSONObject s;
        try {
            s = host.sysState();
        } catch (Throwable t) {
            return;
        }
        pushSys(s);
    }

    /** OTA 事件 → 设置页（下载进度同时驱动强制更新蒙层，若它正开着） */
    public void onOtaEvent(String kind, JSONObject data) {
        if (forceUpdate != null && data != null && "progress".equals(kind)) {
            forceUpdate.progress(data.optInt("progress", 0));
        }
        onOta(kind, data);
    }

    // ================================================================== 图标缓存

    /** 给抽屉 / 设置页复用的应用图标缓存（解码一次，别反复来） */
    public Bitmap iconCached(String pkgOrKey) {
        if (pkgOrKey == null || pkgOrKey.isEmpty()) return null;
        Bitmap b = icons.get(pkgOrKey);
        if (b != null) return b;
        b = host.icon(pkgOrKey);
        if (b != null) icons.put(pkgOrKey, b);
        return b;
    }

    /** 竖屏兜底（对应原型 #rotate）：车机是横屏，竖屏下界面无可读性 */
    public String orientationHint() {
        return "请旋转设备为横屏";
    }
}
