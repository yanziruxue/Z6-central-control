package com.l6.carmedia;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocketFactory;

/**
 * 把 TLS 连接「钉」到指定 IP 的 SSLSocketFactory（v1.5.8）。
 *
 * 用途一（钉 IP，v1.5.8）：手机/车机本地 DNS 可能把接口域名解到**错误 IP**。我们用可信 DNS（设置页手填）
 * 解析出正确 IP 后，用它把连接导向那台机器；**SNI 与证书/主机名校验仍用原域名**
 * （即 host 参数）—— 所以安全性不降级，只是绕开了本机那份坏掉的解析结果。
 *
 * 用途二（noSni=true，2026-09-30）：网络路径上有设备会解析 TLS1.2 ClientHello 里的 SNI 扩展，
 * 只要 SNI 含 `ziruxue.top` 就回 RST；而上报域名正好中招。构造时传 {@code noSni=true}
 * 即可让这次握手**不带 SNI**，改由 HTTP 的 `Host` 头选 vhost ——
 * **代价**是握手拿到的是默认 server 的证书（可能不含本次子域），调用方需自行放宽主机名校验。
 * 证书链与有效期仍由默认 TrustManager 正常校验。
 *
 * 实现要点：Android 的 HttpsURLConnection（内部 OkHttp）会先按系统 DNS 连一个裸 socket，
 * 再调 {@code createSocket(裸socket, host, port, true)} 把它包成 TLS。
 * 我们在这里**丢弃那个裸 socket**（它连的可能是错 IP），改连到钉住的 IP；
 * HttpsURLConnection 的 HTTP 编解码逻辑照旧不变。
 *
 * 注意：那个「先按系统 DNS 连一次」的裸连接仍会发生一次（随即被关掉），属于必要代价 ——
 * 只有拿到那一层 socket 才有机会把它换掉。
 */
public final class PinnedSsl extends SSLSocketFactory {

    private final SSLSocketFactory base;
    private final InetAddress ip;
    private final int connectTimeoutMs;
    /** true = 不发 SNI（把 IP 字面量当 host 交给底层工厂）。用途与代价见 createSocket 内注释。 */
    private final boolean noSni;

    public PinnedSsl(SSLSocketFactory base, InetAddress ip, int connectTimeoutMs) {
        this(base, ip, connectTimeoutMs, false);
    }

    public PinnedSsl(SSLSocketFactory base, InetAddress ip, int connectTimeoutMs, boolean noSni) {
        this.base = base;
        this.ip = ip;
        this.connectTimeoutMs = connectTimeoutMs;
        this.noSni = noSni;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }   // 丢掉按（可能错误的）系统解析连上的 socket
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
        // noSni：把 host 换成 IP 字面量再交给底层工厂 —— SNI 只能是主机名、不能是 IP 字面量，
        // 于是这次 ClientHello 里不再带 SNI 扩展。
        //
        // 为什么需要它（2026-09-30 实测）：网络路径上有一台设备会解析 TLS1.2 ClientHello 的
        // SNI 扩展，只要 SNI 含 ziruxue.top 就回一个 RST（换任意其他 SNI 或干脆不带 SNI 都通）。
        // Android 9 车机最高只支持 TLS1.2、绕不开，所以上报通道改用「不发 SNI + 靠 Host 头路由」。
        //
        // 代价（调用方必须承担）：服务端只能按 HTTP 的 Host 头选 vhost，握手拿到的是默认 server
        // 的证书（可能不含本次要访问的子域）⇒ 调用方需自行放宽主机名校验。证书链与有效期照常校验。
        String sniHost = noSni ? ip.getHostAddress() : host;
        Socket sock = base.createSocket(raw, sniHost, port, true);
        if (noSni && sock instanceof javax.net.ssl.SSLSocket) {
            // 双保险：上面把 host 换成 IP 只能「让实现不去推导 SNI」，但不同实现（JDK / Conscrypt）
            // 行为未必一致。这里再明确把 SNI 列表置为空 —— 空列表 JSSE 语义就是「不发 SNI」。
            // 万一底层不支持该 API 也不影响主路径（上面的 IP 已经兜了一层）。
            try {
                javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) sock;
                javax.net.ssl.SSLParameters p = ss.getSSLParameters();
                p.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>emptyList());
                ss.setSSLParameters(p);
            } catch (Throwable ignored) { }
        }
        return sock;
    }

    @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }

    @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }

    @Override public Socket createSocket(String host, int port) throws IOException {
        return base.createSocket(host, port);
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return base.createSocket(host, port, localHost, localPort);
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return base.createSocket(host, port);
    }

    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return base.createSocket(address, port, localAddress, localPort);
    }
}
