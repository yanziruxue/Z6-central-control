package com.l6.carmedia;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import android.os.Handler;
import android.os.Looper;

/**
 * 日志上报（v2.0.17）：把崩溃 / 运行日志 POST 到远端事件接口。
 *
 * <p><b>目标</b>：{@link #ENDPOINT} = https://yanzi-api.ziruxue.top/api/l6/event
 * <br><b>鉴权</b>：自定义请求头 {@code token: <共享token>}（服务端校验该头）。
 *
 * <p><b>设计约束</b>：
 *  - 复用项目既有的 {@link HttpURLConnection} 风格（与 Ota / Lyrics 一致），不引入任何第三方依赖；
 *  - 全量 try-catch，任何异常静默吞掉，绝不带崩进程或拖慢主流程；
 *  - 所有网络调用都在后台线程执行；{@link #sendEvent} 本身是同步阻塞的，调用方负责线程；
 *    {@link #sendCrash} / {@link #flushPendingCrash} / {@link #sendLogText} 内部自带后台线程；
 *  - token 写在源码常量（端侧 App 的共享 token 性质，可被反编译读取，已与需求方确认）。
 */
public final class L6Reporter {

    /** 远端事件接口。 */
    public static final String ENDPOINT = "https://yanzi-api.ziruxue.top/api/l6/event";

    /** 备用（内网）事件接口（30507 为服务端实际监听端口）：仅地址不同，鉴权头与 JSON 载荷与 {@link #ENDPOINT} 完全一致。 */
    public static final String ENDPOINT_LAN = "http://192.168.24.10:30507/api/l6/event";

    /** 共享 token：经自定义请求头 token: <TOKEN> 携带，服务端校验。 */
    public static final String TOKEN = "366577b64b2b76ac4dd318b99cff9745eb6c24f140a8da7fad43eb99546832c4";

    /** 后台线程池（守护线程，不阻止进程退出）。 */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "L6Reporter");
        t.setDaemon(true);
        return t;
    });

    private L6Reporter() {
    }

    /** 线程局部：记录当前线程最后一次网络异常的「类名: 信息」，用于 -1 时细分 DNS/超时/TLS（不跨线程污染）。 */
    private static final ThreadLocal<String> sLastErr = new ThreadLocal<>();

    /** v2.0.28：运行时探测到的 ROM TLS 能力，随 -1 诊断回显，车机点一次按钮即可取证。 */
    private static volatile String sTlsDiag = "";

    /** 取当前线程最后一次网络异常详情（-1 时附带 TLS 能力诊断；非 -1 时为 null）。 */
    public static String getLastError() {
        String d = sLastErr.get();
        if (d == null) return null;
        return sTlsDiag.isEmpty() ? d : d + " | " + sTlsDiag;
    }

    /**
     * POST 一个事件到远端（同步、吞异常）。
     * @return HTTP 状态码（2xx 表示成功）；网络 / 解析异常统一返回 -1。调用方据码判断并反馈。
     * 调用方需自行保证不在主线程调用（sendCrash / flushPendingCrash / sendLogText 已包线程）。
     */
    public static int sendEvent(JSONObject ev) {
        return sendEventTo(ENDPOINT, ev);
    }

    /**
     * 同 {@link #sendEvent}，但可指定目标地址（备用内网通道用）。
     *
     * <p>★ v2.0.28：删除 v2.0.27 的 TLS1.3 双路径死代码。根因收敛为「公网 443 只接受
     * TLS 1.3，而车机 API 28 默认只发 TLS 1.2」；Android 官方 {@code SSLContext.getInstance("TLSv1.3")}
     * 要到 API 29 才存在，API 28 真机实测抛 NoSuchAlgorithmException 且被 catch 吞掉 ⇒
     * v2.0.27 那两条路径一次都没运行过。本版改用 API 28 一定拿得到的 {@code "TLS"} context +
     * 运行时探测 + 显式 setEnabledProtocols 尝试协商 1.3；ROM 不支持则静默回落 1.2。
     * 内网 http 通道完全不进 TLS 逻辑（与之前一致）。
     */
    public static int sendEventTo(String endpoint, JSONObject ev) {
        sLastErr.remove();      // ★ 修诊断残留：线程池复用会带出上次的异常详情
        sTlsDiag = "";
        try {
            return postByConnection(endpoint, ev.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            String m = t.getMessage();
            sLastErr.set(t.getClass().getSimpleName() + (m != null ? ": " + m : ""));
            return -1;
        }
    }

    /**
     * 构造「尽量协商 TLS 1.3」的 SSLSocketFactory（零第三方依赖）。
     *
     * <p>AOSP Conscrypt CAPABILITIES.md：{@code SSLContext.getInstance("TLS")} 在支持 1.3 的实现上
     * 会启用 TLSv1.0-1.3；配合 setEnabledProtocols 显式开启 1.3。车机 MTK 定制 ROM 的 Conscrypt
     * 版本未知，故由 pickProtocols() 运行时探测，不支持就只留 1.2 —— 全量 try-catch，绝不抛。
     */
    private static SSLSocketFactory tlsCapableFactory() {
        try {
            final SSLContext ctx = SSLContext.getInstance("TLS");   // ★ 不是 "TLSv1.3"
            ctx.init(null, null, null);
            final SSLSocketFactory base = ctx.getSocketFactory();
            final String[] protocols = pickProtocols(base);
            return new SSLSocketFactory() {
                private Socket wrap(Socket s) {
                    if (s instanceof SSLSocket) {
                        try {
                            ((SSLSocket) s).setEnabledProtocols(protocols);
                        } catch (Throwable ignored) {
                            // ROM 不认该协议名就保持默认，绝不因尝试带崩上报
                        }
                    }
                    return s;
                }

                @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
                @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
                @Override public Socket createSocket() throws IOException { return wrap(base.createSocket()); }
                @Override public Socket createSocket(String h, int p) throws IOException { return wrap(base.createSocket(h, p)); }
                @Override public Socket createSocket(String h, int p, InetAddress lh, int lp) throws IOException {
                    return wrap(base.createSocket(h, p, lh, lp));
                }
                @Override public Socket createSocket(InetAddress h, int p) throws IOException { return wrap(base.createSocket(h, p)); }
                @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException {
                    return wrap(base.createSocket(a, p, la, lp));
                }
                @Override public Socket createSocket(Socket s, String h, int p, boolean ac) throws IOException {
                    return wrap(base.createSocket(s, h, p, ac));
                }
            };
        } catch (Throwable t) {
            sTlsDiag = "tlsFactoryFailed=" + t.getClass().getSimpleName();
            return null;
        }
    }

    /** 运行时探测 ROM 支持的协议；支持 1.3 则放首位，否则只留 1.2。结果写入 sTlsDiag 供 -1 诊断回显。 */
    private static String[] pickProtocols(SSLSocketFactory base) {
        String[] supported = new String[0];
        try {
            SSLSocket probe = (SSLSocket) base.createSocket();
            supported = probe.getSupportedProtocols();
            probe.close();
        } catch (Throwable ignored) {
        }
        boolean has13 = false;
        for (String p : supported) {
            if ("TLSv1.3".equals(p)) has13 = true;
        }
        sTlsDiag = "tls13Supported=" + has13 + " " + Arrays.toString(supported);
        return has13 ? new String[]{"TLSv1.3", "TLSv1.2"} : new String[]{"TLSv1.2"};
    }

    /** 常规 HttpURLConnection 路径：https 挂 tlsCapableFactory，http（内网）原样直连。 */
    private static int postByConnection(String endpoint, byte[] body) {
        HttpURLConnection c = null;
        OutputStream os = null;
        try {
            URL u = new URL(endpoint);
            c = (HttpURLConnection) u.openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = tlsCapableFactory();
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json, */*;q=0.5");
            c.setRequestProperty("User-Agent", "L6CarMedia-Reporter/1.0");
            c.setRequestProperty("token", TOKEN);
            os = c.getOutputStream();
            os.write(body);
            os.flush();
            int code = c.getResponseCode();
            sLastErr.remove();
            return code;
        } catch (Throwable t) {
            String m = t.getMessage();
            sLastErr.set(t.getClass().getSimpleName() + (m != null ? ": " + m : ""));
            return -1;
        } finally {
            try {
                if (os != null) os.close();
            } catch (Throwable ignored) {
            }
            if (c != null) try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 手动发送结果回调：在 UI 主线程回调，便于直接弹吐司。detail 为网络异常详情（-1 时有值，如 UnknownHostException: ...）。 */
    public interface ResultCallback {
        void onResult(int code, String detail);
    }

    /**
     * 把 HTTP 状态码翻译成用户可读的简短说明（手动发送反馈用）。
     * @param code   sendEvent 的返回值；-1 表示网络异常
     * @param detail sendEvent 捕获的异常详情（类名: 信息）；-1 且有值时 finer 区分 DNS/超时/TLS
     */
    public static String codeMessage(int code, String detail) {
        if (code >= 200 && code < 300) return "成功";
        if (code == -1) {
            if (detail != null && !detail.isEmpty()) return "网络异常（" + detail + "）";
            return "网络异常（无网络 / 超时 / 被拦截）";
        }
        switch (code) {
            case 400: return "请求格式错误（服务端拒收，检查 kind/字段）";
            case 401: return "鉴权失败（token 不匹配）";
            case 403: return "接口已停用";
            case 404: return "接口不存在（段名 / 路径错误）";
            case 429: return "请求过于频繁";
            default:
                if (code >= 500 && code < 600) return "服务器内部错误";
                return "未知错误（" + code + "）";
        }
    }

    /** 兼容旧调用：detail 取空。 */
    public static String codeMessage(int code) {
        return codeMessage(code, "");
    }

    /**
     * 构造事件外壳（带设备 / 版本信息）。
     *
     * <p>★ v2.0.27：新增 voltage（12V 电瓶电压，单位 V，取不到为 -1）与
     * currentVersion（当前应用版本名）。原 ver 字段保留不动 —— 只增不改，
     * 老服务端解析不受影响；崩溃 / 启动补发 / 手动云端 / 手动内网四类事件全部覆盖。
     */
    private static JSONObject baseEvent() {
        JSONObject o = new JSONObject();
        try {
            String ver = L6Log.appVerPublic();
            o.put("ver", ver);
            o.put("currentVersion", ver);
            o.put("voltage", L6Log.batteryVoltageV());
            o.put("os", "Android " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            o.put("model", android.os.Build.MODEL);
            o.put("time", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 崩溃自动上报：后台线程 POST 崩溃堆栈（fire-and-forget，最佳努力）。 */
    public static void sendCrash(final String threadName, final String stackText) {
        POOL.execute(() -> {
            try {
                JSONObject o = baseEvent();
                o.put("kind", "crash");
                o.put("thread", threadName == null ? "?" : threadName);
                o.put("msg", stackText == null ? "" : stackText);
                sendEvent(o);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 启动兜底补发：扫描 app-log 下残留的 l6-crash-*.txt，逐个上报，成功即删。
     * 保证崩溃瞬间的日志（当时可能没网 / 进程随即被杀）在下次启动时最终送达。内部自带后台线程。
     */
    public static void flushPendingCrash() {
        POOL.execute(() -> {
            try {
                File dir = crashDir();
                if (dir == null || !dir.isDirectory()) return;
                File[] fs = dir.listFiles((d, n) -> n.startsWith("l6-crash-") && n.endsWith(".txt"));
                if (fs == null) return;
                for (File f : fs) {
                    String text = readFile(f);
                    if (text == null) continue;
                    JSONObject o = baseEvent();
                    o.put("kind", "crash");
                    o.put("thread", "pending:" + f.getName());
                    o.put("msg", text);
                    int code = sendEvent(o);
                    if (code >= 200 && code < 300) {
                        f.delete();
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 上报一段运行日志文本（手动按钮调用）。后台线程执行，结果经主线程回调。 */
    public static void sendLogText(final String title, final String text, final ResultCallback cb) {
        sendLogTextTo(ENDPOINT, title, text, cb);
    }

    /** 同 {@link #sendLogText}，但发往指定地址（备用内网通道用）。 */
    public static void sendLogTextTo(final String endpoint, final String title, final String text, final ResultCallback cb) {
        POOL.execute(() -> {
            int code = -1;
            try {
                JSONObject o = baseEvent();
                o.put("kind", "log");
                o.put("title", title == null ? "" : title);
                o.put("msg", text == null ? "" : text);
                code = sendEventTo(endpoint, o);
            } catch (Throwable ignored) {
            }
            final int rc = code;
            final String detail = getLastError();
            if (cb != null) {
                new Handler(Looper.getMainLooper()).post(() -> cb.onResult(rc, detail));
            }
        });
    }

    /** l6-crash-*.txt 所在目录（与 L6Log 运行日志同目录 app-log/）。 */
    private static File crashDir() {
        try {
            File daily = L6Log.logFile();
            if (daily != null && daily.getParentFile() != null) return daily.getParentFile();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readFile(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
