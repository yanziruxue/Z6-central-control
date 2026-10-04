package com.l6.carmedia.nat;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * 原生主页容器对外暴露的能力 —— 各面板（主页三栏 / 底栏 / 设置页 / 抽屉 / 小部件面板）
 * 只依赖这个接口，不互相直接持有。
 *
 * ★ 拆这一层是为了让各面板可以**独立重写 + 独立编译**：主页三栏、底栏、设置页、
 *   抽屉各自只认 Shell，谁改了都不会牵动别人；调试时也能单独 new 出来挂到测试 Activity 上。
 */
public interface Shell {

    Host host();

    Prefs prefs();

    Context ctx();

    /** 所有浮层（抽屉 / 小部件面板 / OTA 蒙层 / Toast）都挂这里，保证在最上层 */
    FrameLayout overlay();

    // ------------------------------------------------------------------ 导航

    int PAGE_HOME = 0;
    int PAGE_SET = 1;

    int page();

    /** 翻页（带位移动画，与 JS 版的 track 位移同款） */
    void gotoPage(int i);

    // ------------------------------------------------------------------ 主页

    /** 主页三栏重建（「主页模式」改了勾选/顺序、小部件状态变了都要调） */
    void refreshHome();

    /**
     * 重建 dock 右侧的快捷方式区。
     *
     * ★ 抽屉里钉入 / 移除后必须调它 —— dock 只在自己初始化与拖动结束时重建，
     *   不通知就会出现「抽屉里已钉、底栏却没有」，要重启 App 才补上。
     */
    void refreshDock();

    /** 某栏当前是否显示 */
    boolean paneVisible(String pane);

    /** 重贴壁纸（换壁纸 / 换类型 / 改压暗层） */
    void applyWall();

    /** 主页「导航区」里的原生小部件槽；导航栏没勾选时为 null */
    ViewGroup widgetSlot();

    /** 小部件槽尺寸或绑定变了 → 重新贴合 */
    void refreshWidget();

    /** 每秒一次的系统数据（媒体 / 导航 / 车况），由 NatShell 转发给各面板 */
    void pushSys(JSONObject sys);

    // ------------------------------------------------------------------ 浮层

    /**
     * 打开应用抽屉。
     *
     * @param type  "music" / "nav" / null = 全部
     * @param title 标题文案
     * @param pick  选中回填；null = 纯浏览（此时长按可钉入/移除 dock 快捷方式）
     */
    void openDrawer(String type, String title, DrawerPick pick);

    /** 小部件选择面板 */
    void openWidgetPanel();

    /** 轻提示（原生 Toast；浮层已在最上层，不必自绘） */
    void toast(String msg);

    /** OTA 事件转发给设置页 / 强制更新蒙层 */
    void onOta(String kind, JSONObject data);

    /** 强制更新蒙层（force=true 的版本） */
    void forceUpdate(String changelog, Runnable onInstall, Runnable onCancel);

    /** 抽屉选中回调 */
    interface DrawerPick {
        void onPick(Def.App a);
    }
}
