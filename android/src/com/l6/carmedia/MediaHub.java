package com.l6.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 真实媒体接入：读系统正在播放的音乐，并直接控制它。
 *
 * 数据来源：MediaSessionManager.getActiveSessions(本应用的 NotificationListenerService 组件)。
 *   拿到 MediaController 后可读 title / artist / album / 时长 / 播放进度 / 播放状态 / 专辑封面，
 *   也能通过 TransportControls 执行上一曲 / 暂停 / 下一曲 / 拖动进度 —— 即“真实数据 + 真实控制”。
 *
 * 权限：读取活跃会话需要「通知使用权」（用户手动授权，见 {@link #hasAccess}）。
 *       未授权时 getActiveSessions 会抛 SecurityException，这里统一兜底为「无真实数据」，
 *       页面会自动回落演示数据，不会崩。
 */
public class MediaHub {

    private static volatile MediaHub I;

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());

    private MediaSessionManager msm;
    private MediaController ctrl;
    private MediaController.Callback cb;

    private boolean playing = false;
    private String artSig = "";
    private String lastJson = "";
    private Bitmap lastArtBmp;
    private String lastArtUrl;

    public static MediaHub get(Context c) {
        if (I == null) {
            synchronized (MediaHub.class) {
                if (I == null) {
                    I = new MediaHub(c.getApplicationContext());
                }
            }
        }
        return I;
    }

    private MediaHub(Context c) {
        ctx = c;
    }

    /* ==================== 权限 ==================== */

    /** 本应用是否已取得通知使用权（用固定字符串常量，兼容所有 API）。 */
    public static boolean hasAccess(Context c) {
        try {
            String flat = Settings.Secure.getString(
                    c.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /* ==================== 会话发现 ==================== */

    /** 重新枚举系统活跃媒体会话（切歌、换 App、授权后都要调一次）。 */
    public void refresh() {
        h.post(() -> {
            try {
                if (!hasAccess(ctx)) {
                    detach();
                    pushInactive();
                    return;
                }
                if (msm == null) {
                    msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                }
                if (msm == null) {
                    pushInactive();
                    return;
                }
                ComponentName cn = new ComponentName(ctx, L6NotifyService.class);
                List<MediaController> list = msm.getActiveSessions(cn);
                MediaController best = pick(list);
                if (best == null) {
                    detach();
                    pushInactive();
                    return;
                }
                if (best != ctrl) {
                    attach(best);
                }
                push(true);
            } catch (Throwable e) {
                // 未授权 / 系统限制 → 视为无真实数据
                detach();
                pushInactive();
            }
        });
    }

    /** 优先取「正在播放」的会话，否则取优先级最高的（列表本身按最近活跃排序）。 */
    private MediaController pick(List<MediaController> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        MediaController first = null;
        for (MediaController c : list) {
            if (c == null) {
                continue;
            }
            if (first == null) {
                first = c;
            }
            try {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        return first;
    }

    private void attach(MediaController c) {
        detach();
        ctrl = c;
        artSig = "";
        lastJson = "";
        try {
            cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState s) {
                    push(false);
                }

                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    artSig = "";
                    push(true);
                }

                @Override
                public void onSessionDestroyed() {
                    detach();
                    pushInactive();
                }
            };
            c.registerCallback(cb, h);
        } catch (Throwable ignored) {
        }
    }

    private void detach() {
        try {
            if (ctrl != null && cb != null) {
                ctrl.unregisterCallback(cb);
            }
        } catch (Throwable ignored) {
        }
        ctrl = null;
        cb = null;
        artSig = "";
        lastJson = "";
    }

    /* ==================== 状态推送 ==================== */

    /** 每秒调用一次：只推当前会话（不重新枚举，成本低），让进度条动起来。 */
    public void tick() {
        if (ctrl != null) {
            push(false);
        }
    }

    /** 页面初始化时用：一次性拿全量状态（含封面）。 */
    public JSONObject snapshot() {
        return build(true);
    }

    private void push(boolean withArt) {
        JSONObject o = build(withArt);
        if (o == null) {
            pushInactive();
            return;
        }
        String json = o.toString();
        if (!json.equals(lastJson)) {
            lastJson = json;
            SysHub.push(o);
        }
    }

    /** 组装媒体状态；无有效会话时返回 null。 */
    private JSONObject build(boolean withArt) {
        MediaController c = ctrl;
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
            String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
            if (artist.isEmpty()) {
                artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            String album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
            if (title.isEmpty() && artist.isEmpty()) {
                return null;   // 会话存在但没有曲目信息（如电台/语音）→ 不接管页面
            }
            long dur = 0;
            try {
                dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            } catch (Throwable ignored) {
            }
            PlaybackState ps = c.getPlaybackState();
            int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            playing = st == PlaybackState.STATE_PLAYING;

            JSONObject o = new JSONObject();
            o.put("kind", "media");
            o.put("active", true);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("pkg", c.getPackageName());
            o.put("app", label(c.getPackageName()));
            o.put("playing", playing);
            o.put("pos", posOf(ps));
            o.put("dur", dur);
            if (withArt) {
                String art = artOf(md);
                if (art != null && !art.equals(artSig)) {
                    artSig = art;
                    o.put("art", art);
                }
            }
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 播放位置按速度外推，否则暂停时进度条会停在最后一次上报的位置。 */
    private long posOf(PlaybackState ps) {
        if (ps == null) {
            return 0;
        }
        long p;
        try {
            p = ps.getPosition();
            long t = ps.getLastPositionUpdateTime();
            if (ps.getState() == PlaybackState.STATE_PLAYING && t > 0) {
                float sp = ps.getPlaybackSpeed();
                if (sp <= 0f) {
                    sp = 1f;
                }
                p += (long) ((SystemClock.elapsedRealtime() - t) * sp);
            }
        } catch (Throwable e) {
            p = 0;
        }
        return Math.max(0, p);
    }

    /** 专辑封面：取最大 200px 缩略图，JPEG q80 → data URL。
     *  编解码在主线程，所以对同一张 Bitmap 做缓存（refresh 每 10s 会走一次）。 */
    private String artOf(MediaMetadata md) {
        if (md == null) {
            return null;
        }
        Bitmap bmp = null;
        try {
            bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        } catch (Throwable ignored) {
        }
        if (bmp == null) {
            try {
                bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            } catch (Throwable ignored) {
            }
        }
        if (bmp == null) {
            try {
                MediaDescription d = md.getDescription();
                if (d != null) {
                    bmp = d.getIconBitmap();
                }
            } catch (Throwable ignored) {
            }
        }
        if (bmp == null) {
            return null;
        }
        if (bmp == lastArtBmp) {
            return lastArtUrl;
        }
        try {
            int w = bmp.getWidth(), hh = bmp.getHeight();
            int max = 200;
            if (w > max || hh > max) {
                float s = Math.min((float) max / w, (float) max / hh);
                Bitmap nb = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * s)), Math.max(1, Math.round(hh * s)), true);
                if (nb != bmp) {
                    bmp = nb;
                }
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo);
            String url = "data:image/jpeg;base64,"
                    + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            lastArtBmp = bmp;
            lastArtUrl = url;
            return url;
        } catch (Throwable e) {
            return null;
        }
    }

    /* ==================== 控制 ==================== */

    /** action：toggle | play | pause | prev | next | seek:<毫秒> */
    public void control(String action) {
        h.post(() -> {
            try {
                if (ctrl == null) {
                    refresh();
                }
                MediaController c = ctrl;
                if (c == null) {
                    SysHub.pushJson("{\"kind\":\"media\",\"active\":false}");
                    return;
                }
                MediaController.TransportControls t = c.getTransportControls();
                if (t == null) {
                    return;
                }
                String a = action == null ? "" : action;
                if ("next".equals(a)) {
                    t.skipToNext();
                } else if ("prev".equals(a)) {
                    t.skipToPrevious();
                } else if ("play".equals(a)) {
                    t.play();
                } else if ("pause".equals(a)) {
                    t.pause();
                } else if ("toggle".equals(a)) {
                    PlaybackState ps = c.getPlaybackState();
                    int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
                    if (st == PlaybackState.STATE_PLAYING) {
                        t.pause();
                    } else {
                        t.play();
                    }
                } else if (a.startsWith("seek:")) {
                    t.seekTo(Long.parseLong(a.substring(5)));
                }
                h.postDelayed(() -> push(false), 400);
            } catch (Throwable ignored) {
            }
        });
    }

    /* ==================== 工具 ==================== */

    /** 通知页面「无真实媒体」——通知监听断连时也要调用。 */
    public void pushInactive() {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "media");
            o.put("active", false);
            String json = o.toString();
            if (!json.equals(lastJson)) {
                lastJson = json;
                playing = false;
                artSig = "";
                SysHub.push(o);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    private static String str(MediaMetadata md, String key) {
        if (md == null) {
            return "";
        }
        try {
            CharSequence cs = md.getText(key);
            return cs == null ? "" : cs.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs == null ? pkg : cs.toString();
        } catch (Throwable e) {
            return pkg;
        }
    }

    public void release() {
        try {
            h.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        detach();
    }
}
