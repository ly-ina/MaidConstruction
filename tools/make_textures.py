#!/usr/bin/env python3
"""把借来的底图改色成本模组自己的贴图（方案 A：保持像素结构与明暗，只换调色板 + 加角标）。

用法：

    python tools/make_textures.py

为什么要脚本：这几张贴图是**从底图派生的**，不是画出来的——改动只有"调色板 + 几个角标"。
把它写成脚本，以后想换配色（比如主题色从青蓝换成别的）改一张表重跑即可，
不用重新画；也省得下一个人对着几张 16×16 猜"这是谁画的、怎么来的"。

底图取自编译缓存里的原版与 AE2（我们**不**打包它们，只在生成时读）：
  - minecraft:item/paper           -> 蓝图物品
  - minecraft:item/writable_book   -> 绑定书
  - ae2:item/wireless_terminal     -> 无线女仆终端
  - ae2:part/terminal              -> 女仆终端（方块，cube_all，要求能平铺）

几条刻意的做法：
  - **只用最近邻缩放**：像素图一旦有插值就会糊，16×16 上尤其明显；
  - **亮度映射**而不是整体上色：底图的明暗关系（高光、边缘、内阴影）保留，看起来才像"同一套贴图"；
  - **最亮的那几档改成强调色**：底图里最亮的地方通常就是屏幕/纸面的高光，
    正好是我们想点主题色的位置，比"画一个方块上去"贴合形状；
  - 角标只画在**不透明**的像素上，避免在透明区域凭空多出几个点。
"""
import glob
import os
import sys
import zipfile

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_ITEM = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'blueprint', 'textures', 'item')
OUT_BLOCK = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'blueprint', 'textures', 'block')

SIZE = 16

# ------------------------------------------------------------------
# 主题调色板：暗 -> 亮五档
# ------------------------------------------------------------------
BLUEPRINT_RAMP = [(0x1B, 0x38, 0x5C), (0x2C, 0x5A, 0x91), (0x4E, 0x8F, 0xD0),
                  (0x9F, 0xCB, 0xF2), (0xE8, 0xF4, 0xFF)]
TERMINAL_RAMP = [(0x1C, 0x20, 0x28), (0x33, 0x39, 0x45), (0x55, 0x5D, 0x6E),
                 (0x86, 0x8F, 0xA3), (0xC9, 0xD1, 0xDF)]
BOOK_RAMP = [(0x3A, 0x1B, 0x1E), (0x6B, 0x2E, 0x30), (0x9E, 0x4A, 0x42),
             (0xC9, 0x86, 0x6E), (0xF0, 0xD9, 0xC0)]

ACCENT_CYAN = (0x55, 0xE0, 0xD0)
ACCENT_RED = (0xE0, 0x5A, 0x5A)
ACCENT_PINK = (0xE8, 0x7A, 0xB0)
PAPER_WHITE = (0xEA, 0xF4, 0xFF)

# 底图：asset 路径 -> 在哪些缓存 jar 里找
VANILLA_JARS = ('**/forge_gradle/**/client.jar',)
AE2_JARS = ('**/deobf_dependencies/**/ae2-*.jar',)


def read_asset(cache_globs, entry):
    """从 gradle 缓存里读出某个资源（我们只读，不打包）。"""
    home = os.path.expanduser('~')
    for pattern in cache_globs:
        for jar in glob.glob(os.path.join(home, '.gradle', 'caches', pattern), recursive=True):
            try:
                with zipfile.ZipFile(jar) as z:
                    if entry in z.namelist():
                        return Image.open(z.open(entry)).convert('RGBA')
            except (zipfile.BadZipFile, KeyError, OSError):
                continue
    raise SystemExit('找不到底图 %s（先在项目里跑一次构建，把依赖下下来）' % entry)


def luminance(rgb):
    """感知亮度（0..1）。用它当"原图的明暗"，不改变结构。"""
    return (0.299 * rgb[:, :, 0] + 0.587 * rgb[:, :, 1] + 0.114 * rgb[:, :, 2]) / 255.0


def recolor(img, ramp, accent=None, accent_from=1.01):
    """把底图按亮度映射到调色板；亮度超过 accent_from 的像素改成强调色。"""
    if img.size != (SIZE, SIZE):
        # 底图比 16 大就最近邻缩下来（保持锐利），比 16 小就原样留着（居中不放大）
        img = img.resize((SIZE, SIZE), Image.NEAREST) if img.width > SIZE else img
    src = np.array(img, dtype=np.float32)
    lum = luminance(src)
    steps = len(ramp) - 1
    idx = np.clip((lum * steps + 0.5).astype(np.int32), 0, steps)
    out = np.zeros((src.shape[0], src.shape[1], 3), dtype=np.float32)
    for i, color in enumerate(ramp):
        out[idx == i] = color
    # 再乘一个随亮度变化的系数，保住底图那点局部对比（否则整块会显得平）
    out *= (0.88 + 0.24 * lum)[:, :, None]
    if accent is not None:
        out[lum >= accent_from] = accent
    out = np.clip(out, 0, 255)
    result = np.zeros((src.shape[0], src.shape[1], 4), dtype=np.uint8)
    result[:, :, :3] = out.astype(np.uint8)
    result[:, :, 3] = src[:, :, 3].astype(np.uint8)
    return result


def opaque_bounds(px):
    """不透明像素的范围（画角标要在形状里面画，不能画到空气上）。"""
    solid = px[:, :, 3] > 8
    ys, xs = np.nonzero(solid)
    if len(xs) == 0:
        return None
    return xs.min(), xs.max(), ys.min(), ys.max()


def put(px, x, y, color):
    """画一个像素——只画在不透明处（缺省加点透明度会让边缘脏）。"""
    if 0 <= x < px.shape[1] and 0 <= y < px.shape[0] and px[y, x, 3] > 8:
        px[y, x, :3] = color
        px[y, x, 3] = 255


def make_blueprint():
    px = recolor(read_asset(VANILLA_JARS, 'assets/minecraft/textures/item/paper.png'),
                 BLUEPRINT_RAMP)
    bounds = opaque_bounds(px)
    if bounds:
        # 蓝图 = 蓝色的纸面 + 中间一横一竖两条格线。
        # 底图的纸是**斜着放的一张纸**（形状本来就不规则），所以格线只画在纸面上：
        # put() 跳过透明像素，纸外不会被多画出一条线
        x0, x1, y0, y1 = bounds
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
        for x in range(x0, x1 + 1):
            put(px, x, cy, PAPER_WHITE)
        for y in range(y0, y1 + 1):
            put(px, cx, y, PAPER_WHITE)
        # 右上角红色角标：像盖了个"定稿"的章。
        # 位置要挑**纸面上**的格子：这张纸是斜放的，右上角那几格其实是空气，
        # 早先按 bbox 角点画，结果一个点都没落上去（put 会跳过透明像素）
        put(px, x1 - 1, cy - 1, ACCENT_RED)
        put(px, x1 - 2, cy - 1, ACCENT_RED)
        put(px, x1 - 1, cy, ACCENT_RED)
    return px, 'blueprint'


def diamond(px, cx, cy, color):
    """3×3 的菱形角标：女仆元素（发饰/心）在这个尺寸上只能这么表示，多了就糊。"""
    for dx, dy in ((0, -1), (-1, 0), (0, 0), (1, 0), (0, 1)):
        put(px, cx + dx, cy + dy, color)


def save(px, directory, name):
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, name + '.png')
    Image.fromarray(px, 'RGBA').save(path)
    return path


def preview(px, name):
    """在终端里画一遍（形状对不对，一眼就能看出来，不用开图片）。"""
    ramp = ' .:-=+*#%@'
    print('  %s (%d×%d)' % (name, px.shape[1], px.shape[0]))
    for row in px:
        line = ''
        for r, g, b, a in row:
            if a < 16:
                line += ' '
                continue
            if a < 200:
                line += '\''
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            line += ramp[min(len(ramp) - 1, 1 + int(lum * (len(ramp) - 2)))]
        print('  |' + line + '|')


def make_wireless_terminal():
    px = recolor(read_asset(AE2_JARS, 'assets/ae2/textures/item/wireless_terminal.png'),
                 TERMINAL_RAMP, accent=ACCENT_CYAN, accent_from=0.74)
    bounds = opaque_bounds(px)
    if bounds:
        # 右上角一个粉色菱形：一眼看出"这台是女仆的"，而不是 AE2 原装
        diamond(px, bounds[1] - 1, bounds[2] + 1, ACCENT_PINK)
    return px, 'wireless_maid_terminal'


def make_binding_book():
    px = recolor(read_asset(VANILLA_JARS, 'assets/minecraft/textures/item/writable_book.png'),
                 BOOK_RAMP, accent=(0xF2, 0xE6, 0xC8), accent_from=0.85)
    bounds = opaque_bounds(px)
    if bounds:
        # 书脊在左，书签系在右侧：三格青色，和终端/蓝图是同一套主题色
        for y in range(bounds[2] + 5, bounds[2] + 8):
            put(px, bounds[1] - 1, y, ACCENT_CYAN)
            put(px, bounds[1] - 2, y, ACCENT_CYAN)
    return px, 'binding_book'


def make_maid_terminal():
    """女仆终端（方块）：这张是**从零画的**，不是改色。

    原因：AE2 的 `part/terminal` 是给线缆挂板用的**薄板**贴图——16×16 里只有 44 个
    不透明像素，就是个空心框（把透明度打出来一看就明白了）。它当整块方块
    （cube_all，六个面同一张）用，看起来就"只有一个轮廓"。方块要的是"机箱正面"，
    所以这里手画一张：深色机壳 + 青色屏幕 + 屏幕上两行字，比继续找底图可靠。
    """
    px = np.zeros((SIZE, SIZE, 4), dtype=np.uint8)
    px[:, :, :3] = TERMINAL_RAMP[1]
    px[:, :, 3] = 255  # 方块贴图铺满，不留透明
    # 1 像素的立体边：上/左亮、下/右暗（像素画里这点差别就是"厚度"）
    px[0, :, :3] = TERMINAL_RAMP[2]
    px[:, 0, :3] = TERMINAL_RAMP[2]
    px[-1, :, :3] = TERMINAL_RAMP[0]
    px[:, -1, :3] = TERMINAL_RAMP[0]
    # 屏幕：青色面板 + 两行深色"字" + 左上角一个亮点（像刚开机）
    px[4:11, 3:13, :3] = ACCENT_CYAN
    px[5, 4, :3] = (0xF2, 0xFF, 0xFD)
    for y in (6, 8):
        px[y, 5:11, :3] = TERMINAL_RAMP[0]
    # 右上角一个粉色菱形：和无线终端同一个标记，"这是女仆的那一台"
    diamond(px, 13, 2, ACCENT_PINK)
    return px, 'maid_terminal'


def main():
    jobs = [
        (make_blueprint, OUT_ITEM),
        (make_wireless_terminal, OUT_ITEM),
        (make_binding_book, OUT_ITEM),
        (make_maid_terminal, OUT_BLOCK),
    ]
    for build, directory in jobs:
        px, name = build()
        path = save(px, directory, name)
        print('写出 %s' % os.path.relpath(path, ROOT).replace(os.sep, '/'))
        preview(px, name)
    return 0


if __name__ == '__main__':
    sys.exit(main())
