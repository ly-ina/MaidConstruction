#!/usr/bin/env python3
"""把画好的 logo 原图处理成能用的两个尺寸（裁边 → 圆角 → 缩放）。

用法：

    python tools/make_logo.py

原图放在 `docs/logo-source.jpg`（画师/AI 出的成品，不是中间稿），产物：

  - `src/main/resources/logo.png`（256×256）—— `mods.toml` 的 `logoFile`，游戏内模组列表用它；
  - `docs/logo-512.png`（512×512）—— 发布页上传用。

处理只有三步，但每步都有个坑：

  1. **按比例内缩 1.2%**：原图四周有一圈 1~2% 的浅色底（不是纯白，所以不能用"砍白边"那套），
     内缩一点把这圈不均匀的边先削掉；
  2. **圆角遮罩自己画**：原图的圆角是画出来的、半径各不相同，自己按 15.6% 的半径重画一遍，
     四角就干净了（遮罩画在 4 倍尺寸再缩下来，边缘才有抗锯齿）；
  3. **顺序**：先缩到目标尺寸再套遮罩 —— 反过来（先套遮罩再缩）会把透明边缘和底色混出一圈灰边。

换成新版图：覆盖 `docs/logo-source.jpg` 再跑一次即可。
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, 'docs', 'logo-source.jpg')
OUT_IN_JAR = os.path.join(ROOT, 'src', 'main', 'resources', 'logo.png')
OUT_UPLOAD = os.path.join(ROOT, 'docs', 'logo-512.png')

# 内缩比例（削掉原图那圈浅色底）与圆角半径比例（相对边长）
INSET_RATIO = 0.012
RADIUS_RATIO = 0.156


def rounded(source, size):
    """缩放 + 圆角遮罩。遮罩在 4 倍尺寸上画好再缩，圆角才平滑。"""
    width, height = size
    radius = int(min(size) * RADIUS_RATIO)
    big = 4
    mask = Image.new('L', (width * big, height * big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width * big - 1, height * big - 1],
                                           radius=radius * big, fill=255)
    out = source.resize(size, Image.LANCZOS).convert('RGBA')
    out.putalpha(mask.resize(size, Image.LANCZOS))
    return out


def main():
    image = Image.open(SOURCE).convert('RGB')
    inset = int(min(image.size) * INSET_RATIO)
    panel = image.crop((inset, inset, image.width - inset, image.height - inset))
    rounded(panel, (256, 256)).save(OUT_IN_JAR)
    rounded(panel, (512, 512)).save(OUT_UPLOAD)
    print('原图 %s → 内缩 %dpx → 写出 %s、%s'
          % (image.size, inset, os.path.relpath(OUT_IN_JAR, ROOT), os.path.relpath(OUT_UPLOAD, ROOT)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
