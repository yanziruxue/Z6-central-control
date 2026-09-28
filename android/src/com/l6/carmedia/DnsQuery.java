package com.l6.carmedia;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 DNS 客户端（纯逻辑，零 Android 依赖 → 可被 tools/LogicSmoke.java 离线单测）。
 *
 * 存在的理由：手机/车机的**本地 DNS 可能把上报域名解析到错误 IP**。实测过一次：
 * 域名被解到 60.205.231.18，而那台机器上的证书只签给 www.shuan.tech —— 与本域毫无关系，
 * 于是带证书/主机名校验的 HTTPS 永远失败（客户端看到的就是一句 Connection reset）。
 * 设置页因此允许手填一个可信 DNS 服务器（如 223.5.5.5），解析绕开本地解析器。
 * 本类只负责按 RFC 1035 组装 A 查询、解析应答 —— 组包/解包都是纯函数，便于回归。
 *
 * 只做最小必要实现：单问题、QTYPE=A、QCLASS=IN、走 UDP 53；不处理 TC 截断
 * （A 应答极小，实际不会超 512 字节）。
 */
public final class DnsQuery {

    public static final int TYPE_A = 1;
    public static final int CLASS_IN = 1;
    /** 报文头固定 12 字节。 */
    private static final int HDR = 12;

    private DnsQuery() {}

    /** 组一个标准 A 查询包（RD=1，单问题）。id 由调用方生成，用来校验应答是否配对本请求。 */
    public static byte[] buildQuery(String name, int id) {
        byte[] qname = encodeName(name);
        byte[] buf = new byte[HDR + qname.length + 4];
        buf[0] = (byte) ((id >> 8) & 0xFF);
        buf[1] = (byte) (id & 0xFF);
        buf[2] = 0x01;   // QR=0 Opcode=0 AA=0 TC=0 RD=1
        buf[3] = 0x00;   // RA=0 Z=0 RCODE=0
        buf[4] = 0x00; buf[5] = 0x01;               // QDCOUNT = 1
        // ANCOUNT / NSCOUNT / ARCOUNT 保持 0
        System.arraycopy(qname, 0, buf, HDR, qname.length);
        int p = HDR + qname.length;
        buf[p] = 0x00; buf[p + 1] = (byte) TYPE_A;      // QTYPE  = A
        buf[p + 2] = 0x00; buf[p + 3] = (byte) CLASS_IN; // QCLASS = IN
        return buf;
    }

    /** 解析应答里的**第一条** A 记录；取不到（ID 不符 / rcode 报错 / 没有 A）返回 null。 */
    public static String parseFirstA(byte[] resp, int id) {
        List<String> all = parseAllA(resp, id);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 解析应答里的全部 A 记录（去重、保序）。CNAME 之类的记录会被自动跳过 ——
     * 只要同一应答里带了目标 A 记录就能取到（递归解析器通常都会带）。
     */
    public static List<String> parseAllA(byte[] resp, int id) {
        List<String> out = new ArrayList<>();
        if (resp == null || resp.length < HDR) return out;
        int rid = ((resp[0] & 0xFF) << 8) | (resp[1] & 0xFF);
        if (rid != id) return out;                       // 串包/伪包：ID 不对直接丢
        int flags = ((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF);
        if ((flags & 0x000F) != 0) return out;           // RCODE != 0（NXDOMAIN/SERVFAIL…）
        int qd = ((resp[4] & 0xFF) << 8) | (resp[5] & 0xFF);
        int an = ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF);
        int off = HDR;
        for (int i = 0; i < qd && off < resp.length; i++) {   // 跳过问题段
            off = skipName(resp, off) + 4;                    // + QTYPE + QCLASS
        }
        for (int i = 0; i < an && off + 10 <= resp.length; i++) {   // 逐条资源记录
            off = skipName(resp, off);                        // NAME（可能是压缩指针）
            if (off + 10 > resp.length) break;
            int type = ((resp[off] & 0xFF) << 8) | (resp[off + 1] & 0xFF);
            int cls = ((resp[off + 2] & 0xFF) << 8) | (resp[off + 3] & 0xFF);
            int rdlen = ((resp[off + 8] & 0xFF) << 8) | (resp[off + 9] & 0xFF);
            int rdata = off + 10;
            if (type == TYPE_A && cls == CLASS_IN && rdlen == 4 && rdata + 4 <= resp.length) {
                String ip = (resp[rdata] & 0xFF) + "." + (resp[rdata + 1] & 0xFF) + "."
                        + (resp[rdata + 2] & 0xFF) + "." + (resp[rdata + 3] & 0xFF);
                if (!out.contains(ip)) out.add(ip);           // 去重
            }
            off = rdata + rdlen;                              // 用 RDLENGTH 跳过 RDATA（CNAME 也不用解析）
        }
        return out;
    }

    /** 组装 QNAME：`api.test` → `3api4test0`（每段前置长度字节，末尾 0）。 */
    static byte[] encodeName(String name) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (name != null) {
            for (String label : name.split("\\.")) {
                if (label.isEmpty()) continue;                // 容忍末尾点/连续点
                byte[] b = label.getBytes(StandardCharsets.UTF_8);
                o.write(b.length & 0xFF);
                o.write(b, 0, b.length);
            }
        }
        o.write(0);
        return o.toByteArray();
    }

    /** 跳过一个（可能带 0xC0 压缩指针的）域名，返回其后的偏移。 */
    static int skipName(byte[] d, int off) {
        while (off < d.length) {
            int len = d[off] & 0xFF;
            if (len == 0) return off + 1;                     // 根标签，结束
            if ((len & 0xC0) == 0xC0) return off + 2;         // 压缩指针即域名末尾
            off += 1 + len;
        }
        return off;
    }

    /** 是否合法 IPv4（四段、纯数字、每段 0-255）。设置页输入校验用。 */
    public static boolean isIpv4(String s) {
        if (s == null) return false;
        String[] p = s.trim().split("\\.", -1);
        if (p.length != 4) return false;
        for (String x : p) {
            if (x.isEmpty() || x.length() > 3) return false;
            for (int i = 0; i < x.length(); i++) {
                if (!Character.isDigit(x.charAt(i))) return false;
            }
            if (Integer.parseInt(x) > 255) return false;
        }
        return true;
    }

    /** 是否合法 IP（IPv4，或含 ':' 且字符合法的 IPv6 —— IPv6 做宽松校验即可）。 */
    public static boolean isIp(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (isIpv4(t)) return true;
        if (t.indexOf(':') < 0) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!ok) return false;
        }
        return true;
    }
}
