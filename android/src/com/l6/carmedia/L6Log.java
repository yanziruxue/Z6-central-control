package com.l6.carmedia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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
    /** 「上报 api 接口」开关。**v1.5.12 起默认关闭**：网络侧按 SNI 阻断尚未解决，
     *  自动上报只会每 60s 白失败一次、把日志刷满。设置页可手动开启；
     *  「立即上报 / 测试接口」按钮走 force 分支，不受本开关约束，随时可手工验证链路。
     *  本地落盘与 Tasker 广播不受影响，始终工作。 */
    private static boolean apiUploadEnabled = false;
    /** 自定义 DNS 服务器（设置页「接口 DNS」手填，如 223.5.5.5）；留空 = 用系统 DNS。 */
    private static volatile String customDns = "";
    private static Context ctx;

    /** 日志上传到服务器的目标接口（API 形态：POST JSON）。改这里即可换地址/路径。 */
    private static final String LOG_UPLOAD_URL = "https://yanzi-api.ziruxue.top/api/l6zk/log";

    /*
     * ===================== 上报双通道（v1.5.13） =====================
     * 主通道（加密）  : https://yanzi-api.ziruxue.top/api/l6zk/log   + 钉 IP + **不发 SNI**
     * 保底通道（明文）: http://yanzi-api.ziruxue.top:2000/api/l6zk/log + 显式 Host 头
     *
     * 为什么要保底：主通道靠「不发 SNI」绕开链路侧按 SNI 的 RST（见 §6.10 / PinnedSsl 注释）——
     * 那是**钻了过滤器的空子**，规则一变就全挂。保底通道走**明文 HTTP 直连网闸端口**：
     * 没有 ClientHello、没有 SNI，压根没有可被解析的东西，确定性最高。
     *
     * ★ 为什么 URL 里写域名而不是 IP：写域名让这条通道跟着 DNS 走（换 IP 不用改 App）；
     *   连的还是解析出来的那台机器，效果与写 IP 相同。
     * ★★ 为什么必须显式指定 Host：网闸按 **Host 精确匹配**服务（`detector.js` 里是 `service.host === host`），
     *   而 URL 带了 `:2000` ⇒ 自动生成的 Host 会是 `yanzi-api.ziruxue.top:2000`（**带端口**）
     *   ⇒ 一条服务都匹配不上 ⇒ 403「未识别到来源服务，默认拦截」。
     *   所以这里强制把 Host 覆盖成**不带端口**的域名。这条依赖很容易静默失效，
     *   一旦保底通道返回 403 且 body 含「未识别到来源服务」，先查这里。（探测矩阵 F 组可复验。）
     * ★ 顺序 / 回退条件：主通道**网络层**失败（code<0 或 5xx）才切保底；
     *   4xx 是请求本身的问题（400 行数超限 / 403 / 413），换通道答案一样，白白多打一次。
     *   保底通道**只发一次不重试** —— 已经切到保底还失败，说明网是真的断了，再重试只是拖时间。
     */
    private static final String LOG_UPLOAD_URL_FALLBACK = "http://yanzi-api.ziruxue.top:2000/api/l6zk/log";
    /** 网闸识别「老六中控上报」这个服务所用的 Host（**不带端口**，见上面的说明）。 */
    private static final String INGEST_HOST = "yanzi-api.ziruxue.top";
    /** 通道名（回显到日志/状态行，出问题时一眼看出走的是哪条）。 */
    private static final String CH_TLS = "443 HTTPS（钉IP+去SNI）";
    private static final String CH_2000 = ":2000 HTTP（直连网闸·明文）";

    /*
     * ===================== 与《上报触发与接口及上报内容》对齐的接口约定 =====================
     * ① 鉴权头：X-Telemetry-Key = 设备标识（本应用用 ANDROID_ID，重装不变、不用随机 UUID），
     *    同时充当服务端的统计主键 —— 与文档 §4.1 的 `X-Telemetry-Key: <deviceId>` 一致。
     * ② 载荷：除原有的 device/app/ver/file/ts/lines 外，补上文档字段字典里的同名键
     *    （device_uuid / appVersion / os / osVersion / arch / channel / virtualized /
     *     uploadEnabled / installedAt / ts_iso / hardware / details）。
     *    一律「只增不改」—— 现有键保持不变，服务端老解析逻辑不受影响。
     * ③ 超时：单次请求 10 秒（文档 REQUEST_TIMEOUT_MS）。
     * ④ 不做 install / heartbeat 区分：本应用只需要「每分钟上报最近 1 分钟的日志」，
     *    装机量统计由服务端按 device_uuid 自行去重即可。
     */
    private static final String AUTH_HEADER = "X-Telemetry-Key";
    /** 单次 HTTP 超时（文档 §2.3：REQUEST_TIMEOUT_MS = 10 秒）。 */
    private static final int REQUEST_TIMEOUT_MS = 10000;
    /** 交付形态标记，与文档 channel 字段同义（docker-manager 是 sea-linux-x64）。 */
    private static final String CHANNEL = "apk-android";
    /** 自动上传间隔（秒）。用户要求每分钟一次（成功后就是这个节奏）。 */
    private static final int UPLOAD_INTERVAL_SEC = 60;
    /** 失败退避上限（秒）。服务端持久性错误（如 403 路径不放行）时逐级拉长，避免每 60s 白打一次。 */
    private static final int UPLOAD_BACKOFF_MAX_SEC = 600;
    /*
     * 单批最多尝试几次 + 每档重试延迟，统一由 LogDiag 定义（RETRY_DELAYS_MS，纯逻辑可单测）。
     * 为什么立刻重试：这类 Connection reset 是**瞬时**链路抖动，当场重发往往就过；
     * 而 60→600s 的退避会把一次抖动放大成用户看到的连串失败。
     * v1.5.7 起阶梯从「1 次 / 400ms」放宽为「2 次 / 0.4s + 2s」——
     * 手机实测 400ms 后重发会落在同一个故障窗口里，两次一起失败。
     */
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

    /**
     * 鉴权类失败（401 / 403）登记：对齐文档 §8.2 的 fatal 分类 —— 「避免密钥错误时持续轰击远端」。
     * 直接把下一次允许上报的时间推到退避上限（10 分钟），不再走 60→120→240… 的阶梯，
     * 也不做 0.4s/2s 的立即重试（postJsonWithRetry 本来就不对 4xx 重试，这里再兜一层节奏）。
     */
    private static int noteFatalAuth() {
        failStreak = 0;
        nextAllowedAt = System.currentTimeMillis() + UPLOAD_BACKOFF_MAX_SEC * 1000L;
        return UPLOAD_BACKOFF_MAX_SEC;
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
    /** 最近一次成功上报走的通道（成功日志回显用；批次循环结束后拿不到那个 PostResult）。 */
    private static volatile String lastChannel = "";
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
        customDns = loadCustomDns();              // 恢复设置页手填的「接口 DNS」
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
    /**
     * 开关写入后立即生效；同时持久化，下次启动自动恢复。
     *
     * 开关「切换瞬间」补发一条（对齐文档 §3.3 的精神）：让远端立刻知道本机 opt-in 的最新值
     * （载荷里的 uploadEnabled）。关闭方向也要发得出去，所以走 uploadNow(force=true) 绕过守卫 ——
     * 文档那边靠强制 enabled=true 绕过，这里等价地靠 force 绕过。
     * 注意：这是**一次性的用户显式操作回执**，不做 install 语义（按需求不区分 install/heartbeat）。
     */
    public static void setApiUploadEnabled(boolean b) {
        boolean changed = (apiUploadEnabled != b);
        apiUploadEnabled = b;
        saveApiUploadFlag(b);
        if (changed) {
            android.util.Log.i("L6LogUp", "上报开关切换 → " + (b ? "开启" : "关闭") + "，补发一条以同步 uploadEnabled");
            uploadNow(true);
        }
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

    /** 开关的偏好键。 */
    private static final String KEY_API_UPLOAD = "api_upload";
    /** v1.5.12 一次性迁移标记：老包默认是「开」，升级后必须真的落成「关」。 */
    private static final String KEY_API_UPLOAD_OFF_V1512 = "api_upload_off_v1512";

    /**
     * 读取上次的开关状态。**v1.5.12 起默认关闭**（网络侧按 SNI 阻断尚未解决，自动上报
     * 只会每 60s 白失败一次）；读失败也按「关」处理，免得静默恢复成持续失败。
     *
     * 另做一次性迁移：老版本已把 `api_upload` 存成 true 的车机，**只改这里的默认值不管用**
     * （默认值仅在键不存在时生效）⇒ 必须显式重置一次。此后用户手动开/关都照常持久化。
     */
    private static boolean loadApiUploadFlag() {
        try {
            SharedPreferences sp = l6Prefs();
            if (sp == null) return false;
            if (!sp.getBoolean(KEY_API_UPLOAD_OFF_V1512, false)) {
                sp.edit().putBoolean(KEY_API_UPLOAD_OFF_V1512, true)
                        .putBoolean(KEY_API_UPLOAD, false).apply();
                android.util.Log.i("L6LogUp", "v1.5.12：上报开关默认值变更，已一次性重置为「关闭」");
                return false;
            }
            return sp.getBoolean(KEY_API_UPLOAD, false);
        } catch (Throwable e) {
            android.util.Log.w("L6LogUp", "读取上报开关失败", e);
            return false;
        }
    }

    /* ---------- 自定义 DNS（设置页「接口 DNS」手填）----------
     * 背景：手机/车机本地 DNS 可能把上报域名解到**错误 IP**。实测过一次：域名被解到
     * 60.205.231.18，而那台机器上的证书只签给 www.shuan.tech —— 与本域毫无关系，
     * 于是带证书/主机名校验的 HTTPS 永远失败（客户端只看到一句 Connection reset）。
     * 这里允许手填一个可信 DNS，解析绕开本机那份坏掉的解析结果。 */
    public static void setCustomDns(String s) {
        String v = s == null ? "" : s.trim();
        if (!v.isEmpty() && !DnsQuery.isIp(v)) v = "";   // 非法输入一律当「未设置」，别把配置写坏
        customDns = v;
        peerCache = ""; peerCacheAt = 0;                 // 换了解析源，旧的对端缓存作废
        saveCustomDns(v);
    }
    public static String getCustomDns() { return customDns; }

    private static void saveCustomDns(String v) {
        try {
            SharedPreferences sp = l6Prefs();
            if (sp != null) sp.edit().putString("custom_dns", v).apply();
        } catch (Throwable e) {
            android.util.Log.w("L6LogUp", "持久化自定义 DNS 失败", e);
        }
    }
    private static String loadCustomDns() {
        try {
            SharedPreferences sp = l6Prefs();
            String v = sp != null ? sp.getString("custom_dns", "") : "";
            return (v != null && DnsQuery.isIp(v)) ? v : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 一次解析的结果：用到的 IP + 来源（用于回显/诊断）。 */
    private static final class Resolution {
        String[] ips = new String[0];
        String source = "系统 DNS";
        String err = "";
    }

    /** 解析上报域名：配了自定义 DNS 就用它（UDP 53 直查），失败再回落系统 DNS 并把原因写进 source。 */
    private static Resolution resolveHost(String host) {
        Resolution r = new Resolution();
        if (!customDns.isEmpty()) {
            try {
                String ip = queryDns(customDns, host);
                if (ip != null && !ip.isEmpty()) {
                    r.ips = new String[]{ip};
                    r.source = "自定义 " + customDns;
                    return r;
                }
                r.err = "自定义 DNS " + customDns + " 无 A 记录";
            } catch (Throwable t) {
                r.err = "自定义 DNS " + customDns + " 查询失败(" + t.getClass().getSimpleName() + ")";
            }
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            String[] ips = new String[all.length];
            for (int i = 0; i < all.length; i++) ips[i] = all[i].getHostAddress();
            r.ips = ips;
            r.source = r.err.isEmpty() ? "系统 DNS" : ("系统 DNS（回落：" + r.err + "）");
        } catch (Throwable t) {
            if (r.err.isEmpty()) r.err = "系统 DNS 解析失败(" + t.getClass().getSimpleName() + ")";
            r.source = (customDns.isEmpty() ? "系统 DNS" : "系统 DNS 回落") + " 失败：" + r.err;
        }
        return r;
    }

    /** 用指定 DNS 服务器（UDP 53）查一次 A 记录，返回首个 IP；查不到返回 null。 */
    private static String queryDns(String dnsServer, String host) throws Exception {
        int id = (int) (System.nanoTime() & 0xFFFF);
        byte[] q = DnsQuery.buildQuery(host, id);
        DatagramSocket ds = new DatagramSocket();
        try {
            ds.setSoTimeout(5000);
            ds.send(new DatagramPacket(q, q.length, InetAddress.getByName(dnsServer), 53));
            byte[] buf = new byte[1500];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            byte[] data = new byte[resp.getLength()];
            System.arraycopy(resp.getData(), 0, data, 0, resp.getLength());
            return DnsQuery.parseFirstA(data, id);
        } finally {
            try { ds.close(); } catch (Throwable ignored) { }
        }
    }

    /** IP 数组 → 空格分隔字符串。 */
    private static String joinIps(String[] ips) {
        StringBuilder sb = new StringBuilder();
        for (String s : ips) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** IP 数组的协议族判定（探针回显用）。 */
    private static String familyOf(String[] ips) {
        if (ips == null || ips.length == 0) return "未知";
        boolean v4 = false, v6 = false;
        for (String s : ips) {
            if (s.indexOf(':') >= 0) v6 = true; else v4 = true;
        }
        return (v4 && v6) ? "双栈" : (v6 ? "IPv6" : "IPv4");
    }

    /**
     * 从解析结果里挑一个可用 IPv4（没有 v4 才回落第一个；全空返回 null）。
     *
     * 钉 IP 时优先 v4：车机走物联网卡，是纯 IPv4 环境。若把连接钉到一条本机并不通的 v6
     * 地址上，上报会以一种很难归因的方式失败（连不上，但既不报 DNS 错也不报 TLS 错）。
     */
    private static InetAddress pickIpv4(String[] ips) {
        if (ips == null || ips.length == 0) return null;
        for (String s : ips) {
            if (s == null || s.isEmpty() || s.indexOf(':') >= 0) continue;   // 跳过 IPv6 字面量
            try { return InetAddress.getByName(s); } catch (Throwable ignored) { }
        }
        try { return InetAddress.getByName(ips[0]); } catch (Throwable ignored) { return null; }
    }

    /* ===================== 上传到服务器（增量 + 定时） ===================== */

    /** 上传状态回调（可选）：原生侧每次上传后把结果推给页面（L6LogUploadStatus）。 */
    public interface UploadListener { void onStatus(JSONObject status); }
    public static void setUploadListener(UploadListener l) { uploadListener = l; }
    /** 页面读取最近一次上传状态（JSON：{ok,msg,ts}）。 */
    public static String getUploadStatus() { return lastStatus != null ? lastStatus.toString() : "{}"; }

    /* ===================== 「测试接口」连通性探针（v1.5.7） ===================== */

    /** 探针结果回调：由 MainActivity 接上，经 window.L6LogProbeResult 推给设置页。 */
    public interface ProbeListener { void onResult(JSONObject r); }
    private static volatile ProbeListener probeListener;
    public static void setProbeListener(ProbeListener l) { probeListener = l; }

    /**
     * 三段式连通性探针（独立线程执行，结果回抛）：
     *   ① DNS —— 走哪个解析源、解析到哪些 IP、IPv4 还是 IPv6、耗时
     *   ② TCP —— 裸 socket 建连（**不含 TLS**）：能区分「TCP 就不通」与「TCP 通但 TLS/HTTP 挂」
     *   ③ 请求 —— 真发一次上报，把服务端真实响应（状态码 / error 原文）当场拿回来
     *
     * 存在的理由：上报失败只给一句「Connection reset」，看不出断在建立连接 / 写入 / 读取哪一段，
     * 前几个版本因此只能靠猜（体积？UA？服务端？）。这个探针一次跑完就把三段摊开。
     * 注意 ③ 会在服务端真实写入一行探针日志 —— 这是刻意的：只有真请求才能证明链路端到端通。
     */
    public static void probeLogApi() {
        Thread t = new Thread(() -> {
            JSONObject o = new JSONObject();
            try {
                URL u = new URL(LOG_UPLOAD_URL);
                String host = u.getHost();
                int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getProtocol()) ? 443 : 80);
                o.put("url", LOG_UPLOAD_URL).put("host", host).put("port", port);

                // ① DNS：走哪个解析源、解析到什么、v4 还是 v6、多久
                //    配了自定义 DNS 时，把系统 DNS 的结果也一并回显 —— 两个源解到不同 IP 时一眼看穿
                long tDns = System.currentTimeMillis();
                Resolution res = resolveHost(host);
                long dnsMs = System.currentTimeMillis() - tDns;
                String ips = res.ips.length == 0
                        ? (res.err.isEmpty() ? "无解析结果" : res.err) : joinIps(res.ips);
                o.put("dns", ips).put("family", familyOf(res.ips)).put("dnsMs", dnsMs)
                        .put("dnsSource", res.source);
                if (!customDns.isEmpty()) {
                    try {
                        InetAddress[] all = InetAddress.getAllByName(host);
                        StringBuilder sb = new StringBuilder();
                        for (InetAddress a : all) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(a.getHostAddress());
                        }
                        o.put("sysDns", sb.length() == 0 ? "无解析结果" : sb.toString());
                    } catch (Throwable ignored) { }
                }

                // ② TCP 裸建连（不含 TLS）
                long tcpMs = -1;
                String tcpErr = "";
                Socket sk = null;
                try {
                    // 优先测「上报实际会连的那个 IP」（配了自定义 DNS 时就是它解析出来的那个）
                    InetAddress first = res.ips.length > 0
                            ? InetAddress.getByName(res.ips[0]) : InetAddress.getAllByName(host)[0];
                    long tTcp = System.currentTimeMillis();
                    sk = new Socket();
                    sk.connect(new InetSocketAddress(first, port), 8000);
                    tcpMs = System.currentTimeMillis() - tTcp;
                } catch (Throwable e) {
                    tcpErr = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "?" : e.getMessage());
                } finally {
                    if (sk != null) try { sk.close(); } catch (Throwable ignored) { }
                }
                o.put("tcpMs", tcpMs).put("tcpErr", tcpErr);

                // ②b 顺带裸测保底通道端口 :2000 —— 安全组/网闸绑定任一没放开，这里立刻就能看出来。
                //     刻意只做 TCP 建连（不发请求）：这是「端口通不通」的最小判定，也不打服务端。
                long tcp2000Ms = -1;
                String tcp2000Err = "";
                Socket sk2 = null;
                try {
                    InetAddress first2 = res.ips.length > 0
                            ? InetAddress.getByName(res.ips[0]) : InetAddress.getAllByName(host)[0];
                    long t2 = System.currentTimeMillis();
                    sk2 = new Socket();
                    sk2.connect(new InetSocketAddress(first2, 2000), 8000);
                    tcp2000Ms = System.currentTimeMillis() - t2;
                } catch (Throwable e) {
                    tcp2000Err = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "?" : e.getMessage());
                } finally {
                    if (sk2 != null) try { sk2.close(); } catch (Throwable ignored) { }
                }
                o.put("tcp2000Ms", tcp2000Ms).put("tcp2000Err", tcp2000Err);
                i("L6LogUp", "探针 TCP 结果：:443 " + (tcpMs < 0 ? tcpErr : tcpMs + "ms")
                        + " ｜ :2000 " + (tcp2000Ms < 0 ? tcp2000Err : tcp2000Ms + "ms"));

                // ③ 真发一次请求（探针刻意不走重试：要的就是「这一次」的真实结果）
                JSONObject body = new JSONObject();
                body.put("device", deviceId);
                body.put("app", "L6CC");
                body.put("ver", appVer());
                body.put("file", "probe");
                body.put("ts", System.currentTimeMillis());
                JSONArray lines = new JSONArray();
                lines.put(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                        + " [INFO] L6LogUp: 接口连通性测试（设置页手动触发）");
                body.put("lines", lines);
                fillMeta(body);   // 探针走同一套元信息，保证「探针能过 = 正式上报也能过」
                // ③ 真发一次请求（探针刻意不走重试阶梯：要的就是「这一次」的真实结果）。
                //    ★ 但**要走双通道**：探针的使命是回答「现在到底能不能上报」，
                //      如果它只试 443，就会出现「探针失败了、其实保底通道能用」的假警报。
                PostResult r = postJsonChannels(body.toString(), false);
                o.put("code", r.code).put("phase", r.phase).put("err", r.err)
                        .put("resp", r.resp).put("ms", r.ms).put("peer", r.peer)
                        .put("channel", r.channel).put("trail", r.trail);
                o.put("ok", r.ok());
                o.put("summary", LogDiag.probeSummary(r.ok(), dnsMs, tcpMs, r.ms));
            } catch (Throwable t2) {
                try {
                    o.put("fatal", t2.getClass().getSimpleName() + ": "
                            + (t2.getMessage() == null ? "?" : t2.getMessage()));
                    o.put("summary", "探针自身异常：" + t2.getClass().getSimpleName());
                } catch (Throwable ignored) { }
            }
            ProbeListener l = probeListener;
            if (l != null) try { l.onResult(o); } catch (Throwable ignored) { }
        }, "L6LogProbe");
        t.setDaemon(true);
        t.start();
    }

    /* ===================== 「接口探测矩阵」（v1.5.12） =====================
     * 目的：把「用 API 直接访问车机数据」这件事的**通路**一次性探清楚。
     *
     * 背景：「网络侧按 SNI 阻断」这条规律（ClientHello 里能解析出 SNI 且含 ziruxue.top 就回
     * RST）是在 PC 上用 Python/OpenSSL 验证出来的。车机是 Android 9 + 仅 TLS1.2 + 物联网卡，
     * 环境完全不同 —— 必须在真机上再验一遍，并且把「到底哪种访问方式能真正打到 API」
     * 一项一项列出来，而不是只有一句「上报失败」。
     *
     * 做法：内建候选矩阵（同域名不同握手形态 / 明文 / 不同路径 / 有无鉴权 / 不同方法），
     * 每一项都走**完整链路** DNS → TCP → TLS → HTTP，四项结果逐项写进本地日志
     * （标签 L6ApiMatrix），整份矩阵再另存一个 JSON 文件便于导出比对。
     *
     * 为什么每项都要真发一次请求：只有真请求能区分
     *   ① 握手就挂（tls 阶段 RST）  ② 握手过了但接口 4xx/403  ③ 整条链路通。
     * 这三种在 App 里最终都只表现成一句「上传失败 网络异常」，根因完全不同。
     *
     * 副作用说明：所有请求都会在服务端留下真实记录 —— 这是刻意的，探针存在的意义就是端到端。
     * 上报开关已默认关闭（只影响每 60s 的自动任务），本探测由用户手动触发，不受开关约束。
     */
    private static final int MATRIX_TCP_TIMEOUT_MS = 6000;
    private static final int MATRIX_READ_TIMEOUT_MS = 6000;

    /** 逐项结果回调（跑完一项推一条），由 MainActivity 接上，经 window.L6LogMatrixResult 推给页面。 */
    public interface MatrixListener { void onResult(JSONObject r); }
    private static volatile MatrixListener matrixListener;
    public static void setMatrixListener(MatrixListener l) { matrixListener = l; }
    /** 防重入：矩阵跑一轮要十几秒，按钮连点不该叠两轮（否则日志互相穿插、结论自相矛盾）。 */
    private static volatile boolean matrixRunning = false;
    public static boolean isMatrixRunning() { return matrixRunning; }

    /** 一条候选访问方式。 */
    private static final class ApiCase {
        final String group;      // 分组（A 握手形态 / B 明文 / C 鉴权 / D 路径 / E 站点）
        final String name;       // 人类可读名字
        final String url;
        final String method;     // GET / POST
        final boolean sni;       // true = 按域名握手（发 SNI）；false = 钉 IP 且显式不发 SNI
        final String tls;        // "" = 默认协商；"TLSv1.2" / "TLSv1.3" = 强制该版本
        final String authValue;  // null = 用本机 deviceId；空串 = 不带鉴权头；其它 = 故意写错的值
        final boolean body;      // 是否带 JSON 载荷

        ApiCase(String group, String name, String url, String method,
                boolean sni, String tls, String authValue, boolean body) {
            this.group = group; this.name = name; this.url = url; this.method = method;
            this.sni = sni; this.tls = tls; this.authValue = authValue; this.body = body;
        }
    }

    /** 候选矩阵。base 用当前上报域名，保证探的就是上报实际要打的那台机器。 */
    private static List<ApiCase> matrixCases() {
        String base = "yanzi-api.ziruxue.top";
        String logPath = "/api/l6zk/log";
        String httpsLog = "https://" + base + logPath;
        List<ApiCase> l = new ArrayList<>();

        // A 组：同一接口、同一机器，只改「握手形态」——这一组直接对上 SNI 阻断规律
        l.add(new ApiCase("A 握手形态", "域名+SNI+默认协议", httpsLog, "POST", true, "", null, true));
        l.add(new ApiCase("A 握手形态", "域名+SNI+TLS1.2", httpsLog, "POST", true, "TLSv1.2", null, true));
        l.add(new ApiCase("A 握手形态", "钉IP+去SNI+默认协议", httpsLog, "POST", false, "", null, true));
        l.add(new ApiCase("A 握手形态", "钉IP+去SNI+TLS1.2", httpsLog, "POST", false, "TLSv1.2", null, true));
        l.add(new ApiCase("A 握手形态", "域名+SNI+TLS1.3", httpsLog, "POST", true, "TLSv1.3", null, true));

        // B 组：明文（不走 TLS，就没有 ClientHello、没有 SNI 可拦）
        l.add(new ApiCase("B 明文", "明文HTTP+域名:80", "http://" + base + logPath, "POST", false, "", null, true));
        l.add(new ApiCase("B 明文", "明文HTTP打到443", "http://" + base + ":443" + logPath, "POST", false, "", null, true));

        // F 组：直连网闸（绕开容器 nginx 与整个 TLS 层）
        //   :2000 是宿主机上的 Node 网闸端口，容器 nginx 也是 proxy_pass 到它的；
        //   若它对公网可达，那就是最省事的通路（连 TLS 都没有，自然没有 SNI 可拦）。
        //   但按部署约定它只绑 docker 网桥 IP 且安全组只开 80/443 ⇒ 预计公网被拒，
        //   这里刻意保留这两项，用来在**车机上**再确认一次（换接入网络结论可能不同）。
        l.add(new ApiCase("F 直连网闸", "明文HTTP直连网闸:2000",
                "http://" + base + ":2000" + logPath, "POST", false, "", null, true));
        l.add(new ApiCase("F 直连网闸", "明文HTTP打主机:80",
                "http://" + base + ":80" + logPath, "POST", false, "", null, true));

        // C 组：鉴权逻辑（服务端到底认不认 X-Telemetry-Key）
        l.add(new ApiCase("C 鉴权", "去SNI+不带鉴权头", httpsLog, "POST", false, "", "", true));
        l.add(new ApiCase("C 鉴权", "去SNI+错误鉴权头", httpsLog, "POST", false, "", "l6-wrong-key-probe", true));

        // D 组：路径（判断网关是「按前缀拦」还是「按精确路径拦」——决定新接口能不能加）
        l.add(new ApiCase("D 路径", "GET 上报路径(单数)",
                "https://" + base + "/api/l6zk/log", "GET", false, "", null, false));
        l.add(new ApiCase("D 路径", "GET 上报路径(复数 logs)",
                "https://" + base + "/api/l6zk/logs", "GET", false, "", null, false));
        l.add(new ApiCase("D 路径", "GET 前缀 /api/l6zk/",
                "https://" + base + "/api/l6zk/", "GET", false, "", null, false));
        l.add(new ApiCase("D 路径", "GET 未定义子路径 /api/l6zk/device",
                "https://" + base + "/api/l6zk/device", "GET", false, "", null, false));

        // E 组：站点根（看这台机器/网关对外到底是什么形态，用来判断有没有前置 WAF）
        l.add(new ApiCase("E 站点", "GET /", "https://" + base + "/", "GET", false, "", null, false));
        l.add(new ApiCase("E 站点", "GET /health", "https://" + base + "/health", "GET", false, "", null, false));
        return l;
    }

    /**
     * 跑一轮完整探测矩阵（独立线程，逐项回调 + 逐项写日志，结束另存 JSON）。
     * 由设置页「🔬 API 探测」按钮触发。
     */
    public static void probeApiMatrix() {
        if (matrixRunning) {
            i("L6ApiMatrix", "上一轮探测还没跑完，忽略本次点击");
            return;
        }
        matrixRunning = true;
        Thread t = new Thread(() -> {
            List<ApiCase> cases = matrixCases();
            int total = cases.size();
            long t0 = System.currentTimeMillis();
            JSONArray rows = new JSONArray();
            int pass = 0;
            i("L6ApiMatrix", "==================== API 探测矩阵开始（共 " + total
                    + " 项）====================");
            i("L6ApiMatrix", "本机 Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                    + ") / " + Build.MODEL + " / ver " + appVer()
                    + " / 上报开关 " + (apiUploadEnabled ? "开" : "关")
                    + " / 自定义DNS " + (customDns.isEmpty() ? "无" : customDns));
            // 先把落盘位置写进日志：中途被打断也能知道该去哪里取这次的结果
            try {
                File md = artifactDir();
                i("L6ApiMatrix", "结果将另存到：" + (md == null ? "（无可用目录）" : md.getAbsolutePath()));
            } catch (Throwable ignored) { }
            try {
                for (int k = 0; k < total; k++) {
                    ApiCase c = cases.get(k);
                    JSONObject r;
                    try {
                        r = matrixTry(k + 1, total, c);
                    } catch (Throwable e) {
                        r = new JSONObject();
                        try {
                            r.put("phase", "self")
                             .put("err", e.getClass().getSimpleName() + ": " + str(e.getMessage()));
                        } catch (Throwable ignored) { }
                    }
                    try {
                        r.put("idx", k + 1).put("total", total).put("group", c.group).put("name", c.name);
                    } catch (Throwable ignored) { }
                    if (r.optBoolean("ok", false)) pass++;
                    rows.put(r);
                    // 逐项写本地日志：即使页面没接上回调，日志里也留得下完整结论
                    i("L6ApiMatrix", matrixLine(k + 1, total, c, r));
                    emitMatrix(r, false);
                    // 每项之间隔一下：连续快速发起的连接本身也会触发链路侧的限流，
                    // 一秒内打十几个 RST 会把「确定性阻断」和「瞬时限流」混在一起，结论就没法用了。
                    try { Thread.sleep(400); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            } catch (Throwable e) {
                i("L6ApiMatrix", "矩阵异常中断：" + e.getClass().getSimpleName() + ": " + str(e.getMessage()));
            }
            long ms = System.currentTimeMillis() - t0;
            JSONObject done = new JSONObject();
            try {
                done.put("kind", "done").put("total", total).put("pass", pass)
                    .put("ms", ms).put("file", matrixDump(rows, total, pass, ms));
            } catch (Throwable ignored) { }
            i("L6ApiMatrix", "==================== 探测结束：通 " + pass + "/" + total
                    + "，用时 " + (ms / 1000) + "s ====================");
            emitMatrix(done, true);
            matrixRunning = false;
        }, "L6ApiMatrix");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 单项探测：DNS → TCP → TLS → HTTP 全链路，每一步的耗时/失败阶段都记进结果。
     *
     * TLS 这里刻意**手工建连**（裸 socket 连好后再 wrap），而不是交给 HttpsURLConnection：
     *   ① 只有手工才能把「握手有没有挂、挂在哪一步」与后面的 HTTP 分开，HttpURLConnection
     *      会把手握期的 RST 统一记成 write 阶段失败；
     *   ② 才能自由切换「带 SNI / 不带 SNI / 强制某个 TLS 版本」这三种形态。
     */
    private static JSONObject matrixTry(int idx, int total, ApiCase c) {
        JSONObject o = new JSONObject();
        long t0 = System.currentTimeMillis();
        Socket sk = null;
        try {
            URL u = new URL(c.url);
            String host = u.getHost();
            boolean https = "https".equals(u.getProtocol());
            int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path = path + "?" + u.getQuery();

            o.put("url", c.url).put("host", host).put("port", port).put("method", c.method)
             .put("sni", c.sni ? "带" : "无").put("tls", c.tls.isEmpty() ? "默认" : c.tls)
             .put("auth", c.authValue == null ? "deviceId" : (c.authValue.isEmpty() ? "无" : "错误值"));

            // ① DNS：走哪个源、解析到什么、v4/v6、耗时
            long tDns = System.currentTimeMillis();
            Resolution res = resolveHost(host);
            o.put("dnsMs", System.currentTimeMillis() - tDns)
             .put("dns", res.ips.length == 0 ? (res.err.isEmpty() ? "无解析结果" : res.err) : joinIps(res.ips))
             .put("family", familyOf(res.ips)).put("dnsSource", res.source);

            InetAddress pin = pickIpv4(res.ips);
            if (pin == null) {
                o.put("phase", "dns").put("err", "无可用 IPv4 地址").put("ms", System.currentTimeMillis() - t0);
                return o;
            }
            o.put("peer", pin.getHostAddress());

            // ② TCP 裸建连（不含 TLS）：先分清「TCP 就不通」和「TCP 通、后面才挂」
            long tTcp = System.currentTimeMillis();
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(pin, port), MATRIX_TCP_TIMEOUT_MS);
            } catch (Throwable e) {
                closeQuietly(raw);
                o.put("tcpMs", System.currentTimeMillis() - tTcp)
                 .put("phase", "tcp").put("err", err(e))
                 .put("ms", System.currentTimeMillis() - t0);
                return o;
            }
            sk = raw;
            o.put("tcpMs", System.currentTimeMillis() - tTcp);

            // ③ TLS 握手：SNI / 协议版本都在这一步体现
            if (https) {
                long tTls = System.currentTimeMillis();
                try {
                    SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    // ★ SNI 只能是主机名，不能是 IP 字面量 —— 所以「钉 IP」这个动作本身
                    //   就等于「不发 SNI」。下面再显式把 server names 清空一次做双保险。
                    String name = c.sni ? host : pin.getHostAddress();
                    SSLSocket ss = (SSLSocket) f.createSocket(raw, name, port, true);
                    if (!c.sni) {
                        SSLParameters p = ss.getSSLParameters();
                        p.setServerNames(java.util.Collections.<SNIServerName>emptyList());
                        ss.setSSLParameters(p);
                    }
                    if (!c.tls.isEmpty()) ss.setEnabledProtocols(new String[]{c.tls});
                    ss.setSoTimeout(MATRIX_READ_TIMEOUT_MS);
                    ss.startHandshake();   // 显式握手：RST 会在这里抛出，而不是拖到 write
                    sk = ss;
                    o.put("tlsMs", System.currentTimeMillis() - tTls)
                     .put("proto", ss.getSession().getProtocol())
                     .put("cipher", ss.getSession().getCipherSuite());
                    try {
                        java.security.cert.Certificate[] cs = ss.getSession().getPeerCertificates();
                        if (cs != null && cs.length > 0
                                && cs[0] instanceof java.security.cert.X509Certificate) {
                            java.security.cert.X509Certificate x = (java.security.cert.X509Certificate) cs[0];
                            String dn = x.getSubjectX500Principal().getName();
                            int ci = dn.indexOf("CN=");
                            o.put("certCn", ci >= 0 ? dn.substring(ci + 3).split(",")[0] : dn);
                            java.util.Collection<List<?>> sans = x.getSubjectAlternativeNames();
                            StringBuilder sb = new StringBuilder();
                            if (sans != null) {
                                for (List<?> e2 : sans) {
                                    if (e2.size() > 1 && sb.length() < 140) {
                                        if (sb.length() > 0) sb.append(',');
                                        sb.append(e2.get(1));
                                    }
                                }
                            }
                            o.put("certSan", sb.toString());
                        }
                    } catch (Throwable ignored) { }
                } catch (Throwable e) {
                    o.put("tlsMs", System.currentTimeMillis() - tTls)
                     .put("phase", "tls").put("err", err(e))
                     .put("ms", System.currentTimeMillis() - t0);
                    closeQuietly(raw);
                    return o;
                }
            }

            // ④ 真发一次 HTTP：这是唯一能证明「整条链路端到端通」的一步
            try {
                StringBuilder req = new StringBuilder();
                req.append(c.method).append(' ').append(path).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(host).append("\r\n");
                req.append("User-Agent: L6CC-ApiMatrix/1.0\r\n");
                req.append("Accept: */*\r\n");
                req.append("Connection: close\r\n");
                if (c.authValue == null) {
                    req.append(AUTH_HEADER).append(": ").append(deviceId).append("\r\n");
                } else if (!c.authValue.isEmpty()) {
                    req.append(AUTH_HEADER).append(": ").append(c.authValue).append("\r\n");
                }
                byte[] payload = null;
                if (c.body) {
                    JSONObject b = new JSONObject();
                    b.put("device", deviceId);
                    b.put("app", "L6CC");
                    b.put("ver", appVer());
                    b.put("file", "apimatrix");
                    b.put("ts", System.currentTimeMillis());
                    JSONArray ls = new JSONArray();
                    ls.put(FMT.format(new Date()) + " [INFO] L6ApiMatrix: #" + idx + "/" + total
                            + " " + c.name + "（探测矩阵）");
                    b.put("lines", ls);
                    fillMeta(b);
                    payload = b.toString().getBytes(StandardCharsets.UTF_8);
                    req.append("Content-Type: application/json; charset=utf-8\r\n");
                    req.append("Content-Length: ").append(payload.length).append("\r\n");
                }
                req.append("\r\n");
                OutputStream os = sk.getOutputStream();
                os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
                if (payload != null) os.write(payload);
                os.flush();
                if (payload != null) o.put("sentKb", payload.length / 1024);

                // 读响应：先把状态行+头读全，再给 body 一小段时间
                byte[] buf = new byte[8192];
                int got = 0;
                sk.setSoTimeout(MATRIX_READ_TIMEOUT_MS);
                java.io.InputStream in = sk.getInputStream();
                boolean headDone = false;
                while (got < buf.length) {
                    int n;
                    try {
                        n = in.read(buf, got, buf.length - got);
                    } catch (java.net.SocketTimeoutException ste) {
                        break;
                    }
                    if (n <= 0) break;
                    got += n;
                    if (!headDone && indexOf(buf, got, "\r\n\r\n") >= 0) {
                        headDone = true;
                        try { sk.setSoTimeout(1200); } catch (Throwable ignored) { }
                    }
                }
                o.put("recv", got);
                String text = new String(buf, 0, got, StandardCharsets.UTF_8);
                int nl = text.indexOf("\r\n");
                String statusLine = nl > 0 ? text.substring(0, nl) : text;
                int code = -1;
                try {
                    String[] parts = statusLine.split(" ");
                    if (parts.length >= 2) code = Integer.parseInt(parts[1].trim());
                } catch (Throwable ignored) { }
                int sep = text.indexOf("\r\n\r\n");
                String body = sep >= 0 ? text.substring(sep + 4) : "";
                o.put("code", code).put("statusLine", clip(statusLine, 90))
                 .put("resp", clip(oneLine(body), 240))
                 .put("ok", code >= 200 && code < 300).put("phase", "http");
            } catch (Throwable e) {
                o.put("phase", "http").put("err", err(e));
            }
        } catch (Throwable e) {
            try { o.put("phase", "self").put("err", err(e)); } catch (Throwable ignored) { }
        } finally {
            closeQuietly(sk);
            try { o.put("ms", System.currentTimeMillis() - t0); } catch (Throwable ignored) { }
        }
        return o;
    }

    /** 一行人类可读结论，写进本地日志用（字段顺序与页面渲染一致）。 */
    private static String matrixLine(int idx, int total, ApiCase c, JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(idx).append('/').append(total).append(' ').append(c.name);
        sb.append(" | ").append(c.method).append(' ').append(c.url);
        sb.append(" | ").append(c.sni ? "带SNI" : "无SNI")
          .append('/').append(c.tls.isEmpty() ? "默认协议" : c.tls);
        sb.append(" | dns ").append(r.optString("dnsSource", "?")).append(' ')
          .append(r.optString("dns", "?")).append(' ').append(r.optLong("dnsMs", -1)).append("ms");
        long tcp = r.optLong("tcpMs", -1);
        sb.append(" | tcp ").append(tcp < 0 ? "未建连" : (tcp + "ms"));
        if (r.has("tlsMs")) {
            sb.append(" | tls ").append(r.optLong("tlsMs")).append("ms ")
              .append(r.optString("proto", ""));
            String cn = r.optString("certCn", "");
            if (!cn.isEmpty()) sb.append(" cn=").append(cn);
            String san = r.optString("certSan", "");
            if (!san.isEmpty()) sb.append(" san=").append(san);
        }
        String err = r.optString("err", "");
        if (!err.isEmpty()) sb.append(" | ✗ ").append(r.optString("phase", "?")).append("阶段 ").append(err);
        if (r.has("code")) {
            sb.append(" | http ").append(r.optInt("code", -1)).append(' ')
              .append(r.optString("statusLine", ""));
        }
        String resp = r.optString("resp", "");
        if (!resp.isEmpty()) sb.append(" | body ").append(clip(resp, 160));
        sb.append(" | ").append(r.optLong("ms", 0)).append("ms");
        return sb.toString();
    }

    /**
     * 整份矩阵另存一个 JSON 文件（Download/L6/logs/api-matrix-<时间>.json），返回路径。
     * 存在的理由：日志是「一行一条」的流水，逐项结果散在几十行里；要把结论拿去比对/外发，
     * 一份结构化的 JSON 比让人从日志里手工摘要可靠得多。
     */
    private static String matrixDump(JSONArray rows, int total, int pass, long ms) {
        try {
            File dir = artifactDir();
            if (dir == null) return "";
            File f = new File(dir, "api-matrix-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".json");
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis()).put("time", FMT.format(new Date()));
            o.put("device", deviceId).put("app", "L6CC").put("ver", appVer());
            o.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("model", Build.MODEL).put("uploadEnabled", apiUploadEnabled);
            o.put("customDns", customDns);
            o.put("total", total).put("pass", pass).put("ms", ms);
            o.put("rows", rows);
            FileWriter w = new FileWriter(f, false);
            w.write(o.toString(2));
            w.close();
            i("L6ApiMatrix", "矩阵已另存：" + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Throwable e) {
            i("L6ApiMatrix", "矩阵另存失败：" + err(e));
            return "";
        }
    }

    /**
     * 结构化产物（探测矩阵 JSON / 信号采集 JSON）的落盘目录。
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
                File d2 = new File(ctx.getExternalFilesDir(null), "logs");
                if (!d2.exists()) d2.mkdirs();
                return d2;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 把一条结果推给页面（MainActivity 侧转成 window.L6LogMatrixResult）。 */
    private static void emitMatrix(JSONObject o, boolean done) {
        MatrixListener l = matrixListener;
        if (l == null) return;
        try {
            if (done && !o.has("kind")) o.put("kind", "done");
            if (!done && !o.has("kind")) o.put("kind", "case");
            l.onResult(o);
        } catch (Throwable ignored) { }
    }

    /**
     * 把一个 JSON 安全地作为「调用 window.&lt;fn&gt;(对象)」的 JS 片段（Base64 包一层）。
     *
     * <p>为什么必须包：结果里全是中文（方式名 / 错误原文 / 证书 SAN / 系统属性值），
     * 直接拼进 {@code evaluateJavascript} 会被引号或换行炸掉，而报错又是**静默**的。
     */
    public static String jsCall(String fn, String json) {
        String b64 = android.util.Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return "(window." + fn + "?window." + fn + "(JSON.parse((window.L6B64||atob)('"
                + b64 + "'))):null)";
    }

    /** 探测矩阵逐项回推（等价 {@code jsCall("L6LogMatrixResult", json)}）。 */
    public static String matrixJs(String json) {
        return jsCall("L6LogMatrixResult", json);
    }

    /** 供其它模块取当前版本名（同 {@link #appVer()}）。 */
    public static String appVerPublic() {
        return appVer();
    }

    /* ---------- 矩阵用的小工具 ---------- */
    private static String err(Throwable e) {
        return e.getClass().getSimpleName() + ": " + str(e.getMessage());
    }

    private static String str(String s) { return s == null ? "?" : s; }

    /** 截断（保留尾部信息量最大的部分：服务端 error 常在中后段）。 */
    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 折成一行：响应体里的换行/缩进在「一行一条」的日志里会把结构彻底打散。 */
    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim();
    }

    /** 在字节数组里找子串（响应头的结束标记，避免先转字符串再找导致多一次拷贝）。 */
    private static int indexOf(byte[] buf, int len, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= len; i++) {
            for (int j = 0; j < n.length; j++) if (buf[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
    }

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

    /* ===================== 载荷元信息（对齐接口说明 §5 字段字典） =====================
     * 与文档的差异（刻意的，不强行凑字段）：
     *   · `app` 沿用本应用既有的 "L6CC"（服务端已按此解析），另给 `channel` 标交付形态；
     *   · `ts` 保持 epoch 毫秒（既有契约），另给 `ts_iso` 提供文档那种 ISO 8601（UTC）；
     *   · 不提供 deviceFile / 不做 install 判定 —— 本应用没有标识文件，也不区分 install/heartbeat。
     * 任何一项采集失败都不影响上报：内部逐块 try/catch，缺谁就少谁。
     */
    private static void fillMeta(JSONObject body) {
        try { body.put("device_uuid", deviceId); } catch (Throwable ignored) { }
        try { body.put("appVersion", appVer()); } catch (Throwable ignored) { }
        try { body.put("os", "android"); } catch (Throwable ignored) { }
        try { body.put("osVersion", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"); } catch (Throwable ignored) { }
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            body.put("arch", (abis != null && abis.length > 0) ? abis[0] : "");
        } catch (Throwable ignored) { }
        try { body.put("channel", CHANNEL); } catch (Throwable ignored) { }
        try { body.put("virtualized", isVirtualized()); } catch (Throwable ignored) { }
        try { body.put("uploadEnabled", apiUploadEnabled); } catch (Throwable ignored) { }
        try { body.put("ts_iso", iso(System.currentTimeMillis())); } catch (Throwable ignored) { }
        try { body.put("installedAt", iso(pkgFirstInstallTime())); } catch (Throwable ignored) { }
        try { body.put("appVersionCode", appVerCode()); } catch (Throwable ignored) { }
        try { body.put("hardware", hardwareJson()); } catch (Throwable ignored) { }
        try { body.put("details", detailsJson()); } catch (Throwable ignored) { }
    }

    /** epoch 毫秒 → ISO 8601（UTC），与文档 §5.1 的 ts / installedAt 同格式。 */
    private static String iso(long ms) {
        if (ms <= 0) return "";
        try {
            return java.time.Instant.ofEpochMilli(ms).toString();
        } catch (Throwable t) {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).format(new Date(ms));
        }
    }

    private static int appVerCode() {
        if (ctx == null) return 0;
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable e) { return 0; }
    }

    /** 首次安装时间（PackageManager 的 firstInstallTime；取不到返回 0）。 */
    private static long pkgFirstInstallTime() {
        if (ctx == null) return 0;
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.firstInstallTime;
        } catch (Throwable e) { return 0; }
    }

    /**
     * 是否运行在模拟器 / 虚拟机里（对齐文档 §6.2 的 virtualized 语义，仅作标记，不参与任何哈希）。
     * 车机上恒为 false；一旦为 true 说明这份数据来自模拟器，统计侧可据此剔除。
     */
    private static boolean isVirtualized() {
        try {
            String probe = (Build.FINGERPRINT + "|" + Build.MODEL + "|" + Build.HARDWARE + "|"
                    + Build.PRODUCT + "|" + Build.BRAND + "|" + Build.MANUFACTURER).toLowerCase(Locale.ROOT);
            String[] marks = {"generic", "emulator", "goldfish", "ranchu", "sdk_gphone", "vbox", "vmware", "qemu"};
            for (String mk : marks) if (probe.contains(mk)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 6 维硬件标识（对齐文档 §5.2 的 hardware：安装时快照；这里取 Build 静态量，天然是快照）。 */
    private static JSONObject hardwareJson() {
        JSONObject h = new JSONObject();
        try { h.put("board", nz(Build.BOARD)); } catch (Throwable ignored) { }
        try { h.put("brand", nz(Build.BRAND)); } catch (Throwable ignored) { }
        try { h.put("model", nz(Build.MODEL)); } catch (Throwable ignored) { }
        try { h.put("product", nz(Build.PRODUCT)); } catch (Throwable ignored) { }
        try { h.put("device", nz(Build.DEVICE)); } catch (Throwable ignored) { }
        try { h.put("manufacturer", nz(Build.MANUFACTURER)); } catch (Throwable ignored) { }
        try { h.put("fingerprint", nz(Build.FINGERPRINT)); } catch (Throwable ignored) { }
        // 序列号：Android 10+ 需 READ_PRIVILEGED_PHONE_STATE，普通应用必抛 → 回落空串而不是让整块失败
        try { h.put("serial", Build.VERSION.SDK_INT < 26 ? nz(Build.SERIAL) : ""); } catch (Throwable ignored) { }
        return h;
    }

    /** 富硬件 / 运行期明细（对齐文档 §5.3 的 details：实时采集）。 */
    private static JSONObject detailsJson() {
        JSONObject dt = new JSONObject();
        try { dt.put("sdkInt", Build.VERSION.SDK_INT); } catch (Throwable ignored) { }
        try { dt.put("release", nz(Build.VERSION.RELEASE)); } catch (Throwable ignored) { }
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            JSONArray arr = new JSONArray();
            if (abis != null) for (String a : abis) arr.put(a);
            dt.put("abiList", arr);
        } catch (Throwable ignored) { }
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            dt.put("display", dm.widthPixels + "x" + dm.heightPixels + "@" + dm.densityDpi + "dpi");
        } catch (Throwable ignored) { }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            dt.put("memTotalMB", mi.totalMem / 1024 / 1024);
        } catch (Throwable ignored) { }
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            dt.put("storageTotalMB", sf.getTotalBytes() / 1024 / 1024);
            dt.put("storageFreeMB", sf.getAvailableBytes() / 1024 / 1024);
        } catch (Throwable ignored) { }
        try { dt.put("uptimeMin", SystemClock.elapsedRealtime() / 60000L); } catch (Throwable ignored) { }
        try { dt.put("logFile", getLogPath()); } catch (Throwable ignored) { }
        try { dt.put("dnsServer", customDns == null || customDns.isEmpty() ? "system" : customDns); } catch (Throwable ignored) { }
        return dt;
    }

    /** null / 空 → 空串（文档 §5.5：采集失败保留占位，不删字段）。 */
    private static String nz(String v) {
        return v == null ? "" : v;
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
            int doneBatches = 0, sentLines = 0, sentKb = 0;
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
                fillMeta(body);   // 文档字段字典里的同名键（device_uuid / os / hardware / details …）

                String json = body.toString();
                int kb = json.getBytes(StandardCharsets.UTF_8).length / 1024;
                PostResult r = postJsonChannels(json, true);
                if (r.ok()) {
                    sentOffset = baseOffset + ck.endOffset;
                    sentLines += ck.lines.length;
                    doneBatches++;
                    continue;
                }
                // 把体积回显出来：弱网 RST 排查时，「多大被掐掉」是关键信息
                sentKb += kb;
                // 401 / 403 属「鉴权 / 路径」类持久错误（文档 §8.2 的 fatal）：重发一万次也一样，
                // 直接退到最长退避并明确回显「鉴权失败」，别让它伪装成普通弱网抖动。
                boolean fatalAuth = (r.code == 401 || r.code == 403);
                int wait = fatalAuth ? noteFatalAuth() : noteFailure();   // 否则 60→120→240→480→600s；已成功的批次不会重发
                // 失败原因必须带真材实料：网络异常回显「哪一段挂的 + 异常类 + 耗时 + 对端 IP」，
                // 有 HTTP 响应的回显状态码 + 服务端 error 原文。v1.5.7 起不再只说「网络异常」——
                // 前几版正是因为看不出断在 connect/write/read 哪一段，只能靠猜。
                // 文案不再自带「上传失败」前缀：页面状态行已有「⚠️ 上报失败」徽标，重复会说两遍。
                String reason = LogDiag.reason(r.code, r.err, r.phase, r.ms, errDetail(r.resp));
                // 通道必须写进日志：两条通道的失败原因完全不同（443 是握手被 RST、:2000 是端口/路由），
                // 不写通道就会出现「同一个报错、两个根因」的糊涂账。
                w("L6LogUp", "上传失败 " + reason + " / " + kb + "KB / 试 " + r.attempts + " 次 / 对端 "
                        + (r.peer.isEmpty() ? "?" : r.peer) + " / 解析 " + r.dnsSource
                        + " / 通道 " + r.channel + " [轨迹 " + r.trail + "]"
                        + " → " + LOG_UPLOAD_URL);
                // 失败即停，且不推进 sentOffset —— 已成功的批次不会重发，未发的下一轮从断点续。
                // 部分成功时把「哪一批挂了 + 为什么挂」一起回显，别让原因被「部分成功」盖掉。
                String head = sentLines > 0
                        ? "部分成功（" + sentLines + " 行已上传，第 " + (doneBatches + 1) + "/" + chunks.length + " 批失败）："
                        : "";
                String fatalTip = fatalAuth ? "鉴权/路径被拒（HTTP " + r.code + "），" : "";
                setStatus(false, head + fatalTip + reason + " · 本次 " + kb + "KB · 试 " + r.attempts
                        + " 次 · 通道 " + r.channel + " · " + wait + "s 后重试");
                return;
            }
            if (sentLines > 0) {
                noteSuccess();   // 有一批成功就说明链路通了，立刻回到每 60s 一次
                setStatus(true, "已上传 " + sentLines + " 行 / " + sentKb + "KB"
                        + (chunks.length > 1 ? "（" + chunks.length + " 批）" : ""));
                i("L6LogUp", "上传成功 " + sentLines + " 行 / 通道 " + lastChannel + " → " + LOG_UPLOAD_URL);
            }
        } catch (Throwable t) {
            noteFailure();   // 本地异常（读文件/拼 JSON）也要退避，否则每 60s 炸一次
            setStatus(false, "异常:" + (t.getMessage() == null ? "?" : t.getMessage()));
        }
    }

    /**
     * POST 结果：HTTP 状态码 + 服务端响应体 + 网络异常信息，外加 v1.5.7 的诊断三件套
     * （失败阶段 / 对端地址 / 耗时）—— 只回显一句「网络异常」时，根因无从判断。
     */
    private static final class PostResult {
        int code = -1;      // HTTP 状态码；<0 表示根本没连上（DNS/TLS/超时/未知主机）
        String resp = "";   // 服务端响应体（最多 512 字符），失败时里面有 error 原文
        String err = "";    // 网络异常信息（含异常类名，code<0 时有效）
        int attempts = 1;   // 实际尝试次数（含立即重试），用于状态行回显
        String phase = "";  // 失败阶段：connect / write / read —— 决定根因方向
        String peer = "";   // 本次连接到的对端地址（IPv6 加方括号），DNS 劫持时一眼可见
        String dnsSource = "";  // 解析来源：系统 DNS / 自定义 223.5.5.5 —— 解到错 IP 时一眼可见
        long ms = 0;        // 本次尝试耗时（含建连 + 收发），毫秒
        String channel = "";    // 本结果由哪条通道产生（443 HTTPS / :2000 HTTP）
        String trail = "";      // 走过的通道轨迹（"主通道 失败(Connection reset) → :2000 成功"）

        boolean ok() { return code >= 200 && code < 300; }
    }

    /** POST JSON 到指定 URL（沿用 Ota/Lyrics 的 HttpURLConnection 风格）。返回状态码与响应体。 */
    private static PostResult postJson(String url, String json) {
        return postJson(url, json, null);
    }

    /**
     * POST JSON 到指定 URL，可选**覆盖 Host 头**。
     *
     * @param hostHeader 非空则强制把 HTTP `Host` 覆盖成该值。
     *   用途只有一个：明文直连网闸 `:2000` 时，URL 里带了端口 ⇒ 自动生成的 Host 会是
     *   `yanzi-api.ziruxue.top:2000`，而网闸按 Host **精确匹配**服务 ⇒ 匹配不上直接 403。
     *   其余通道（443）传 null，保持默认 Host。
     *
     * ★ 这个覆盖必须在 `getOutputStream()` **之前**设置完：`setRequestProperty` 只对
     *   尚未真正发出请求的连接生效，握手/发送一旦开始就再也改不动了。
     */
    private static PostResult postJson(String url, String json, String hostHeader) {
        PostResult r = new PostResult();
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        // 失败时用它定位：请求根本没出去？写一半被掐？还是响应没回来？
        // 前几版就是因为只有一句「网络异常」，这三个阶段分不开，根因无从下手。
        String phase = "connect";
        try {
            URL u = new URL(url);
            String host = u.getHost();
            Resolution res = resolveHost(host);
            r.dnsSource = res.source;
            r.peer = res.ips.length > 0 ? joinIps(res.ips) : resolvePeer(host);
            c = (HttpURLConnection) u.openConnection();
            // ★ 上报通道固定走「钉 IP + 不发 SNI」（2026-09-30 定稿）：
            //   网络路径上有设备会解析 TLS1.2 ClientHello 的 SNI 扩展，只要 SNI 含 ziruxue.top
            //   就回一个 RST（实测 25/25；换成任意其他 SNI、或干脆不带 SNI，都通）。Android 9
            //   车机最高只支持 TLS1.2，绕不开这条规则 —— 所以改成这次握手**不带 SNI**，
            //   由 HTTP 的 Host 头去选 vhost：服务端与域名一行都不用动。
            //   （详见 PinnedSsl 的 noSni 注释。）
            //   顺带保留原有作用：即便本机解析器给的是错 IP，也照旧连到 res.ips 里的机器。
            InetAddress pin = pickIpv4(res.ips);
            if (pin != null && c instanceof HttpsURLConnection) {
                try {
                    HttpsURLConnection hc = (HttpsURLConnection) c;
                    hc.setSSLSocketFactory(new PinnedSsl(
                            (SSLSocketFactory) SSLSocketFactory.getDefault(), pin, 15000, true));
                    // 不带 SNI 时握手拿到的是默认 server 的证书，SAN 未必含本接口子域
                    // （实测：SAN 只有 ziruxue.top / www.ziruxue.top）⇒ 必须放行主机名校验。
                    // 证书链与有效期仍由默认 TrustManager 正常校验：只在「主机名」这一维放松。
                    hc.setHostnameVerifier((h, session) -> true);
                } catch (Throwable t) {
                    android.util.Log.w("L6LogUp", "钉 IP + 去 SNI 失败，改用系统解析", t);
                }
            }
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 文档 §4.1 的鉴权头：deviceId 同时是服务端统计主键（不用随机 UUID）。
            // 每次都用当前 deviceId 重新写一遍：它是启动时确定的，不存在中途变化的可能。
            c.setRequestProperty(AUTH_HEADER, deviceId);
            // 每批都用全新连接：Android 会复用 keep-alive 套接字，而弱网/车机侧的对端
            // 可能已经单方面关掉旧连接，复用它的那一刻就是 Connection reset。
            c.setRequestProperty("Connection", "close");
            c.setDoOutput(true);
            c.setConnectTimeout(REQUEST_TIMEOUT_MS);
            c.setReadTimeout(REQUEST_TIMEOUT_MS);
            // ★ 保底通道（明文直连网闸 :2000）必须显式覆盖 Host —— 原因见 postJson 的 @param hostHeader。
            //   放在这里（getOutputStream 之前）：请求一旦开始发送就再也改不动请求头。
            if (hostHeader != null && !hostHeader.isEmpty()) {
                c.setRequestProperty("Host", hostHeader);
            }
            phase = "write";
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.close();
            phase = "read";
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
            r.phase = phase;
            // 异常类名必须带上：SocketException("Connection reset") 与 SSLException / 各超时
            // 指向完全不同的根因，只回显 message 会把这个关键区分丢掉。
            r.err = t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "?" : t.getMessage());
        } finally {
            r.ms = System.currentTimeMillis() - t0;
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return r;
    }

    /**
     * 解析目标主机到 IP 列表（IPv6 加方括号便于辨认），供失败回显与探针使用。
     * 带 5 分钟缓存：既让每次失败都带上「连的是哪个 IP」（DNS 被劫持 / 只给到 v6 这类问题一眼可见），
     * 又不会让每批上报都多打一次 DNS —— 首次解析同时把系统 DNS 缓存喂热，后续建连直接命中。
     */
    private static volatile String peerCache = "";
    private static volatile long peerCacheAt = 0;

    private static String resolvePeer(String host) {
        long now = System.currentTimeMillis();
        if (!peerCache.isEmpty() && now - peerCacheAt < 300000L) return peerCache;
        String out;
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            StringBuilder sb = new StringBuilder();
            for (InetAddress a : all) {
                if (sb.length() > 0) sb.append(' ');
                boolean v6 = a instanceof Inet6Address;
                sb.append(v6 ? "[" : "").append(a.getHostAddress()).append(v6 ? "]" : "");
            }
            out = sb.length() == 0 ? "无解析结果" : sb.toString();
        } catch (Throwable t) {
            out = "解析失败(" + t.getClass().getSimpleName() + ")";
        }
        peerCache = out;
        peerCacheAt = now;
        return out;
    }

    /**
     * 带「立即重试阶梯」的 POST（0.4s → 2s，最多 3 次；阶梯与判据都在 LogDiag，纯逻辑可单测）。
     * 只在**瞬时**失败时重试：code &lt; 0（根本没连上）或 code &gt;= 500（上游暂时性故障）；
     * 4xx 是请求本身的问题（400 TOO_MANY_LINES / 403 / 413…），重发一模一样的内容毫无意义，直接返回。
     */
    private static PostResult postJsonWithRetry(String url, String json, String hostHeader) {
        PostResult r = postJson(url, json, hostHeader);
        for (int attempt = 1; attempt < LogDiag.maxAttempts() && LogDiag.isTransient(r.code); attempt++) {
            long delay = LogDiag.delayAfter(attempt);
            if (delay < 0) break;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return r;
            }
            r = postJson(url, json, hostHeader);
            r.attempts = attempt + 1;
        }
        return r;
    }

    /* ===================== 双通道上报（v1.5.13） =====================
     * 顺序：**443 HTTPS（钉 IP + 去 SNI，加密）为主 → :2000 HTTP（直连网闸，明文）保底**。
     * 为什么是这个顺序（用户 2026-09-30 拍板）：加密优先；:2000 只在主通道网络层挂掉时顶上。
     * 主通道是「钻过滤器空子」（不发 SNI），规则一变就失效；保底通道明文、没有 ClientHello，
     * 压根没有被解析被拦的可能 ⇒ 两条腿交替，单点失效不再等于丢日志。
     *
     * 回退条件只有「网络层失败」（`LogDiag.isTransient`：code<0 或 5xx）——
     * 4xx 是请求本身的问题（400 行数超限 / 403 / 413），换通道答案一样，没必要多打一次。
     */
    private static PostResult postJsonChannels(String json, boolean retry) {
        // ① 主通道：443 + 去 SNI。保底通道存在的前提下，主通道仍然走完整重试阶梯
        //    （它成功概率最高，多试两次比直接降级划算）。
        PostResult r1 = retry ? postJsonWithRetry(LOG_UPLOAD_URL, json, null)
                              : postJson(LOG_UPLOAD_URL, json, null);
        r1.channel = CH_TLS;
        if (r1.ok() || !LogDiag.isTransient(r1.code)) {
            r1.trail = CH_TLS + " → " + (r1.ok() ? "成功" : ("HTTP " + r1.code));
            if (r1.ok()) lastChannel = r1.channel;
            return r1;
        }
        // ② 保底通道：明文直连网闸 :2000，**只发一次不重试** —— 已经切到保底还失败，
        //    说明网是真断了，再重试只是拖时间（下一轮 60s 后还会再来）。
        PostResult r2 = postJson(fallbackUrl2000(), json, INGEST_HOST);
        r2.channel = CH_2000;
        String head = CH_TLS + " 失败(" + brief(r1) + ") → ";
        r2.trail = head + CH_2000 + (r2.ok() ? " 成功" : (" 也失败(" + brief(r2) + ")"));
        if (r2.ok()) { lastChannel = r2.channel; return r2; }
        // 两条都挂：结论用保底那条（它是最后试的、最能代表当下状态），
        // 但**必须把主通道的失败原因并进 resp**，否则根因被后一条完全盖掉、又回到「只有一句网络异常」。
        if (r2.resp.isEmpty()) {
            try { r2.resp = "[" + head + "]"; } catch (Throwable ignored) { }
        }
        return r2;
    }

    /**
     * 保底通道的实际 URL：**优先把解析出来的 IPv4 写进 URL**，拿不到才回落到域名形态。
     * 钉 IP 的理由与 443 通道一致（v1.5.8 踩过：本机 DNS 把接口域名解到别人家的 IP）。
     * 明文 HTTP 没有证书校验兜底，解析错了只会得到一个没头没脑的 403/404 —— 更要钉住。
     */
    private static String fallbackUrl2000() {
        try {
            InetAddress pin = pickIpv4(resolveHost(INGEST_HOST).ips);
            if (pin != null) return "http://" + pin.getHostAddress() + ":2000/api/l6zk/log";
        } catch (Throwable ignored) { }
        return LOG_UPLOAD_URL_FALLBACK;
    }

    /** 一行短原因（回退轨迹用，别把完整 err 塞进状态行）。 */
    private static String brief(PostResult r) {
        if (r.code >= 0) return "HTTP " + r.code + (r.phase.isEmpty() ? "" : ("@" + r.phase));
        return r.err.isEmpty() ? "网络异常" : r.err;
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
