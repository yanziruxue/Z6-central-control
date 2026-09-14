package com.l6.carmedia;

import android.util.Base64;

import org.json.JSONObject;

/**
 * 系统实时数据中枢。
 *
 * 把「媒体会话 / 导航通知 / 通知使用权状态」统一成一条事件推给页面：
 *   evaluateJavascript(window.L6SysEvent(JSON.parse(atob(...))))
 *
 * 事件形态（kind 区分）：
 *   {kind:"media",  active, title, artist, album, pkg, app, playing, pos, dur, art?}
 *   {kind:"nav",    active, pkg, app, arrow, turn, road, next, remain, eta, clock, dest, raw}
 *   {kind:"lyrics", title, artist, source, lines:[{t,s}] | error}
 *   {kind:"access", granted}
 *
 * 之所以用 Base64 + atob：歌词/通知文本里可能带引号、换行、emoji，
 * 直接拼进 evaluateJavascript 会炸，Base64 编码后是纯 ASCII 字母数字，绝对安全。
 */
public class SysHub {

    public interface Emit {
        void js(String js);
    }

    private static volatile Emit emitter;

    public static void setEmitter(Emit e) {
        emitter = e;
    }

    /** 推送一个 JSON 对象事件。 */
    public static void push(JSONObject o) {
        if (o == null) {
            return;
        }
        pushJson(o.toString());
    }

    /** 推送一段已经序列化好的 JSON 文本。 */
    public static void pushJson(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        Emit e = emitter;
        if (e == null) {
            return;
        }
        try {
            e.js(pack("L6SysEvent", json));
        } catch (Throwable ignored) {
        }
    }

    /** 通知使用权状态变化。 */
    public static void pushAccess(boolean granted) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "access");
            o.put("granted", granted);
            push(o);
        } catch (Throwable ignored) {
        }
    }

    /** 把 JSON 安全地作为 JS 字面量塞进 evaluateJavascript（atob 解码后 JSON.parse）。 */
    public static String pack(String entry, String json) {
        String b64 = Base64.encodeToString(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "(window." + entry + "?window." + entry + "(JSON.parse((window.L6B64||atob)('" + b64 + "'))):null)";
    }
}
