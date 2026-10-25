import numpy as np
from PIL import Image
import sys

def load(p):
    return np.asarray(Image.open(p).convert("RGB")).astype(int)

def R(c): return c[0]
def G(c): return c[1]
def B(c): return c[2]
def is_white(c): return c[0] > 236 and c[1] > 236 and c[2] > 236
def is_bluebtn(c): return c[2] > 150 and c[0] < 130 and c[1] < 160 and (c[2] - c[0]) > 60
def is_red(c): return c[0] > 160 and c[1] < 100 and c[2] < 100

def run(path, SX, SY):
    a = load(path)
    H, W = a.shape[:2]
    X = lambda v: round(v * SX)
    Y = lambda v: round(v * SY)

    def px(x, y):
        x, y = round(x), round(y)
        if x < 0 or y < 0 or x >= W or y >= H: return (-1, -1, -1)
        return tuple(a[y, x])

    xs = [X(90), X(630)]; ys = [500, 620, 740, 860, 980, 1030]
    hits = sum(1 for x in xs for y in ys if is_white(px(x, Y(y))))
    dialog = hits >= len(xs) * len(ys) - 2

    daka = -1
    for y in range(Y(1150), Y(1400), max(4, round(6 * SY))):
        blue = cnt = 0
        for x in range(X(120), X(600), max(6, round(10 * SX))):
            cnt += 1
            if is_bluebtn(px(x, y)): blue += 1
        if blue > cnt * 0.7:
            daka = y; break

    rows = 0
    for y in range(700, 1121, 6):
        red = 0
        for x in range(140, 581, 6):
            if is_red(px(x, Y(y))):
                red += 1
                if red >= 5: rows += 1; break

    print(f"{path}  size={W}x{H} SX={SX}")
    print(f"  dialogPresent={dialog} (白点 {hits}/12)")
    print(f"  findDakaY={daka}  (期望≈{round(1250*SY)})")
    print(f"  successRows={rows}  successPresent={rows>=2}")

run(r"C:\Users\admin\Desktop\连点器\shot\native720.jpg", 1.0, 1.0)
run(r"C:\Users\admin\Desktop\连点器\v2\f010.png", 0.8, 0.8)
run(r"C:\Users\admin\Desktop\连点器\v2\f024.png", 0.8, 0.8)
run(r"C:\Users\admin\Desktop\连点器\v2\f015.png", 0.8, 0.8)
run(r"C:\Users\admin\Desktop\连点器\v2\f004.png", 0.8, 0.8)
