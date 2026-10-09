#!/usr/bin/env python3
"""图片勘测辅助工具：为 1:1 复刻 PPT 提供网格定位、分区放大、像素取色。

用法:
  analyze_image.py <image> --grid [--step 100] -o grid.png
  analyze_image.py <image> --bands 6 [-o band_]
  analyze_image.py <image> --crop x0,y0,x1,y1 [--scale 2] -o zoom.png
  analyze_image.py <image> --px x1,y1 [x2,y2 ...]
"""
import argparse
import sys

from PIL import Image, ImageDraw


def cmd_grid(img, args):
    g = img.convert("RGB").copy()
    d = ImageDraw.Draw(g)
    w, h = g.size
    for x in range(0, w, args.step):
        d.line([(x, 0), (x, h)], fill=(255, 0, 0), width=1)
        d.text((x + 2, 2), str(x), fill=(255, 0, 0))
    for y in range(0, h, args.step):
        d.line([(0, y), (w, y)], fill=(255, 0, 0), width=1)
        d.text((2, y + 2), str(y), fill=(255, 0, 0))
    out = args.output or "/tmp/grid.png"
    g.save(out)
    print(f"size={img.size} grid -> {out}")


def cmd_bands(img, args):
    w, h = img.size
    n = args.bands
    prefix = args.output or "/tmp/band_"
    bh = h // n
    for i in range(n):
        y0 = i * bh
        y1 = h if i == n - 1 else (i + 1) * bh
        band = img.crop((0, y0, w, y1))
        out = f"{prefix}{i + 1}.png"
        band.save(out)
        print(f"band {i + 1}: y=[{y0},{y1}) -> {out}")


def cmd_crop(img, args):
    x0, y0, x1, y1 = [int(v) for v in args.crop.split(",")]
    c = img.crop((x0, y0, x1, y1))
    if args.scale != 1:
        c = c.resize((c.width * args.scale, c.height * args.scale), Image.LANCZOS)
    out = args.output or "/tmp/crop.png"
    c.save(out)
    print(f"crop ({x0},{y0},{x1},{y1}) x{args.scale} -> {out}")


def cmd_px(img, args):
    arr = img.convert("RGB")
    for pt in args.px:
        x, y = [int(v) for v in pt.split(",")]
        r, g, b = arr.getpixel((x, y))
        print(f"({x},{y}) = #{r:02X}{g:02X}{b:02X}")


def main():
    ap = argparse.ArgumentParser(description="Image survey helper for pixel-accurate PPT replication")
    ap.add_argument("image")
    ap.add_argument("--grid", action="store_true")
    ap.add_argument("--step", type=int, default=100)
    ap.add_argument("--bands", type=int, default=0)
    ap.add_argument("--crop", metavar="x0,y0,x1,y1")
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--px", nargs="+", metavar="x,y")
    ap.add_argument("-o", "--output")
    args = ap.parse_args()

    img = Image.open(args.image)
    if not (args.grid or args.bands or args.crop or args.px):
        print(f"size={img.size}")
        sys.exit(0)
    if args.grid:
        cmd_grid(img, args)
    if args.bands:
        cmd_bands(img, args)
    if args.crop:
        cmd_crop(img, args)
    if args.px:
        cmd_px(img, args)


if __name__ == "__main__":
    main()
