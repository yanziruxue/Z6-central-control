package com.l6.carmedia.nat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import java.io.File;
import java.io.InputStream;

/**
 * 壁纸层（对应原型 {@code #wallMedia} / {@code #wallVideo} / {@code #wallScrim}）。
 *
 * ★ 层级关系照搬原型：三张 fixed 全屏层 z-index 都是 0，而 {@code #app} 是 z-index 1
 *   ⇒ 壁纸永远在界面之下、且**只在主页生效**（切到设置页要把它藏起来，否则设置页半透明卡片
 *   底下会透出照片，可读性直接崩）。
 *
 * ★ 动态壁纸用 {@link VideoView}：原型是 {@code <video autoplay loop muted playsinline>}，
 *   原生最接近的就是 VideoView + 无限循环 + 静音。静音很关键 —— 壁纸视频带声音会盖住音乐。
 *
 * ★ 资源来源两种（见 {@link Prefs.WallItem}）：
 *   {@code asset:xxx} = 打包进去的预设壁纸；其余 = Download/L6/壁纸 下的绝对路径。
 *   解码失败**必须回落纯色底**，绝不能让壁纸层变成一块黑或直接崩。
 */
public class Wallpaper {

    private final Shell sh;
    private final FrameLayout root;
    private final View colorLayer;
    private final ImageView img;
    private final VideoView video;
    private final View scrim;

    private Bitmap curBmp;
    private String curPath;
    private boolean homeVisible = true;

    public Wallpaper(Shell sh) {
        this.sh = sh;
        Context c = sh.ctx();

        root = new FrameLayout(c);
        root.setLayoutParams(new FrameLayout.LayoutParams(U.MP, U.MP));

        // ① 纯色底：任何情况下都在，图片没解出来时至少不是黑的
        colorLayer = new View(c);
        colorLayer.setBackground(new ColorDrawable(U.BG));
        root.addView(colorLayer, new FrameLayout.LayoutParams(U.MP, U.MP));

        // ② 静态图（CENTER_CROP 等价于 background-size:cover）
        img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setVisibility(View.GONE);
        root.addView(img, new FrameLayout.LayoutParams(U.MP, U.MP));

        // ③ 动态视频
        video = new VideoView(c);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(U.MP, U.MP));

        // ④ 压暗层：linear-gradient(180deg, rgba(6,9,14,.26), rgba(6,9,14,.48))
        scrim = new View(c);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4206090E, 0x7A06090E}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(U.MP, U.MP));
    }

    public View view() {
        return root;
    }

    /** 依当前设置重贴壁纸（换壁纸 / 换类型 / 改压暗层 / 上传完成都调它） */
    public void reload() {
        Prefs p = sh.prefs();
        Prefs.WallItem it = p.currentWall();
        boolean isVid = Prefs.isVideo(it);
        String path = it == null ? "" : it.path;

        // 纯色底跟着设置里的 wallColor 走（解析失败保留默认深色）
        try {
            colorLayer.setBackground(new ColorDrawable(android.graphics.Color.parseColor(p.wallColor)));
        } catch (Throwable ignored) {
            colorLayer.setBackground(new ColorDrawable(U.BG));
        }

        if (p.wallDim) {
            scrim.setVisibility(View.VISIBLE);
        } else {
            scrim.setVisibility(View.GONE);
        }

        if (isVid) {
            img.setVisibility(View.GONE);
            recycleBmp();
            playVideo(path);
        } else {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            showImage(path);
        }

        // 注意：压暗层是「壁纸生效时才有意义」的，纯色底 + 无图时留着也无妨（原型同样如此）
        root.setVisibility(homeVisible ? View.VISIBLE : View.GONE);
    }

    private void showImage(String path) {
        if (path == null || path.isEmpty()) {
            img.setVisibility(View.GONE);
            recycleBmp();
            return;
        }
        if (curBmp != null && path.equals(curPath)) {
            img.setVisibility(View.VISIBLE);
            return;
        }
        Bitmap b = decode(path);
        recycleBmp();
        if (b == null) {
            img.setVisibility(View.GONE);
            android.util.Log.w("L6Wall", "壁纸解码失败: " + path);
            return;
        }
        curBmp = b;
        curPath = path;
        img.setImageBitmap(b);
        img.setVisibility(View.VISIBLE);
    }

    private Bitmap decode(String path) {
        try {
            if (path.startsWith(Prefs.ASSET_PREFIX)) {
                String name = path.substring(Prefs.ASSET_PREFIX.length());
                InputStream is = sh.ctx().getAssets().open(name);
                try {
                    return BitmapFactory.decodeStream(is);
                } finally {
                    is.close();
                }
            }
            File f = new File(path);
            if (!f.isFile()) return null;
            // ★ 两段式解码：先只读边界量出尺寸，再按屏幕宽做 inSampleSize 采样。
            //   直接 decodeFile 一张 4000px 的图会吃几十 MB 内存，车机上容易 OOM 被杀。
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int w = Math.max(1, o.outWidth);
            int s = 1;
            while (w / s > U.screenW) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void recycleBmp() {
        if (curBmp != null && !curBmp.isRecycled()) curBmp.recycle();
        curBmp = null;
        curPath = null;
    }

    private void playVideo(String path) {
        if (path == null || path.isEmpty() || path.startsWith(Prefs.ASSET_PREFIX)) {
            // 预设「动态壁纸」本来就没有视频文件（原型里 src 是空串）⇒ 静默退回纯色底
            video.setVisibility(View.GONE);
            return;
        }
        File f = new File(path);
        if (!f.isFile()) {
            video.setVisibility(View.GONE);
            return;
        }
        try {
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(Uri.fromFile(f));
            video.setOnPreparedListener(mp -> {
                try {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);      // 静音：壁纸视频绝不能盖住音乐
                } catch (Throwable ignored) {
                }
                video.start();
            });
            // 视频出错（编码不支持等）→ 退回纯色底，不要把整块变成黑
            video.setOnErrorListener((mp, what, extra) -> {
                video.setVisibility(View.GONE);
                return true;
            });
        } catch (Throwable t) {
            video.setVisibility(View.GONE);
        }
    }

    /**
     * 主页 / 设置页切换时显隐壁纸。
     * ★ 原型里「壁纸仅在主页生效」是靠设置页那张不透明卡片盖住的；原生这里显式隐藏更省电
     *   （视频壁纸在设置页还在解码播放就是纯浪费）。
     */
    public void setHomeVisible(boolean on) {
        if (homeVisible == on) return;
        homeVisible = on;
        root.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            try {
                video.pause();
            } catch (Throwable ignored) {
            }
        } else {
            try {
                video.start();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 页面尺寸变化（旋转/窗口变化）导致解码采样档位需要重算时调用 */
    public void onScreenChanged() {
        curPath = null;      // 强制重新解码
        reload();
    }

    /** 供外部（NatShell）取当前层，方便挂到根容器 */
    public ViewGroup group() {
        return root;
    }
}
