package com.l6.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高德广播探针 / 数据源（v1.5.20 探测版 → **v1.5.21 升级为导航数据源**）。
 *
 * 为什么最终走广播而不是解析通知：2026-10-03 装车实测（v1.5.20 探测版）拿到
 * `KEY_TYPE=10001 · 引导 · 导航 · 无名道路 → 兴物线 · 本段剩 1.1公里 · 电子眼 451m`，
 * 其中「1.1公里」「451m」与高德自己悬浮窗上的「1.1 公里」「451米」**逐个吻合** ——
 * 而同一时刻「最近没有收到导航 App 的任何通知」⇒ 通知那条路在这台车机上是**死的**。
 * 于是本类从「只显示摘要」升级为**真正的导航数据源**，通知解析退居兜底。
 *
 * 三条状态判定（广播没有「导航开始/结束」的必然保证，得自己拼）：
 *   1. 收到 `10001` ⇒ 导航中（active=true），并刷新 lastGuideAt；
 *   2. 收到 `10019` 且 `EXTRA_STATE` = 9（结束导航）/ 39（到达目的地）⇒ 立刻结束；
 *   3. 12 秒没再收到 `10001` ⇒ 判定超时结束（车已熄火 / 已退出导航 / 广播被 ROM 掐）。
 *
 * ★ 隐式广播：Android 8+ 禁止 manifest 静态注册接收隐式广播 ⇒ **必须动态注册**
 *   （放在常驻的桌面 Activity 里；本应用是默认桌面，生命周期≈开机到关机）。
 * ★ 收广播不需要任何权限，也不涉及签名权限。
 * ★ 隐私：协议里还有 `CAR_LATITUDE` / `CAR_LONGITUDE`（自车经纬度），**刻意不取** ——
 *   诊断行是要给用户截图看的，不该把位置信息摆到屏幕上。
 */
public final class NavBcastProbe {

    /** 超过这么久没收到 10001 就认为导航结束（秒表由刷新频率决定，10001 通常 1 秒 1 条）。 */
    private static final long STALE_MS = 12000;
    /** 超时检查节奏。 */
    private static final long TICK_MS = 2000;

    /** 探测期提取的字段（★ 刻意排除经纬度）。 */
    private static final String[] KEYS = {
            "TYPE",
            "CUR_ROAD_NAME", "NEXT_ROAD_NAME",
            "ICON", "NEW_ICON",
            "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME",
            "ROUTE_REMAIN_DIS_AUTO", "ROUTE_REMAIN_TIME_AUTO",
            "SEG_REMAIN_DIS", "SEG_REMAIN_DIS_AUTO", "NEXT_SEG_REMAIN_DIS",
            "CUR_SPEED", "LIMITED_SPEED",
            "CAMERA_DIST", "CAMERA_TYPE", "CAMERA_SPEED",
            "SAPA_DIST", "SAPA_NAME", "SAPA_TYPE",
            "TRAFFIC_LIGHT_NUM",
            "EXTRA_STATE", "STATE",
            "EXTRA_DRIVE_WAY", "EXTRA_TMC_SEGMENT",
            "lightsData", "redLightCountDownSeconds", "greenLightLastSecond",
    };

    private static BroadcastReceiver rx;
    private static Handler ticker;
    private static int count;
    /** 诊断行用的「最近一条广播」摘要（含已开始监听 / 超时等状态文案）。 */
    private static String last = "";
    /** 通知监听服务的连接状态（null = 还不知道）。由 {@link L6NotifyService} 回报。 */
    private static Boolean svc;
    /** 最近一条 10001 的时刻。 */
    private static long lastGuideAt;
    /** 当前是否已经按「导航中」推给页面。 */
    private static boolean active;
    /** 已推送的导航 JSON（内容没变就不重复推）。 */
    private static String lastNavJson = "";

    private NavBcastProbe() {
    }

    /* ==================== 生命周期 ==================== */

    /** 注册探针（重复调用无副作用）。 */
    public static void start(Context c) {
        if (rx != null || c == null) {
            return;
        }
        try {
            final BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent it) {
                    try {
                        onHit(it);
                    } catch (Throwable ignored) {
                    }
                }
            };
            IntentFilter f = new IntentFilter(NavBcastFmt.ACTION_SEND);
            c.getApplicationContext().registerReceiver(r, f);
            rx = r;
            count = 0;
            active = false;
            lastGuideAt = 0L;
            lastNavJson = "";
            // 先推一条「已开始监听」：用户一眼能区分「探针没跑起来」和「跑起来了但没广播」
            last = "已开始监听 " + NavBcastFmt.ACTION_SEND + "（尚未收到）";
            startTicker();
            pushStatus();
            L6Log.i("L6Nav", "高德广播探针已注册 action=" + NavBcastFmt.ACTION_SEND);
        } catch (Throwable t) {
            rx = null;
            L6Log.i("L6Nav", "高德广播探针注册失败 " + t);
        }
    }

    /** 注销探针。 */
    public static void stop(Context c) {
        BroadcastReceiver r = rx;
        rx = null;
        stopTicker();
        if (r == null || c == null) {
            return;
        }
        try {
            c.getApplicationContext().unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    /** 由 {@link L6NotifyService} 回报「通知使用权服务」是否已连上（诊断用，能区分服务没起来 / 没通知）。 */
    public static void noteService(boolean connected) {
        svc = connected;
        if (rx != null) {
            pushStatus();
        }
    }

    /** 广播数据是否新鲜（通知源据此让位，避免两条链路互相打架）。 */
    public static boolean isLive() {
        return active && (System.currentTimeMillis() - lastGuideAt) <= STALE_MS;
    }

    /* ==================== 超时判定 ==================== */

    private static void startTicker() {
        if (ticker != null) {
            return;
        }
        try {
            ticker = new Handler(Looper.getMainLooper());
            ticker.postDelayed(tick, TICK_MS);
        } catch (Throwable t) {
            ticker = null;
        }
    }

    private static void stopTicker() {
        Handler h = ticker;
        ticker = null;
        if (h != null) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Handler h = ticker;
            if (h == null) {
                return;
            }
            try {
                if (active && (System.currentTimeMillis() - lastGuideAt) > STALE_MS) {
                    last = "广播超时（" + (STALE_MS / 1000) + "s 未收到 10001）→ 判定导航结束";
                    active = false;
                    pushInactive("stale");
                }
            } catch (Throwable ignored) {
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    /* ==================== 广播到达 ==================== */

    private static void onHit(Intent it) {
        Bundle ex = (it == null) ? null : it.getExtras();
        int kt = (ex == null) ? -1 : ex.getInt(NavBcastFmt.EXTRA_KEY_TYPE, -1);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (ex != null) {
            for (String k : KEYS) {
                if (ex.containsKey(k)) {
                    m.put(k, val(ex.get(k)));
                }
            }
            if (m.isEmpty()) {
                // 一个已知字段都没有 ⇒ 把收到的键全摆出来，免得「未收录接口」被当成「没收到」
                for (String k : ex.keySet()) {
                    if (m.size() >= 12) {
                        break;
                    }
                    m.put(k, val(ex.get(k)));
                }
            }
        }

        count++;
        last = "[" + count + "] " + NavBcastFmt.summarize(kt, m);
        L6Log.i("L6Nav", "高德广播 " + last);

        if (kt == NavBcastFmt.KT_GUIDE) {
            pushGuide(m);
        } else if (kt == NavBcastFmt.KT_STATE) {
            checkStateEnd(m);
        }
        // 无论认不认得，都刷新诊断行（探测期最重要的能力就是「看得见」）
        pushStatus();
    }

    /** 10001 引导信息 ⇒ 导航中。 */
    private static void pushGuide(Map<String, Object> m) {
        NavBcastData d = NavBcastData.of(m);
        lastGuideAt = System.currentTimeMillis();
        active = true;
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", true);
            o.put("src", NavBcastData.SRC_BCAST);
            o.put("arrow", d.arrow());
            o.put("turn", d.turnName());
            o.put("road", d.curRoad);
            o.put("next", d.nextRoad);
            o.put("remain", d.remainDist);
            o.put("eta", d.remainTime);
            o.put("seg", d.segDist);
            // ★ 不发 dest：广播源的 NEXT_ROAD_NAME 是「下一条路」，不是目的地；
            //   当成「到 X」显示是错的（v1.5.20 的暂用写法，v1.5.21 渲染已能自己拼 next）
            o.put("speed", d.speed);
            o.put("limit", d.hasLimit() ? d.limit : -1);
            o.put("camera", d.hasCamera() ? d.camera : -1);
            o.put("light", d.hasLight() ? d.light : -1);
            o.put("chips", d.chips());
            o.put("icon", d.icon);
            o.put("type", d.type);
            o.put("cruise", d.isCruise());
            o.put("raw", last);

            String json = o.toString();
            if (json.equals(lastNavJson)) {
                return;                                    // 内容没变不重复推（10001 通常 1s 一条）
            }
            lastNavJson = json;
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    /** 10019 地图状态 ⇒ 导航开始 / 结束的权威信号。 */
    private static void checkStateEnd(Map<String, Object> m) {
        String raw = str(m, "EXTRA_STATE");
        if (raw.isEmpty()) {
            raw = str(m, "STATE");
        }
        int st = NavBcastFmt.intOf(raw, Integer.MIN_VALUE);
        if (st == 9 || st == 39) {
            // 9 结束导航 / 39 到达目的地 —— 这是高德自己说的，比超时更可信，立刻结束
            last = "10019 状态 " + st + " " + NavBcastFmt.stateName(st) + " → 结束";
            active = false;
            pushInactive("state" + st);
        }
    }

    /* ==================== 推送 ==================== */

    private static void pushInactive(String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "nav");
            o.put("active", false);
            o.put("src", NavBcastData.SRC_BCAST);
            o.put("reason", reason);
            String json = o.toString();
            if (json.equals(lastNavJson)) {
                return;
            }
            lastNavJson = json;
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    /** 诊断事件：只更新「最近一条广播」那一行，绝不碰 active 状态。 */
    private static void pushStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "navbc");
            o.put("bc", last);
            o.put("n", count);
            if (svc != null) {
                o.put("svc", svc.booleanValue());
            }
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 工具 ==================== */

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String val(Object v) {
        try {
            String t = String.valueOf(v).replace('\n', ' ').trim();
            return t.length() > 48 ? (t.substring(0, 48) + "…") : t;
        } catch (Throwable e) {
            return "";
        }
    }
}
