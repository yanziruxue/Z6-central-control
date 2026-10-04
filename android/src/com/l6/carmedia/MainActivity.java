package com.l6.carmedia;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.l6.carmedia.nat.Def;
import com.l6.carmedia.nat.NatShell;
import com.l6.carmedia.nat.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 车机媒体·导航大屏 —— Android 壳（WebView + L6Native 原生桥接）。
 *
 * 原生能力：
 *  1) getInstalledAppsJson()  枚举车机已装的音乐 / 导航 App（PackageManager），供下拉设置页自动识别；
 *  2) saveWallpaper(type,b64,name)  上传的静态图/动态视频按类型落盘到 Download/L6/壁纸/{静态,动态}；
 *  3) launchApp(key)  按设置里选中的导航源调起对应车机导航 App（外部 App 全屏运行）。
 *
 * 页面侧通过注入的 L6Native 适配层调用，原型 HTML 无需改动即可独立运行。
 */
public class MainActivity extends Activity implements com.l6.carmedia.nat.Host {

    private static final int REQ_FILE = 1001;
    /** 需要在 API 29 及以下申请的传统存储权限。 */
    private static final int REQ_PERM = 1002;

    /** 原生界面容器（v2.0.0 起常驻，为 null 只可能是 build() 抛异常） */
    private NatShell natShell;

    /**
     * ★ 原生界面的「系统状态合成快照」，字段与 {@link com.l6.carmedia.nat.Host#sysState()} 契约一致：
     * {@code {media:{...}, nav:{...}, lyrics:{...}, car:{...}, notifyAccess:bool}}。
     *
     * 由 {@link SysHub} 的原始事件出口按 kind 归位（见 #natIngest）。加锁是因为
     * MediaHub / 通知服务 / 歌词线程都会推事件，JSONObject 本身不是线程安全的。
     */
    private final JSONObject natSys = new JSONObject();

    /** 上一次请求歌词的「曲名|歌手」；换歌才重拉（对应原型 REAL.lrcKey）。 */
    private String natLrcKey = "";

    /** launchSrc 的 600ms 同键去重状态（对应原型 startNav 的 lastNavKey / lastNavStart）。 */
    private String lastLaunchKey = "";
    private long lastLaunchAt = 0L;

    /** 车辆数据的标量字段（对应 JS VEH 的散字段）。 */
    private static final String[] CAR_SCALARS = {"fuel", "batt", "odo", "load", "temp", "lat", "ip",
            "lock", "lights", "hazard", "gear", "trunk"};

    /** 车辆数据的分组字段（doors / windows / tire / ac）。 */
    private static final String[] CAR_GROUPS = {"doors", "windows", "tire", "ac"};

    /** JS 桥实例。抽成字段是为了让原生侧能**直接调用同一份业务逻辑**，不重复实现。 */
    private Bridge api;

    /** 原生版选壁纸用的请求码（WebView 版走 onShowFileChooser，原生版只能自己拉系统选择器） */
    private static final int REQ_WALL = 1003;
    /** 正在选壁纸的类型（static / dynamic），选完落盘时要按它归档 */
    private String pickWallKind = Prefs.KIND_STATIC;

    /** 主线程 Handler（v1.5.x 借 WebView.post 用，v2.0.0 删掉 WebView 后自己持一个） */
    private android.os.Handler ui;
    /** 原生界面的根容器（v2.0.0 起只是容器：原生 Shell 挂在这一层）。 */
    private android.widget.FrameLayout rootView;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackAt = 0L;
    /** 即将调起导航 App：onPause 时若确实离开本界面，才挂「返回」悬浮按钮（导航未成功接管则不留按钮）。 */
    private boolean navLaunching = false;
    /** 全局 Context（Application 级），供 L6Log 发广播 / 落盘使用。 */
    private static Context appCtx = null;

    /* ==================== 导航保活（HOME 请求回弹） ==================== */

    /**
     * 导航保活窗口（毫秒）。调起导航 App 后这段时间内，若系统 HOME 请求把本界面顶到前台，
     * 就把导航再推回前台。
     *
     * 背景（用户实测）：本应用被设为车机默认桌面后，车机导航 App **启动完成后（约 2~10 秒）
     * 会自己发一次 HOME 请求回桌面** —— 桌面既然是本应用，地图就被顶掉，用户看到的现象就是
     * 「打开导航后自动返回」。用户明确要求「老六中控必须当默认桌面」，所以不能靠「别当桌面」
     * 绕开，只能在这里把落到本界面的 HOME 请求弹回导航。
     *
     * ✅ 根因已确认（2026-09-15）：**是当时那个版本的高德车机版自己发 HOME** —— 用户换用
     *    另一个版本的高德后该现象即消失，本应用侧无责。保活机制继续保留作**防御**：老六既然是
     *    默认桌面，任何发 HOME 的 App（换回旧版导航、其它导航 App、车机 ROM 看门狗）都会复现，
     *    有它在就不会再顶掉地图。
     */
    private static final long NAV_GUARD_MS = 20000L;

    /** 最多回弹次数：超过就放手，避免与「周期性发 HOME」的导航 App 互相顶成闪屏。 */
    private static final int NAV_GUARD_MAX = 3;

    /**
     * 「用户按 HOME」的识别宽限：回弹成功后的这段时间内若又收到 HOME，判定为**用户自己按的**
     * （导航 App 不会刚回桌面就再发一次），于是放行、让用户留在主页。
     *
     * 没有这段逻辑会有一个硬伤：回弹后 20 秒保活窗口仍在、计数也没用完，用户按 HOME 想回主页
     * 会被**反复弹回导航**，得按满 3 次并等窗口过期才行 —— 等于「老六当桌面」时按 HOME 回不了主页。
     */
    private static final long NAV_GUARD_GRACE_MS = 8000L;

    /**
     * 保活状态全部用 **static** 存：车机 ROM 未必走 onNewIntent —— 它可能把本 Activity 整个
     * 重建再用 HOME Intent 启动一次，那时实例字段全被重置、onNewIntent 也不会触发。
     * 静态字段跨实例存活，配合 onCreate 里读 getIntent() 就能把这条路径也覆盖住。
     */
    private static Intent navGuardIntent;           // 最近一次调起的导航 App 启动 Intent；null = 保活未开启
    private static long navGuardUntil = 0L;         // 保活窗口截止（SystemClock.uptimeMillis()）
    private static int navGuardHits = 0;            // 已回弹次数
    private static boolean navBounceLeft = false;   // 回弹后确已退到后台（onPause 置位 → 700ms 兜底不必执行）
    private static long navGuardBounceAt = 0L;      // 上次回弹时刻（uptimeMillis）→ 用于识别「用户按的 HOME」
    /** 本次恢复到前台是否为「系统 HOME 请求」。onNewIntent / onCreate 记录 → onResume 消费。 */
    private boolean pendingHomeIntent = false;

    /** 每秒推进一次媒体进度条；每 10 秒兜底重建一次会话（防止系统不回调解绑）。 */
    private int mediaTickCount = 0;
    private final Runnable mediaTick = new Runnable() {
        @Override
        public void run() {
            try {
                MediaHub.get(MainActivity.this).tick();
                if (++mediaTickCount % 10 == 0) {
                    MediaHub.get(MainActivity.this).refresh();
                }
            } catch (Throwable ignored) {
            }
            if (ui != null) {
                ui.postDelayed(this, 1000);
            }
        }
    };

    /** 已知车机 App 映射：包名 -> {key, 显示名, 图标, 类型} */
    private static final String[][] APP_MAP = {
            {"com.kugou.android",        "kugou",    "酷狗音乐",      "\uD83C\uDFB5", "music"},
            {"com.kugou.android.car",    "kugou",    "酷狗音乐(车机)", "\uD83C\uDFB5", "music"},
            {"com.tencent.qqmusic",      "qq",       "QQ音乐",        "\uD83C\uDFB6", "music"},
            {"com.tencent.qqmusiccar",   "qq",       "QQ音乐(车机)",   "\uD83C\uDFB6", "music"},
            {"cn.kuwo.player",           "kuwo",     "酷我音乐",      "\uD83C\uDFBC", "music"},
            {"com.netease.cloudmusic",   "netease",  "网易云音乐",     "\uD83C\uDFA7", "music"},
            {"com.ximalaya.ting.android", "ximalaya", "喜马拉雅",      "\uD83D\uDCFB", "music"},
            {"com.autonavi.amap",        "amap",     "高德地图",      "\uD83E\uDDED", "nav"},
            {"com.autonavi.amapauto",    "amap",     "高德地图(车机)", "\uD83E\uDDED", "nav"},
            {"com.baidu.BaiduMap",       "baidu",    "百度地图",      "\uD83D\uDDFA", "nav"},
            {"com.baidu.navi",           "baidu",    "百度导航",      "\uD83D\uDDFA", "nav"},
            {"com.tencent.map",          "tencent",  "腾讯地图",      "\uD83D\uDEA9", "nav"},
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();
        appCtx = getApplicationContext();
        L6Log.init(this);
        // 车机信号采集：能力清单 / 每条变化 / 心跳 / 结束，都走同一条回推。
        // 变化里的 from/to 是车机属性原文（可能含引号与中文），同样必须 Base64 包一层。
        SignalCapture.setListener(json -> {
            // v2.0.0：设置页「信号采集」卡片的状态在每次重建时**现读**，这里只负责触发重建
            if (natShell != null) natShell.refreshSet();
        });
        L6Log.i("L6", "应用启动");

        // ★ v2.0.0：界面全部原生（NatShell 手写 View），**不再有 WebView、也不再内置 HTML 页面**。
        //   rootView 只作容器：原生 Shell 挂在这一层。
        rootView = new android.widget.FrameLayout(this);
        setContentView(rootView);
        ui = new android.os.Handler(android.os.Looper.getMainLooper());

        api = new Bridge();

        if (Build.VERSION.SDK_INT <= 28) {
            try {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            } catch (Throwable ignored) {
            }
        }

        // v2.0.0：只有原生界面一条路（没有 WebView、没有 HTML 兜底页）
        startNativeUi();

        // 启动本 Activity 的那个 Intent 若是「系统 HOME 请求」，也按 HOME 请求处理。
        // 覆盖这条路径：车机 ROM 没走 onNewIntent，而是把本 Activity 重建后用 HOME Intent
        // 启动 —— 此时保活状态（static）还在，靠这里认回来，onResume 就能把导航弹回去。
        pendingHomeIntent = isHomeRequest(getIntent());

        // 系统实时数据（媒体会话 / 导航通知）统一走 window.L6SysEvent
        // ★ 同时喂一份「原始 JSON」给原生界面（v2.0.0 无 window，这条路必须并行走）
        SysHub.setRaw(json -> natIngest(json));
        // v2.0.0：没有页面了 —— SysHub 的「JS 片段」出口整体不再需要，
        //   原生数据只走 setRaw(json) → natIngest(json) 这一条（见上面那行）。

        // v1.5.20：高德广播探针 —— 探测该车机 ROM 是否把 AUTONAVI_STANDARD_BROADCAST_SEND 转发给第三方。
        // 隐式广播在 Android 8+ 不能静态注册，只能在这里动态注册（本应用是默认桌面，生命周期≈常驻）。
        try { NavBcastProbe.start(this); } catch (Throwable ignored) {}

        // 启动 5s 后静默检查一次 OTA（不打扰首屏）
        ui.postDelayed(() -> {
            try { checkOta(); } catch (Throwable ignored) {}
        }, 5000);

        // 媒体进度每秒推进（含 10s 兜底重建会话）
        ui.postDelayed(mediaTick, 1200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (natShell != null) {
            natShell.start();      // 原生界面：每秒拉一次系统数据
        }
        // 系统 HOME 请求落到本界面（导航 App 回桌面导致）→ 在这里把导航弹回前台。
        // 判据用 pendingHomeIntent（而非直接看 onNewIntent）：onNewIntent 与
        // 「onCreate 读 getIntent()」两条路径都会置它，后者覆盖 ROM 重建实例的情况。
        final boolean bounced = pendingHomeIntent && tryBounceBackToNav();
        pendingHomeIntent = false;
        if (bounced) {
            // 导航 App 回桌面把本界面顶了上来，我们正在把它弹回去：
            // ① 不能撤悬浮「返回」按钮 —— 用户随后要靠它回主页（onPause 不会再挂，因为 navLaunching 已消费）
            // ② 不向页面推「已回主页」（L6NavReturn），否则主页状态会闪一下
            try { FloatNav.show(getApplicationContext(), "返回"); } catch (Throwable ignored) {}
        } else {
            // 回到本界面（用户按 HOME / 点悬浮返回 / 从导航或设置页返回）→ 撤销悬浮返回按钮；
            // 若确在导航中，按钮由 onPause(navLaunching) 负责挂上，不会丢失。
            try { FloatNav.hide(this); } catch (Throwable ignored) {}
        }
        // 用户可能刚从「通知使用权」设置页返回，这里重新读一次权限并刷新
        try {
            boolean ok = MediaHub.hasAccess(this);
            SysHub.pushAccess(ok);
            MediaHub.get(this).refresh();
        } catch (Throwable ignored) {
        }
        // 用户可能刚从「允许安装未知应用」里授权，回来把没装完的 OTA 包续上
        try {
            if (Ota.hasPendingInstall()) {
                Ota.resumeInstallIfPending(this, this::forwardOta);
            }
        } catch (Throwable ignored) {
        }
        // 回到本界面（系统 HOME 键 / 从授权页返回）→ 通知页面复位迷你导航卡片，
        // 并把最新悬浮窗授权状态刷到设置页（悬浮窗权限已并入「系统权限」卡片）。
        // 被 HOME 请求顶回并已回弹导航时跳过：用户并不在主页，推了只会让状态闪一下。
        // 回到本界面 → 重建主页三栏（复位迷你导航卡片 / 重算栏可见性）
        if (!bounced) {
            try {
                if (natShell != null) natShell.refreshHome();
            } catch (Throwable ignored) {
            }
        }
        // 悬浮窗授权 / 默认桌面 可能刚在系统「应用管理 / 默认应用」页改过
        // → 重建设置页（卡片状态在 build 时现读，重建即刷新）
        try {
            if (natShell != null) natShell.refreshSet();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (natShell != null) {
            natShell.stop();       // 界面不可见就别再轮询（车机上省电）
        }
        // 仅当确系「调起导航 App 后本界面被盖住」才挂返回按钮；
        // 导航 App 没完全启动 / 失败导致本界面仍在前台时，onPause 不会触发，按钮也就不出现。
        // 确已离开本界面 → 通知回弹逻辑「已经成功退到后台」，700ms 兜底不必再抢前台
        if (navGuardIntent != null) {
            navBounceLeft = true;
        }
        if (navLaunching) {
            navLaunching = false;
            boolean ok = FloatNav.show(getApplicationContext(), "返回");
            if (!ok) {
                toast("未授予悬浮窗权限：返回主页请到设置页「系统权限 → 悬浮窗」授权");
            }
        }
    }

    /* ---------------- 导航保活：把落到本界面的 HOME 请求弹回导航 ---------------- */

    /**
     * 单任务模式下，系统投递给已存在实例的新 Intent 走这里（本 Activity 是 singleTask）。
     *
     * 关键用途：**区分两种「回到本界面」的意图** ——
     *   ① 系统 HOME 请求（ACTION_MAIN + CATEGORY_HOME）：导航 App 主动回桌面导致，
     *      本应用既然是默认桌面就会被顶上来 → 要弹回导航；
     *   ② 用户点悬浮「返回」按钮 / 外部调起本界面（ACTION_MAIN + CATEGORY_LAUNCHER）：
     *      用户真的想待在主页 → 撤销保活，绝不能弹走。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            setIntent(intent);
        } catch (Throwable ignored) {
        }
        if (isHomeRequest(intent)) {
            // 只记标记，真正的回弹统一放到 onResume 执行 —— 那时窗口栈已稳定，
            // 且能与「实例被重建」路径（onCreate 也是记这个标记）共用同一个出口。
            pendingHomeIntent = true;
        } else {
            // 非 HOME 请求 = 用户主动回主页（或本应用被外部主动调起）→ 撤销保活
            pendingHomeIntent = false;
            disarmNavGuard();
        }
    }

    /** 是否为系统 HOME 请求（区别于 CATEGORY_LAUNCHER 的普通调起）。 */
    private boolean isHomeRequest(Intent i) {
        try {
            return i != null
                    && Intent.ACTION_MAIN.equals(i.getAction())
                    && i.hasCategory(Intent.CATEGORY_HOME);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 刚成功调起导航 App → 记下它的启动 Intent 并开启保活窗口。 */
    private void armNavGuard(Intent nav) {
        try {
            navGuardIntent = new Intent(nav);
            navGuardUntil = android.os.SystemClock.uptimeMillis() + NAV_GUARD_MS;
            navGuardHits = 0;
            navBounceLeft = false;
            navGuardBounceAt = 0L;      // 新一次导航启动：清掉上一轮的回弹时刻，避免误判为「用户按键」
        } catch (Throwable t) {
            navGuardIntent = null;
        }
    }

    /** 撤销保活（用户已明确回主页 / 启动失败 / 换目标 / 窗口过期 / 反复回弹后放手）。 */
    private void disarmNavGuard() {
        navGuardIntent = null;
        navGuardUntil = 0L;
        navGuardHits = 0;
        navBounceLeft = false;
    }

    /**
     * HOME 请求落到本界面时，把导航 App 再推回前台。
     *
     * 先试最轻的 {@link Activity#moveTaskToBack}：不新建实例、也不重跑导航 App 的启动逻辑，
     * 因此**不会再次触发它发 HOME**（这一点很关键 —— 重新 startActivity 会重跑启动流程，
     * 容易顶成闪屏循环）。700ms 后若本界面仍在前台（onPause 没来），才退化为显式调起导航。
     *
     * @return true 表示已接手这次 HOME 请求（调用方据此跳过「已回主页」相关回推）
     */
    private boolean tryBounceBackToNav() {
        try {
            if (navGuardIntent == null) {
                return false;
            }
            final long now = android.os.SystemClock.uptimeMillis();
            if (now > navGuardUntil) {
                disarmNavGuard();       // 保活窗口已过：此时按 HOME 就该停在主页
                return false;
            }
            // 刚回弹过又立刻收到 HOME → 判定为**用户自己按的**（导航 App 不会刚被推回去就再发一次），
            // 于是放行并撤销保活，让用户留在主页。
            // 没有这一段会有硬伤：回弹后窗口仍在、计数没用完 → 用户按 HOME 想回主页会被反复弹回导航，
            // 得按满 3 次并等 20 秒窗口过期才行，等于「老六当桌面时按 HOME 回不了主页」。
            if (navGuardHits >= 1 && now - navGuardBounceAt < NAV_GUARD_GRACE_MS) {
                android.util.Log.i("L6Nav", "回弹后 " + (now - navGuardBounceAt)
                        + "ms 再次收到 HOME → 判为用户按键，留在主页");
                L6Log.i("L6Nav", "回弹后 " + (now - navGuardBounceAt)
                        + "ms 再次收到 HOME → 判为用户按键，留在主页");
                disarmNavGuard();
                toast("已停留在主页");
                return false;
            }
            if (navGuardHits >= NAV_GUARD_MAX) {
                disarmNavGuard();       // 反复回弹 → 放手，避免和导航 App 互相顶成闪屏
                return false;
            }
            navGuardHits++;
            navBounceLeft = false;
            navGuardBounceAt = now;     // 记下回弹时刻 → 下一次 HOME 据此识别「用户按键」
            android.util.Log.i("L6Nav", "HOME 请求被拦截，回弹导航（第 " + navGuardHits + " 次）");
            L6Log.i("L6Nav", "HOME 请求被拦截，回弹导航（第 " + navGuardHits + " 次）");
            toast("已切回导航；如需回到主页请再按一次 HOME");
            final Intent nav = new Intent(navGuardIntent);
            moveTaskToBack(true);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (navGuardIntent == null || navBounceLeft) {
                    return;             // 已成功退到后台 → 无需兜底
                }
                try {
                    nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(nav);  // 部分 ROM 的桌面窗口压不走，只能显式调起导航
                } catch (Throwable ignored) {
                }
            }, 700L);
            return true;
        } catch (Throwable t) {
            L6Log.e("L6Nav", "回弹处理异常: " + t);
            return false;
        }
    }

    /**
     * 用户一碰本界面就撤销保活 —— 这是「人要留在这里」的最强信号，
     * 避免 700ms 兜底把正在操作的用户莫名弹去导航。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (navGuardIntent != null && ev != null
                && ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            disarmNavGuard();
        }
        return super.dispatchTouchEvent(ev);
    }


    /**
     * 车机日夜模式切换（系统 UI_MODE_NIGHT）→ 立即回推页面切主题。
     * 依赖 manifest 里 activity 的 configChanges 含 uiMode（已声明），否则会重建 Activity。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (natShell != null) {
            natShell.onScreenChanged();   // u 缩放基准 / 壁纸解码档位 / 页高都要重算
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (ui != null) {
                ui.removeCallbacks(mediaTick);
            }
        } catch (Throwable ignored) {
        }
        try {
            MediaHub.get(this).release();
        } catch (Throwable ignored) {
        }
        try { NavBcastProbe.stop(this); } catch (Throwable ignored) {}
        if (natShell != null) {
            natShell.stop();
        }
        SysHub.setEmitter(null);
        SysHub.setRaw(null);
        super.onDestroy();
    }

    /* ---------------- 沉浸式全屏（车机常驻） ---------------- */

    private void enterImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    /* ---------------- 返回键：先回车机主页，再退出 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 设置页按返回回主页；主页按返回走「回车机主页 / 压后台」策略（见 leaveOrStay）
            if (natShell != null && natShell.page() != NatShell.PAGE_HOME) {
                natShell.gotoPage(NatShell.PAGE_HOME);
                return true;
            }
            leaveOrStay();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 主页返回键提示节流：长按 BACK 会重复触发 onKeyDown，不节流会排出一长串 Toast。 */
    private long lastLeaveToastAt = 0L;

    /**
     * 在主页（idx==0）按下返回键时的去向。
     * ⚠️ 绝不能用 finish()：本应用常被设为车机默认桌面，Activity 一销毁，系统会立刻把
     *    桌面（还是本应用）重新拉起来 —— 表现就是「按返回键后界面回到初始状态、壁纸与
     *    已选源全部丢失」（用户反馈过）。这里只压后台 / 原地不动，Activity 与 WebView
     *    状态原样保留。
     */
    private void leaveOrStay() {
        disarmNavGuard();   // 用户主动按返回键 = 明确要留在主页 → 撤销导航保活
        try {
            if (isDefaultHomeOf(this)) {
                // 自己就是桌面：压后台会把桌面也压走，保持不动；顺便去重，避免按键重复刷屏
                long now = System.currentTimeMillis();
                if (now - lastLeaveToastAt > 1500L) {
                    lastLeaveToastAt = now;
                    toast("已在主页");
                }
            } else {
                moveTaskToBack(true);         // 非桌面：退回车机上一层
            }
        } catch (Throwable e) {
            try {
                moveTaskToBack(true);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_WALL) {
            // 原生版选壁纸：系统选择器返回的 Uri → 读字节 → 按类型落盘 → 进壁纸列表并选中
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importWallUri(data.getData(), pickWallKind);
            }
            return;
        }
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        result = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                        result = new Uri[]{data.getClipData().getItemAt(0).getUri()};
                    }
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 轻提示。★ 必须 public：它同时实现 nat.Host#toast —— 接口方法不许比实现更宽 */
    @Override
    public void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * 静态判据：系统当前默认桌面（HOME）是否为本应用。
     * 供 onResume 回推 与 Bridge.isDefaultHome() 共用
     * —— Bridge 是内嵌类，外层 onResume 调不到它的实例方法，所以逻辑放这里。
     */
    static boolean isDefaultHomeOf(android.content.Context ctx) {
        try {
            String self = ctx.getPackageName();
            // ① Android 10+：HOME 由 RoleManager 统一管理，isRoleHeld 才是权威判据。
            //    车机 ROM 上 resolveActivity 经常解析不到（明明已设成默认桌面却报「未设置」），
            //    所以不能只靠 ②。
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    android.app.role.RoleManager rm = (android.app.role.RoleManager)
                            ctx.getSystemService(android.content.Context.ROLE_SERVICE);
                    if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                            && rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            // ② 传统判据：ACTION_MAIN + CATEGORY_HOME 解析出的默认 Activity 是否是自己
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = ctx.getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null && self.equals(r.activityInfo.packageName)) {
                return true;
            }
            // ③ 兜底：系统里只有本应用声明了 HOME（没有别的桌面可选 → 必然是我们）
            java.util.List<android.content.pm.ResolveInfo> rs = ctx.getPackageManager()
                    .queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (rs != null) {
                boolean mine = false;
                int others = 0;
                for (android.content.pm.ResolveInfo x : rs) {
                    if (x == null || x.activityInfo == null) {
                        continue;
                    }
                    if (self.equals(x.activityInfo.packageName)) {
                        mine = true;
                    } else {
                        others++;
                    }
                }
                if (mine && others == 0) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 静态判据：系统当前是否处于深色（夜间）模式。
     * 车机日/夜切换会回调 onConfigurationChanged(uiMode)，与 onResume 一并向页面回推；
     * 与 Bridge.isNightMode() 共用（Bridge 是内嵌类，外层调不到它的实例方法）。
     */
    static boolean isNightModeOf(android.content.Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable e) {
            return true;                       // 车机默认深色主题
        }
    }

    /* ==================== 原生桥接 ==================== */

    public class Bridge {

        /**
         * 收集车机「可启动」的应用（包名 + 显示名）。
         *
         * ⚠️ 必须**优先**用 `queryIntentActivities(MAIN + CATEGORY_LAUNCHER)`：本应用的
         *    AndroidManifest `<queries>` 里**显式声明**了 MAIN/LAUNCHER，这条路径一定能越过
         *    Android 11+ 的包可见性限制。而 `getInstalledApplications` /
         *    `getLaunchIntentForPackage` 依赖 `QUERY_ALL_PACKAGES` 权限 —— **部分车机 ROM 会
         *    忽略该权限**，枚举结果就会为空或只剩自己，表现为「应用列表看不到已安装的应用」。
         *    下面的回退分支只服务极个别连 queryIntentActivities 都受限的 ROM。
         *
         * ⚠️ 现场排查：`adb logcat -s L6Apps` —— 会打出枚举到的应用个数与异常原因
         *    （以前这里是 `catch (Throwable ignored)`，错误被静默吞掉，无法定位）。
         */
        private java.util.List<String[]> collectLaunchable(PackageManager pm) {
            java.util.List<String[]> out = new java.util.ArrayList<>();
            Set<String> seen = new HashSet<>();
            try {
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                if (ris != null) {
                    for (ResolveInfo ri : ris) {
                        if (ri == null || ri.activityInfo == null
                                || ri.activityInfo.applicationInfo == null) {
                            continue;
                        }
                        String pkg = ri.activityInfo.applicationInfo.packageName;
                        if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) {
                            continue;
                        }
                        CharSequence cs = ri.loadLabel(pm);
                        out.add(new String[]{pkg, cs == null ? pkg : cs.toString().trim()});
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("L6Apps", "queryIntentActivities 枚举失败: " + t);
            }
            if (out.isEmpty()) {
                try {
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        String pkg = ai.packageName;
                        if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) {
                            continue;
                        }
                        if (pm.getLaunchIntentForPackage(pkg) == null) {
                            continue;
                        }
                        CharSequence cs = ai.loadLabel(pm);
                        out.add(new String[]{pkg, cs == null ? pkg : cs.toString().trim()});
                    }
                } catch (Throwable t) {
                    android.util.Log.w("L6Apps", "getInstalledApplications 枚举失败: " + t);
                }
            }
            android.util.Log.i("L6Apps", "可启动应用枚举结果: " + out.size() + " 个");
            return out;
        }

        /**
         * 取某个包的启动 Intent，带**显式 Intent 兜底**。
         *
         * `getLaunchIntentForPackage` 在包可见性受限时会返回 null（部分车机 ROM 忽略
         * `QUERY_ALL_PACKAGES`）；此时用 `queryIntentActivities` 查出它的 launcher Activity
         * 拼一个**显式** Intent —— 显式 Intent（setClassName）不受 Android 11+ 包可见性限制，
         * 车机上一定拉得起来。
         */
        private Intent launchIntentOf(PackageManager pm, String pkg) {
            try {
                Intent li = pm.getLaunchIntentForPackage(pkg);
                if (li != null) {
                    return li;
                }
            } catch (Throwable ignored) {
            }
            try {
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                main.setPackage(pkg);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                if (ris != null && !ris.isEmpty()) {
                    ResolveInfo ri = ris.get(0);
                    if (ri != null && ri.activityInfo != null) {
                        Intent c = new Intent(Intent.ACTION_MAIN);
                        c.addCategory(Intent.CATEGORY_LAUNCHER);
                        c.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        return c;
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("L6Apps", "launchIntentOf(" + pkg + ") 失败: " + t);
            }
            return null;
        }

        /** 枚举车机已安装的可启动 App，识别音乐 / 导航类，返回 JSON 数组字符串。 */
        @JavascriptInterface
        public String getInstalledAppsJson() {
            JSONArray out = new JSONArray();
            Set<String> seenKeys = new HashSet<>();
            try {
                PackageManager pm = getPackageManager();
                for (String[] entry : collectLaunchable(pm)) {
                    String pkg = entry[0];
                    String label = entry[1];
                    String key = null, name = null, icon = null, type = null;

                    for (String[] m : APP_MAP) {
                        if (m[0].equalsIgnoreCase(pkg)) {
                            key = m[1];
                            name = m[2];
                            icon = m[3];
                            type = m[4];
                            break;
                        }
                    }
                    if (key == null) {
                        type = guessType(label, pkg);
                        key = pkg;
                        name = label;
                        icon = "music".equals(type) ? "\uD83C\uDFB5"
                                : "nav".equals(type) ? "\uD83E\uDDED" : "\uD83D\uDCE6";
                    }
                    if (!"music".equals(type) && !"nav".equals(type)) {
                        continue;   // 页面只会用到 music / nav 两类
                    }
                    if (!seenKeys.add(key)) {
                        continue;   // 同一 key（如酷狗+酷狗车机）只保留一个
                    }
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("key", key);
                    o.put("name", name);
                    o.put("icon", icon);
                    o.put("type", type);
                    out.put(o);
                }
            } catch (Throwable ignored) {
            }
            return out.toString();
        }

        /**
         * 全部可启动 App（不过滤类型），供「打开应用列表」抽屉拉起任意已装应用。
         *
         * ⚠️⚠️ `@JavascriptInterface` **绝对不能漏**：Android 4.2+ 起，由 addJavascriptInterface
         *    暴露给页面的对象，**只有标注了该注解的方法才允许被 JS 调用**；未标注的在 JS 侧
         *    等同「方法不存在」，调用即抛错。
         *
         *     本方法自 v1.4.6 引入起就一直缺这个注解，而 SHIM 是 `R.getAllAppsJson()` 调的 →
         *     JS 侧抛错 → SHIM 的 `catch(e){cb([])}` **静默**转成空数组 → 页面显示
         *     「未读到已安装的应用」。症状极具迷惑性：编译通过、运行不崩、jsdom 冒烟全绿
         *     （冒烟里的桥是 JS 桩，永远可用），只有真机上点「📋 打开应用列表」才会暴露。
         *
         *     ⚠️ 防回归：`tools/bridge-contract-check.js` 会静态比对「SHIM 里 R.xxx() 调用的
         *     方法名」与「带 @JavascriptInterface 的方法名」，缺失即报错。
         */
        @JavascriptInterface
        public String getAllAppsJson() {
            JSONArray out = new JSONArray();
            Set<String> seenKeys = new HashSet<>();
            try {
                PackageManager pm = getPackageManager();
                for (String[] entry : collectLaunchable(pm)) {
                    String pkg = entry[0];
                    String label = entry[1];
                    String key = null, name = null, icon = null, type = null;
                    for (String[] m : APP_MAP) {
                        if (m[0].equalsIgnoreCase(pkg)) {
                            key = m[1]; name = m[2]; icon = m[3]; type = m[4]; break;
                        }
                    }
                    if (key == null) {
                        type = guessType(label, pkg);
                        key = pkg; name = label;
                        icon = "music".equals(type) ? "\uD83C\uDFB5"
                                : "nav".equals(type) ? "\uD83E\uDDED" : "\uD83D\uDCE6";
                    }
                    if (!seenKeys.add(key)) continue;   // 同一 key 只保留一个
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg); o.put("key", key); o.put("name", name);
                    o.put("icon", icon); o.put("type", type);
                    out.put(o);
                }
            } catch (Throwable ignored) {
            }
            return out.toString();
        }

        /** 应用图标缓存（按包名）——抽屉里有几十个 App，缓存后避免重复编码。 */
        private final java.util.HashMap<String, String> iconCache = new java.util.HashMap<>();

        /**
         * 取一个应用的真实图标，返回 "data:image/png;base64,..."（失败返回空串）。
         * 入参可以是包名，也可以是白名单短 key（如 kugou），会先解析成包名再取。
         * 页面原来只能拿到 emoji（🎵/🧭/📦），所以应用列表和「当前源」都没有真图标 —— 本桥补上。
         */
        @JavascriptInterface
        public String getAppIcon(String pkgOrKey) {
            try {
                if (pkgOrKey == null || pkgOrKey.isEmpty()) {
                    return "";
                }
                String pkg = pkgOrKey;
                if (pkg.indexOf('.') <= 0) {                  // 短 key → 包名
                    for (String[] m : APP_MAP) {
                        if (m[1].equals(pkgOrKey)) {
                            pkg = m[0];
                            break;
                        }
                    }
                }
                String hit = iconCache.get(pkg);
                if (hit != null) {
                    return hit;
                }
                android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(pkg);
                int size = Math.max(48, (int) (40 * getResources().getDisplayMetrics().density));
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(cv);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                String url = "data:image/png;base64," + android.util.Base64
                        .encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                iconCache.put(pkg, url);
                return url;
            } catch (Throwable t) {
                return "";
            }
        }

        /** 低置信度归类：仅按名称/包名关键词猜音乐或导航。 */
        private String guessType(String label, String pkg) {
            String z = (label + " " + pkg).toLowerCase(Locale.ROOT);
            // 音乐类：覆盖常见音乐/收音/听书/播客关键词与包名片段（白名单之外的音乐 App 也认得出）
            if (z.contains("\u97f3\u4e50") || z.contains("music") || z.contains("radio")
                    || z.contains("\u542c\u4e66") || z.contains("song") || z.contains("fm") || z.contains("\u7535\u53f0")
                    || z.contains("\u871c\u67d0") || z.contains("xmly") || z.contains("kugou")
                    || z.contains("qqmusic") || z.contains("netease") || z.contains("spotify")
                    || z.contains("podcast") || z.contains("\u64ad\u5ba2")
                    || z.contains("\u871c\u8702") || z.contains("\u8702\u9e1f") || z.contains("ximalaya")) {
                return "music";
            }
            // 导航类：覆盖常见地图/导航关键词与包名片段（车机版/定制版导航也认得出）
            if (z.contains("\u5730\u56fe") || z.contains("\u5bfc\u822a") || z.contains("map")
                    || z.contains("navi") || z.contains("gps") || z.contains("amap")
                    || z.contains("baidumap") || z.contains("tencentmap") || z.contains("\u9ad8\u5fb7")
                    || z.contains("\u767e\u5ea6\u5730\u56fe") || z.contains("\u817e\u8baf\u5730\u56fe")) {
                return "nav";
            }
            return "other";
        }

        /** 上传的壁纸落盘：Download/L6/壁纸/{静态|动态}/文件名。 */
        @JavascriptInterface
        public void saveWallpaper(String type, String base64, String filename) {
            String kind = "dynamic".equals(type) ? "\u52a8\u6001" : "\u9759\u6001";
            if (filename == null || filename.trim().isEmpty()) {
                filename = "wall_" + System.currentTimeMillis() + ".bin";
            }
            String data = base64 == null ? "" : base64;
            int comma = data.indexOf(',');
            if (data.startsWith("data:") && comma > 0) {
                data = data.substring(comma + 1);
            }
            byte[] bytes;
            try {
                bytes = Base64.decode(data, Base64.DEFAULT);
            } catch (Throwable e) {
                toast("壁纸保存失败：数据解析错误");
                return;
            }
            if (bytes.length == 0) {
                toast("壁纸保存失败：内容为空");
                return;
            }

            // 首选公共目录 Download/L6/壁纸/<类型>（API 29 及以下需存储权限）
            File dir = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "L6/\u58c1\u7eb8"), kind);
            if (writeTo(dir, filename, bytes)) {
                toast("已保存 → Download/L6/\u58c1\u7eb8/" + kind + "/" + filename);
                return;
            }
            // 兜底：应用专属外置目录（API 30+ 无公共目录写入权限时）
            File alt = new File(getExternalFilesDir(null), "\u58c1\u7eb8/" + kind);
            if (writeTo(alt, filename, bytes)) {
                toast("已保存 → " + alt.getAbsolutePath() + "/" + filename);
                return;
            }
            toast("壁纸保存失败：无写入权限");
        }

        private boolean writeTo(File dir, String filename, byte[] bytes) {
            OutputStream os = null;
            try {
                if (dir == null) {
                    return false;
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    return false;
                }
                File f = new File(dir, filename);
                os = new FileOutputStream(f);
                os.write(bytes);
                os.flush();
                return true;
            } catch (Throwable e) {
                return false;
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /**
         * 调起设置里选中的导航 App。key 有两种形态：
         *   1) APP_MAP 白名单里的短 key（如 amap / baidu）
         *   2) 动态识别出来的应用，其 key 就是**包名**（如 com.baidu.naviauto）
         * 两种都要能启动；都落空时兜底取「第一个已装导航 App」。
         * 只认白名单会导致车机上装了非白名单导航（车机版/定制版）时报「未找到可启动的导航 App」。
         */
        @JavascriptInterface
        public void launchApp(String key) {
            disarmNavGuard();   // 每次重新发起导航都先重置保活，避免回弹复用到上一次的目标
            try {
                if (key == null) {
                    key = "";
                }
                PackageManager pm = getPackageManager();
                Intent i = null;

                // ① 白名单：短 key → 包名
                String target = null;
                for (String[] m : APP_MAP) {
                    if (m[1].equals(key)) {
                        target = m[0];
                        break;
                    }
                }
                if (target != null) {
                    i = pm.getLaunchIntentForPackage(target);
                }
                // ② 动态识别项：key 本身就是包名
                if (i == null && key.indexOf('.') > 0) {
                    i = pm.getLaunchIntentForPackage(key);
                }
                // ③ 兜底（含「系统默认」）：取第一个已装导航 App
                if (i == null) {
                    i = firstNavIntent();
                }
                if (i == null) {
                    toast("未找到可启动的导航 App（车机未安装导航应用）");
                    return;
                }
                // 防呆：目标就是本应用时绝不能启动 —— 那会让界面「闪一下就回到老六中控」，
                // 看起来像「导航打开后自动返回」（历史遗留的 navApp 值可能指向本应用包名）。
                if (isSelfIntent(i)) {
                    toast("导航源不能是「老六中控」自己，请在设置里重新选择车机已装的导航 App");
                    navLaunching = false;
                    return;
                }
                // 地图/导航是直接全屏盖住本界面的外部 App。返回主页有两条路：
                //   ① 用户把本应用设为默认桌面后，按 HOME 键即回本界面（见「系统权限」卡片的「设为默认桌面」）；
                //   ② 多数车机 ROM 锁死系统桌面、不让第三方应用设为默认桌面，此时挂一个
                //      左下角「🏠 返回」悬浮按钮（底部、非顶部、可拖动避让），点击即把本应用拉回前台。
                // 悬浮按钮需 SYSTEM_ALERT_WINDOW 授权，未授权则降级提示、不阻塞导航启动。
                // 返回按钮「不在 launchApp 里直接挂」，而是标记 navLaunching，等本 Activity 真正
                // onPause（确证导航 App 已接管前台）时再挂。这样导航 App 没完全启动 / 启动失败、
                // 本界面没被盖住时，按钮不会出现；即便出现，点击 hide+bringToFront 也一定能回本界面。
                navLaunching = true;
                String l6pkg = (target != null) ? target : (key.indexOf('.') > 0 ? key : "兜底导航App");
                L6Log.i("L6Nav", "调起导航: " + l6pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                // 记下导航 App 的启动 Intent 并开启保活窗口：导航启动完成后若发 HOME 请求回桌面，
                // 桌面（本应用）会被顶上来 —— 此时由 onNewIntent 把它弹回导航（见 tryBounceBackToNav）。
                armNavGuard(i);
            } catch (Throwable e) {
                navLaunching = false;   // 启动抛异常：本次不算「去导航」，onPause 不会误挂按钮
                toast("启动导航失败：" + e.getMessage());
                L6Log.e("L6Nav", "启动导航失败: " + e.getMessage());
            }
        }

        /**
         * 音乐源「启动 / 唤醒」：用户主动点设置页按钮才前台拉起选中的音乐 App，
         * 让它成为系统活跃媒体会话（从而被本应用接管播放 / 上下曲）。
         * 与 launchApp(导航) 不同：① 不挂悬浮返回按钮（音乐走后台控制，v1.4.2 决定）；
         * ② 仅针对音乐类（白名单 music 类型 + key 即包名兜底），本地源 usb/bt 直接忽略。
         */
        @JavascriptInterface
        public void launchMusic(String key) {
            try {
                if (key == null || key.isEmpty() || "usb".equals(key) || "bt".equals(key)) {
                    return;   // 本地源（U盘/蓝牙）无需启动
                }
                PackageManager pm = getPackageManager();
                Intent i = null;
                for (String[] m : APP_MAP) {
                    if (m[1].equals(key) && "music".equals(m[4])) {
                        i = pm.getLaunchIntentForPackage(m[0]);
                        break;
                    }
                }
                if (i == null && key.indexOf('.') > 0) {
                    i = pm.getLaunchIntentForPackage(key);   // 动态识别项：key 即包名
                }
                if (i == null) {
                    toast("未找到该音乐 App（车机未安装或未识别）");
                    return;
                }
                if (isSelfIntent(i)) {
                    toast("音乐源不能是「老六中控」自己，请在设置里重新选择音乐 App");
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);   // 用户主动「启动/唤醒」→ 拉起该 App 成为活跃媒体会话
                L6Log.i("L6Music", "启动音乐 " + key);
                bringSelfToFront();  // 唤醒后立刻把仪表盘拉回前台：音乐 App 接管播放但留在后台
            } catch (Throwable e) {
                toast("启动音乐 App 失败：" + e.getMessage());
                L6Log.e("L6Music", "启动音乐 App 失败: " + e.getMessage());
            }
        }

        /**
         * dock 应用快捷方式：按包名拉起任意已装应用（前台）。
         * 与 launchApp(导航) 不同：不挂悬浮返回按钮、不置 navLaunching（它不是导航）。
         */
        @JavascriptInterface
        public void launchPkg(String pkg) {
            try {
                if (pkg == null || pkg.isEmpty()) {
                    return;
                }
                PackageManager pm = getPackageManager();
                Intent i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) {
                    toast("未找到该应用（" + pkg + "）");
                    return;
                }
                if (isSelfIntent(i)) {
                    toast("不能从 dock 启动「老六中控」自己");
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable e) {
                toast("启动应用失败：" + e.getMessage());
            }
        }

        /** 把本 Activity（仪表盘）重新拉回前台，盖在刚启动的外部 App 之上。 */
        private void bringSelfToFront() {
            runOnUiThread(() -> {
                try {
                    Intent self = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (self != null) {
                        self.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(self);
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        /**
         * 该 Intent 是不是指向本应用自己。
         * 用于拦截「把自己当外部 App 启动」——那会表现为「界面闪一下就回到老六中控」，
         * 用户看到的现象就是「打开导航后自动返回」（历史遗留的 navApp / musicSrc 值可能指向本应用）。
         */
        private boolean isSelfIntent(Intent i) {
            try {
                if (i == null) {
                    return false;
                }
                if (i.getComponent() != null && i.getComponent().getPackageName() != null) {
                    return getPackageName().equals(i.getComponent().getPackageName());
                }
                if (i.getPackage() != null) {
                    return getPackageName().equals(i.getPackage());
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 找第一个可用的导航 App：**先白名单，再按名称/包名关键词动态识别**。
         * 之前只扫白名单，车机装了车机版/定制版导航（如 com.baidu.naviauto）时永远返回 null。
         */
        private Intent firstNavIntent() {
            Intent fallback = null;
            try {
                PackageManager pm = getPackageManager();
                for (String[] entry : collectLaunchable(pm)) {
                    String pkg = entry[0];
                    String label = entry[1];
                    if (pkg == null || pkg.equals(getPackageName())) {
                        continue;   // 兜底时也绝不把自己当导航 App
                    }
                    for (String[] m : APP_MAP) {
                        if (m[0].equalsIgnoreCase(pkg) && "nav".equals(m[4])) {
                            Intent li = launchIntentOf(pm, pkg);
                            if (li != null) {
                                return li;      // 白名单命中优先级最高
                            }
                        }
                    }
                    if (fallback == null && "nav".equals(guessType(label, pkg))) {
                        fallback = launchIntentOf(pm, pkg);   // 记下第一个动态识别出的导航 App
                    }
                }
            } catch (Throwable ignored) {
            }
            return fallback;
        }

        /* ==================== OTA 升级 ==================== */

        /** 返回当前 OTA 配置 JSON；页面用于调试展示。 */
        @JavascriptInterface
        public String getOtaConfig() {
            try {
                return Ota.loadConfig(MainActivity.this).toJson().toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        /** 检查更新（v2.0.0 起界面全原生：直接走原生入口，事件由 forwardOta 转发）。 */
        @JavascriptInterface
        public void checkOtaUpdate() {
            checkOta();
        }

        /** 下载并安装（同上：走原生入口）。 */
        @JavascriptInterface
        public void installOtaUpdate(String url, String sha256) {
            installOta(url, sha256);
        }

        /** 取消正在进行的下载（页面「取消下载」按钮）；结果以 kind=cancelled 事件回推。 */
        @JavascriptInterface
        public void cancelOtaUpdate() {
            L6Log.i("L6Ota", "取消下载");
            Ota.cancelDownload();
        }

        /* ==================== 主页模式：跳导航 App 悬浮窗权限页（v1.5.16） ==================== */

        /**
         * 打开指定 App 的「显示在其他应用上层（悬浮窗）」权限入口。
         *
         * 为什么只能做成「送用户去开关」：Android 没有任何公开 API 能把第三方 App 的画面
         * 装进我们的界面里。导航 App 想浮在本应用之上，必须由它自己的悬浮窗功能实现，
         * 所以这里只负责把用户送到那个开关。
         *   ① ACTION_MANAGE_OVERLAY_PERMISSION + package:（部分 ROM 直达该应用的开关页）
         *   ② 失败再退到「应用详情页」，用户自己点 权限 → 显示在其他应用上层
         */
        @JavascriptInterface
        public boolean openAppOverlaySettings(String pkg) {
            if (pkg == null || pkg.length() == 0) return false;
            try {
                Intent i = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                        Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                L6Log.i("L6Home", "打开悬浮窗权限页: " + pkg);
                return true;
            } catch (Throwable ignored) { }
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                L6Log.i("L6Home", "退到应用详情页: " + pkg);
                return true;
            } catch (Throwable t) {
                L6Log.w("L6Home", "打开悬浮窗设置失败: " + t);
                toast("无法打开该应用的权限页");
                return false;
            }
        }

        /* ==================== 悬浮窗权限 + 默认桌面 ==================== */

        /** 是否已授予悬浮窗权限（"显示在其他应用上层"）。未授权时不影响使用（用系统 HOME 返回）。 */
        @JavascriptInterface
        public boolean hasOverlayPermission() {
            return FloatNav.canDrawOverlay(MainActivity.this);
        }

        /** 跳系统悬浮窗授权页；授权后由页面回调 refresh 读取新状态。 */
        @JavascriptInterface
        public void openOverlaySettings() {
            FloatNav.openOverlaySettings(MainActivity.this);
        }

        /**
         * 把本应用设为车机默认桌面（HOME）。
         *
         * 用户明确要求「老六中控就是车机默认桌面」—— 返回主页靠它 + 导航保活（见 tryBounceBackToNav）。
         * 但车机 ROM 的「默认应用 → 主屏幕」入口往往藏得极深，因此这里四级降级，尽量一步到位：
         *   ① API 29+ RoleManager.createRequestRoleIntent(ROLE_HOME)：直接弹系统确认框
         *      「是否将老六中控设为主屏幕应用？」—— 最可靠；
         *   ② Settings.ACTION_HOME_SETTINGS：系统的「主屏幕应用」页；
         *   ③ Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS：默认应用总页；
         *   ④ 拉起 HOME 选择（部分老车机只有这个入口）。
         * 全限定类名 + try/catch + SDK_INT 守卫，与 isDefaultHomeOf 写法一致（避开 ART 类校验）。
         */
        @JavascriptInterface
        public void openHomeSettings() {
            L6Log.i("L6Home", "打开默认桌面设置");
            // ① Android 10+：RoleManager 直接申请 HOME 角色
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.app.role.RoleManager rm = (android.app.role.RoleManager)
                            getSystemService(android.content.Context.ROLE_SERVICE);
                    if (rm != null
                            && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                            && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                        Intent req = rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME);
                        req.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(req);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
            // ② 系统「主屏幕应用」设置页
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
            // ③ 系统「默认应用」总设置页
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
            // ④ 最后退化为拉起 HOME 选择
            try {
                Intent h = new Intent(Intent.ACTION_MAIN);
                h.addCategory(Intent.CATEGORY_HOME);
                h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(h);
            } catch (Throwable ignored) {
            }
        }

        /**
         * 返回车机「原桌面」（系统 Launcher）。
         * 与 openHomeSettings（跳设置页让用户改默认桌面）不同：这里直接切回原桌面。
         * 关键：显式挑一个<b>非本应用</b>的 HOME App 启动 —— 本应用若已被设为默认桌面，
         * 无脑 startActivity(CATEGORY_HOME) 会绕回自己，等于没反应。
         * 优先取包名含 launcher / .home 的桌面；若车机没有任何第三方桌面（本应用即唯一桌面），
         * 退化为 moveTaskToBack 把本界面压到后台，露出系统上一层。
         */
        @JavascriptInterface
        public void goHome() {
            try {
                PackageManager pm = getPackageManager();
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                java.util.List<android.content.pm.ResolveInfo> rs = pm.queryIntentActivities(home, 0);
                String self = getPackageName();
                android.content.pm.ResolveInfo pick = null;
                if (rs != null) {
                    for (android.content.pm.ResolveInfo r : rs) {
                        if (r.activityInfo == null) continue;
                        String p = r.activityInfo.packageName;
                        if (p.equals(self)) continue;                 // 跳过自己，保证回到「原桌面」
                        String lp = p.toLowerCase();
                        boolean isLauncher = lp.contains("launcher") || lp.contains(".home")
                                || lp.contains("homescreen") || lp.contains("desktop");
                        if (pick == null || isLauncher) pick = r;
                        if (isLauncher) break;                         // 命中真正的桌面就定下来
                    }
                }
                if (pick != null) {
                    home.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
                    startActivity(home);
                } else {
                    moveTaskToBack(true);                              // 车机没有独立桌面 → 压后台
                }
            } catch (Throwable e) {
                try { moveTaskToBack(true); } catch (Throwable ignored) {
                }
            }
        }

        /**
         * 本应用当前是否已是系统默认桌面（HOME）。
         * 设置页「系统权限」卡用它显示「默认桌面：已设置 / 未设置」——
         * 之前页面只写了静态「未设置」，没有任何读取逻辑，导致设为默认桌面后仍显示未设置。
         * 逻辑落在外层静态方法 isDefaultHomeOf()，本方法只做委托（Bridge 是内嵌类）。
         */
        @JavascriptInterface
        public boolean isDefaultHome() {
            return isDefaultHomeOf(MainActivity.this);
        }

        /**
         * 系统当前是否深色（夜间）模式。页面据此切换浅色 / 深色主题（跟随系统，不自行按时间判断）。
         * 逻辑落在外层静态方法 isNightModeOf()，本方法只做委托（Bridge 是内嵌类）。
         */
        @JavascriptInterface
        public boolean isNightMode() {
            return isNightModeOf(MainActivity.this);
        }

        /* ==================== 真实系统数据（音乐 / 导航） ==================== */

        /** 页面初始化时一次性拿：通知使用权 + 当前媒体状态。 */
        @JavascriptInterface
        public String getSysState() {
            JSONObject o = new JSONObject();
            try {
                o.put("notifyAccess", MediaHub.hasAccess(MainActivity.this));
                JSONObject m = MediaHub.get(MainActivity.this).snapshot();
                o.put("media", m == null ? new JSONObject() : m);
            } catch (Throwable ignored) {
            }
            return o.toString();
        }

        /** 是否已获得通知使用权（读系统媒体会话 / 导航通知的前提）。 */
        @JavascriptInterface
        public boolean hasNotifyAccess() {
            return MediaHub.hasAccess(MainActivity.this);
        }

        /** 跳到系统的「通知使用权」设置页让用户授权。 */
        @JavascriptInterface
        public void openNotifyAccess() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable e) {
                toast("无法打开通知使用权设置：" + e.getMessage());
            }
        }

        /** 重新枚举系统活跃媒体会话 / 重新读取导航状态。 */
        @JavascriptInterface
        public void refreshSys() {
            try {
                SysHub.pushAccess(MediaHub.hasAccess(MainActivity.this));
                MediaHub.get(MainActivity.this).refresh();
            } catch (Throwable ignored) {
            }
        }

        /** 真实控制系统播放器：toggle | play | pause | prev | next | seek:<毫秒>。 */
        @JavascriptInterface
        public void mediaControl(String action) {
            try {
                MediaHub.get(MainActivity.this).control(action);
            } catch (Throwable ignored) {
            }
        }

        /** 按歌名/歌手拉取真实歌词（带时间轴），结果通过 window.L6SysEvent 的 kind=lyrics 推回。 */
        @JavascriptInterface
        public void requestLyrics(String title, String artist) {
            try {
                Lyrics.fetch(MainActivity.this, title, artist, json -> {
                    SysHub.pushJson(json);
                });
            } catch (Throwable ignored) {
            }
        }

        /* ==================== 运行日志（收集 + 推送 Tasker） ==================== */

        /** 最近 n 条日志（JSON 数组，每条含 time/level/tag/msg）。供设置页弹层实时展示。 */
        @JavascriptInterface
        public String getLogJson(int n) {
            return L6Log.recentJson(n).toString();
        }

        /** 清空内存缓冲（不影响已落盘的历史日志文件）。 */
        @JavascriptInterface
        public void clearLog() {
            L6Log.clear();
        }

        /** 当天日志文件绝对路径（便于人工/工具从 Download/L6 收集）。 */
        @JavascriptInterface
        public String getLogPath() {
            return L6Log.getLogPath();
        }

        /** 是否把每条日志实时广播给 Tasker（com.l6.carmedia.LOG）。默认开。 */
        @JavascriptInterface
        public void setLogBroadcast(boolean b) {
            L6Log.setBroadcastEnabled(b);
        }

        @JavascriptInterface
        public boolean isLogBroadcast() {
            return L6Log.isBroadcastEnabled();
        }

        /** 下载/导出当天日志文件：经 OtaFileProvider 暴露 content://，用系统分享面板保存到任意应用。 */
        @JavascriptInterface
        public void exportLog() {
            try {
                File f = L6Log.logFile();
                if (f == null || !f.exists()) {
                    toast("暂无日志文件（先产生一些运行日志）");
                    return;
                }
                Uri u = Uri.parse("content://" + OtaFileProvider.AUTHORITY + "/logs/" + f.getName());
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(i, "下载 / 分享日志"));
            } catch (Throwable e) {
                toast("导出日志失败：" + (e.getMessage() == null ? "未知" : e.getMessage()));
            }
        }

        /*
         * ---------------- 车机信号采集（v1.5.15）----------------
         * 场景：用户点「开始采集」→ 自行操作车辆功能 → 点「结束采集」→ 把日志发出来（操作了什么由用户说明）。
         * 目的：看清「操作车辆功能时，这台车机向外吐了什么信号」，为将来在这些事件上做响应
         *      （开车门提示、倒车切画面等）打底。
         * 只读、不申请新权限；采集期间每秒比对系统快照差异，逐条写本地日志（标签 L6Signal）
         * 并另存 Download/L6/signal-*.json。
         */

        /** 开始采集（已结束/未开始时有效；已在跑则忽略）。 */
        @JavascriptInterface
        public void startSignalCapture() {
            L6Log.i("L6Signal", "用户从设置页开始车机信号采集");
            SignalCapture.start(MainActivity.this);
        }

        /** 请求结束采集（真正的落盘与汇总在采集线程里完成）。 */
        @JavascriptInterface
        public void stopSignalCapture() {
            L6Log.i("L6Signal", "用户从设置页结束车机信号采集");
            SignalCapture.stop();
        }

        /** 是否正在采集（页面用它切换按钮文案并防重复开始）。 */
        @JavascriptInterface
        public boolean isSignalCapturing() {
            return SignalCapture.isRunning();
        }

    }
    /* ==================================================================
     *  原生界面宿主实现（nat.Host）
     *
     *  原生 UI 只依赖 nat.Host 这个接口，不直接碰 Activity。这里的实现绝大多数是
     *  **转发给同一个 Bridge 实例** —— Bridge 是 v2.0.0 之前 JS 桥用的那套底层能力
     *  （OTA / 日志 / 信号采集 / 应用枚举），WebView 删了但能力留着，直接复用，
     *  避免「原生版重写一遍 ⇒ 与旧行为不一致」。
     *
     *  ★ 有两处是原生新增的（不再走 Bridge）：
     *    ① 应用列表 / 图标改成「原生对象」而不是 JSON 字符串（视图层不用自己解析）；
     *    ② 壁纸选择走系统文件选择器 + 直接落盘（不再经过 dataURL → localStorage）。
     * ================================================================== */

    /** 应用列表缓存：PackageManager 枚举 + 图标解码都不便宜，抽屉/dock/设置页来回切会反复要 */
    private java.util.List<Def.App> appCache;
    private long appCacheAt = 0L;
    private static final long APP_CACHE_MS = 30000L;

    @Override
    public android.content.Context ctx() {
        return this;
    }

    @Override
    public java.util.List<Def.App> allApps() {
        long now = System.currentTimeMillis();
        if (appCache != null && now - appCacheAt < APP_CACHE_MS) {
            return appCache;
        }
        java.util.List<Def.App> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(api.getAllAppsJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Def.App a = new Def.App();
                a.pkg = o.optString("pkg", "");
                a.key = o.optString("key", a.pkg);
                a.name = o.optString("name", a.pkg);
                a.emoji = o.optString("icon", "\uD83D\uDCE6");
                a.type = o.optString("type", "other");
                if (!a.pkg.isEmpty()) out.add(a);
            }
            appCache = out;
            appCacheAt = now;
        } catch (Throwable t) {
            android.util.Log.w("L6Apps", "原生侧解析应用列表失败: " + t);
            if (appCache == null) appCache = out;
        }
        return appCache;
    }

    @Override
    public java.util.List<Def.App> appsOfType(String type) {
        java.util.List<Def.App> out = new java.util.ArrayList<>();
        for (Def.App a : allApps()) {
            if (type == null || type.equals(a.type)) out.add(a);
        }
        return out;
    }

    /** 图标缓存：getAppIcon 返回 base64 data URL，解一次记住 */
    private final java.util.HashMap<String, android.graphics.Bitmap> iconCache2 = new java.util.HashMap<>();
    private final java.util.HashSet<String> iconMiss = new java.util.HashSet<>();

    @Override
    public android.graphics.Bitmap icon(String pkgOrKey) {
        if (pkgOrKey == null || pkgOrKey.isEmpty()) return null;
        if (iconCache2.containsKey(pkgOrKey)) return iconCache2.get(pkgOrKey);
        if (iconMiss.contains(pkgOrKey)) return null;
        try {
            String url = api.getAppIcon(pkgOrKey);
            if (url == null || !url.startsWith("data:image")) {
                iconMiss.add(pkgOrKey);
                return null;
            }
            int comma = url.indexOf(',');
            byte[] raw = android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT);
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (b == null) {
                iconMiss.add(pkgOrKey);
                return null;
            }
            iconCache2.put(pkgOrKey, b);
            return b;
        } catch (Throwable t) {
            iconMiss.add(pkgOrKey);
            return null;
        }
    }

    @Override
    public String appName(String pkg) {
        for (Def.App a : allApps()) {
            if (a.pkg.equals(pkg)) return a.name;
        }
        return pkg == null ? "" : pkg;
    }

    /**
     * 调起「音 / 导航源」。
     * ★ 必须先判类型再分发：launchApp 是导航专用（带保活 + 悬浮返回按钮），launchMusic 是音乐专用
     *   （后台唤醒、不挂按钮）。用错会把音乐 App 当导航起，多出一个不该有的悬浮按钮。
     */
    @Override
    public void launchSrc(String v) {
        if (v == null || v.isEmpty()) {
            toast("未设置导航源，请到「设置 → 导航源」选择");
            return;
        }
        // ★ 与原型 startNav 的「600ms 同键去重」保持一致：一次下滑手势会**连续触发多次**
        //   （observe 里每超过一次阈值就再派发一次），按钮层与手势层也可能各调一次。
        //   没有这道闸门，同一个 App 会被连拉好几次。
        long nowMs = System.currentTimeMillis();
        if (v.equals(lastLaunchKey) && nowMs - lastLaunchAt < 600) return;
        lastLaunchKey = v;
        lastLaunchAt = nowMs;
        if (Def.isLocalMusic(v)) {
            api.launchMusic(v);       // 本地源（U盘/蓝牙）无需启动，桥内部会直接返回
            return;
        }
        String type = null;
        for (Def.App a : allApps()) {
            if (a.pkg.equals(v) || a.srcKey().equals(v)) {
                type = a.type;
                break;
            }
        }
        if ("music".equals(type)) {
            api.launchMusic(v);
        } else {
            // 导航（含白名单外、认不出类型的包名）：交给 launchApp，它有「兜底取第一个导航 App」逻辑
            api.launchApp(v);
        }
    }

    @Override
    public void launchPkg(String pkg) {
        api.launchPkg(pkg);
    }

    @Override
    public void goHome() {
        api.goHome();
    }

    @Override
    public void ui(Runnable r) {
        runOnUiThread(r);
    }

    /**
     * 原生界面的系统状态快照。
     *
     * ★ 不能再用 {@code api.getSysState()} —— 那个桥方法只回 {@code {notifyAccess, media}}，
     * 是当初给「页面初始化一次性取值」用的；导航 / 歌词只以 SysHub 事件形式存在，
     * 原生界面必须读我们自己的合成快照。
     */
    @Override
    public JSONObject sysState() {
        JSONObject out = new JSONObject();
        synchronized (natSys) {
            copyNat(out, "media");
            copyNat(out, "nav");
            copyNat(out, "lyrics");
            copyNat(out, "car");
        }
        try {
            out.put("notifyAccess", MediaHub.hasAccess(this));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void copyNat(JSONObject out, String k) {
        Object v = natSys.opt(k);
        if (v == null) {
            return;
        }
        try {
            out.put(k, v);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 原生界面接管时的「合成快照」种子：媒体状态先取一次全量（含封面）。
     * 之后每秒的 tick 推不带封面的事件，靠 {@link #natIngestMedia} 沿用上一张。
     */
    private void seedNatSys() {
        try {
            JSONObject m = MediaHub.get(this).snapshot();
            if (m != null) {
                synchronized (natSys) {
                    natSys.put("media", m);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            synchronized (natSys) {
                natSys.put("notifyAccess", MediaHub.hasAccess(this));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自检 / 演示入口（{@code am start ... --ez l6demo true}）。
     *
     * 数据走的是与生产完全同一条路：SysHub.pushJson → natIngest → 每秒刷新。
     * 分两批下发：媒体（会触发真实歌词请求）先来，歌词晚 4s 覆盖上去，
     * 免得假歌名拿不到歌词、刚铺好的示例歌词被「暂无歌词 · 请求失败」顶掉。
     */
    private void startNatDemo() {
        rootView.postDelayed(() -> {
            try {
                SysHub.pushJson("{\"kind\":\"veh\",\"fuel\":62,\"batt\":12.6,\"odo\":38521,"
                        + "\"load\":37,\"temp\":41,\"lat\":28,\"ip\":\"192.168.1.9\","
                        + "\"lock\":true,\"lights\":true,\"hazard\":false,\"trunk\":false,"
                        + "\"ac\":{\"on\":true,\"mode\":\"AUTO\",\"temp\":23,\"fan\":2,\"circulate\":\"内\"},"
                        + "\"tire\":{\"fl\":2.4,\"fr\":2.3,\"rl\":2.2,\"rr\":1.8},"
                        + "\"doors\":{\"fl\":false,\"fr\":true,\"rl\":false,\"rr\":false},"
                        + "\"windows\":{\"fl\":true,\"fr\":false,\"rl\":false,\"rr\":false}}");
                SysHub.pushJson("{\"kind\":\"nav\",\"active\":true,\"app\":\"高德地图车机版\","
                        + "\"arrow\":\"⬈\",\"turn\":\"右转\",\"road\":\"中山大道\",\"remain\":\"3.2 km\","
                        + "\"eta\":\"14:35\",\"dest\":\"广州塔\",\"src\":\"bcast\",\"cruise\":false}");
                SysHub.pushJson("{\"kind\":\"media\",\"active\":true,\"title\":\"示例歌曲\","
                        + "\"artist\":\"示例歌手\",\"app\":\"USB\",\"playing\":true,"
                        + "\"pos\":42000,\"dur\":213000}");
                L6Log.i("L6Demo", "已下发车辆 / 导航 / 媒体示例数据");
            } catch (Throwable t) {
                L6Log.w("L6Demo", "示例数据下发失败: " + t);
            }
        }, 1200);
        rootView.postDelayed(() -> {
            try {
                SysHub.pushJson("{\"kind\":\"lyrics\",\"title\":\"示例歌曲\",\"artist\":\"示例歌手\","
                        + "\"lines\":[{\"t\":0,\"s\":\"第一行示例歌词\"},{\"t\":40000,\"s\":\"第二行示例歌词\"},"
                        + "{\"t\":44000,\"s\":\"第三行示例歌词\"},{\"t\":48000,\"s\":\"第四行示例歌词\"},"
                        + "{\"t\":52000,\"s\":\"第五行示例歌词\"},{\"t\":56000,\"s\":\"第六行示例歌词\"}]}");
            } catch (Throwable t) {
                L6Log.w("L6Demo", "示例歌词下发失败: " + t);
            }
        }, 5200);
    }

    /**
     * SysHub 原始事件 → 原生合成快照。
     *
     * 事件形态见 {@link SysHub} 类注释；只认原生界面需要的 kind，其余原样忽略。
     * 线程任意 ⇒ natSys 全程加锁。
     */
    private void natIngest(String json) {
        if (json == null || json.isEmpty() || natShell == null) {
            return;      // 没起原生界面就别费这个劲（网页版由 JS 侧自己接）
        }
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Throwable t) {
            return;
        }
        String kind = o.optString("kind", "");
        if (kind.isEmpty()) {
            return;
        }
        boolean wake = true;
        if ("media".equals(kind)) {
            natIngestMedia(o);
        } else if ("access".equals(kind)) {
            synchronized (natSys) {
                try {
                    natSys.put("notifyAccess", o.optBoolean("granted", false));
                } catch (Throwable ignored) {
                }
            }
        } else if ("nav".equals(kind)) {
            synchronized (natSys) {
                try {
                    natSys.put("nav", o);
                } catch (Throwable ignored) {
                }
            }
        } else if ("lyrics".equals(kind)) {
            synchronized (natSys) {
                try {
                    natSys.put("lyrics", o);
                } catch (Throwable ignored) {
                }
            }
        } else if ("veh".equals(kind) || "car".equals(kind)) {
            natMergeVeh(o);
        } else {
            wake = false;
        }
        if (wake && natShell != null) {
            natShell.sysWake();
        }
    }

    /**
     * 媒体事件：归位 + 换歌拉歌词。
     *
     * ★ 封面沿用：MediaHub 每秒的 tick 推的是 build(false)（不带 art），只在下发那一刻带一次，
     * 直接覆盖会让封面在 1 秒后消失 ⇒ 新事件没带 art 时沿用上一张（与 JS 的
     * applyRealMedia「只在 d.art 存在时才改封面」等价）。
     */
    private void natIngestMedia(JSONObject o) {
        boolean active = o.optBoolean("active", false);
        String title = o.optString("title", "");
        String artist = o.optString("artist", "");
        synchronized (natSys) {
            JSONObject prev = natSys.optJSONObject("media");
            if (prev != null && !o.has("art")) {
                Object art = prev.opt("art");
                if (art != null) {
                    try {
                        o.put("art", art);
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                natSys.put("media", o);
            } catch (Throwable ignored) {
            }
        }

        // 换歌才重新拉歌词；无会话 / 会话结束 ⇒ 清掉上一首的，别让旧歌词挂在新歌上
        String key = active ? (title + "|" + artist) : "";
        if (key.equals(natLrcKey)) {
            return;
        }
        natLrcKey = key;
        synchronized (natSys) {
            natSys.remove("lyrics");
        }
        if (key.isEmpty()) {
            return;
        }
        try {
            api.requestLyrics(title, artist);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 车辆数据的增量合并 —— 原生版的 {@code window.L6VehicleData(obj)}。
     *
     * 协议（与原型完全一致，只传有数据的字段即可，其余继续显示占位符「—」）：
     * <pre>
     * {"kind":"veh", fuel,batt,odo, load,temp,lat,ip, lock,lights,hazard,gear,trunk,
     *  "ac":{on,mode,temp,fan,circulate}, "tire":{fl,fr,rl,rr},
     *  "doors":{fl,fr,rl,rr}, "windows":{fl,fr,rl,rr}}
     * </pre>
     * ★ 谁下发？将来对接 Linux 服务端 / 广播接收器时，在任意线程调
     * {@code SysHub.pushJson(json)} 即可，不必再关心界面。
     */
    private void natMergeVeh(JSONObject d) {
        synchronized (natSys) {
            JSONObject cur = natSys.optJSONObject("car");
            JSONObject next;
            try {
                next = cur == null ? new JSONObject() : new JSONObject(cur.toString());
            } catch (Throwable t) {
                next = new JSONObject();
            }
            for (String k : CAR_SCALARS) {
                if (d.has(k)) {
                    try {
                        next.put(k, d.get(k));
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (String g : CAR_GROUPS) {
                JSONObject src = d.optJSONObject(g);
                if (src == null) {
                    continue;
                }
                JSONObject dst = next.optJSONObject(g);
                if (dst == null) {
                    dst = new JSONObject();
                    try {
                        next.put(g, dst);
                    } catch (Throwable ignored) {
                    }
                }
                java.util.Iterator<String> it = src.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        dst.put(k, src.get(k));
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                natSys.put("car", next);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void mediaControl(String action) {
        api.mediaControl(action);
    }

    @Override
    public void requestLyrics(String title, String artist) {
        api.requestLyrics(title, artist);
    }

    @Override
    public void refreshSys() {
        api.refreshSys();
    }

    @Override
    public boolean hasNotifyAccess() {
        return api.hasNotifyAccess();
    }

    @Override
    public void openNotifyAccess() {
        api.openNotifyAccess();
    }

    @Override
    public boolean isDefaultHome() {
        return isDefaultHomeOf(this);
    }

    @Override
    public void openHomeSettings() {
        api.openHomeSettings();
    }

    @Override
    public boolean hasOverlay() {
        return api.hasOverlayPermission();
    }

    @Override
    public void openOverlay() {
        api.openOverlaySettings();
    }

    @Override
    public boolean openAppOverlaySettings(String pkg) {
        return api.openAppOverlaySettings(pkg);
    }

    @Override
    public JSONArray log(int n) {
        try {
            return new JSONArray(api.getLogJson(n));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    @Override
    public void clearLog() {
        api.clearLog();
    }

    @Override
    public String logPath() {
        return api.getLogPath();
    }

    @Override
    public boolean logBroadcast() {
        return api.isLogBroadcast();
    }

    @Override
    public void setLogBroadcast(boolean b) {
        api.setLogBroadcast(b);
    }

    @Override
    public void exportLog() {
        api.exportLog();
    }

    @Override
    public void startSignal() {
        api.startSignalCapture();
    }

    @Override
    public void stopSignal() {
        api.stopSignalCapture();
    }

    @Override
    public boolean isSignalCapturing() {
        return api.isSignalCapturing();
    }

    @Override
    public JSONObject otaConfig() {
        try {
            JSONObject o = new JSONObject(api.getOtaConfig());
            // ★ 原型 #otaCur 依赖 window.__l6Version，而它从未被赋值 ⇒ 网页版「当前版本」恒为 "--"。
            //   Ota.Config.toJson() 本身也不含 version 字段；原生能直接取到真实版本，这里补上。
            if (o.optString("version", "").isEmpty()) {
                o.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            }
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    @Override
    public void checkOta() {
        L6Log.i("L6Ota", "检查更新");
        Ota.checkUpdate(this, this::forwardOta);
    }

    @Override
    public void installOta(String url, String sha256) {
        L6Log.i("L6Ota", "下载并安装: " + (url == null ? "" : url));
        Ota.downloadAndInstall(this, url, sha256, this::forwardOta);
    }

    @Override
    public void cancelOta() {
        L6Log.i("L6Ota", "取消下载");
        Ota.cancelDownload();
    }

    @Override
    public void otaEvent(String kind, JSONObject data) {
        if (natShell != null) natShell.onOtaEvent(kind, data);
    }

    /**
     * Ota 的事件回调（后台线程）→ 归一化成原生 UI 认得的 kind → 主线程转发。
     *
     * ★ 必须做这层映射：Ota 侧用的是「实现语义」的 kind（check / download / retry …），
     *   而界面关心的是「状态语义」（available / latest / progress …）。直接在视图层判 Ota 的 kind
     *   会把协议细节漏进 UI。
     */
    private void forwardOta(String json) {
        try {
            final JSONObject o = new JSONObject(json);
            String raw = o.optString("kind", "check");
            String kind;
            switch (raw) {
                case "check":
                    if (!o.optString("error", "").isEmpty()) {
                        kind = "error";
                    } else {
                        kind = o.optBoolean("hasUpdate", false) ? "available" : "latest";
                    }
                    // 字段别名：界面读的是通用名，Ota 给的是发布信息名
                    o.put("version", o.optString("versionName", o.optString("currentVersion", "")));
                    o.put("url", o.optString("apkUrl", ""));
                    break;
                case "download":
                    kind = "progress";
                    break;
                case "retry":
                    kind = "downloading";
                    break;
                default:
                    kind = raw;
                    break;
            }
            final String fk = kind;
            final boolean force = o.optBoolean("force", false) && "available".equals(kind);
            runOnUiThread(() -> {
                if (natShell == null) return;
                natShell.onOtaEvent(fk, o);
                if (force) {
                    // 强制更新：遮住整个界面，只能更新或取消
                    natShell.forceUpdate(o.optString("changelog", ""),
                            () -> installOta(o.optString("apkUrl", ""), o.optString("sha256", "")),
                            this::cancelOta);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("L6Ota", "原生侧解析 OTA 事件失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 壁纸

    @Override
    public void pickWallpaperFile(String kind) {
        pickWallKind = Prefs.KIND_DYNAMIC.equals(kind) ? Prefs.KIND_DYNAMIC : Prefs.KIND_STATIC;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // ★ 同 WebView 版的老坑：mp4 在部分车机上被报成 application/octet-stream，
            //   不把它一起放进 MIME 列表，动态壁纸就选不到视频。
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "image/*", "video/*", "application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(Intent.createChooser(i, "\u9009\u62e9\u58c1\u7eb8"), REQ_WALL);
        } catch (Throwable e) {
            toast("无法打开文件选择器：" + e.getMessage());
        }
    }

    /** Uri → 字节 → 落盘 → 进列表并选中 */
    private void importWallUri(Uri uri, String kind) {
        try {
            String name = wallDisplayName(uri);
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                toast("读取壁纸失败：无法打开文件");
                return;
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] data = bo.toByteArray();
            if (data.length == 0) {
                toast("读取壁纸失败：文件为空");
                return;
            }
            // 按文件自身类型归档（与原型「上传后自动归档」同义）：扩展名/命名都认不出来时按视频魔数兜底
            boolean vid = name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|webm|mkv|mov|3gp)$")
                    || isVideoBytes(data);
            String realKind = vid ? Prefs.KIND_DYNAMIC : Prefs.KIND_STATIC;
            File f = Prefs.writeWallFile(realKind, data, name);
            if (f == null) {
                toast("壁纸保存失败");
                return;
            }
            Prefs p = Prefs.get(this);
            String id = (vid ? "d_" : "s_") + f.getName();
            boolean dup = false;
            for (Prefs.WallItem it : p.walls(realKind)) {
                if (it.id.equals(id)) dup = true;
            }
            Prefs.WallItem item = new Prefs.WallItem(id, f.getName(), f.getAbsolutePath(), vid);
            if (!dup) p.walls(realKind).add(item);
            p.wall = realKind;
            p.selectWall(realKind, id);      // selectWall 内部 save()
            if (natShell != null) {
                natShell.applyWall();
                natShell.toast("已上传并切换到" + (vid ? "动态" : "静态") + "壁纸");
            }
            onWallSaved(realKind, item);
        } catch (Throwable e) {
            toast("壁纸导入失败：" + e.getMessage());
            L6Log.e("L6Wall", "壁纸导入失败: " + e);
        }
    }

    /** 视频文件的魔数判定（前 12 字节里找 ftyp） */
    private static boolean isVideoBytes(byte[] d) {
        int n = Math.min(d.length - 4, 16);
        for (int i = 0; i < n; i++) {
            if (d[i] == 'f' && d[i + 1] == 't' && d[i + 2] == 'y' && d[i + 3] == 'p') return true;
        }
        return false;
    }

    private String wallDisplayName(Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String p = uri.getLastPathSegment();
            name = (p == null || p.trim().isEmpty()) ? ("wall_" + System.currentTimeMillis() + ".jpg") : p;
        }
        return name;
    }

    @Override
    public void onWallSaved(String kind, Prefs.WallItem item) {
        if (natShell != null) {
            natShell.refreshHome();
            natShell.refreshSet();   // ★ 没这一行：设置页的壁纸网格要下次进来才看得到新图
        }
    }

    // ---------------------------------------------------------------- 原生界面启动

    /**
     * 起原生界面：WebView 从视图树里摘掉（实例保留给桥用），换成 {@link NatShell} 的视图树。
     */
    private void startNativeUi() {
        try {
            natShell = new NatShell(this);
            android.view.View v = natShell.build();
            rootView.addView(v, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            // ★ 版本号**不要硬编码**：这一行是「原生界面到底起没起来」的唯一判据
            //   （v2.0.0 的黑屏事故就是靠它定位的），写死版本号会让人以为装的是旧包
            //   —— v2.0.1 运行时它还印着 v2.0.0。改成现读 PackageManager。
            String verName = "?";
            try {
                verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            L6Log.i("L6", "原生界面已启用 (v" + verName + ")");
            seedNatSys();
            if (getIntent() != null && getIntent().getBooleanExtra("l6demo", false)) {
                startNatDemo();
            }
        } catch (Throwable t) {
            // v2.0.0 没有网页版可以回退了 —— 但**绝不能留一块白屏**：
            // 把错误原文与完整堆栈直接铺到屏幕上（车机上用户能拍照反馈，比黑屏强太多）。
            //   ★ 堆栈必须要有：只记 toString() 只能看到「某个 FrameLayout 是 null」，
            //     而 build() 里 addView 有十几处，等于没线索（实测为此白绕一轮）。
            L6Log.e("L6", "原生界面启动失败: " + t + "\n"
                    + android.util.Log.getStackTraceString(t));
            android.util.Log.e("L6Nat", "原生界面启动失败", t);
            natShell = null;
            try {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setTextColor(0xFFFF8080);
                tv.setTextSize(12);
                tv.setPadding(24, 24, 24, 24);
                tv.setText("老六中控启动失败\n\n" + t + "\n\n"
                        + android.util.Log.getStackTraceString(t));
                rootView.removeAllViews();
                rootView.addView(tv, new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            } catch (Throwable ignored) {
            }
        }
    }

}
