package com.l6.carmedia.nat;

/**
 * 应用中心（v2.0.39 重构）：右下角 📦 应用中心按钮入口。
 *
 * <p>★ v2.0.37 曾用飞牛分享页（网页 SPA）承载「应用中心」，但车机常无浏览器，
 *   且 SPA 不是 API、App 读不出软件列表，只能三级降级去复制链接，本质是伪降级。
 *
 * <p>★ v2.0.39 改为<b>应用内软件仓库</b>：数据源复用本 App 自己的 GitHub Release
 *   （车机已验证可直连 + gh-proxy 镜像回落），点一下即在原生面板里列出全部版本、直接装，
 *   全程不打开任何网页、不依赖浏览器。列表拉取与下载安装均走 {@link Ota} 复用链路。
 *
 * <p>本类只做「入口转发」，所有 UI 与网络逻辑在 {@link AppCenterPane} / {@link Ota} 内。
 */
public final class AppCenter {
    private AppCenter() {}

    /** 打开应用内软件仓库面板（不依赖浏览器）。 */
    public static void open(Shell sh) {
        if (sh == null) return;
        new AppCenterPane(sh).open();
    }
}
