package com.l6.carmedia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 内嵌浏览器（v2.0.40）：全屏 WebView，承载「软件仓库（飞牛分享页）」与
 * 「日志 NAS 上传（飞牛收集页）」两个场景 —— 车机没有浏览器 App 也能在应用内打开网页。
 *
 * <p>能力边界（按需求「能实现日志NAS上传和软件仓库应用下载就可以」收口）：
 * <ul>
 *   <li>JS / DOM Storage 开启 —— 飞牛页面是 SPA，不开 JS 整页空白；</li>
 *   <li>{@code onShowFileChooser} —— 收集页的 {@code <input type=file>} 选文件上传日志；</li>
 *   <li>{@code DownloadListener} —— 分享页点下载 .apk 时拦截：下到 filesDir/update/ 后
 *       直接走 {@link Ota#installLocalApk} 拉起系统安装器（与 OTA 同一条安装链路）；</li>
 *   <li>顶栏 返回 / 前进 / 标题 / 关闭；底部可选提示条（如日志文件绝对路径）。</li>
 * </ul>
 *
 * <p>★ 车机 ROM 可能精简掉 WebView 组件：{@link #webViewAvailable} 返回 false 时调用方应走
 *   各自的兜底（软件仓库回落 GitHub 面板、NAS 诚实提示路径），本 Activity 兜底 finish。
 *
 * <p>★ 安全基线：{@code setAllowFileAccess(false)} —— 页面（含第三方 JS）不能直接读本地
 *   file:// 文件；用户上传的文件走系统选择器返回的 content:// Uri，不暴露真实路径给页面。
 */
public class BrowserActivity extends Activity {

    public static final String EXTRA_URL = "url";      // 必填：要打开的 https 页面
    public static final String EXTRA_TITLE = "title";  // 可选：顶栏标题
    public static final String EXTRA_HINT = "hint";    // 可选：底部提示条（如日志文件路径）

    private static final int REQ_FILE = 4001;

    /** 车机是否带 WebView 组件（getCurrentWebViewPackage 为 null 即精简 ROM，内嵌浏览器不可用）。 */
    public static boolean webViewAvailable(Context c) {
        try {
            return WebView.getCurrentWebViewPackage() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private WebView wv;
    private TextView titleTv;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!webViewAvailable(this)) {
            finish();
            return;
        }
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10141E);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏：返回 / 标题 / 关闭 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF151B29);
        int pad = px(12);
        top.setPadding(pad, px(8), pad, px(8));

        TextView backBtn = barBtn("‹");
        backBtn.setOnClickListener(v -> {
            if (wv != null && wv.canGoBack()) wv.goBack();
            else finish();
        });
        top.addView(backBtn, new LinearLayout.LayoutParams(px(40), px(34)));

        TextView fwdBtn = barBtn("›");
        fwdBtn.setOnClickListener(v -> {
            if (wv != null && wv.canGoForward()) wv.goForward();
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(px(40), px(34));
        flp.leftMargin = px(6);
        top.addView(fwdBtn, flp);

        titleTv = new TextView(this);
        titleTv.setTextColor(0xFFE6EAF2);
        titleTv.setTextSize(15);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setGravity(Gravity.CENTER_VERTICAL);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleTv.setText(t == null || t.isEmpty() ? url : t);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, px(34), 1f);
        tlp.leftMargin = px(10);
        tlp.rightMargin = px(10);
        top.addView(titleTv, tlp);

        TextView closeBtn = barBtn("✕");
        closeBtn.setOnClickListener(v -> finish());
        top.addView(closeBtn, new LinearLayout.LayoutParams(px(40), px(34)));
        col.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(0xFF2A3242);
        col.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));

        // ---- WebView ----
        wv = createWebView();
        if (wv == null) {           // WebView 组件存在但初始化崩溃（stub ROM）→ 兜底退出
            finish();
            return;
        }
        col.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 可选提示条（如：日志已生成在 XX，请在页面中选择该文件上传） ----
        String hint = getIntent().getStringExtra(EXTRA_HINT);
        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(0xFF9AA6BC);
            h.setTextSize(12);
            h.setBackgroundColor(0xFF0B0F18);
            h.setPadding(pad, px(8), pad, px(8));
            col.addView(h, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        setContentView(root);
        wv.loadUrl(url);
    }

    private WebView createWebView() {
        try {
            WebView v = new WebView(this);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);           // 飞牛页面是 SPA，必须开
            s.setDomStorageEnabled(true);           // 页面状态存 localStorage
            s.setAllowFileAccess(false);            // 页面 JS 不能直接读本地 file://
            s.setAllowContentAccess(false);
            s.setLoadsImagesAutomatically(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            v.setBackgroundColor(Color.BLACK);

            v.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String u) {
                    // 全部在内部 WebView 里打开（分享页内的下载/跳转不外抛到其它 App）
                    return false;
                }
            });
            v.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(WebView view, String title) {
                    if (titleTv != null && title != null && !title.isEmpty()) {
                        String t = getIntent().getStringExtra(EXTRA_TITLE);
                        // 页面标题只补位：调用方显式给了标题就以它为准
                        if (t == null || t.isEmpty()) titleTv.setText(title);
                    }
                }

                @Override
                public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                                 FileChooserParams params) {
                    // NAS 收集页的 <input type=file>：弹系统文件选择器，让用户选日志 .txt
                    if (fileCb != null) fileCb.onReceiveValue(null);   // 上一次未回完 → 先释放
                    fileCb = cb;
                    try {
                        startActivityForResult(params.createIntent(), REQ_FILE);
                        return true;
                    } catch (Throwable e) {
                        fileCb = null;
                        cb.onReceiveValue(null);
                        return false;
                    }
                }
            });
            v.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                    // 软件仓库分享页点下载：拦到本地下完直接装（.apk），其它文件存盘提示路径
                    downloadInBackground(url, cd != null ? cd : "", url.toLowerCase().endsWith(".apk"));
                }
            });
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- 文件选择回调

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            if (cb != null) cb.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(res, data));
            return;
        }
        super.onActivityResult(req, res, data);
    }

    // ---------------------------------------------------------- 下载 → 安装

    /**
     * 后台下载 WebView 拦到的下载链接。.apk 落 filesDir/update/（OtaFileProvider 能直接
     * content:// 出来给系统安装器，与 OTA 下载同目录同链路）；其它文件同样落这里并 toast 路径。
     */
    private void downloadInBackground(final String url, final String contentDisposition,
                                      final boolean isApk) {
        toast(isApk ? "开始下载…" : "开始下载…");
        final String name = guessName(url, contentDisposition, isApk);
        new Thread(() -> {
            File dir = new File(getFilesDir(), "update");
            if (!dir.exists() && !dir.mkdirs()) {
                toast("下载失败：无法创建下载目录");
                return;
            }
            File out = new File(dir, name);
            HttpURLConnection c = null;
            InputStream in = null;
            FileOutputStream os = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "L6CarMedia/1.0");
                int code = c.getResponseCode();
                if (code >= 400) {
                    toast("下载失败：HTTP " + code);
                    return;
                }
                in = c.getInputStream();
                os = new FileOutputStream(out);
                byte[] buf = new byte[16 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                }
                os.flush();
                if (isApk) {
                    // 装之前过一道魔数闸（镜像出错时常返 200 + HTML 错误页）
                    if (!looksLikeZip(out)) {
                        out.delete();
                        toast("下载失败：文件不是有效的安装包");
                        return;
                    }
                    toast("下载完成（" + (done / 1024) + " KB），拉起安装…");
                    runOnUiThread(() -> Ota.installLocalApk(BrowserActivity.this, out));
                } else {
                    toast("已保存到：" + out.getAbsolutePath());
                }
            } catch (Throwable e) {
                try { out.delete(); } catch (Throwable ignored) {}
                toast("下载失败：" + (e.getMessage() == null ? "网络错误" : e.getMessage()));
            } finally {
                try { if (os != null) os.close(); } catch (Throwable ignored) {}
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
                if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            }
        }, "L6BrowserDl").start();
    }

    /** 下载文件名：.apk 用固定 dl_ 前缀防冲突；其余从 contentDisposition / URL 尽量解析。 */
    private static String guessName(String url, String contentDisposition, boolean isApk) {
        if (isApk) return "dl_" + System.currentTimeMillis() + ".apk";
        String n = null;
        if (contentDisposition != null) {
            int i = contentDisposition.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                n = contentDisposition.substring(i + 9).replaceAll("\"", "").trim();
                int s = n.indexOf(';');
                if (s > 0) n = n.substring(0, s);
            }
        }
        if (n == null || n.isEmpty()) {
            String clean = url.indexOf('?') > 0 ? url.substring(0, url.indexOf('?')) : url;
            n = clean.substring(clean.lastIndexOf('/') + 1);
        }
        n = n.replaceAll("[^A-Za-z0-9._\\-]", "_");
        if (n.isEmpty() || n.length() > 64) n = "file_" + System.currentTimeMillis();
        return n;
    }

    private static boolean looksLikeZip(File f) {
        try {
            if (f.length() < 4096) return false;
            try (InputStream is = new java.io.FileInputStream(f)) {
                byte[] h = new byte[4];
                if (is.read(h) != 4) return false;
                return h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5 || h[2] == 7);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------- 生命周期

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else finish();
    }

    @Override
    protected void onDestroy() {
        // 文件选择回调没回完必须释放，否则 WebCore 等待回调、后续 chooser 全部失灵
        if (fileCb != null) {
            try { fileCb.onReceiveValue(null); } catch (Throwable ignored) {}
            fileCb = null;
        }
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.destroy();
            } catch (Throwable ignored) {}
            wv = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 顶栏文字按钮（与主页视觉一致的暗底圆角）。 */
    private TextView barBtn(String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(0xFFE6EAF2);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(0x22FFFFFF, px(10)));
        t.setClickable(true);
        return t;
    }

    private static android.graphics.drawable.GradientDrawable round(int color, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 本页自用的 dp→px（BrowserActivity 在 com.l6.carmedia 包，够不到 nat.U）。 */
    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
