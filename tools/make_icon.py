# -*- coding: utf-8 -*-
"""生成 KidGuard 启动图标（多尺寸 PNG）。"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = r"D:\DSH工作区\安卓7以上小米盒子电视精简系统包\kidguard\app\res"
SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}

def font(size):
    for p in (r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\msyhbd.ttc",
              r"C:\Windows\Fonts\simhei.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()

def make(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = max(1, size // 16)
    r = size // 5
    # 圆角深蓝底
    d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=r, fill=(11, 16, 32, 255))
    # 内圈青色环
    ring = max(2, size // 16)
    inset = size // 5
    d.ellipse([inset, inset, size - inset, size - inset], outline=(79, 195, 247, 255), width=ring)
    # 中间 K
    f = font(int(size * 0.42))
    txt = "K"
    bb = d.textbbox((0, 0), txt, font=f)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((size - w) / 2 - bb[0], (size - h) / 2 - bb[1]), txt, font=f, fill=(236, 239, 241, 255))
    return img

for folder, px in SIZES.items():
    out = os.path.join(BASE, folder)
    os.makedirs(out, exist_ok=True)
    make(px).save(os.path.join(out, "ic_launcher.png"))
    print("wrote", os.path.join(out, "ic_launcher.png"))
print("ICON_DONE")
