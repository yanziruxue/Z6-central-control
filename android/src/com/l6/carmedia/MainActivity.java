package com.l6.carmedia;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    /** 需要在 API 29 及以下申请的传统存储权限。 */
    private static final int REQ_PERM = 1002;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackAt = 0L;
    /** 即将调起导航 App：onPause 时若确实离开本界面，才挂「返回」悬浮按钮（导航未成功接管则不留按钮）。 */
    private boolean navLaunching = false;

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
            if (web != null) {
                web.postDelayed(this, 1000);
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

    /** 注入到页面的适配层：把 addJavascriptInterface 对象包装成页面期望的 window.L6Native(cb 风格)。 */
    private static final String SHIM =
            "(function(){try{" +
            "var R=window.L6NativeRaw;if(!R)return;" +
            "window.L6Native={" +
            "getInstalledApps:function(cb){try{cb(JSON.parse(R.getInstalledAppsJson()));}catch(e){cb([]);}}," +
            "saveWallpaper:function(t,b,n){try{R.saveWallpaper(t,b,n);}catch(e){}}," +
            "launchApp:function(k){try{R.launchApp(k);}catch(e){}}," +
            "launchMusic:function(k){try{R.launchMusic(k);}catch(e){}}," +
            "checkOtaUpdate:function(){try{R.checkOtaUpdate();}catch(e){}}," +
            "installOtaUpdate:function(u,s){try{R.installOtaUpdate(u,s);}catch(e){}}," +
            "getOtaConfig:function(){try{return JSON.parse(R.getOtaConfig()||'{}');}catch(e){return{};}}," +
            // ---- 真实系统数据：媒体会话（音乐）+ 导航通知（导航）----
            "getSysState:function(){try{return JSON.parse(R.getSysState()||'{}');}catch(e){return{};}}," +
            "hasNotifyAccess:function(){try{return !!R.hasNotifyAccess();}catch(e){return false;}}," +
            "openNotifyAccess:function(){try{R.openNotifyAccess();}catch(e){}}," +
            "refreshSys:function(){try{R.refreshSys();}catch(e){}}," +
            "mediaControl:function(a){try{R.mediaControl(a);}catch(e){}}," +
            "requestLyrics:function(t,a){try{R.requestLyrics(t,a);}catch(e){}}," +
            // ---- 悬浮窗权限（已并入「系统权限」卡片，作返回主页备用通道）；默认桌面设置入口 ----
            "hasOverlay:function(){try{return !!R.hasOverlayPermission();}catch(e){return false;}}," +
            "openOverlay:function(){try{R.openOverlaySettings();}catch(e){}}," +
            "openHomeSettings:function(){try{R.openHomeSettings();}catch(e){}}" +
            "};" +
            "try{buildMusicSrc();buildNavApp();}catch(e){}" +
            "}catch(e){}})()";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0E14"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 动态视频壁纸自动播放
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        try {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {
        }

        web.addJavascriptInterface(new Bridge(), "L6NativeRaw");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(SHIM, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SHIM, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    String[] accept = params.getAcceptTypes();
                    String type = "*/*";
                    if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) {
                        type = accept[0];
                    }
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(type);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(Intent.createChooser(i, "\u9009\u62e9\u6587\u4ef6"), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开文件选择器：" + e.getMessage());
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT <= 28) {
            try {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            } catch (Throwable ignored) {
            }
        }

        web.loadUrl("file:///android_asset/index.html");

        // 系统实时数据（媒体会话 / 导航通知）统一走 window.L6SysEvent
        SysHub.setEmitter(js -> {
            final WebView w = web;
            if (w == null) {
                return;
            }
            w.post(() -> {
                try {
                    w.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            });
        });

        // 启动 5s 后静默检查一次 OTA（不打扰首屏）
        web.postDelayed(() -> {
            try { new Bridge().checkOtaUpdate(); } catch (Throwable ignored) {}
        }, 5000);

        // 媒体进度每秒推进（含 10s 兜底重建会话）
        web.postDelayed(mediaTick, 1200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到本界面（系统 HOME 返回 / 从导航或设置页返回）→ 撤销悬浮返回按钮；
        // 若确在导航中，按钮由 onPause(navLaunching) 负责挂上，不会丢失。
        try { FloatNav.hide(this); } catch (Throwable ignored) {}
        // 用户可能刚从「通知使用权」设置页返回，这里重新读一次权限并刷新
        try {
            boolean ok = MediaHub.hasAccess(this);
            SysHub.pushAccess(ok);
            MediaHub.get(this).refresh();
        } catch (Throwable ignored) {
        }
        // 用户可能刚从「允许安装未知应用」里授权，回来把没装完的 OTA 包续上
        try {
            if (Ota.hasPendingInstall() && web != null) {
                Ota.resumeInstallIfPending(this, json -> {
                    final String js = Ota.safeJs(json);
                    web.post(() -> web.evaluateJavascript(js, null));
                });
            }
        } catch (Throwable ignored) {
        }
        // 回到本界面（系统 HOME 键 / 从授权页返回）→ 通知页面复位迷你导航卡片，
        // 并把最新悬浮窗授权状态刷到设置页（悬浮窗权限已并入「系统权限」卡片）。
        try {
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){try{if(window.L6NavReturn)window.L6NavReturn();}catch(e){}})()", null);
            }
        } catch (Throwable ignored) {
        }
        try {
            boolean ov = FloatNav.canDrawOverlay(this);
            web.evaluateJavascript(
                    "(function(){try{if(window.L6OverlayEvent)window.L6OverlayEvent(" + ov
                            + ");}catch(e){}})()", null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 仅当确系「调起导航 App 后本界面被盖住」才挂返回按钮；
        // 导航 App 没完全启动 / 失败导致本界面仍在前台时，onPause 不会触发，按钮也就不出现。
        if (navLaunching) {
            navLaunching = false;
            boolean ok = FloatNav.show(getApplicationContext(), "返回");
            if (!ok) {
                toast("未授予悬浮窗权限：返回主页请到设置页「系统权限 → 悬浮窗」授权，或把本应用设为默认桌面");
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.removeCallbacks(mediaTick);
            }
        } catch (Throwable ignored) {
        }
        try {
            MediaHub.get(this).release();
        } catch (Throwable ignored) {
        }
        SysHub.setEmitter(null);
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
            if (web == null) {
                finish();
                return true;
            }
            web.evaluateJavascript(
                    "(function(){try{var p=(typeof idx!=='undefined')?idx:0;" +
                    "if(p!==0){go(0);if(typeof setNav==='function')setNav(false);return 'back';}" +
                    "return 'exit';}catch(e){return 'exit';}})()",
                    value -> {
                        if (value != null && value.contains("exit")) {
                            finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    /* ==================== 原生桥接 ==================== */

    public class Bridge {

        /** 枚举车机已安装的可启动 App，识别音乐 / 导航类，返回 JSON 数组字符串。 */
        @JavascriptInterface
        public String getInstalledAppsJson() {
            JSONArray out = new JSONArray();
            Set<String> seenKeys = new HashSet<>();
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo ai : apps) {
                    String pkg = ai.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) {
                        continue;
                    }
                    if (pm.getLaunchIntentForPackage(pkg) == null) {
                        continue;   // 非可启动 App，跳过
                    }
                    CharSequence labelCs = ai.loadLabel(pm);
                    String label = labelCs == null ? pkg : labelCs.toString().trim();
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

        /** 低置信度归类：仅按名称/包名关键词猜音乐或导航。 */
        private String guessType(String label, String pkg) {
            String z = (label + " " + pkg).toLowerCase(Locale.ROOT);
            // 音乐类：覆盖常见音乐/收音/听书/播客关键词与包名片段（白名单之外的音乐 App 也认得出）
            if (z.contains("\u97f3\u4e50") || z.contains("music") || z.contains("radio")
                    || z.contains("\u542c\u4e66") || z.contains("audio") || z.contains("player")
                    || z.contains("song") || z.contains("fm") || z.contains("\u7535\u53f0")
                    || z.contains("\u871c\u67d0") || z.contains("xmly") || z.contains("kg")
                    || z.contains("kugou") || z.contains("qqmusic") || z.contains("netease")
                    || z.contains("spotify") || z.contains("podcast") || z.contains("\u64ad\u5ba2")
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
                // 地图/导航是直接全屏盖住本界面的外部 App。返回主页有两条路：
                //   ① 用户把本应用设为默认桌面后，按 HOME 键即回本界面（见「系统权限」卡片的「设为默认桌面」）；
                //   ② 多数车机 ROM 锁死系统桌面、不让第三方应用设为默认桌面，此时挂一个
                //      左下角「🏠 返回」悬浮按钮（底部、非顶部、可拖动避让），点击即把本应用拉回前台。
                // 悬浮按钮需 SYSTEM_ALERT_WINDOW 授权，未授权则降级提示、不阻塞导航启动。
                // 返回按钮「不在 launchApp 里直接挂」，而是标记 navLaunching，等本 Activity 真正
                // onPause（确证导航 App 已接管前台）时再挂。这样导航 App 没完全启动 / 启动失败、
                // 本界面没被盖住时，按钮不会出现；即便出现，点击 hide+bringToFront 也一定能回本界面。
                navLaunching = true;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable e) {
                navLaunching = false;   // 启动抛异常：本次不算「去导航」，onPause 不会误挂按钮
                toast("启动导航失败：" + e.getMessage());
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
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);   // 用户主动「启动/唤醒」→ 前台拉起接管；不挂返回按钮
            } catch (Throwable e) {
                toast("启动音乐 App 失败：" + e.getMessage());
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
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    Intent li = pm.getLaunchIntentForPackage(ai.packageName);
                    if (li == null) {
                        continue;
                    }
                    for (String[] m : APP_MAP) {
                        if (m[0].equalsIgnoreCase(ai.packageName) && "nav".equals(m[4])) {
                            return li;      // 白名单命中优先级最高
                        }
                    }
                    if (fallback == null) {
                        CharSequence lc = ai.loadLabel(pm);
                        String label = lc == null ? ai.packageName : lc.toString();
                        if ("nav".equals(guessType(label, ai.packageName))) {
                            fallback = li;  // 记下第一个动态识别出的导航 App
                        }
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

        /** 检查更新；进度/结果通过 window.L6OtaEvent 推送到页面（事件由主线程 evaluateJavascript 派发）。 */
        @JavascriptInterface
        public void checkOtaUpdate() {
            Ota.checkUpdate(MainActivity.this, json -> {
                final String js = Ota.safeJs(json);
                web.post(() -> web.evaluateJavascript(js, null));
            });
        }

        /** 下载并安装；进度通过 window.L6OtaEvent 推送。 */
        @JavascriptInterface
        public void installOtaUpdate(String url, String sha256) {
            Ota.downloadAndInstall(MainActivity.this, url, sha256, json -> {
                final String js = Ota.safeJs(json);
                web.post(() -> web.evaluateJavascript(js, null));
            });
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
         * 跳系统的「默认应用」设置页，让用户把本应用设为默认桌面（HOME）。
         * 返回主页机制依赖此设置：设为本应用后，系统 HOME 键 / 上滑回桌面都会回到本界面。
         * 老版本无 MANAGE_DEFAULT_APPS_SETTINGS 常量时，退化为直接拉起 HOME 选择。
         */
        @JavascriptInterface
        public void openHomeSettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable e) {
                try {
                    Intent h = new Intent(Intent.ACTION_MAIN);
                    h.addCategory(Intent.CATEGORY_HOME);
                    h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(h);
                } catch (Throwable ignored) {
                }
            }
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
    }
}
