#!/usr/bin/env python3
"""Draws the touch HUD icons into data/packages/mainmenu/textures/UI.

Pixel-style white glyphs with a dark outline on a 64x64 canvas, plus the round
translucent button background (up and pressed). The UI atlas is packed with
nearest filtering, so the hard pixel edges stay sharp at any button size.

    python scripts/make-touch-icons.py

Only the standard library is used. Rebuild the data afterwards
(./gradlew make_data, or -PrebuildData for the APK).
"""
import math
import os
import struct
import zlib

N = 64
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "data", "packages", "mainmenu", "textures", "UI")


# ---------------------------------------------------------------- shapes
# Every shape is a predicate f(x, y) on pixel centres; a glyph is a list of
# (predicate, add) pairs applied in order, add=False cuts a hole.

def circle(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def ring(cx, cy, r, w):
    return lambda x, y: abs(math.hypot(x - cx, y - cy) - r) <= w / 2


def rect(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def rounded(x0, y0, x1, y1, r):
    def f(x, y):
        if not (x0 <= x <= x1 and y0 <= y <= y1):
            return False
        cx = min(max(x, x0 + r), x1 - r)
        cy = min(max(y, y0 + r), y1 - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    return f


def line(x0, y0, x1, y1, w):
    def f(x, y):
        dx, dy = x1 - x0, y1 - y0
        t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)))
        return math.hypot(x - (x0 + t * dx), y - (y0 + t * dy)) <= w / 2
    return f


def poly(points):
    def f(x, y):
        inside = False
        j = len(points) - 1
        for i in range(len(points)):
            xi, yi = points[i]
            xj, yj = points[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
        return inside
    return f


def arc(cx, cy, r, w, a0, a1):
    """Degrees, image coordinates (0 = right, clockwise), from a0 to a1."""
    def f(x, y):
        if abs(math.hypot(x - cx, y - cy) - r) > w / 2:
            return False
        a = math.degrees(math.atan2(y - cy, x - cx)) % 360
        return (a - a0) % 360 <= (a1 - a0) % 360
    return f


def arrow_head(cx, cy, r, a, size):
    """Triangle at angle a of a circle, pointing towards decreasing angles."""
    ra = math.radians(a)
    px, py = cx + r * math.cos(ra), cy + r * math.sin(ra)
    tx, ty = math.sin(ra), -math.cos(ra)          # tangent, decreasing angle
    nx, ny = math.cos(ra), math.sin(ra)           # radial
    return poly([
        (px + tx * size, py + ty * size),
        (px - tx * 2 + nx * size, py - ty * 2 + ny * size),
        (px - tx * 2 - nx * size, py - ty * 2 - ny * size),
    ])


# ---------------------------------------------------------------- glyphs

GLYPHS = {
    # crosshair
    "fire": [
        (ring(32, 32, 14, 4), True), (circle(32, 32, 3.5), True),
        (line(32, 5, 32, 14, 4), True), (line(32, 50, 32, 59, 4), True),
        (line(5, 32, 14, 32, 4), True), (line(50, 32, 59, 32, 4), True),
    ],
    # circular arrow
    "reload": [
        (arc(32, 32, 16, 5.5, 300, 210), True),
        (arrow_head(32, 32, 16, 300, 9), True),
    ],
    # two chevrons down onto the ground
    "crouch": [
        (line(17, 13, 32, 25, 5.5), True), (line(32, 25, 47, 13, 5.5), True),
        (line(17, 27, 32, 39, 5.5), True), (line(32, 39, 47, 27, 5.5), True),
        (rect(13, 46, 51, 51), True),
    ],
    # pointing hand
    "use": [
        (rounded(25, 6, 33, 36, 4), True),
        (rounded(33, 21, 40, 38, 3.5), True),
        (rounded(40, 24, 46, 40, 3), True),
        (rounded(46, 28, 51, 42, 2.5), True),
        (rounded(23, 34, 51, 52, 6), True),
        (rect(28, 48, 47, 57), True),
        (line(26, 42, 15, 31, 7), True),
    ],
    # swap arrows
    "weapon": [
        (line(10, 21, 46, 21, 5.5), True), (poly([(42, 10), (56, 21), (42, 32)]), True),
        (line(18, 43, 54, 43, 5.5), True), (poly([(22, 32), (8, 43), (22, 54)]), True),
    ],
    # three bars
    "menu": [
        (rect(13, 15, 51, 21), True), (rect(13, 29, 51, 35), True), (rect(13, 43, 51, 49), True),
    ],
    # two people
    "players": [
        (circle(43, 22, 6.5), True), (poly([(31, 52), (33, 37), (43, 32), (53, 37), (56, 52)]), True),
        (circle(23, 21, 7.5), True), (poly([(8, 54), (10, 37), (23, 31), (36, 37), (38, 54)]), True),
        (line(37, 36, 37, 54, 2), False),
    ],
    # backpack
    "inventory": [
        (ring(32, 15, 7, 4), True),
        (rounded(14, 18, 50, 56, 8), True),
        (line(14, 30, 50, 30, 2.5), False),
        (rounded(22, 37, 42, 50, 3), False),
        (rect(25, 40, 39, 47), True),
    ],
    # speech bubble with dots
    "chat": [
        (rounded(8, 10, 56, 42, 9), True), (poly([(17, 38), (31, 38), (13, 54)]), True),
        (circle(21, 26, 3.5), False), (circle(32, 26, 3.5), False), (circle(43, 26, 3.5), False),
    ],
    # magnifier
    "zoom": [
        (ring(27, 27, 14, 5), True), (line(38, 38, 54, 54, 8), True),
    ],
    # three dots
    "more": [
        (circle(17, 32, 5.5), True), (circle(32, 32, 5.5), True), (circle(47, 32, 5.5), True),
    ],
    # back to the previous weapon
    "last": [
        (line(48, 46, 48, 24, 5.5), True), (line(48, 24, 22, 24, 5.5), True),
        (poly([(24, 12), (9, 24), (24, 36)]), True),
    ],
}

WHITE = (255, 255, 255, 255)
OUTLINE = (12, 12, 12, 210)


def render_glyph(ops):
    mask = [[False] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            for f, add in ops:
                if f(px, py):
                    mask[y][x] = add
    # dark outline 2 px around the glyph, so it reads on sky and on dirt
    img = [[(0, 0, 0, 0)] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            if mask[y][x]:
                img[y][x] = WHITE
                continue
            near = any(
                0 <= x + dx < N and 0 <= y + dy < N and mask[y + dy][x + dx]
                for dy in range(-2, 3) for dx in range(-2, 3) if dx * dx + dy * dy <= 5)
            if near:
                img[y][x] = OUTLINE
    return img


def render_round(fill, border):
    img = [[(0, 0, 0, 0)] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            d = math.hypot(x + 0.5 - 32, y + 0.5 - 32)
            if d <= 29.5:
                img[y][x] = fill
            elif d <= 31.5:
                img[y][x] = border
    return img


def write_png(path, img):
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in img)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    for name, ops in GLYPHS.items():
        write_png(os.path.join(OUT, "touch-icon-%s.png" % name), render_glyph(ops))
    write_png(os.path.join(OUT, "touch-round.png"),
              render_round((0, 0, 0, 105), (255, 255, 255, 95)))
    write_png(os.path.join(OUT, "touch-round-down.png"),
              render_round((255, 255, 255, 80), (255, 255, 255, 210)))
    print("written %d icons into %s" % (len(GLYPHS) + 2, os.path.normpath(OUT)))


if __name__ == "__main__":
    main()
