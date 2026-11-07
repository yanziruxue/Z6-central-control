package com.l6.carmedia.nat;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 应用中心入口（v2.0.37）：右下角 📋 应用列表按钮右侧的固定按钮。
 *
 * <p>★ 该地址与 NAS 收集页同属飞牛分享网页 SPA，不是 API。App 内不复刻 fnOS 私有
 *   协议（authx 签名 / 密码 / 动态中继），只负责用系统浏览器打开。
 *
 * <p>★★ 车机常没有浏览器，因此提供三级降级：ACTION_VIEW → 剪贴板复制链接 →
 *   ACTION_SEND 文本分享 → 直接贴出完整链接。每一级只在**真实执行之后**才返回对应
 *   文案，绝不谎报「打开成功 / 安装成功」。
 */
public final class AppCenter {
    private AppCenter() {}

    /** 应用中心飞牛分享页（单一真源，避免多处写死地址）。 */
    public static final String URL = "https://share.fnnas.net/s/9d57d97236a04890bf";

    /**
     * 打开应用中心，返回可直接 toast 的真实结果文案。
     * 所有分支都吞掉异常（入口按钮在 UI 线程，任何异常都不许把界面带崩）。
     */
    public static String open(Context c) {
        if (c == null) return "⚠️ 应用中心：" + URL;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(view);
            return "✅ 已打开应用中心";
        } catch (ActivityNotFoundException noBrowser) {
            // 设备无浏览器：走剪贴板降级
        } catch (Throwable ignored) {
        }
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("应用中心", URL));
                return "⚠️ 设备无浏览器，应用中心链接已复制\n" + URL;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "应用中心");
            send.putExtra(Intent.EXTRA_TEXT, URL);
            Intent chooser = Intent.createChooser(send, "分享应用中心链接");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(chooser);
            return "⚠️ 设备无浏览器，已打开分享\n应用中心：" + URL;
        } catch (Throwable ignored) {
        }
        return "⚠️ 请手动打开应用中心：" + URL;
    }
}
