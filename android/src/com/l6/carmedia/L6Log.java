package com.l6.carmedia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    /** 是否把日志上报到服务器接口（由设置页开关控制，持久化到 SharedPreferences，默认开）。 */
    private static boolean apiUploadEnabled = true;
    private static Context ctx;

    /** 日志上传到服务器的目标接口（API 形态：POST JSON）。改这里即可换地址/路径。 */
    private static final String LOG_UPLOAD_URL = "https://yanzi-api.ziruxue.top/api/l6zk/log";
    /** 自动上传间隔（秒）。用户要求每分钟一次（成功后就是这个节奏）。 */
    private static final int UPLOAD_INTERVAL_SEC = 60;
    /** 失败退避上限（秒）。服务端持久性错误（如 403 路径不放行）时逐级拉长，避免每 60s 白打一次。 */
    private static final int UPLOAD_BACKOFF_MAX_SEC = 600;
    /** 退避窗口：早于这个时间点不发自动上报（手动「立即上报」不受限）。 */
    private static long nextAllowedAt = 0;
    /** 连续失败次数，用来算退避阶梯；成功即清零。 */
    private static int failStreak = 0;

    /** 失败登记：按 60→120→240→480→600s 指数退避，返回本次要等多久（秒）。 */
    private static int noteFailure() {
        failStreak++;
        int mult = 1 << Math.min(failStreak, 4);   // 2/4/8/16 倍，超过由 MAX 兜住
        int wait = (int) Math.min((long) UPLOAD_INTERVAL_SEC * mult, UPLOAD_BACKOFF_MAX_SEC);
        nextAllowedAt = System.currentTimeMillis() + wait * 1000L;
        return wait;
    }

    /** 成功登记：链路恢复，立刻回到每 60s 一次的节奏。 */
    private static void noteSuccess() {
        failStreak = 0;
        nextAllowedAt = 0;
    }
    private static ScheduledExecutorService scheduler;
    private static long sentOffset = 0;          // 已发送到服务器的字节偏移（增量上传）
    private static String sentFile = "";         // 当前正在追的日志文件名（跨天换文件自动从头）
    private static String deviceId = "unknown";  // 设备标识（ANDROID_ID），多车机区分来源
    private static JSONObject lastStatus = new JSONObject();  // 最近一次上传结果，供页面读取
    private static UploadListener uploadListener;
    /** 定时任务的上传动作：受开关约束。手动上报另走 uploadNow(true) 的强制分支。 */
    private static final Runnable UPLOAD_TASK = () -> uploadOnce(false);
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
        apiUploadEnabled = loadApiUploadFlag();   // 恢复上次在设置页选的「上报api接口」开关
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

    /* ---------- 「上报api接口」开关（设置页控制，持久化到 SharedPreferences） ---------- */
    /** 开关写入后立即生效；同时持久化，下次启动自动恢复。 */
    public static void setApiUploadEnabled(boolean b) {
        apiUploadEnabled = b;
        saveApiUploadFlag(b);
    }
    public static boolean isApiUploadEnabled() { return apiUploadEnabled; }

    /** 日志开关的持久化载体（无 Context 时返回 null，调用方需判空）。 */
    private static SharedPreferences l6Prefs() {
        return ctx != null ? ctx.getSharedPreferences("l6_log", Context.MODE_PRIVATE) : null;
    }

    private static void saveApiUploadFlag(boolean b) {
        try {
            SharedPreferences sp = l6Prefs();
            if (sp != null) sp.edit().putBoolean("api_upload", b).apply();
        } catch (Throwable e) {
            android.util.Log.w("L6LogUp", "持久化上报开关失败", e);
        }
    }

    /** 读取上次的开关状态（默认开；读失败也按「开」处理，避免静默丢日志）。 */
    private static boolean loadApiUploadFlag() {
        try {
            SharedPreferences sp = l6Prefs();
            return sp != null ? sp.getBoolean("api_upload", true) : true;
        } catch (Throwable e) {
            android.util.Log.w("L6LogUp", "读取上报开关失败", e);
            return true;
        }
    }

    /* ===================== 上传到服务器（增量 + 定时） ===================== */

    /** 上传状态回调（可选）：原生侧每次上传后把结果推给页面（L6LogUploadStatus）。 */
    public interface UploadListener { void onStatus(JSONObject status); }
    public static void setUploadListener(UploadListener l) { uploadListener = l; }
    /** 页面读取最近一次上传状态（JSON：{ok,msg,ts}）。 */
    public static String getUploadStatus() { return lastStatus != null ? lastStatus.toString() : "{}"; }

    /**
     * 手动触发一次上传（设置页「立即上报」按钮；网络在后台线程执行，不阻塞 UI）。
     * 手动走 force=true —— 开关只管「每 60s 自动上报」，用户显式点按钮就该真的发出去；
     * 若在这里被开关静默拦掉，表现就是「点了没反应」，极易被当成 bug。
     */
    public static void uploadNow() {
        uploadNow(true);
    }

    /**
     * 触发一次上传。
     * @param force true = 忽略「上报api接口」开关强制发送（手动按钮）；false = 受开关约束（定时任务）
     */
    public static void uploadNow(boolean force) {
        final Runnable task = () -> uploadOnce(force);
        if (scheduler != null) scheduler.execute(task);
        else new Thread(task).start();
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
    /** 定时上报入口：受设置页开关约束。 */
    private static void uploadOnce() {
        uploadOnce(false);
    }

    private static void uploadOnce(boolean force) {
        if (force) {
            // 手动「立即上报」：跳过退避窗口，也不受开关限制（否则点了没反应，像 bug）
            nextAllowedAt = 0;
            failStreak = 0;
        } else {
            if (!apiUploadEnabled) return;   // 设置页关闭了「上报api接口」：自动上报完全不打服务器
            // 退避窗口内：上一轮失败还没到重试时间，静默跳过（状态行保留上次失败原因）
            if (System.currentTimeMillis() < nextAllowedAt) return;
        }
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
            // 服务端单批硬上限 2000 行（再大会被上游 ECONNRESET，客户端表现为 Connection reset），
            // 所以必须切批发；每批成功才推进 sentOffset，失败则留在原地等下一轮重试。
            LogBatch.Chunk[] chunks = LogBatch.split(complete, LogBatch.MAX_LINES_PER_BATCH);
            if (chunks.length == 0) { setStatus(true, "无新增"); return; }

            final long baseOffset = sentOffset;   // endOffset 是相对本次 text 起点，不是相对文件头
            int doneBatches = 0, sentLines = 0;
            for (int bi = 0; bi < chunks.length; bi++) {
                LogBatch.Chunk ck = chunks[bi];
                if (ck.lines.length == 0) continue;
                if (doneBatches >= LogBatch.MAX_BATCHES_PER_ROUND) {
                    // 积压太多，留到下一轮，避免一轮占住上传线程太久
                    setStatus(true, "已上传 " + sentLines + " 行（还有积压，下轮继续）");
                    return;
                }
                JSONArray lines = new JSONArray();
                for (String ln : ck.lines) lines.put(ln);
                JSONObject body = new JSONObject();
                body.put("device", deviceId);
                body.put("app", "L6CC");
                body.put("ver", appVer());
                body.put("file", name);
                body.put("ts", System.currentTimeMillis());
                body.put("lines", lines);

                PostResult r = postJson(LOG_UPLOAD_URL, body.toString());
                if (r.ok()) {
                    sentOffset = baseOffset + ck.endOffset;
                    sentLines += ck.lines.length;
                    doneBatches++;
                    continue;
                }
                int wait = noteFailure();   // 按 60→120→240→480→600s 退避；已成功的批次不会重发
                if (r.code < 0) {
                    // 根本没连上（DNS/TLS/超时/reset）：没有 HTTP 状态码可显示，回显网络异常
                    setStatus(false, "上传失败（网络异常：" + r.err + "）· " + wait + "s 后重试");
                    w("L6LogUp", "上传失败（网络异常）" + r.err + " → " + LOG_UPLOAD_URL);
                } else {
                    // 服务端有回应但不是 2xx：把 HTTP 状态码 + 服务端 error 原文直接回显到车机状态行
                    setStatus(false, "上传失败 HTTP " + r.code + errDetail(r.resp)
                            + " · " + wait + "s 后重试");
                    w("L6LogUp", "上传失败 HTTP " + r.code + " " + r.resp + " → " + LOG_UPLOAD_URL);
                }
                // 失败即停，且不推进 sentOffset —— 已成功的批次不会重发，未发的下一轮从断点续
                if (sentLines > 0) {
                    setStatus(false, "部分成功：" + sentLines + " 行已上传，第 " + (doneBatches + 1)
                            + "/" + chunks.length + " 批失败 · " + wait + "s 后重试");
                }
                return;
            }
            if (sentLines > 0) {
                noteSuccess();   // 有一批成功就说明链路通了，立刻回到每 60s 一次
                setStatus(true, "已上传 " + sentLines + " 行"
                        + (chunks.length > 1 ? "（" + chunks.length + " 批）" : ""));
                i("L6LogUp", "上传成功 " + sentLines + " 行 → " + LOG_UPLOAD_URL);
            }
        } catch (Throwable t) {
            noteFailure();   // 本地异常（读文件/拼 JSON）也要退避，否则每 60s 炸一次
            setStatus(false, "异常:" + (t.getMessage() == null ? "?" : t.getMessage()));
        }
    }

    /** POST 结果：HTTP 状态码 + 服务端响应体 + 网络异常信息（用于让车机状态行显示真实失败原因）。 */
    private static final class PostResult {
        int code = -1;      // HTTP 状态码；<0 表示根本没连上（DNS/TLS/超时/未知主机）
        String resp = "";   // 服务端响应体（最多 512 字符），失败时里面有 error 原文
        String err = "";    // 网络异常信息（code<0 时有效）

        boolean ok() { return code >= 200 && code < 300; }
    }

    /** POST JSON 到指定 URL（沿用 Ota/Lyrics 的 HttpURLConnection 风格）。返回状态码与响应体。 */
    private static PostResult postJson(String url, String json) {
        PostResult r = new PostResult();
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
            r.code = c.getResponseCode();
            // 失败时服务端会在响应体里给出 error 原文，读出来回显到车机；成功时也要读完以释放连接
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    r.code < 400 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            br.close();
            r.resp = sb.length() > 512 ? sb.substring(0, 512) : sb.toString();
        } catch (Throwable t) {
            r.code = -1;
            r.err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return r;
    }

    /** 从服务端响应体里摘一句人类可读原因（优先 error，其次 message），取不到则返回空串。 */
    private static String errDetail(String resp) {
        if (resp == null || resp.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(resp);
            String s = o.optString("error", "");
            if (s.isEmpty()) s = o.optString("message", "");
            if (s.isEmpty()) return "";
            if (s.length() > 60) s = s.substring(0, 60) + "…";
            return "：" + s;
        } catch (Throwable t) {
            return "";
        }
    }
}
