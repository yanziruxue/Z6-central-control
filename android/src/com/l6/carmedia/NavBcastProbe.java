package com.l6.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高德广播探针（v1.5.20 · **探测版**）。
 *
 * 只做一件事：动态注册 {@link NavBcastFmt#ACTION_SEND}，把收到的每条广播压成一行摘要，
 * 经独立事件 {@code navbc} 推到主页导航区域的空态里显示。**绝不修改导航状态**（不碰 active）。
 *
 * 为什么要探测：高德有没有把广播发出来、车机 ROM 有没有把它转发给第三方应用，
 * 官方文档没有承诺（第三方项目的 README 也明说「不同车机 ROM 的广播行为不同」）。
 * 与其先写完整渲染再上机反复试，不如先把「收到了什么」摆出来 —— 一次实装就能定生死：
 *   · 有 KEY_TYPE=10001/10019 ⇒ 数据链成立，下一步直接做结构化渲染（转向/车道/红绿灯）；
 *   · 一直「未收到」      ⇒ 该 ROM 不转发，只能落回通知文案解析（或走 AIDL 等更重的路）。
 *
 * ★ 隐式广播：Android 8+ 禁止 manifest 静态注册接收隐式广播，所以这里**必须动态注册**
 *   （放在常驻的桌面 Activity 里；本应用是默认桌面，生命周期基本等同于开机到关机）。
 * ★ 收广播不需要任何权限，也不涉及签名权限。
 * ★ 隐私：协议里还有 CAR_LATITUDE / CAR_LONGITUDE（自车经纬度），本版**刻意不取** ——
 *   诊断行是要给用户截图看的，不该把位置信息摆到屏幕上。
 */
public final class NavBcastProbe {

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
    private static int count;
    private static String last = "";

    private NavBcastProbe() {
    }

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
            // 先推一条「已开始监听」：用户一眼能区分「探针没跑起来」和「跑起来了但没广播」
            last = "已开始监听 " + NavBcastFmt.ACTION_SEND + "（尚未收到）";
            push();
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
        if (r == null || c == null) {
            return;
        }
        try {
            c.getApplicationContext().unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 内部 ---------------- */

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
        push();
    }

    private static void push() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "navbc");
            o.put("bc", last);
            o.put("n", count);
            SysHub.push(o);
        } catch (Throwable ignored) {
        }
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
