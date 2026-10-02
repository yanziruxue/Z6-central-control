package com.l6.carmedia;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志收集器（内存环形缓冲 + 落盘 + 推送给 Tasker）。
 *
 * 设计目标：让「老六中控」的运行事件可被收集。
 *   - ① 推送给 Tasker：每条日志发一条广播 {@link #ACTION_LOG}，Tasker 用「Intent Received」
 *       事件（Action = com.l6.carmedia.LOG）即可实时收集到 L6 的日志，extras 见 EXTRA_*；
 *   - ② 落盘：当天日志写到 Download/L6/l6-YYYY-MM-DD.log（与壁纸同目录，便于人工/工具收集），
 *       高版本系统写不进公共目录时回落到应用私有目录 getExternalFilesDir/logs/；
 *   - ③ 内存缓冲：最近 300 条，供设置页弹层实时展示（getLogJson）。
 *
 * <p><b>v1.5.15 起不再包含「上报 api 接口」相关实现</b>（上报开关 / 自定义 DNS / 连通性探针 /
 * API 探测矩阵 / 每分钟自动上传 / 双通道 POST）—— 整套已删除，本类退回纯本地日志。
 * 采集类产物（信号采集 JSON）经 {@link #artifactDir()} 与日志落同一目录，靠 {@link #jsCall}
 * 回推页面。
 *
 * 所有写入/广播都包了 try/catch，单条失败不影响主流程（含 OTA、导航这种关键路径）。
 */
public final class L6Log {

    /** Tasker 用「Intent Received」事件监听此 Action 即可接收 L6 日志。 */
    public static final String ACTION_LOG = "com.l6.carmedia.LOG";
    public static final String EXTRA_LEVEL = "level";   // INFO / WARN / ERROR
    public static final String EXTRA_TAG = "tag";        // 模块，如 L6Nav / L6Ota
    public static final String EXTRA_MSG = "msg";        // 内容
    public static final String EXTRA_TIME = "time";      // 时间戳（epoch millis）

    private static final int MAX_BUFFER = 300;
    private static final List<Entry> buf = new ArrayList<>();
    private static final Object lock = new Object();
    private static boolean broadcastEnabled = true;
    private static Context ctx;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

    public static final class Entry {
        public final long t;
        public final String level, tag, msg;
        Entry(long t, String level, String tag, String msg) {
            this.t = t; this.level = level; this.tag = tag; this.msg = msg;
        }
    }

    /** 在 MainActivity.onCreate 调用一次，提供广播与落盘所需的 Context。 */
    public static void init(Context c) {
        ctx = c != null ? c.getApplicationContext() : null;
    }

    public static void i(String tag, String msg) { log("INFO", tag, msg); }
    public static void w(String tag, String msg) { log("WARN", tag, msg); }
    public static void e(String tag, String msg) { log("ERROR", tag, msg); }

    public static void log(String level, String tag, String msg) {
        if (level == null) level = "INFO";
        if (tag == null) tag = "";
        if (msg == null) msg = "";
        long t = System.currentTimeMillis();
        Entry e = new Entry(t, level, tag, msg);
        synchronized (lock) {
            buf.add(e);
            while (buf.size() > MAX_BUFFER) buf.remove(0);
        }
        appendFile(t, level, tag, msg);
        broadcast(level, tag, msg, t);
    }

    /** 实时推送给 Tasker（及其它监听该 Action 的接收者）。 */
    private static void broadcast(String level, String tag, String msg, long t) {
        if (!broadcastEnabled || ctx == null) return;
        try {
            Intent it = new Intent(ACTION_LOG);
            it.putExtra(EXTRA_LEVEL, level);
            it.putExtra(EXTRA_TAG, tag);
            it.putExtra(EXTRA_MSG, msg);
            it.putExtra(EXTRA_TIME, t);
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }

    /** 追加一行到当天日志文件（断电/崩溃后仍可追溯）。 */
    private static void appendFile(long t, String level, String tag, String msg) {
        try {
            File f = logFile();
            if (f == null) return;
            File p = f.getParentFile();
            if (p != null && !p.exists() && !p.mkdirs()) return;
            String line = FMT.format(new Date(t)) + " [" + level + "] " + tag + ": " + msg + "\n";
            FileWriter w = new FileWriter(f, true);
            w.append(line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 当天日志文件（**直接放 Download/L6 下**；取不到公共目录时回落应用私有目录 L6/）。 */
    public static File logFile() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "L6");
            return new File(dir, "l6-" + DATE.format(new Date()) + ".log");
        } catch (Throwable e) {
            try {
                if (ctx != null) {
                    return new File(ctx.getExternalFilesDir(null), "L6/l6-" + DATE.format(new Date()) + ".log");
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    public static String getLogPath() {
        File f = logFile();
        return f != null ? f.getAbsolutePath() : "";
    }

    /**
     * 结构化产物（信号采集 JSON）的落盘目录。
     * **刻意复用当天日志所在目录**（logFile() 的父目录），而不是自己再算一遍路径：
     * 两份产物必须能一次导出取回。自己算的话，一旦 logFile() 走了「应用私有目录」
     * 回落分支，就会出现「日志在这儿、JSON 在那儿」的分裂，人工收集时必然漏一个。
     *
     * <p>public 是因为 {@link SignalCapture} 也要往同一处落盘。
     */
    public static File artifactDir() {
        try {
            File f = logFile();
            File d = (f != null) ? f.getParentFile() : null;
            if (d != null) {
                if (!d.exists()) d.mkdirs();
                if (d.exists()) return d;
            }
        } catch (Throwable ignored) { }
        try {
            if (ctx != null) {
                File d2 = new File(ctx.getExternalFilesDir(null), "L6");
                if (!d2.exists()) d2.mkdirs();
                return d2;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 返回最近 n 条（n<=0 取全部），每条含 t/time/level/tag/msg。 */
    public static JSONArray recentJson(int n) {
        JSONArray arr = new JSONArray();
        synchronized (lock) {
            int start = buf.size() - (n > 0 ? n : MAX_BUFFER);
            if (start < 0) start = 0;
            for (int i = start; i < buf.size(); i++) {
                Entry e = buf.get(i);
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", e.t);
                    o.put("time", FMT.format(new Date(e.t)));
                    o.put("level", e.level);
                    o.put("tag", e.tag);
                    o.put("msg", e.msg);
                    arr.put(o);
                } catch (Throwable ignored) {
                }
            }
        }
        return arr;
    }

    /** 清空内存缓冲（不影响已落盘的历史文件）。 */
    public static void clear() {
        synchronized (lock) { buf.clear(); }
    }

    public static void setBroadcastEnabled(boolean b) { broadcastEnabled = b; }
    public static boolean isBroadcastEnabled() { return broadcastEnabled; }

    /**
     * 把一个 JSON 安全地作为「调用 window.&lt;fn&gt;(对象)」的 JS 片段（Base64 包一层）。
     *
     * <p>为什么必须包：结果里全是中文（标签名 / 系统属性值 / 错误原文），
     * 直接拼进 {@code evaluateJavascript} 会被引号或换行炸掉，而报错又是**静默**的。
     */
    public static String jsCall(String fn, String json) {
        String b64 = android.util.Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return "(window." + fn + "?window." + fn + "(JSON.parse((window.L6B64||atob)('"
                + b64 + "'))):null)";
    }

    /** 当前应用版本名（供采集产物记录来源版本）。 */
    public static String appVerPublic() {
        return appVer();
    }

    private static String appVer() {
        if (ctx == null) return "?";
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable e) { return "?"; }
    }
}
