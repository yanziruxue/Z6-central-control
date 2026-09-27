package com.l6.carmedia;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志上报的分批切分（零 Android 依赖，可被 tools/LogicSmoke.java 用普通 JVM 直接回归）。
 *
 * 为什么要分批：服务端对单次上报有硬性限制，实测边界为
 *   ≤2000 行      → 200 OK
 *   >2000 行      → 400 {"code":"TOO_MANY_LINES","error":"单批最多 2000 行"}
 *   约 1.6MB 以上 → 502 {"message":"上游请求失败：write ECONNRESET"}
 * 最后一种在客户端的表现就是 java.net.SocketException: Connection reset，
 * 而 App 更新/重启后 sentOffset 归零，首包会把整个当日日志从头发一遍，正好踩线。
 *
 * 所以：一次攒的行必须切成多批发，且失败时只推进已成功批次的偏移（下一轮从这里续）。
 */
public final class LogBatch {

    /** 单批行数上限。服务端 2000，留一半余量，避免边界抖动。 */
    public static final int MAX_LINES_PER_BATCH = 1000;

    /** 单轮最多发几批。日志大量积压时防止一轮占住上传线程太久，剩下的下一轮继续。 */
    public static final int MAX_BATCHES_PER_ROUND = 10;

    private LogBatch() { }   // 纯工具类

    /** 一批待发送的行，以及这批内容在本次文本里的**结束字节位置**（用于精确推进文件偏移）。 */
    public static final class Chunk {
        public final String[] lines;
        /** 本批最后一个字节之后的位置，相对本次传入 text 的起点（不是相对文件头）。 */
        public final int endOffset;

        Chunk(String[] lines, int endOffset) {
            this.lines = lines;
            this.endOffset = endOffset;
        }
    }

    /**
     * 把一段「以 '\n' 结尾」的文本按行切批。空行会被跳过（不占位），但仍计入偏移。
     *
     * @param text        到最后一个 '\n' 为止的完整内容（半行不该传进来）
     * @param maxPerBatch 单批最多几行
     * @return 若干批；各批 endOffset 递增，最后一批的 endOffset == text 的 UTF-8 字节数
     */
    public static Chunk[] split(String text, int maxPerBatch) {
        if (text == null || text.isEmpty()) return new Chunk[0];
        if (maxPerBatch <= 0) throw new IllegalArgumentException("maxPerBatch 必须为正数");

        String[] arr = text.split("\n", -1);
        List<Chunk> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int cursor = 0;

        for (int i = 0; i < arr.length; i++) {
            String ln = arr[i];
            boolean lastElem = (i == arr.length - 1);
            String s = ln.endsWith("\r") ? ln.substring(0, ln.length() - 1) : ln;

            // 末尾若不是空串，说明这是一行「还没写完」的内容（后面没有 '\n'）。
            // 必须留下等下一轮补成完整行，否则会把半行发出去并且偏移推进过头。
            if (lastElem && !s.isEmpty()) continue;

            // 换行符本身也要算进偏移；split 的最后一个元素不带分隔符
            int step = ln.getBytes(StandardCharsets.UTF_8).length + (lastElem ? 0 : 1);

            if (!s.isEmpty()) cur.add(s);

            cursor += step;
            if (cur.size() >= maxPerBatch) {
                out.add(new Chunk(cur.toArray(new String[0]), cursor));
                cur.clear();
            }
        }
        if (!cur.isEmpty()) out.add(new Chunk(cur.toArray(new String[0]), cursor));
        return out.toArray(new Chunk[0]);
    }

    /** 便捷入口：按默认上限切批。 */
    public static Chunk[] split(String text) {
        return split(text, MAX_LINES_PER_BATCH);
    }
}
