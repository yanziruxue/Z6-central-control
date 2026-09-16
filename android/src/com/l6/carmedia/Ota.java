package com.l6.carmedia;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OTA 升级：GitHub Releases 主源 + 国内镜像回落 + SHA-256 校验 + 拉起系统安装器。
 *
 * 信息源（见 ota.properties）—— **默认且唯一实际启用的是 GitHub Releases**：
 *   1) OTA_JSON_URL  —— 可选：自建 version.json（留空即纯 GitHub；不再用于 NAS 中继）
 *   2) REPO_OWNER + REPO_NAME —— GitHub releases
 *      （api.github.com/repos/{owner}/{repo}/releases/latest，tag 形如 v1.2.4）
 *
 * 「失败」的判据不止网络不通：返回 ok:false、结构里没有 versionCode/downloadUrl 都算失败
 * （见 isUsable），必须继续试下一级 —— 否则会把坏数据当结果、误报「已是最新」而不去回落。
 * 每一级的尝试与失败原因都记进 tried，随结果一起回给页面便于排障。
 *
 * 镜像回落：每条 URL 按顺序尝试 [直连, MIRROR+直连]。MIRROR 默认 https://gh-proxy.com/，
 * 可通过 ota.properties 或系统环境变量 UPDATE_MIRROR 覆盖。
 *
 * 进度/状态回调：通过 EventCb.emit(jsonStr)，由 MainActivity 转发给页面 window.L6OtaEvent。
 */
public class Ota {

    public interface EventCb {
        void emit(String json);
    }

    /** 配置：从 assets/ota.properties + 环境变量 UPDATE_MIRROR 解析。 */
    public static class Config {
        public String repoOwner = "";
        public String repoName = "";
        public String otaJsonUrl = "";   // 可选自建 JSON；留空即纯 GitHub Releases
        public String mirror = "https://gh-proxy.com/";

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("repoOwner", repoOwner);
            o.put("repoName", repoName);
            o.put("otaJsonUrl", otaJsonUrl);
            o.put("mirror", mirror);
            return o;
        }
    }

    public static Config loadConfig(Activity act) {
        Config c = new Config();
        try (InputStream is = act.getAssets().open("ota.properties");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("REPO_OWNER".equals(k)) c.repoOwner = v;
                else if ("REPO_NAME".equals(k)) c.repoName = v;
                else if ("OTA_JSON_URL".equals(k)) c.otaJsonUrl = v;
                else if ("MIRROR".equals(k) && !v.isEmpty()) c.mirror = v;
            }
        } catch (Throwable ignored) {
        }
        try {
            String env = System.getenv("UPDATE_MIRROR");
            if (env != null && !env.isEmpty()) c.mirror = env;
        } catch (Throwable ignored) {
        }
        return c;
    }

    /** 检查更新。回调 JSON：{kind:"check", error?, hasUpdate, currentVersion, currentCode, versionName, versionCode, changelog, apkUrl, sha256, force, size} */
    public static void checkUpdate(Activity act, EventCb cb) {
        new Thread(() -> {
            try {
                Config cfg = loadConfig(act);
                JSONObject result = new JSONObject();
                result.put("kind", "check");

                String curVer = "0";
                int curCode = 0;
                try {
                    curVer = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
                    curCode = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
                } catch (Throwable ignored) {
                }
                result.put("currentVersion", curVer);
                result.put("currentCode", curCode);

                // ---- 源选择：GitHub Releases 为主源（可选 OTA_JSON_URL 自建 JSON 前置覆盖）----
                // 判据用 isUsable()：坏数据（ok:false / 缺 versionCode / 缺 downloadUrl）不算成功，
                // 必须继续试下一级 —— 否则会把坏数据当结果、误报「已是最新」而不去回落主源。
                JSONObject remote = null;
                String source = "";
                List<String> tried = new ArrayList<>();

                // ① 可选：自建 version.json（留空即跳过，不影响 GitHub 主源）
                if (!cfg.otaJsonUrl.isEmpty()) {
                    JSONObject r = safeFetch(cfg.otaJsonUrl, cfg.mirror);
                    if (isUsable(r)) {
                        remote = r;
                        source = "json";
                    } else {
                        tried.add("自建 JSON " + hostOf(cfg.otaJsonUrl) + "：" + whyBad(r));
                    }
                }

                // ② GitHub releases 主源：取到 JSON 即接受（tag 解析失败时给出「已是最新」而非报错）
                if (remote == null && !cfg.repoOwner.isEmpty() && !cfg.repoName.isEmpty()) {
                    String ghApi = "https://api.github.com/repos/" + cfg.repoOwner + "/" + cfg.repoName + "/releases/latest";
                    JSONObject r = safeFetch(ghApi, cfg.mirror);
                    if (r != null) {
                        remote = r;
                        source = "github";
                    } else {
                        tried.add("GitHub 无响应或未发版（" + cfg.repoOwner + "/" + cfg.repoName + "）");
                    }
                }

                if (remote == null) {
                    String why = joinSemi(tried);
                    // 只有一个信息源时给最直白的提示（页面拼成「检查失败：GitHub 无响应或未发版」）；
                    // 多源时才逐条列出失败原因，便于排障。
                    result.put("error", tried.size() <= 1
                            ? why
                            : "所有 OTA 信息源均不可用（已尝试：" + why + "）");
                    result.put("tried", why);
                    L6Log.e("L6Ota", "检查更新失败：" + why);
                    cb.emit(result.toString());
                    return;
                }
                result.put("source", source);
                result.put("sourceLabel", sourceLabel(source));
                // 有源被跳过（回落发生过）时把原因一并带回，页面可提示「已回落」
                if (!tried.isEmpty()) result.put("tried", joinSemi(tried));

                int newCode = remote.optInt("versionCode", 0);
                String newVer = remote.optString("versionName", "0");
                // newCode 必须 > 0：tag 解析失败（0）时不能误判成“有更新”
                boolean hasUpdate = newCode > 0 && newCode > curCode;
                if (hasUpdate) L6Log.i("L6Ota", "发现新版本 v" + newVer + "（当前 v" + curVer + "）");
                else L6Log.i("L6Ota", "已是最新（v" + curVer + "）");
                result.put("hasUpdate", hasUpdate);
                result.put("versionName", newVer);
                result.put("versionCode", newCode);
                result.put("changelog", remote.optString("changelog", ""));
                result.put("apkUrl", remote.optString("downloadUrl", ""));
                result.put("sha256", remote.optString("sha256", ""));
                result.put("force", remote.optBoolean("force", false));
                result.put("size", remote.optLong("size", 0));
                cb.emit(result.toString());
            } catch (Throwable e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("kind", "check");
                    err.put("error", e.getMessage() == null ? "未知错误" : e.getMessage());
                    cb.emit(err.toString());
                } catch (Throwable ignored) {
                }
            }
        }, "L6OtaCheck").start();
    }

    /**
     * 下载并安装：先下载到 filesDir/update/，SHA-256 校验后拉起系统安装器。
     *
     * 下载源按顺序尝试 [直连, MIRROR + 直连] —— GitHub release asset 落在
     * objects.githubusercontent.com，国内常被屏蔽，所以镜像回落必须覆盖下载而不只是检查接口。
     *
     * Android 8.0+ 需要先有「允许安装未知应用」授权；未授权时保存待装文件、跳系统设置页，
     * 用户授权回来后由 MainActivity.onResume 调 resumeInstallIfPending() 续装。
     */
    public static void downloadAndInstall(Activity act, String url, String sha256, EventCb cb) {
        new Thread(() -> {
            Config cfg = loadConfig(act);
            File dir = new File(act.getFilesDir(), "update");
            if (!dir.exists() && !dir.mkdirs()) {
                emitError(cb, "无法创建下载目录"); return;
            }
            String fname = "ota_" + System.currentTimeMillis() + ".apk";
            File out = new File(dir, fname);

            // 候选下载源：直连优先，再试镜像（gh-proxy）—— GitHub asset 落在
            // objects.githubusercontent.com，国内常被屏蔽，镜像回落必须覆盖下载而不只是检查接口
            List<String> urls = new ArrayList<>();
            if (url != null && !url.isEmpty()) urls.add(url);
            if (cfg.mirror != null && !cfg.mirror.isEmpty()) {
                String m = applyMirror(url, cfg.mirror);
                if (!urls.contains(m)) urls.add(m);
            }
            if (urls.isEmpty()) {
                emitError(cb, "下载地址为空"); return;
            }

            boolean ok = false;
            String lastErr = "";
            for (int i = 0; i < urls.size(); i++) {
                final String src = (i == 0) ? "主源" : "镜像";
                emit(cb, "kind", "download", "progress", 0, "source", src);
                ok = httpDownload(urls.get(i), out, pct ->
                        emit(cb, "kind", "download", "progress", pct, "source", src));
                if (ok) break;
                try { out.delete(); } catch (Throwable ignored) {}
                lastErr = (i == 0) ? "主源下载失败，正在尝试国内镜像…" : "下载失败：主源与镜像均不可用";
                if (i < urls.size() - 1) emit(cb, "kind", "retry", "message", lastErr, "source", "镜像");
            }
            if (!ok) {
                emitError(cb, lastErr.isEmpty() ? "下载失败（HTTP 或网络错误）" : lastErr);
                L6Log.e("L6Ota", "下载安装失败：" + (lastErr.isEmpty() ? "HTTP 或网络错误" : lastErr));
                return;
            }

            if (sha256 != null && !sha256.isEmpty()) {
                String actual = sha256Of(out);
                if (actual.isEmpty() || !sha256.equalsIgnoreCase(actual)) {
                    out.delete();
                    emitError(cb, "SHA-256 校验失败，已终止安装"); return;
                }
            }
            emit(cb, "kind", "downloaded", "size", out.length());
            L6Log.i("L6Ota", "下载完成，准备安装（" + out.length() + " 字节）");

            act.runOnUiThread(() -> installOrAskPermission(act, out, cb));
        }, "L6OtaDownload").start();
    }

    /** 有权限就直接装；没有就存起来 + 跳设置页，等 onResume 续装。 */
    private static void installOrAskPermission(Activity act, File apk, EventCb cb) {
        if (canInstall(act)) {
            doInstall(act, apk, cb);
            return;
        }
        pendingFile = apk;
        pendingCb = cb;
        emit(cb, "kind", "needPermission",
                "message", "需要授权「允许安装未知应用」才能完成升级");
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable e) {
            emitError(cb, "无法打开安装授权设置页：" + (e.getMessage() == null ? "未知" : e.getMessage()));
        }
    }

    private static boolean canInstall(Activity act) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return act.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable e) {
            return false;
        }
    }

    private static void doInstall(Activity act, File apk, EventCb cb) {
        try {
            Uri u = Uri.parse("content://" + OtaFileProvider.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            emit(cb, "kind", "installing");
        } catch (Throwable e) {
            emitError(cb, "启动安装器失败：" + (e.getMessage() == null ? "未知" : e.getMessage()));
        }
    }

    /** 用户从「未知应用授权」页返回后，MainActivity.onResume 调这个续装。 */
    public static void resumeInstallIfPending(Activity act, EventCb cb) {
        File f = pendingFile;
        if (f == null || !f.exists()) {
            pendingFile = null;
            pendingCb = null;
            return;
        }
        if (!canInstall(act)) return;          // 还是没授权，继续等
        EventCb c = (cb != null) ? cb : pendingCb;
        pendingFile = null;
        pendingCb = null;
        if (c != null) doInstall(act, f, c);
    }

    public static boolean hasPendingInstall() {
        return pendingFile != null && pendingFile.exists();
    }

    private static File pendingFile = null;
    private static EventCb pendingCb = null;

    /* ---------------- HTTP ---------------- */

    // ------------------------------------------------------------------
    // 信息源可用性判定（多源链式回落的判据基础）
    // ------------------------------------------------------------------

    /**
     * 该源的结果是否「严格可用」：错误结构 {ok:false}、缺版本号、缺下载直链都算失败，
     * 调用方据此继续回落下一级信息源。
     */
    private static boolean isUsable(JSONObject r) {
        if (r == null) return false;
        if (!r.optBoolean("ok", true)) return false;            // 中继错误结构 {ok:false,error:...}
        if (r.optInt("versionCode", 0) <= 0) return false;      // 版本号解析失败 → 判定不了更新
        return !r.optString("downloadUrl", "").isEmpty();       // 没有直链就下不了
    }

    /** 该源为什么不能用（排障文案，随结果回给页面）。 */
    private static String whyBad(JSONObject r) {
        if (r == null) return "无响应或返回内容不是 JSON";
        if (!r.optBoolean("ok", true)) {
            String err = r.optString("error", "");
            return err.isEmpty() ? "返回失败（ok:false）" : err;
        }
        if (r.optInt("versionCode", 0) <= 0) return "未能解析出版本号";
        if (r.optString("downloadUrl", "").isEmpty()) return "缺少下载直链";
        return "数据不完整";
    }

    /** fetchAny 的兜底版：任何异常都不外抛，保证单个源挂掉不影响后续回落。 */
    private static JSONObject safeFetch(String url, String mirror) {
        try {
            return fetchAny(url, mirror);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 信息源友好名（页面直接显示用）。 */
    private static String sourceLabel(String source) {
        if ("json".equals(source)) return "自建 JSON";
        if ("github".equals(source)) return "GitHub";
        return source == null ? "" : source;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable e) {
            return url;
        }
    }

    private static String joinSemi(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("；");
            sb.append(p);
        }
        return sb.toString();
    }

    private static JSONObject fetchAny(String urlStr, String mirror) {
        // 1) 直连 2) 镜像 回退
        String[] urls = (mirror != null && !mirror.isEmpty())
                ? new String[]{urlStr, applyMirror(urlStr, mirror)}
                : new String[]{urlStr};
        for (String u : urls) {
            JSONObject r = httpGetJson(u);
            if (r != null) return r;
        }
        return null;
    }

    /** gh-proxy 类镜像规则：MIRROR + "/" + 直连 URL。 */
    private static String applyMirror(String url, String mirror) {
        if (url.startsWith(mirror)) return url;
        return (mirror.endsWith("/") ? mirror : mirror + "/") + url;
    }

    private static JSONObject httpGetJson(String urlStr) {
        HttpURLConnection c = null;
        InputStream is = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json, application/json;q=0.9, */*;q=0.5");
            c.setRequestProperty("User-Agent", "L6CarMedia-OTA/1.0");
            int code = c.getResponseCode();
            if (code != 200) return null;
            is = c.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            String text = baos.toString("UTF-8");
            return parseAny(text);
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject parseAny(String text) {
        try {
            JSONObject o = new JSONObject(text);
            if (o.has("tag_name") || o.has("assets")) {
                return normalizeGithubRelease(o);
            }
            return o;   // 自建 JSON 直接透传
        } catch (Throwable e) {
            return null;
        }
    }

    private static JSONObject normalizeGithubRelease(JSONObject gh) throws Exception {
        JSONObject out = new JSONObject();
        String tag = gh.optString("tag_name", "v0");
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        String[] p = tag.split("\\.");
        int code = 0;
        try {
            if (p.length >= 3) code = Integer.parseInt(p[0]) * 10000 + Integer.parseInt(p[1]) * 100 + Integer.parseInt(p[2]);
            else if (p.length == 2) code = Integer.parseInt(p[0]) * 10000 + Integer.parseInt(p[1]) * 100;
        } catch (Throwable ignored) {}
        out.put("versionName", tag);
        out.put("versionCode", code);
        out.put("changelog", gh.optString("body", ""));
        JSONArray assets = gh.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.endsWith(".apk")) {
                    out.put("downloadUrl", a.optString("browser_download_url", ""));
                    out.put("size", a.optLong("size", 0));
                    break;
                }
            }
        }
        // force / sha256 从 release 正文里读（GitHub release 本身没有这些字段）：
        //   正文含 [force] 或 [force:true]  → 强制更新
        //   正文含 sha256: <64 位十六进制>   → 下载后做完整性校验
        String body = gh.optString("body", "");
        boolean force = body.contains("[force:true]") || body.contains("[force]");
        String sha = "";
        Matcher sm = SHA_IN_BODY.matcher(body);
        if (sm.find()) sha = sm.group(1);
        out.put("force", force);
        out.put("sha256", sha);
        return out;
    }

    /** release 正文里的 sha256: xxxx（允许 `sha256:` / `sha256 =` / `SHA-256:`）。 */
    private static final Pattern SHA_IN_BODY =
            Pattern.compile("(?i)sha-?256\\s*[:=]\\s*([0-9a-fA-F]{64})");

    private static boolean httpDownload(String urlStr, File out, ProgressCb pct) {
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "L6CarMedia-OTA/1.0");
            int code = c.getResponseCode();
            if (code >= 400) return false;
            int total = c.getContentLength();
            in = c.getInputStream();
            os = new FileOutputStream(out);
            byte[] buf = new byte[16 * 1024];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int p = (int) (done * 100L / total);
                    if (p != lastPct) {
                        lastPct = p;
                        pct.onProgress(p);
                    }
                }
            }
            os.flush();
            pct.onProgress(100);
            // 有些镜像出错时仍返回 200 + HTML 错误页，靠 ZIP 魔数挡掉（APK 一定是 PK 开头）
            return looksLikeZip(out);
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
        }
    }

    private static boolean looksLikeZip(File f) {
        try {
            if (f.length() < 4096) return false;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] head = new byte[4];
                if (fis.read(head) != 4) return false;
                return head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5 || head[2] == 7);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    private static String sha256Of(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static void emit(EventCb cb, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
            cb.emit(o.toString());
        } catch (Throwable ignored) {}
    }

    private static void emitError(EventCb cb, String msg) {
        emit(cb, "kind", "error", "message", msg);
    }

    /** 把 JSON 安全地作为 JS 字面量塞进 evaluateJavascript（atob 避免引号/换行炸）。 */
    public static String safeJs(String json) {
        String b64 = Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "(window.L6OtaEvent?window.L6OtaEvent(JSON.parse((window.L6B64||atob)('" + b64 + "'))):null)";
    }

    private interface ProgressCb {
        void onProgress(int pct);
    }
}