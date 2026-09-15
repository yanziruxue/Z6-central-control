package com.l6.carmedia;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮返回按钮。
 *
 * 背景：导航是通过「调起车机已装导航 App（外部 App）」实现的，一旦地图全屏，
 *      车机很可能没有实体返回键 / 任务键，用户就回不到本界面 —— 因此需要在
 *      屏幕边缘常驻一个「返回主页」悬浮按钮。
 *
 * 为什么不用 Service：
 *      Android 8+ 对后台 Service 有限制，且前台 Service 还得常驻一条通知（车机上很碍眼）。
 *      这里直接用 Application Context 往 WindowManager 上挂 TYPE_APPLICATION_OVERLAY：
 *      该类型窗口由**进程**持有、与 Activity 生命周期无关，Activity 销毁也不会连带移除或泄漏，
 *      只在进程被回收时消失。对本场景（低频、短时）足够且更轻。
 *
 * 权限：必须 SYSTEM_ALERT_WINDOW（Android 6+ 需在「设置 → 悬浮窗/显示在其他应用上层」手动授权），
 *      未授权时 show() 直接返回 false，由调用方降级提示。
 */
public final class FloatNav {

    private static View view;
    private static WindowManager wm;
    private static boolean shown = false;

    private FloatNav() {
    }

    /** 悬浮窗是否已授权（API 23 以下无需授权，恒返回 true）。 */
    public static boolean canDrawOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳系统「允许显示在其他应用上层」设置页。 */
    public static void openOverlaySettings(Context c) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 23) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + c.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + c.getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 显示「返回主页」悬浮按钮。
     *
     * @param appCtx 必须用 Application Context —— 避免悬浮窗跟随 Activity 生命周期被回收。
     * @param label  右上角小字（当前导航 App 名），可为空。
     * @return true 表示已挂上窗口；false 表示未授权或异常，调用方应提示用户去授权。
     */
    public static boolean show(Context appCtx, String label) {
        if (!canDrawOverlay(appCtx)) {
            return false;
        }
        hide(appCtx);   // 重复调用先移除旧的，避免叠加
        try {
            final Context ctx = appCtx.getApplicationContext();
            final int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14,
                    ctx.getResources().getDisplayMetrics());

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6141E2B);
            bg.setCornerRadius(999f);
            bg.setStroke(2, 0x3352BFFF);
            root.setBackground(bg);
            root.setPadding(pad, pad, pad, pad);

            TextView icon = new TextView(ctx);
            icon.setText("\uD83C\uDFE0");            // 🏠
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            root.addView(icon);

            if (label != null && !label.isEmpty()) {
                TextView tv = new TextView(ctx);
                tv.setText(label);
                tv.setTextColor(0xFFE6EDF6);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                        ctx.getResources().getDisplayMetrics());
                tv.setLayoutParams(lp);
                root.addView(tv);
            }

            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.START | Gravity.BOTTOM;   // 车机横屏：左下角
            p.x = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                    ctx.getResources().getDisplayMetrics());
            p.y = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                    ctx.getResources().getDisplayMetrics());

            // 拖动换位：避免悬浮按钮挡住地图关键信息
            final WindowManager.LayoutParams pp = p;
            root.setOnTouchListener(new View.OnTouchListener() {
                private float sx, sy;
                private int sxp, syp;
                private long downAt;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = e.getRawX();
                            sy = e.getRawY();
                            sxp = pp.x;
                            syp = pp.y;
                            downAt = System.currentTimeMillis();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            pp.x = sxp + (int) (e.getRawX() - sx);
                            pp.y = syp - (int) (e.getRawY() - sy);   // y 轴向下为正，反向才跟手
                            try {
                                wm.updateViewLayout(view, pp);
                            } catch (Throwable ignored) {
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            // 位移小于阈值且是「点按」→ 直接返回主页（不再经 performClick → onClick，避免拖动后点击链路丢失）
                            float dx = Math.abs(e.getRawX() - sx);
                            float dy = Math.abs(e.getRawY() - sy);
                            if (dx < 8 && dy < 8 && System.currentTimeMillis() - downAt < 400) {
                                hide(ctx);
                                bringToFront(ctx);
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return false;
            }
            wm.addView(root, p);
            view = root;
            shown = true;
            return true;
        } catch (Throwable t) {
            shown = false;
            return false;
        }
    }

    /** 移除悬浮按钮（回到本 App / 页面销毁时调用）。 */
    public static void hide(Context c) {
        if (view == null) {
            shown = false;
            return;
        }
        try {
            WindowManager w = wm != null ? wm
                    : (WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            if (w != null) {
                w.removeViewImmediate(view);
            }
        } catch (Throwable ignored) {
        }
        view = null;
        wm = null;
        shown = false;
    }

    public static boolean isShown() {
        return shown;
    }

    /** 把 MainActivity 拉回前台。 */
    private static void bringToFront(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.setAction(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            c.startActivity(i);
        } catch (Throwable ignored) {
            try {
                Intent pm = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                if (pm != null) {
                    pm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    c.startActivity(pm);
                }
            } catch (Throwable ignored2) {
            }
        }
    }
}
