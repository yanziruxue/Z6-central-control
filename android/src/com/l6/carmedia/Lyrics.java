package com.l6.carmedia;

import android.content.Context;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实歌词：按「歌名 + 歌手」去匹配 LRC，并保留时间轴。
 *
 * 为什么在原生侧拉而不是页面里 fetch：
 *   WebView 从 file:///android_asset 起，跨域请求会被 CORS 拦（网易云等接口不带 CORS 头），
 *   走 HttpURLConnection 没有这个限制，也更省事（同一套 OTA 的网络代码风格）。
 *
 * 结果：{kind:"lyrics", title, artist, source, lines:[{t:毫秒, s:"文字"}]}
 *   - t >= 0 有时间轴 → 页面跟随播放进度高亮（真·同步歌词）
 *   - t = -1 纯文本歌词 → 页面按固定节奏轮转
 *   - lines 为空 → 页面显示「暂无歌词」
 *
 * 信息源优先级：
 *   1) assets/app.properties 里的 LYRIC_API（模板，{title}/{artist} 占位；置 off 可关闭）
 *   2) 内置：网易云音乐公开接口（搜索 → 取歌词）
 */
public class Lyrics {

    public interface Cb {
        void onResult(String json);
    }

    private static final LruCache<String, String> CACHE = new LruCache<>(32);
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; Car) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    public static void fetch(final Context ctx, final String title, final String artist, final Cb cb) {
        final String t = title == null ? "" : title.trim();
        final String a = artist == null ? "" : artist.trim();
        if (t.isEmpty()) {
            cb.onResult(build(t, a, "", new ArrayList<>(), "无歌名"));
            return;
        }
        final String key = t + "|" + a;
        String hit = CACHE.get(key);
        if (hit != null) {
            cb.onResult(hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String lrc = null;
            String source = "";
            try {
                String api = cfg(app, "LYRIC_API");
                if (api != null && !api.isEmpty() && !"off".equalsIgnoreCase(api)) {
                    String u = api.replace("{title}", enc(t)).replace("{artist}", enc(a));
                    String r = get(u, null);
                    if (r != null) {
                        lrc = unwrap(r);
                        source = "自定义接口";
                    }
                }
            } catch (Throwable ignored) {
            }
            if (lrc == null || lrc.trim().isEmpty()) {
                try {
                    String[] r = netease(t, a);
                    if (r != null) {
                        lrc = r[0];
                        source = r[1];
                    }
                } catch (Throwable ignored) {
                }
            }
            List<LrcParse.Line> lines = new ArrayList<>();
            if (lrc != null && !lrc.trim().isEmpty()) {
                lines = LrcParse.parse(lrc);
            }
            String out = build(t, a, source, lines,
                    lines.isEmpty() ? "未匹配到歌词" : "");
            CACHE.put(key, out);
            cb.onResult(out);
        }, "L6Lyrics").start();
    }

    /* ==================== 数据源 ==================== */

    /** 网易云公开接口：搜索歌曲拿 id → 取 LRC。 */
    private static String[] netease(String title, String artist) {
        String kw = artist.isEmpty() ? title : (title + " " + artist);
        String body = "s=" + enc(kw) + "&type=1&offset=0&limit=1";
        String search = post("https://music.163.com/api/search/get/web?csrf_token=", body,
                "https://music.163.com/");
        if (search == null) {
            search = post("https://music.163.com/api/search/get", body, "https://music.163.com/");
        }
        long id = 0;
        try {
            JSONObject o = new JSONObject(search);
            JSONObject r = o.optJSONObject("result");
            JSONArray songs = r == null ? null : r.optJSONArray("songs");
            if (songs != null && songs.length() > 0) {
                id = songs.getJSONObject(0).optLong("id", 0);
            }
        } catch (Throwable ignored) {
        }
        if (id == 0) {
            return null;
        }
        String ly = get("https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1",
                "https://music.163.com/");
        if (ly == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(ly);
            JSONObject lrc = o.optJSONObject("lrc");
            String s = lrc == null ? "" : lrc.optString("lyric", "");
            if (!s.trim().isEmpty()) {
                return new String[]{s, "网易云音乐"};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 自定义接口可能返回纯 LRC，也可能返回 JSON —— 都尽量挖出歌词文本。 */
    private static String unwrap(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.charAt(0) != '{' && s.charAt(0) != '[') {
            return s;   // 纯 LRC / 纯文本
        }
        try {
            Object o = s.charAt(0) == '[' ? new JSONArray(s) : new JSONObject(s);
            String hit = dig(o, 0);
            if (hit != null && !hit.trim().isEmpty()) {
                return hit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在 JSON 里找歌词字段：lrc.lyric / lyrics / data.lyrics / data.lrc.lyric / data.song.lyrics。 */
    private static String dig(Object o, int depth) {
        if (o == null || depth > 4) {
            return null;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < Math.min(a.length(), 8); i++) {
                String r = dig(a.opt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
        if (!(o instanceof JSONObject)) {
            return null;
        }
        JSONObject j = (JSONObject) o;
        for (String k : new String[]{"lyric", "lyrics", "lrc", "text", "content"}) {
            Object v = j.opt(k);
            if (v instanceof String && ((String) v).trim().length() > 10) {
                return (String) v;
            }
        }
        for (String k : new String[]{"lrc", "data", "result", "song", "songs"}) {
            String r = dig(j.opt(k), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /* ==================== LRC 解析（实现在 LrcParse，纯逻辑可单测） ==================== */

    private static String build(String title, String artist, String source,
                                List<LrcParse.Line> lines, String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "lyrics");
            o.put("title", title);
            o.put("artist", artist);
            o.put("source", source);
            JSONArray arr = new JSONArray();
            for (LrcParse.Line l : lines) {
                JSONObject j = new JSONObject();
                j.put("t", l.t);
                j.put("s", l.s);
                arr.put(j);
            }
            o.put("lines", arr);
            if (err != null && !err.isEmpty()) {
                o.put("error", err);
            }
            return o.toString();
        } catch (Throwable e) {
            return "{\"kind\":\"lyrics\",\"title\":\"" + enc(title) + "\",\"lines\":[],\"error\":\"构建失败\"}";
        }
    }

    /* ==================== HTTP ==================== */

    private static String get(String url, String referer) {
        return call(url, null, referer);
    }

    private static String post(String url, String body, String referer) {
        return call(url, body, referer);
    }

    private static String call(String url, String body, String referer) {
        HttpURLConnection c = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (referer != null) {
                c.setRequestProperty("Referer", referer);
            }
            if (body == null) {
                c.setRequestMethod("GET");
            } else {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
            }
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            is = c.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Throwable ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /* ==================== 配置 ==================== */

    private static String cfg(Context ctx, String key) {
        try (InputStream is = ctx.getAssets().open("app.properties");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (key.equals(line.substring(0, eq).trim())) {
                    return line.substring(eq + 1).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s.trim(), "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 保留：需要 base64 内嵌时使用（与 SysHub.pack 一致的安全策略）。 */
    @SuppressWarnings("unused")
    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
