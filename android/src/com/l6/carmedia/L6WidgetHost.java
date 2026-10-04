package com.l6.carmedia;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v1.5.23：把第三方「桌面小部件」真正承载进主页 —— 原生侧。
 *
 * 【为什么是这条路】
 * v1.5.20 已把「把第三方 **Activity 画面**塞进我们窗口」判死（被嵌 Activity 必须声明
 * android:allowEmbedded，高德官方包不会声明）。但**桌面小部件（App Widget）机制完全不同**：
 * 小部件本体是 RemoteViews，由**宿主自己 inflate**，不需要目标 App 做任何配合。
 * 老六就是这台车机的默认桌面 ⇒ 可以当 AppWidgetHost。
 *
 * 【v1.5.22 装车实测（只读探测）已确认可行】
 *   host OK · 系统 244 个 provider · bindAppWidgetIdIfAllowed() **被拒** · 授权界面 **有** · 选择器 有
 * ⇒ 直接绑定不行（我们是普通侧载 APK，没有 BIND_APPWIDGET 这个 signature|privileged 权限），
 *   但官方为这种情况留了补救入口：`ACTION_APPWIDGET_BIND` ——
 *   文档原文：「launch from your AppWidgetHost activity when you want to bind an AppWidget to
 *   display **and bindAppWidgetIdIfAllowed returns false**」。用户点一次「允许」就授。
 *
 * 【★ 最大的坑不在权限，在「放哪儿」】
 * 页面是 WebView（HTML），而 AppWidgetHostView 是**原生 View** —— 原生 View 永远进不了 DOM。
 * 所以只能把它当 **overlay 挂在 WebView 的父容器**上，位置 / 尺寸由前端用
 * `getBoundingClientRect()` **按百分比**上报（页面本身是 `--u` 等比缩放的，用百分比才不随分辨率漂）。
 * 前端在 syncHomeNav / resize 后都会重报一次；区域不可见（宽高为 0）时上报 0 ⇒ 这里自动收起。
 *
 * 【本版（A 方案 MVP）不做持久化】
 * 重启后需要用户重新选一次。但**上次会话残留的 widgetId 必须在启动时删掉** ——
 * 否则每次重启+重绑都在系统里留一个孤儿 id，越积越多（见 {@link #cleanupOrphan()}）。
 */
public final class L6WidgetHost {

    /** 与 {@link WidgetProbe} 用同一个 hostId —— 同一 hostId 在系统里就是同一个 host。 */
    public static final int HOST_ID = 0x1A60;
    /** 拉起系统授权框的 requestCode（MainActivity.onActivityResult 认这个值）。 */
    public static final int REQ_BIND = 0x1A61;

    private static final String PREFS = "l6widget";
    private static final String K_ID = "id";
    private static final String K_PKG = "pkg";
    private static final String K_CLS = "cls";
    private static final String K_LABEL = "label";

    private static L6WidgetHost I;

    private final Context ctx;
    private Activity act;
    private ViewGroup root;
    private AppWidgetHost host;
    private AppWidgetHostView view;

    private int curId = -1;
    private String curPkg = "";
    private String curCls = "";
    private String curLabel = "";

    /**
     * 原生直嵌模式（v2.0.0 起）：小部件被直接 addView 进主页的导航栏槽位，
     * 不再需要前端按百分比上报位置。置位后 {@link #applyPlace()} 变成空操作 ——
     * 否则页面里残留的 place() 调用会把它按百分比重新摆回 overlay 坐标，槽位里的布局被改坏。
     */
    private boolean nativeEmbed = false;

    /** 前端上报的区域矩形（屏幕百分比）。宽或高 ≤0 表示「区域当前不可见」⇒ 收起 overlay。 */
    private volatile double pl = -1, pt = -1, pw = -1, ph = -1;

    private L6WidgetHost(Context c) {
        ctx = c.getApplicationContext();
    }

    public static synchronized L6WidgetHost get(Context c) {
        if (I == null) {
            I = new L6WidgetHost(c);
        }
        return I;
    }

    /** 挂载 overlay 容器（WebView 的父 FrameLayout）。 */
    public void attach(Activity a, ViewGroup r) {
        act = a;
        root = r;
    }

    /**
     * 原生直嵌：把当前小部件（若有）直接填进给定容器。
     *
     * ★ 与 {@link #attach(Activity, ViewGroup)} 的区别：那个是把小部件当 overlay 挂在
     *   WebView 的父容器上、靠百分比定位；这里是把它当成**普通子视图**交给原生布局管。
     *   两条通道互斥 —— 进了直嵌就不再 applyPlace。
     *
     * ★ 必须在主线程调用（AppWidgetHost 要求「同一条线程创建 + 使用」。
     */
    public void attachInto(final ViewGroup slot) {
        if (slot == null) {
            return;
        }
        nativeEmbed = true;
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    slot.removeAllViews();
                    if (view == null) {
                        return;
                    }
                    ViewGroup old = (ViewGroup) view.getParent();
                    if (old != null) {
                        old.removeView(view);
                    }
                    view.setVisibility(View.VISIBLE);
                    view.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    slot.addView(view);
                    // 槽位尺寸要等一次布局才有 ⇒ 量到了再告诉 provider「你被放在多大一块地方」。
                    // 不报的话 provider 按自己的 min 尺寸渲染，塞进槽里会错位。
                    reportSizeWhenLaid(slot);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void reportSizeWhenLaid(final ViewGroup slot) {
        slot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private boolean done = false;

            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (done || view == null || r - l <= 0 || b - t <= 0) {
                    return;
                }
                done = true;
                try {
                    float d = ctx.getResources().getDisplayMetrics().density;
                    int wdp = Math.max(1, Math.round((r - l) / d));
                    int hdp = Math.max(1, Math.round((b - t) / d));
                    view.updateAppWidgetSize(new Bundle(), wdp, hdp, wdp, hdp);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 建 host 并开始监听（必须在主线程调用）。顺便清掉上次会话残留的绑定。 */
    public void listen() {
        try {
            if (host == null) {
                host = new AppWidgetHost(ctx, HOST_ID);
            }
            host.startListening();
        } catch (Throwable ignored) {
        }
        cleanupOrphan();
    }

    public void stop() {
        try {
            if (host != null) {
                host.stopListening();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * A 方案不恢复绑定 ⇒ 上次会话留下的 widgetId 直接删掉。
     * 不删的后果：每次「重启 + 重新选一次小部件」都在系统里多一个悬空 id，久了会拖慢绑定。
     */
    private void cleanupOrphan() {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int id = sp.getInt(K_ID, -1);
            if (id > 0 && host != null) {
                host.deleteAppWidgetId(id);
            }
            sp.edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    /** 全部 provider → JSON 数组串（供页面自建选择列表；不用系统 PICK，它返回的 id 和我们的 host 对不上）。 */
    public String listJson() {
        JSONArray arr = new JSONArray();
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            PackageManager pm = ctx.getPackageManager();
            List<AppWidgetProviderInfo> list = awm.getInstalledProviders();
            if (list != null) {
                List<AppWidgetProviderInfo> copy = new ArrayList<>(list);
                Collections.sort(copy, new Comparator<AppWidgetProviderInfo>() {
                    @Override
                    public int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                        String pa = (a == null || a.provider == null) ? "" : a.provider.getPackageName();
                        String pb = (b == null || b.provider == null) ? "" : b.provider.getPackageName();
                        return pa.compareTo(pb);
                    }
                });
                for (AppWidgetProviderInfo in : copy) {
                    if (in == null || in.provider == null) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("label", labelOf(pm, in));
                    o.put("pkg", in.provider.getPackageName());
                    o.put("cls", in.provider.getClassName());
                    o.put("w", in.minWidth);
                    o.put("h", in.minHeight);
                    arr.put(o);
                }
            }
        } catch (Throwable ignored) {
        }
        return arr.toString();
    }

    /** ① 分配 id 并尝试直接绑定；被拒就把 id 留给后面的系统授权框（事件回 needAuth）。 */
    public void bind(final String pkg, final String cls) {
        post(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    if (host == null) {
                        listen();
                    }
                    ComponentName cn = new ComponentName(pkg, cls);
                    int id = host.allocateAppWidgetId();
                    boolean ok = false;
                    try {
                        ok = AppWidgetManager.getInstance(ctx).bindAppWidgetIdIfAllowed(id, cn);
                    } catch (Throwable ignored) {
                    }
                    curPkg = pkg;
                    curCls = cls;
                    curLabel = labelOfProvider(pkg, cls);
                    if (ok) {
                        show(id, cn);                       // 本机已授予 BIND_APPWIDGET 才会走到这里
                        o.put("on", true);
                    } else {
                        // ★ 官方补救路径：把 id 与 provider 交给系统授权框
                        curId = id;
                        o.put("on", false);
                        o.put("needAuth", true);
                        o.put("id", id);
                    }
                    o.put("label", curLabel);
                } catch (Throwable t) {
                    try {
                        o.put("on", false);
                        o.put("err", String.valueOf(t));
                    } catch (Throwable ignored) {
                    }
                }
                push(o);
            }
        });
    }

    /** ② 拉起系统「允许 X 添加小部件」授权框。 */
    public void auth(final int id, final String pkg, final String cls) {
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act == null) {
                        return;
                    }
                    curId = id;
                    curPkg = pkg;
                    curCls = cls;
                    curLabel = labelOfProvider(pkg, cls);
                    Intent it = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, new ComponentName(pkg, cls));
                    act.startActivityForResult(it, REQ_BIND);
                } catch (Throwable t) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("on", false);
                        o.put("err", String.valueOf(t));
                    } catch (Throwable ignored) {
                    }
                    push(o);
                }
            }
        });
    }

    /**
     * ③ 授权框结果。文档要求：RESULT_OK ⇒ 已绑定（若 provider 带 configure Activity 还得接着启）；
     *    RESULT_CANCELED ⇒ 必须自己 deleteAppWidgetId，别在系统里留悬空 id。
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_BIND) {
            return;
        }
        JSONObject o = new JSONObject();
        try {
            if (resultCode == Activity.RESULT_OK) {
                int id = (data != null)
                        ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, curId) : curId;
                if (id <= 0) {
                    id = curId;
                }
                curId = id;
                show(id, new ComponentName(curPkg, curCls));
                o.put("on", true);
                o.put("label", curLabel);
            } else {
                if (curId > 0 && host != null) {
                    try {
                        host.deleteAppWidgetId(curId);
                    } catch (Throwable ignored) {
                    }
                }
                curId = -1;
                o.put("on", false);
                o.put("cancelled", true);
            }
        } catch (Throwable t) {
            try {
                o.put("on", false);
                o.put("err", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        push(o);
    }

    /** ④ 前端上报区域矩形（屏幕百分比）。 */
    public void place(final double l, final double t, final double w, final double h) {
        pl = l;
        pt = t;
        pw = w;
        ph = h;
        post(new Runnable() {
            @Override
            public void run() {
                applyPlace();
            }
        });
    }

    /** ⑤ 移除：清 view、删 id、清存档。 */
    public void clear() {
        post(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    detachView();
                    if (curId > 0 && host != null) {
                        try {
                            host.deleteAppWidgetId(curId);
                        } catch (Throwable ignored) {
                        }
                    }
                    curId = -1;
                    curPkg = "";
                    curCls = "";
                    curLabel = "";
                    pw = -1;
                    ph = -1;
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
                    o.put("on", false);
                } catch (Throwable t) {
                    try {
                        o.put("on", false);
                        o.put("err", String.valueOf(t));
                    } catch (Throwable ignored) {
                    }
                }
                push(o);
            }
        });
    }

    /** 当前状态（页面重新渲染时对齐用；A 方案重启不恢复，所以启动时必然是 off）。 */
    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", view != null && curId > 0);
            o.put("label", curLabel);
            o.put("id", curId);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /* ---------------- 内部 ---------------- */

    private void show(int id, ComponentName cn) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null || host == null || root == null) {
                return;
            }
            detachView();
            view = host.createView(ctx, id, info);
            view.setAppWidget(id, info);                 // 不调这个，provider 收不到尺寸变化
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(1, 1);
            root.addView(view, lp);
            curId = id;
            if (cn != null) {
                curPkg = cn.getPackageName();
                curCls = cn.getClassName();
            }
            // 记住 id：A 方案不恢复绑定，但下次启动要靠它把孤儿 id 删掉
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(K_ID, id).putString(K_PKG, curPkg)
                    .putString(K_CLS, curCls).putString(K_LABEL, curLabel).apply();
            applyPlace();
        } catch (Throwable ignored) {
        }
    }

    private void detachView() {
        try {
            if (view != null && root != null) {
                root.removeView(view);
            }
        } catch (Throwable ignored) {
        }
        view = null;
    }

    /** 按百分比把 overlay 摆到区域上；区域不可见（≤0）就收起。 */
    private void applyPlace() {
        try {
            if (nativeEmbed) {
                return;      // 直嵌模式下由原生布局管位置，百分比上报一律忽略
            }
            if (view == null || root == null) {
                return;
            }
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(1, 1);
            }
            if (pw <= 0.001 || ph <= 0.001) {
                view.setVisibility(View.INVISIBLE);
                lp.width = 1;
                lp.height = 1;
                lp.leftMargin = 0;
                lp.topMargin = 0;
            } else {
                view.setVisibility(View.VISIBLE);
                lp.width = Math.max(1, (int) Math.round(pw * w));
                lp.height = Math.max(1, (int) Math.round(ph * h));
                lp.leftMargin = Math.max(0, (int) Math.round(pl * w));
                lp.topMargin = Math.max(0, (int) Math.round(pt * h));
                // 告诉 provider「你被放在了多大一块地方」——不报的话它按自己的 min 尺寸渲染，塞进区域会错位
                try {
                    float d = ctx.getResources().getDisplayMetrics().density;
                    int wdp = Math.max(1, Math.round(lp.width / d));
                    int hdp = Math.max(1, Math.round(lp.height / d));
                    view.updateAppWidgetSize(new Bundle(), wdp, hdp, wdp, hdp);
                } catch (Throwable ignored) {
                }
            }
            view.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    private void push(JSONObject o) {
        try {
            o.put("kind", "widget");
        } catch (Throwable ignored) {
        }
        SysHub.push(o);
    }

    /** 所有 UI 动作都甩到主线程 —— AppWidgetHost 要求「同一条线程创建 + 使用」。 */
    private void post(Runnable r) {
        try {
            if (root != null) {
                root.post(r);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (act != null) {
                act.runOnUiThread(r);
            }
        } catch (Throwable ignored) {
        }
    }

    private String labelOfProvider(String pkg, String cls) {
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (cs != null && cs.toString().trim().length() > 0) {
                return cs.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private static String labelOf(PackageManager pm, AppWidgetProviderInfo in) {
        try {
            CharSequence cs = in.loadLabel(pm);
            if (cs != null && cs.toString().trim().length() > 0) {
                return cs.toString().trim();
            }
        } catch (Throwable ignored) {
        }
        return in.provider == null ? "?" : in.provider.getPackageName();
    }
}
