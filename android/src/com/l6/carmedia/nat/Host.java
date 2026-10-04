package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 原生 UI 需要的「宿主能力」——由 Activity 实现，视图层只依赖这个接口。
 *
 * ★ 为什么要有这层接口：
 *   原生重写期里，WebView 版还要留着当**逐块对照的基准**（同一台模拟器、同一份设置，
 *   一边截图一边比）。有了 Host，视图层就不直接绑死 MainActivity，两边可以共存；
 *   等收尾删 WebView 时，把 Host 的实现搬进 MainActivity 即可，视图层一行不用改。
 *
 * ★ 线程约定：**全部方法都在主线程调用**（视图事件回调 / Handler 主循环）。
 *   需要回到 UI 线程时用 {@link #ui(Runnable)}。
 */
public interface Host {

    Context ctx();

    // ------------------------------------------------------------------ 应用

    /** 全部可启动应用（已归类去重）。耗时约几十毫秒，视图层应缓存。 */
    List<Def.App> allApps();

    /** 按类型取应用：{@code "music"} / {@code "nav"} */
    List<Def.App> appsOfType(String type);

    /** 应用图标（真实 PNG 解码后的 Bitmap）；取不到返回 null，调用方回落 emoji */
    Bitmap icon(String pkgOrKey);

    /** 应用显示名（认不出返回包名本身） */
    String appName(String pkg);

    // ------------------------------------------------------------------ 动作

    /** 调起「音/导航源」：入参是短 key（usb/bt/kugou…）或包名 */
    void launchSrc(String srcKey);

    /** 按包名直接调起（dock / 抽屉用） */
    void launchPkg(String pkg);

    /** 切回车机系统 Launcher（dock 右下角 🏠 与手势 up 的 "home" 都走这条） */
    void goHome();

    /** 回主线程执行 */
    void ui(Runnable r);

    /** 轻提示（原生 Toast） */
    void toast(String msg);

    // ------------------------------------------------------------------ 真实系统数据

    /**
     * 系统状态快照，与 JS 版 getSysState() 同构，字段：
     * <pre>
     *  { "media": {...}, "nav": {...}, "lyrics": {...}, "time": "...", "car": {...} }
     * </pre>
     * 主页每秒取一次。任何字段缺失都必须容错（宁可不显示，也不能整块崩）。
     */
    JSONObject sysState();

    /** 媒体控制："prev" / "play" / "pause" / "next" / "toggle" */
    void mediaControl(String action);

    /** 请求歌词（标题 / 艺术家） */
    void requestLyrics(String title, String artist);

    /** 强制刷新系统数据源（媒体会话重绑 + 通知监听重扫） */
    void refreshSys();

    // ------------------------------------------------------------------ 系统权限

    boolean hasNotifyAccess();

    void openNotifyAccess();

    boolean isDefaultHome();

    void openHomeSettings();

    boolean hasOverlay();

    void openOverlay();

    /** 跳到某个 App 自己的「显示在其他应用上层」权限页（高德全局悬浮导航用） */
    boolean openAppOverlaySettings(String pkg);

    // ------------------------------------------------------------------ 运行日志

    JSONArray log(int n);

    void clearLog();

    String logPath();

    boolean logBroadcast();

    void setLogBroadcast(boolean b);

    void exportLog();

    // ------------------------------------------------------------------ 车机信号采集

    void startSignal();

    void stopSignal();

    boolean isSignalCapturing();

    /**
     * 删除 {@code Download/L6/} 下全部 {@code signal-*.json} 采集产物。
     *
     * <p>★ v2.0.3 新增。**只删采集产物，不碰运行日志 {@code l6-*.log}** ——
     * 后者含「原生界面已启用」那行，是判断原生界面起没起来的唯一依据。
     *
     * @return 实际删除的文件数（0 = 没有可清的 / 目录取不到）
     */
    int clearSignalLogs();

    // ------------------------------------------------------------------ OTA

    /** 当前版本/通道等；字段名与 JS 版 getOtaConfig() 一致，缺失容错 */
    JSONObject otaConfig();

    /** 手检更新（异步；结果通过 {@link #otaEvent(String, JSONObject)} 回推） */
    void checkOta();

    void installOta(String url, String sha256);

    void cancelOta();

    /**
     * OTA 事件回推（主线程）：{@code kind} ∈
     * {@code checking / available / latest / downloading / progress / downloaded /
     * installing / needPermission / error / cancelled}。
     */
    void otaEvent(String kind, JSONObject data);

    // ------------------------------------------------------------------ 壁纸

    /** 拉起系统「选择文件」；选中后由 Host 读字节 + 落盘，然后回调 {@link #onWallSaved} */
    void pickWallpaperFile(String kind);

    void onWallSaved(String kind, Prefs.WallItem item);

}
