package com.l6.carmedia;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「车机信号采集」调度器。
 *
 * <p>用法（用户在车机上的操作序列）：
 * <pre>
 *   点「开始采集」 → 去按车门/车窗/车辆按钮 → 点「结束采集」 → 把日志发出来
 * </pre>
 *
 * <p>采集期间每秒采一份 {@link SysProbe#snapshot} 快照，用 {@link SigDiff} 比出与上一秒的差异，
 * 差异即「车机刚才发生了什么」。每条变化同时：
 * <ul>
 *   <li>写进本地日志（标签 {@code L6Signal}）—— 这是最终要发出去的东西；</li>
 *   <li>经 listener 推回页面实时滚动（Base64 包的 JS，见 {@link L6Log#jsCall}）。</li>
 * </ul>
 *
 * <p>采集开始时会先跑一遍 {@link SysProbe#capability}，把「这台车机开放了哪些接口」
 * 写进日志开头 —— 即使一个变化都没抓到，也能看出<b>能挖什么、差什么权限</b>。
 *
 * <p>采集结束时会另存一份结构化 JSON（与日志同目录），把能力清单 / 车辆键 / 标记 /
 * 变化序列 / 窗口内的 logcat 全部打包，便于整份外发。
 *
 * <p><b>不申请任何新权限</b>；不做前台服务 —— 用户会保持 App 在前台
 * （若将来需要在后台采，再挂 {@link L6NotifyService} 那样的前台通知）。
 */
public final class SignalCapture {

    /** 结果回推接口（MainActivity 实现：转成 window.L6SignalResult）。 */
    public interface Listener {
        void onEvent(JSONObject o);
    }

    private static final String TAG = "L6Signal";
    /** 轮询间隔。1s 足够看清按键类信号的先后，又不会把 CPU 打满。 */
    private static final int INTERVAL_MS = 1000;
    /** 单次采集上限，防用户忘了点「结束」。 */
    private static final long MAX_MS = 300_000L;
    /** 每 10s 写一条心跳，用来证明「采集确实活着」。 */
    private static final long BEAT_MS = 10_000L;
    /** 变化条数上限（超了就停收，避免异常刷屏把内存吃光）。 */
    private static final int MAX_CHANGES = 5000;
    /** 单轮推回页面的条数上限（日志仍全量记录）。 */
    private static final int MAX_EMIT_PER_ROUND = 60;
    /** 结束时要抓的 logcat 行数上限。 */
    private static final int LOGCAT_TAIL = 4000;

    private static volatile Listener listener;
    private static volatile boolean running;
    private static volatile boolean stopReq;
    private static volatile String startedIso = "";
    /** 本次采集的起点（相对时间的基准）。每次 start 重置。 */
    private static volatile long startAt;
    private static Thread worker;
    private static Context appCtx;
    private static final List<Mark> marks = new ArrayList<>();

    private SignalCapture() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean isRunning() {
        return running;
    }

    /* ==================== 控制 ==================== */

    /** 开始采集。已在跑则直接返回（页面也会禁用按钮，这里是二次防线）。 */
    public static void start(Context c) {
        if (running) {
            L6Log.i(TAG, "已在采集中，忽略重复的开始指令");
            return;
        }
        appCtx = c == null ? null : c.getApplicationContext();
        running = true;
        stopReq = false;
        startAt = System.currentTimeMillis();
        startedIso = iso();
        marks.clear();
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "l6-signal");
        worker.setDaemon(true);
        worker.start();
    }

    /** 请求结束。真正的收尾（落盘 / 汇总）在采集线程里做，保证顺序。 */
    public static void stop() {
        if (!running) {
            return;
        }
        stopReq = true;
    }

    /**
     * 打一个标记（用户在按某个车辆按钮前后点一下，便于把变化对齐到操作）。
     * 不参与 diff —— 只是往时间轴上插一条注释。
     */
    public static void mark(String label) {
        if (!running) {
            return;
        }
        String lab = (label == null || label.trim().isEmpty()) ? "标记" : label.trim();
        long t = elapsed();
        marks.add(new Mark(t, lab));
        L6Log.i(TAG, stamp(t) + " ★ " + lab);
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "mark").put("t", t).put("label", lab);
        } catch (Throwable ignored) { }
        emit(o);
    }

    /* ==================== 采集主循环 ==================== */

    private static void loop() {
        long t0 = System.currentTimeMillis();
        List<SigDiff.Change> changes = new ArrayList<>();
        JSONObject cap = null;
        try {
            // ① 能力探测（先落地：这是「探索 API 逻辑」的结论）
            if (appCtx != null) {
                cap = SysProbe.capability(appCtx);
            }
        } catch (Throwable e) {
            cap = new JSONObject();
        }
        logCapability(cap);

        TreeMap<String, String> prev = null;
        try {
            prev = SysProbe.snapshot(appCtx);
        } catch (Throwable e) {
            L6Log.i(TAG, "基线快照失败：" + e);
        }
        long nextBeat = BEAT_MS;
        long rounds = 0;

        try {
            while (!stopReq) {
                long el = System.currentTimeMillis() - t0;
                if (el >= MAX_MS) {
                    L6Log.i(TAG, stamp(el) + " ⏱ 已达 " + (MAX_MS / 1000) + "s 上限，自动结束");
                    break;
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException ie) {
                    break;
                }
                rounds++;

                TreeMap<String, String> now = null;
                try {
                    now = SysProbe.snapshot(appCtx);
                } catch (Throwable e) {
                    // 单轮失败不终止采集：车机上偶发 SecurityException 很正常
                    continue;
                }
                List<SigDiff.Change> delta = (prev == null)
                        ? new ArrayList<SigDiff.Change>()
                        : SigDiff.diff(prev, now, ignoreKeys());
                prev = now;

                long t = System.currentTimeMillis() - t0;
                int emitted = 0;
                for (SigDiff.Change ch : delta) {
                    if (changes.size() >= MAX_CHANGES) {
                        break;
                    }
                    changes.add(ch);
                    L6Log.i(TAG, stamp(t) + " " + ch.src() + " | " + ch.describe());
                    if (emitted < MAX_EMIT_PER_ROUND) {
                        emitted++;
                        emitChange(t, ch);
                    }
                }
                if (t >= nextBeat) {
                    nextBeat = t + BEAT_MS;
                    L6Log.i(TAG, stamp(t) + " · 采集中（轮次 " + rounds + "，累计变化 "
                            + changes.size() + " 条，标记 " + marks.size() + " 个）");
                    JSONObject b = new JSONObject();
                    try {
                        b.put("kind", "beat").put("t", t).put("rounds", rounds)
                                .put("changes", changes.size()).put("marks", marks.size());
                    } catch (Throwable ignored) { }
                    emit(b);
                }
            }
        } catch (Throwable e) {
            L6Log.i(TAG, "采集线程异常退出：" + e);
        }

        finish(t0, changes, cap);
    }

    /* ==================== 收尾 ==================== */

    private static void finish(long t0, List<SigDiff.Change> changes, JSONObject cap) {
        long dur = System.currentTimeMillis() - t0;
        String file = "";
        int logLines = 0;
        try {
            String[] logs = dumpLogcat(t0, System.currentTimeMillis());
            logLines = logs.length;
            file = dump(t0, dur, changes, cap, logs);
        } catch (Throwable e) {
            L6Log.i(TAG, "落盘失败：" + e);
        }
        L6Log.i(TAG, stamp(dur) + " END 采集结束：用时 " + (dur / 1000) + "s，变化 "
                + changes.size() + " 条，标记 " + marks.size() + " 个，logcat " + logLines
                + " 行" + (file.isEmpty() ? "" : "，已存 " + file));

        JSONObject o = new JSONObject();
        try {
            o.put("kind", "done").put("ms", dur).put("changes", changes.size())
                    .put("marks", marks.size()).put("logcatLines", logLines).put("file", file);
        } catch (Throwable ignored) { }
        emit(o);

        running = false;
        stopReq = false;
        worker = null;
        marks.clear();
    }

    /**
     * 抓采集窗口内的 logcat。
     *
     * <p>普通 App 一般拿不到 {@code READ_LOGS}（系统权限），这里**尽力而为**：
     * 读不到就返回空数组，由 {@link SysProbe#capability} 里的结论说明原因。
     * 用 {@code -v epoch} 让时间戳可直接比较（Android 7+ 支持），省去解析 {@code MM-DD} 的年份问题。
     */
    private static String[] dumpLogcat(long fromMs, long toMs) {
        try {
            String out = SysProbe.exec(10, "/system/bin/logcat", "-d", "-v", "epoch", "-t", String.valueOf(LOGCAT_TAIL));
            if (out == null || out.trim().isEmpty()) {
                out = SysProbe.exec(10, "/system/bin/logcat", "-d", "-t", String.valueOf(LOGCAT_TAIL));
            }
            if (out == null || out.trim().isEmpty()) {
                return new String[0];
            }
            double lo = fromMs / 1000.0 - 1.0;
            double hi = toMs / 1000.0 + 1.0;
            List<String> keep = new ArrayList<>();
            String[] lines = out.split("\n");
            for (String ln : lines) {
                String s = ln.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int sp = s.indexOf(' ');
                if (sp > 0) {
                    try {
                        double ts = Double.parseDouble(s.substring(0, sp));
                        if (ts < lo || ts > hi) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // 不是 epoch 格式（回退到 -t 时）→ 不过滤，全收
                    }
                }
                keep.add(ln);
                if (keep.size() >= LOGCAT_TAIL) {
                    break;
                }
            }
            return keep.toArray(new String[0]);
        } catch (Throwable e) {
            return new String[0];
        }
    }

    /** 结构化落盘：能力清单 / 车辆键 / 标记 / 变化序列 / logcat 窗口。 */
    private static String dump(long t0, long dur, List<SigDiff.Change> changes,
                               JSONObject cap, String[] logs) {
        File dir = L6Log.artifactDir();
        if (dir == null) {
            return "";
        }
        File f = new File(dir, "signal-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(t0)) + ".json");
        JSONObject o = new JSONObject();
        try {
            o.put("ts", t0).put("startedAt", startedIso).put("endedAt", iso());
            o.put("device", Build.MODEL).put("app", "L6CC");
            o.put("ver", L6Log.appVerPublic());
            o.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("durationMs", dur);
            if (cap != null) {
                o.put("capability", cap);
            }
            JSONArray mk = new JSONArray();
            for (Mark m : marks) {
                mk.put(new JSONObject().put("t", m.t).put("label", m.label));
            }
            o.put("marks", mk);
            JSONArray cs = new JSONArray();
            for (SigDiff.Change ch : changes) {
                cs.put(new JSONObject()
                        .put("src", ch.src()).put("key", ch.name())
                        .put("from", ch.from == null ? JSONObject.NULL : ch.from)
                        .put("to", ch.to == null ? JSONObject.NULL : ch.to)
                        .put("kind", ch.kind));
            }
            o.put("changes", cs);
            JSONArray lg = new JSONArray();
            for (String s : logs) {
                lg.put(s);
            }
            o.put("logcat", lg);
            o.put("ignoreKeys", new JSONArray(new ArrayList<>(ignoreKeys())));
            FileWriter w = new FileWriter(f, false);
            w.write(o.toString(2));
            w.close();
            L6Log.i(TAG, "采集记录已另存：" + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Throwable e) {
            L6Log.i(TAG, "另存失败：" + e);
            return "";
        }
    }

    /* ==================== 脏键忽略清单 ==================== */

    private static Set<String> ignoreCache;

    /**
     * 每秒都在变、且与车辆信号无关的键。
     * 不排掉的话，diff 每一轮都会被「运行时长 / 内存余量 / 播放进度」刷屏，
     * 真正的车门/车窗信号会被淹没。
     */
    private static Set<String> ignoreKeys() {
        Set<String> s = ignoreCache;
        if (s != null) {
            return s;
        }
        s = new HashSet<>();
        s.add("proc:uptime");
        s.add("proc:loadavg");
        s.add("mem:availKB");
        s.add("mem:freeKB");
        s.add("mem:cacheKB");
        s.add("mem:availMB");
        s.add("mem:heapUsedKB");
        s.add("pwr:uptimeMs");
        s.add("media:pos");
        s.add("net:wifi.rssi");
        s.add("set.system:next_alarm_formatted");
        s.add("prop:__count");
        for (int i = 0; i < 16; i++) {
            s.add("cpu:" + i + ".curKHz");
        }
        ignoreCache = s;
        return s;
    }

    /* ==================== 小工具 ==================== */

    private static final class Mark {
        final long t;
        final String label;

        Mark(long t, String label) {
            this.t = t;
            this.label = label;
        }
    }

    private static long elapsed() {
        return System.currentTimeMillis() - startAt;
    }

    /** 相对时间戳，如 {@code [+12.3s]}。 */
    private static String stamp(long t) {
        return "[+" + (t / 1000) + "." + ((t % 1000) / 100) + "s]";
    }

    private static String iso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(new Date());
    }

    private static void emit(JSONObject o) {
        Listener l = listener;
        if (l == null || o == null) {
            return;
        }
        try {
            l.onEvent(o);
        } catch (Throwable ignored) { }
    }

    private static void emitChange(long t, SigDiff.Change ch) {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "change").put("t", t).put("src", ch.src()).put("key", ch.name());
            o.put("from", ch.from == null ? "" : ch.from);
            o.put("to", ch.to == null ? "" : ch.to);
            o.put("type", ch.kind);
        } catch (Throwable ignored) { }
        emit(o);
    }

    /** 把能力探测结论写成人类可读的几行日志（这几行是整个采集里信息密度最高的）。 */
    private static void logCapability(JSONObject cap) {
        if (cap == null) {
            return;
        }
        try {
            String logcat = verdict(cap.opt("logcat"));
            String dumpsys = verdict(cap.opt("dumpsys"));
            JSONObject car = cap.optJSONObject("car");
            String carTxt = "未知";
            if (car != null) {
                carTxt = "类=" + (car.optBoolean("class") ? "有" : "无")
                        + " 车机特性=" + (car.optBoolean("automotiveFeature") ? "有" : "无")
                        + " 动力权限=" + car.optString("carPowerPermission", "?");
            }
            L6Log.i(TAG, "[+0.0s] CAP logcat=" + logcat + " | dumpsys=" + dumpsys
                    + " | CarAPI=" + carTxt
                    + " | 属性 " + cap.optInt("propCount", 0) + " 个"
                    + "，疑车辆键 " + cap.optInt("vehicleKeyCount", 0) + " 个");
            JSONArray vk = cap.optJSONArray("vehicleKeys");
            if (vk != null && vk.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vk.length(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(vk.optString(i));
                    if (sb.length() > 1200) {
                        sb.append(" …(共 ").append(vk.length()).append(" 个)");
                        break;
                    }
                }
                L6Log.i(TAG, "[+0.0s] CAP 车辆相关键：" + sb);
            }
        } catch (Throwable ignored) { }
        JSONObject o = new JSONObject();
        try {
            o.put("kind", "cap").put("cap", cap);
        } catch (Throwable ignored) { }
        emit(o);
    }

    private static String verdict(Object v) {
        if (v == null) {
            return "未知";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            if (o.optBoolean("ok")) {
                return "可读";
            }
            String e = o.optString("err", "");
            if (e.toLowerCase(Locale.ROOT).indexOf("denied") >= 0) {
                return "权限拒绝";
            }
            return e.isEmpty() ? "不可用" : ("不可用(" + e + ")");
        }
        return String.valueOf(v);
    }

    /** 供冒烟断言用：忽略清单必须覆盖这些最吵的键。 */
    public static Set<String> ignoreKeysForTest() {
        return ignoreKeys();
    }

    /** 供冒烟断言用：上限常量。 */
    public static long maxMsForTest() {
        return MAX_MS;
    }

    /** 供冒烟断言用：轮询间隔。 */
    public static int intervalMsForTest() {
        return INTERVAL_MS;
    }
}
