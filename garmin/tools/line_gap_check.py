#!/usr/bin/env python3
"""Checks a watch face screenshot: nothing may touch the divider lines.

Finds the face's horizontal lines (rows with a long unbroken lit run) and fails
if the 2 rows just above or below a line have any lit pixel along the line's span. The debug build's fit check works from font measurements; this works from
what was actually drawn.
"""
import subprocess
import sys

# px; the face's lines are 118-142 px long. Longer runs are the simulator's window.
MIN_RUN = 80
MAX_RUN = 170


class Pixels:
    def __init__(self, path):
        raw = subprocess.run(["convert", path, "-colorspace", "Gray", "-depth", "8", "gray:-"],
                             check=True, capture_output=True).stdout
        dims = subprocess.run(["identify", "-format", "%w %h", path], check=True,
                              capture_output=True, text=True).stdout
        self.w, self.h = (int(v) for v in dims.split())
        self.raw = raw

    def __getitem__(self, xy):
        x, y = xy
        if not (0 <= x < self.w and 0 <= y < self.h):
            return 0
        return self.raw[y * self.w + x]


def is_line(px, y, w):
    """The longest lit run in row y, if it is a face line: long, and black at both ends."""
    n, a, b = runs(px, y, w)
    if MIN_RUN <= n <= MAX_RUN and px[a - 2, y] <= 128 and px[b + 2, y] <= 128:
        return n, a, b
    return 0, 0, 0


def runs(px, y, w):
    best, start, cur = (0, 0, 0), 0, 0
    for x in range(w):
        if px[x, y] > 128:
            if cur == 0:
                start = x
            cur += 1
            if cur > best[0]:
                best = (cur, start, x)
        else:
            cur = 0
    return best


def main(path):
    px = Pixels(path)
    w, h = px.w, px.h
    lines = []
    y = 0
    while y < h:
        n, a, b = is_line(px, y, w)
        if n:
            top = y
            while y + 1 < h and is_line(px, y + 1, w)[0]:
                y += 1
            if y - top < 4:  # the face's lines are 2-3 px thick; taller blocks are the simulator's frame
                lines.append((top, y, a, b))
        y += 1
    if len(lines) != 2:
        print(f"line check: expected 2 lines, found {len(lines)}: {lines}")
        return 1
    bad = 0
    for top, bottom, a, b in lines:
        # at least 2 clear rows either side
        for rows, side in (((top - 1, top - 2), "above"), ((bottom + 1, bottom + 2), "below")):
            lit = sum(1 for ny in rows for x in range(a, b + 1) if px[x, ny] > 128)
            if lit:
                bad += 1
                print(f"line check FAIL: {lit} px drawn within 2 px {side} the line at y={top}-{bottom}")
        print(f"line at y={top}-{bottom}, x={a}-{b}")
    print("line check ok" if bad == 0 else f"line check FAIL ({bad})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
