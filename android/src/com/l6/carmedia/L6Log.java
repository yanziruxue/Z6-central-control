package com.l6.carmedia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 运行日志收集器（内存环形缓冲 + 落盘 + 推送给 Tasker）。
 *
 * 设计目标：让「老六中控」的运行事件可被收集。
 *   - ① 推送给 Tasker：每条日志发一条广播 {@link #ACTION_LOG}，Tasker 用「Intent Received」
 *       事件（Action = com.l6.carmedia.LOG）即可实时收集到 L6 的日志，extras 见 EXTRA_*；
 *   - ② 落盘：当天日志写到 Download/L6/logs/l6-YYYY-MM-DD.log（与壁纸同目录，便于人工/工具收集），
 *       高版本系统写不进公共目录时回落到应用私有目录 getExternalFilesDir/logs/；
 *   - ③ 内存缓冲：最近 300 条，供设置页弹层实时展示（getLogJson）。
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

    /** 日志上传到服务器的目标接口（API 形态：POST JSON）。改这里即可换地址/路径。 */
    private static final String LOG_UPLOAD_URL = "https://l6cc.ziruxue.top/api/log";
    /** 自动上传间隔（秒）。用户要求每分钟一次。 */
    private static final int UPLOAD_INTERVAL_SEC = 60;
    private static ScheduledExecutorService scheduler;
    private static long sentOffset = 0;          // 已发送到服务器的字节偏移（增量上传）
    private static String sentFile = "";         // 当前正在追的日志文件名（跨天换文件自动从头）
    private static String deviceId = "unknown";  // 设备标识（ANDROID_ID），多车机区分来源
    private static JSONObject lastStatus = new JSONObject();  // 最近一次上传结果，供页面读取
    private static UploadListener uploadListener;
    private static final Runnable UPLOAD_TASK = L6Log::uploadOnce;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

    public static final class Entry {
        public final long t;
        public final String level, tag, msg;
        Entry(long t, String level, String tag, String msg) {
            this.t = t; this.level = level; this.tag = tag; this.msg = msg;
        }
    }

    /** 在 MainActivity.onCreate 调用一次，提供广播与落盘所需的 Context，并启动定时上传。 */
    public static void init(Context c) {
        ctx = c != null ? c.getApplicationContext() : null;
        startUploader();
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

    /** 当天日志文件（优先 Download/L6/logs；不可写时回落应用私有目录）。 */
    public static File logFile() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "L6/logs");
            return new File(dir, "l6-" + DATE.format(new Date()) + ".log");
        } catch (Throwable e) {
            try {
                if (ctx != null) {
                    return new File(ctx.getExternalFilesDir(null), "logs/l6-" + DATE.format(new Date()) + ".log");
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

    /* ===================== 上传到服务器（增量 + 定时） ===================== */

    /** 上传状态回调（可选）：原生侧每次上传后把结果推给页面（L6LogUploadStatus）。 */
    public interface UploadListener { void onStatus(JSONObject status); }
    public static void setUploadListener(UploadListener l) { uploadListener = l; }
    /** 页面读取最近一次上传状态（JSON：{ok,msg,ts}）。 */
    public static String getUploadStatus() { return lastStatus != null ? lastStatus.toString() : "{}"; }

    /** 手动触发一次上传（供设置页「立即上传」按钮；网络在后台线程执行，不阻塞 UI）。 */
    public static void uploadNow() {
        if (scheduler != null) scheduler.execute(UPLOAD_TASK);
        else new Thread(UPLOAD_TASK).start();
    }

    /** 确定设备标识并启动每 60s 的周期性上传（首次延迟 5s，避免启动即打服务器）。 */
    private static void startUploader() {
        if (scheduler != null || ctx == null) return;
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            deviceId = (id == null || id.isEmpty()) ? "unknown" : id;
        } catch (Throwable ignored) { }
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "L6LogUpload");
                t.setDaemon(true);
                return t;
            }
        });
        scheduler.scheduleAtFixedRate(UPLOAD_TASK, 5, UPLOAD_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private static void setStatus(boolean ok, String msg) {
        try {
            lastStatus = new JSONObject().put("ok", ok).put("msg", msg == null ? "" : msg).put("ts", System.currentTimeMillis());
        } catch (Throwable ignored) { }
        if (uploadListener != null) {
            try { uploadListener.onStatus(lastStatus); } catch (Throwable ignored) { }
        }
    }

    private static String appVer() {
        if (ctx == null) return "?";
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable e) { return "?"; }
    }

    /**
     * 读取并上传「上次发送位置之后」的完整新行（增量上传，避免每分钟重发整文件）。
     * 只发以 '\n' 结尾的完整行；最后一行尚未写完则等下一轮，避免半行/重复。
     * 跨天换文件（l6-YYYY-MM-DD.log）时 sentFile 不匹配 → 偏移归零、从头发新文件。
     */
    private static void uploadOnce() {
        try {
            File f = logFile();
            if (f == null || !f.exists()) { setStatus(false, "无日志文件"); return; }
            String name = f.getName();
            if (!name.equals(sentFile)) { sentFile = name; sentOffset = 0; }
            long len = f.length();
            if (len < sentOffset) sentOffset = 0;
            if (len <= sentOffset) { setStatus(true, "无新增"); return; }
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.seek(sentOffset);
            byte[] raw = new byte[(int) (len - sentOffset)];
            raf.readFully(raw);
            raf.close();
            String text = new String(raw, StandardCharsets.UTF_8);
            int lastNl = text.lastIndexOf('\n');
            if (lastNl < 0) { setStatus(true, "无完整新行"); return; }  // 最后一行未写完，等下一轮
            String complete = text.substring(0, lastNl + 1);
            byte[] compBytes = complete.getBytes(StandardCharsets.UTF_8);
            JSONArray lines = new JSONArray();
            for (String ln : complete.split("\n", -1)) {
                if (ln.endsWith("\r")) ln = ln.substring(0, ln.length() - 1);
                if (!ln.isEmpty()) lines.put(ln);
            }
            JSONObject body = new JSONObject();
            body.put("device", deviceId);
            body.put("app", "L6CC");
            body.put("ver", appVer());
            body.put("file", name);
            body.put("ts", System.currentTimeMillis());
            body.put("lines", lines);
            boolean ok = postJson(LOG_UPLOAD_URL, body.toString());
            if (ok) {
                sentOffset += compBytes.length;
                setStatus(true, "已上传 " + lines.length() + " 行");
                i("L6LogUp", "上传成功 " + lines.length() + " 行 → " + LOG_UPLOAD_URL);
            } else {
                setStatus(false, "上传失败（HTTP 错误）");
                w("L6LogUp", "上传失败 → " + LOG_UPLOAD_URL);
            }
        } catch (Throwable t) {
            setStatus(false, "异常:" + (t.getMessage() == null ? "?" : t.getMessage()));
        }
    }

    /** POST JSON 到指定 URL（沿用 Ota/Lyrics 的 HttpURLConnection 风格）。返回是否 2xx。 */
    private static boolean postJson(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code < 400 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
            while (br.readLine() != null) { }
            br.close();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
