#!/usr/bin/env python3
"""从 icon.png 生成 Android 启动器图标（平面图标路线）。

需要 PIL（这台机器上 /usr/bin/python3 自带）：
    /usr/bin/python3 tools/make_icons.py

★ 为什么是「平面图标」而不是自适应图标：
  这张图的中间是个**真的透明洞**（蓝色光圈 + 悬空的对勾）。自适应图标的
  background 层是必须的、会铺满整个 108×108 画布，一填色洞就没了；
  而且自适应图标只保证中间 72/108 可见，外面一圈会被遮罩切掉，
  这张图的外圈正好是那道深蓝描边，切掉就不是原图了。
  平面图标不会被裁，但 Android 8+ 的启动器会把它缩小垫底（legacy 处理），
  所以下面把画布**裁到内容外接框**，让圆盘尽量占满——这是平面图标能做到的最大尺寸。

★ 为什么按预乘 alpha 缩放：全透明像素的 RGB 是残留值（洞里淡黄、角上灰蓝），
  直接缩会混进边缘，在蓝圈和洞的边界糊出一圈脏色。所以先 multiply(alpha) 再缩、缩完还原。
"""
from PIL import Image, ImageChops

SRC = 'icon.png'
# dpi 目录名 -> 平面图标边长
DENS = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

# 裁到内容外接框后，再留这么一点边，免得圆盘边缘恰好压在画布边上被切
EDGE_MARGIN = 0.01


def content_box(alpha, w, h):
    """alpha > 8 的区域的外接框；裁成正方形（取长边），保证圆盘不变形。"""
    bb = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
    x0, y0, x1, y1 = bb
    side = max(x1 - x0, y1 - y0)
    pad = int(side * EDGE_MARGIN)
    side += pad * 2
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    half = side // 2
    return (max(0, cx - half), max(0, cy - half),
            min(w, cx + half), min(h, cy + half))


def load_premultiplied(path, box):
    im = Image.open(path).convert('RGBA').crop(box)
    r, g, b, a = im.split()
    return [ImageChops.multiply(r, a),
            ImageChops.multiply(g, a),
            ImageChops.multiply(b, a)], a


def resize_premultiplied(bands, alpha, n):
    a_s = alpha.resize((n, n), Image.LANCZOS)
    b_s = [c.resize((n, n), Image.LANCZOS) for c in bands]

    out = Image.new('RGBA', (n, n))
    dst, pa = out.load(), a_s.load()
    p0, p1, p2 = b_s[0].load(), b_s[1].load(), b_s[2].load()
    for y in range(n):
        for x in range(n):
            al = pa[x, y]
            if al == 0:
                dst[x, y] = (0, 0, 0, 0)
            else:
                dst[x, y] = (min(255, (p0[x, y] * 255 + al // 2) // al),
                             min(255, (p1[x, y] * 255 + al // 2) // al),
                             min(255, (p2[x, y] * 255 + al // 2) // al),
                             al)
    return out


def main():
    im = Image.open(SRC).convert('RGBA')
    box = content_box(im.split()[3], *im.size)
    print(f'源图 {im.size}  内容外接框 {box}  ({box[2]-box[0]}×{box[3]-box[1]})')
    bands, alpha = load_premultiplied(SRC, box)
    for dpi, n in DENS.items():
        icon = resize_premultiplied(bands, alpha, n)
        for name in ('ic_launcher.png', 'ic_launcher_round.png'):
            icon.save(f'app/src/main/res/mipmap-{dpi}/{name}', optimize=True)
        print(f'  {dpi:8s} {n:3d}×{n:<3d}')


if __name__ == '__main__':
    main()
