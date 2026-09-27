package com.l6.carmedia;

/**
 * 日志上报的诊断辅助（**纯逻辑，零 Android 依赖**）—— 供 tools/LogicSmoke.java 直接回归。
 *
 * 背景：v1.5.3~v1.5.6 一直在「猜」Connection reset 的根因（体积？UA？服务端？），每版都在盲改。
 * v1.5.7 起改为「先测量」：把失败发生在上传链路的哪一段（建立连接 / 写入请求体 / 读取响应）
 * 连同异常类与耗时一起回显，让根因可判定而不是靠猜。这个类把其中的判据与文案抽出来，
 * 好让「重试几次、什么时候重试、失败怎么说」这些规则能被单测钉住。
 */
public final class LogDiag {

    /**
     * 立即重试的延迟阶梯（毫秒）：第 i 次尝试失败后等 RETRY_DELAYS_MS[i - 1] 再发下一发。
     * 400ms 抓「就那么一瞬间断了」；2s 抓稍长的窗口；再长就该交给 60→600s 的退避，
     * 而不是让用户盯着一个按钮转圈。
     * ⚠️ v1.5.6 只有「1 次 / 400ms」，手机实测证明 400ms 后重发会落在同一个故障窗口里 ——
     * 两次一起失败，所以 v1.5.7 补到两档。
     */
    public static final long[] RETRY_DELAYS_MS = {400, 2000};

    private LogDiag() { }

    /** 单批最多尝试次数（1 次原始 + 阶梯里每一档各一次）。 */
    public static int maxAttempts() { return RETRY_DELAYS_MS.length + 1; }

    /** 第 attempt 次尝试（1 起）失败后，下一次重试前要等多久；已无重试可做则返回 -1。 */
    public static long delayAfter(int attempt) {
        int idx = attempt - 1;
        if (idx < 0 || idx >= RETRY_DELAYS_MS.length) return -1;
        return RETRY_DELAYS_MS[idx];
    }

    /**
     * 是否属于「当场重发没准就好了」的瞬时故障。
     * code &lt; 0 = 根本没连上（DNS / TLS / 超时 / Connection reset）；
     * code &gt;= 500 = 服务端或上游暂时性故障。
     * 4xx 是请求本身的问题（400 TOO_MANY_LINES / 403 / 413…），重发一模一样的内容毫无意义。
     */
    public static boolean isTransient(int code) {
        return code < 0 || code >= 500;
    }

    /** 失败阶段的中文名（回显给用户看的是中文，不是 connect / write / read）。 */
    public static String phaseLabel(String phase) {
        if ("write".equals(phase)) return "写入请求体";
        if ("read".equals(phase)) return "读取响应";
        return "建立连接";
    }

    /**
     * 拼装「为什么失败」——不含体积 / 尝试次数（那些由调用方追加，避免与状态行耦合），
     * 也**不带**「上传失败」前缀（页面状态行已有「⚠️ 上报失败」徽标，带上会说两遍）。
     *
     * <p>code &lt; 0 → {@code 网络异常·读取响应：SocketException: Connection reset，用时 3021ms}
     * <br>code ≥ 400 → {@code HTTP 400：TOO_MANY_LINES}
     */
    public static String reason(int code, String err, String phase, long ms, String httpDetail) {
        if (code < 0) {
            String e = (err == null || err.isEmpty()) ? "未知网络异常" : err;
            return "网络异常·" + phaseLabel(phase) + "：" + e + "，用时 " + ms + "ms";
        }
        return "HTTP " + code + (httpDetail == null ? "" : httpDetail);
    }

    /** 探针（设置页「测试接口」）的一句话结论：把 DNS / TCP / 请求三段耗时排开。 */
    public static String probeSummary(boolean ok, long dnsMs, long tcpMs, long postMs) {
        StringBuilder sb = new StringBuilder(ok ? "接口连通" : "接口不通");
        sb.append(" · DNS ").append(dnsMs).append("ms");
        if (tcpMs >= 0) sb.append(" / TCP ").append(tcpMs).append("ms");
        sb.append(" / 请求 ").append(postMs).append("ms");
        return sb.toString();
    }
}
