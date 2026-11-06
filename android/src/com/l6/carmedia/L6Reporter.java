package com.l6.carmedia;

/**
 * 日志导出（v2.0.35）：只负责把运行日志写成可上传的本地 .txt 文件。
 *
 * <p><b>★ v2.0.35 职责收缩</b>：按需求「只保留 NAS 收集按钮」，App 主动网络上报能力整体下线。
 *   原先的 {@code ENDPOINT / ENDPOINT_LAN / TOKEN}、TLS 协商、{@code sendEvent* /
 *   sendLogText*}、崩溃自动上报 {@code sendCrash}、启动兜底补发 {@code flushPendingCrash}
 *   全部移除；本类不再持有任何网络地址、鉴权凭据或线程池。
 *
 * <p>崩溃证据仍由 {@link CrashGuard} 落盘到 {@code app-log/l6-crash-*.txt}（纯本地取证，
 *   不联网）；需要外发时由用户在设置页点「📤 上传到 NAS」生成本文件并手动选择上传。
 *
 * <p><b>为什么类名仍叫 L6Reporter</b>：调用方 {@code SetPane} 与产物反查锚点都按这个类名写，
 *   改名会牵动多处且无功能收益；实际职责已是「本地日志导出器」。
 *
 * <p><b>设计约束</b>：
 *  - 零网络调用、零第三方依赖、零静态凭据；
 *  - 全量 try-catch，任何异常都不抛，只把真实失败原因写进 {@link #lastNasError()}；
 *  - 绝不谎报「上传成功」——写盘成功只代表生成了待上传文件。
 */
public final class L6Reporter {

    /** 飞牛「文件收集」任务页面地址（网页 SPA，不是 API；仅供 ACTION_VIEW 打开）。 */
    public static final String NAS_COLLECT_URL = "https://share.fnnas.net/s/988aed0f9fb54bc680";

    /** v2.0.30：最后一次 NAS 文件写入的失败明细（逐候选目录的真实异常），供 UI 如实回显。 */
    private static volatile String sNasError = "";

    private L6Reporter() {
    }

    /** 返回上一次写盘失败的明细；无失败时为空串。 */
    public static String lastNasError() {
        String s = sNasError;
        return s == null ? "" : s;
    }

    /**
     * v2.0.30：把运行日志写成一个可直接上传的 .txt（v2.0.35 保留，成为唯一导出入口）。
     *
     * <p>★ 修复 v2.0.29「无法写入日志文件」：车机上公共 Download 常因危险权限未授予 / 只读而
     * mkdirs 失败，旧实现只认这一个目录且不做真实试写。现在按「公共 Download/L6/upload →
     * 应用外部私有 → 内部私有 → cache」逐级真实写入并校验字节数，任一成功即返回；
     * 全失败则记录每个候选的具体原因，由 {@link #lastNasError()} 回显，绝不谎报成功。
     */
    public static java.io.File writeNasUploadFile(String text) {
        sNasError = "";
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            StringBuilder sb = new StringBuilder();
            sb.append("老六中控 运行日志\n");
            sb.append("version: ").append(L6Log.appVerPublic()).append('\n');
            sb.append("time: ").append(stamp).append('\n');
            sb.append("os: Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("model: ").append(android.os.Build.MODEL).append('\n');
            sb.append("voltage: ").append(L6Log.batteryVoltageV()).append(" V\n");
            sb.append("nas: ").append(NAS_COLLECT_URL).append('\n');
            sb.append("----------------------------------------\n");
            sb.append(text == null ? "(无日志)" : text);
            byte[] data = sb.toString().getBytes("UTF-8");

            StringBuilder err = new StringBuilder();
            java.io.File[] dirs = nasUploadDirs();
            for (java.io.File dir : dirs) {
                if (dir == null) continue;
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        err.append(dir.getAbsolutePath()).append(": mkdirs=false; ");
                        continue;
                    }
                    java.io.File f = new java.io.File(dir, "l6-log-" + stamp + ".txt");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
                    try {
                        out.write(data);
                        out.flush();
                    } finally {
                        try { out.close(); } catch (Throwable ignored) { }
                    }
                    // 硬证据：落盘字节数必须与待写字节数一致，杜绝「文件建了但没内容」的假成功
                    if (f.length() != data.length) {
                        err.append(f.getAbsolutePath()).append(": size=").append(f.length())
                                .append(" expect=").append(data.length).append("; ");
                        try { f.delete(); } catch (Throwable ignored) { }
                        continue;
                    }
                    return f;
                } catch (Throwable t) {
                    err.append(dir.getAbsolutePath()).append(": ")
                            .append(t.getClass().getSimpleName()).append(' ')
                            .append(String.valueOf(t.getMessage())).append("; ");
                }
            }
            sNasError = err.length() == 0 ? "无可用存储目录" : err.toString();
        } catch (Throwable t) {
            sNasError = "unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        L6Log.e("L6Report", "NAS 日志文件生成失败：" + sNasError);
        return null;
    }

    /**
     * NAS 待上传目录候选（按可见性 / 权限成本排序）：
     * ① 公共 Download/L6/upload（文件管理器可见，需 WRITE_EXTERNAL_STORAGE）
     * ② 应用外部私有 externalFiles/L6/upload（无需权限，文件管理器可见）
     * ③ 内部私有 files/upload（无需权限、100% 可写，但仅 adb / 应用自身可见）
     * ④ cache/upload（极端兜底）
     */
    private static java.io.File[] nasUploadDirs() {
        java.util.List<java.io.File> out = new java.util.ArrayList<java.io.File>();
        try {
            java.io.File daily = L6Log.logFile();
            if (daily != null && daily.getParentFile() != null
                    && daily.getParentFile().getParentFile() != null) {
                out.add(new java.io.File(daily.getParentFile().getParentFile(), "upload"));
            }
        } catch (Throwable ignored) { }
        android.content.Context c = L6Log.appContext();
        if (c == null) return out.toArray(new java.io.File[0]);
        try {
            java.io.File ext = c.getExternalFilesDir(null);
            if (ext != null) out.add(new java.io.File(ext, "L6/upload"));
        } catch (Throwable ignored) { }
        try {
            java.io.File files = c.getFilesDir();
            if (files != null) out.add(new java.io.File(files, "upload"));
        } catch (Throwable ignored) { }
        try {
            java.io.File cache = c.getCacheDir();
            if (cache != null) out.add(new java.io.File(cache, "upload"));
        } catch (Throwable ignored) { }
        return out.toArray(new java.io.File[0]);
    }
}
