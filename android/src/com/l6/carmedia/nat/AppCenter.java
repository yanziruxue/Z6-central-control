package com.l6.carmedia.nat;

import android.content.Context;
import android.content.Intent;

import com.l6.carmedia.BrowserActivity;

/**
 * 应用中心（v2.0.40）：右下角 📦 应用中心按钮入口。
 *
 * <p>★ 数据源用回**用户指定的飞牛软件仓库分享页**
 *   {@code https://share.fnnas.net/s/9d57d97236a04890bf} —— 由 {@link BrowserActivity}
 *   内嵌浏览器打开（车机没有浏览器 App 也能用），页面里点下载 .apk 由内嵌浏览器
 *   拦截下载后直接拉起安装。
 *
 * <p>★ 兜底：车机 ROM 若精简掉了 WebView 组件（内嵌浏览器起不来），回落
 *   {@link AppCenterPane}（应用内 GitHub Release 列表，不依赖任何网页），
 *   保证「软件仓库」在任何车机上都有一个能用的入口。
 *
 * <p>本类只做「入口转发」，所有 UI 与网络逻辑在 {@link BrowserActivity} / {@link AppCenterPane} 内。
 */
public final class AppCenter {

    /** 用户指定的软件仓库分享页（飞牛文件分享，SPA 页面，由内嵌浏览器打开） */
    public static final String STORE_URL = "https://share.fnnas.net/s/9d57d97236a04890bf";

    private AppCenter() {}

    /** 打开软件仓库：内嵌浏览器优先（用飞牛链接），WebView 缺失回落应用内 GitHub 面板。 */
    public static void open(Shell sh) {
        if (sh == null) return;
        Context c = sh.ctx();
        if (c == null) return;
        if (BrowserActivity.webViewAvailable(c)) {
            Intent i = new Intent(c, BrowserActivity.class);
            i.putExtra(BrowserActivity.EXTRA_URL, STORE_URL);
            i.putExtra(BrowserActivity.EXTRA_TITLE, "📦 软件仓库");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                return;
            } catch (Throwable t) {
                // 拉起失败（罕见）→ 落到下面的兜底面板
            }
        }
        new AppCenterPane(sh).open();
    }
}
