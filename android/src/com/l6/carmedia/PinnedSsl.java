package com.l6.carmedia;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocketFactory;

/**
 * 把 TLS 连接「钉」到指定 IP 的 SSLSocketFactory（v1.5.8）。
 *
 * 用途：手机/车机本地 DNS 可能把接口域名解到**错误 IP**。我们用可信 DNS（设置页手填）
 * 解析出正确 IP 后，用它把连接导向那台机器；**SNI 与证书/主机名校验仍用原域名**
 * （即 host 参数）—— 所以安全性不降级，只是绕开了本机那份坏掉的解析结果。
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

    public PinnedSsl(SSLSocketFactory base, InetAddress ip, int connectTimeoutMs) {
        this.base = base;
        this.ip = ip;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }   // 丢掉按（可能错误的）系统解析连上的 socket
        }
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
        return base.createSocket(raw, host, port, true);        // SNI/主机名校验仍用 host
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
