#!/usr/bin/env python3
"""Prints a region of a screenshot 1:1 as text ('#' lit, '.' dark), one line per pixel row.

usage: print_pixels.py SCREENSHOT.png X Y W H
"""
import subprocess
import sys

path, x0, y0, w, h = sys.argv[1], *map(int, sys.argv[2:6])
raw = subprocess.run(["convert", path, "-crop", f"{w}x{h}+{x0}+{y0}", "+repage",
                      "-colorspace", "Gray", "-depth", "8", "gray:-"], check=True, capture_output=True).stdout
for y in range(h):
    print("PX " + "".join("#" if raw[y * w + x] > 128 else "." for x in range(w)))
