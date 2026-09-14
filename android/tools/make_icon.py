# -*- coding: utf-8 -*-
"""生成车机媒体大屏的启动图标（长安欧尚 logo）。

素材：tools/oushang-logo.png（透明底，含「速度线」标记 + 「长安欧尚」字标）。
设计：白色圆角方底 + 居中 logo（自动裁掉透明边、按宽度缩放留白），
      因为 logo 字标是深灰（#3E3A39），放在深色车机桌面上会看不见，
      所以底板用白色，和原图自身的白底一致。

输出 mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os
from PIL import Image, ImageDraw

BASE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res"))
LOGO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "oushang-logo.png"))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (255, 255, 255, 255)      # 白色圆角底
EDGE = (0, 0, 0, 16)           # 极淡描边，避免纯白图标在白色桌面上“糊”成一片
CONTENT_W = 0.76               # logo 内容占图标宽度比例
RADIUS = 0.22                  # 圆角比例


def trimmed_logo():
    """按 alpha 通道裁掉透明边，返回紧凑的 logo。"""
    im = Image.open(LOGO).convert("RGBA")
    alpha = im.split()[3]
    box = alpha.getbbox()
    return im.crop(box) if box else im


def render(size, logo):
    S = size * 4                       # 4 倍超采样后再缩小，边缘更干净
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(S * RADIUS)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=BG)

    lw, lh = logo.size
    w = int(S * CONTENT_W)
    h = max(1, round(w * lh / lw))
    lg = logo.resize((w, h), Image.LANCZOS)
    img.alpha_composite(lg, ((S - w) // 2, (S - h) // 2))

    # 描边放在最上层（缩放后再画会被抗锯齿吃掉）
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, outline=EDGE,
                        width=max(1, int(S * 0.012)))
    return img.resize((size, size), Image.LANCZOS)


def main():
    logo = trimmed_logo()
    print("logo 裁边后:", logo.size)
    for name, px in SIZES.items():
        out = os.path.join(BASE, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        p = os.path.join(out, "ic_launcher.png")
        render(px, logo).save(p, "PNG")
        print("icon ->", p, px)


if __name__ == "__main__":
    main()
