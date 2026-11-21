#!/usr/bin/env python3
"""Prints a simulator screenshot of the watch as text, for reading layouts in CI logs.

usage: ascii_shot.py SCREENSHOT.png

Finds the watch's screen (the largest bright-on-dark square region is not reliable in
the simulator's frame, so it uses ImageMagick to dump pixels and crops to the bounding
box of lit pixels), then prints one character per 2x2 pixels: '#' lit, '.' dark.
"""
import subprocess
import sys

path = sys.argv[1]
# width height then one luminance byte per pixel
raw = subprocess.run(["convert", path, "-colorspace", "Gray", "-depth", "8", "gray:-"],
                     check=True, capture_output=True).stdout
dims = subprocess.run(["identify", "-format", "%w %h", path], check=True, capture_output=True, text=True).stdout
w, h = (int(v) for v in dims.split())
lit = [[raw[y * w + x] > 128 for x in range(w)] for y in range(h)]
ys = [y for y in range(h) if any(lit[y])]
xs = [x for x in range(w) if any(lit[y][x] for y in range(h))]
if not ys:
    print("(blank)")
    sys.exit(0)
y0, y1, x0, x1 = min(ys), max(ys), min(xs), max(xs)
for y in range(y0, y1 + 1, 2):
    row = []
    for x in range(x0, x1 + 1, 2):
        on = any(lit[yy][xx] for yy in (y, y + 1) if yy <= y1 for xx in (x, x + 1) if xx <= x1)
        row.append("#" if on else ".")
    print("".join(row).rstrip("."))
