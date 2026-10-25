import numpy as np
from PIL import Image

im = Image.open(r"C:\Users\admin\Desktop\连点器\shot\native720.jpg").convert("RGB")
a = np.asarray(im).astype(int)
H, W, _ = a.shape
print("size", W, H)

r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

# blue circles (#2B7FFF-ish)
blue = (b > 190) & (r < 110) & (g > 80) & (g < 180)
ys, xs = np.nonzero(blue)
print("blue px", len(xs))
# cluster columns into 3 groups
if len(xs):
    order = np.argsort(xs)
    xs_s, ys_s = xs[order], ys[order]
    groups, cur = [], [0]
    for i in range(1, len(xs_s)):
        if xs_s[i] - xs_s[i - 1] > 40:
            groups.append(cur)
            cur = []
        cur.append(i)
    groups.append(cur)
    for gi, idx in enumerate(groups):
        gx, gy = xs_s[idx], ys_s[idx]
        print(f"circle{gi+1}: cx={gx.mean():.0f} cy={gy.mean():.0f} "
              f"x[{gx.min()}-{gx.max()}] y[{gy.min()}-{gy.max()}] n={len(gx)}")

# dark text pixels inside dialog area (digits)
dark = (r < 110) & (g < 110) & (b < 110)
# restrict to dialog box region: find big white box first
white = (r > 250) & (g > 250) & (b > 250)
rowsum = white.sum(axis=1)
colsum = white.sum(axis=0)
# dialog rows: rows where >60% width white
rowsw = np.nonzero(rowsum > 0.55 * W)[0]
print("white rows span", rowsw.min(), rowsw.max())
cols = np.nonzero(colsum > 0.3 * H)[0]
print("white cols span", cols.min(), cols.max())

# scan dark blobs in lower half (buttons) row bands
def bands(mask, axis, minrun=3):
    s = mask.sum(axis=axis)
    out, start = [], None
    for i, v in enumerate(s):
        if v > minrun and start is None:
            start = i
        elif v <= minrun and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(s) - 1))
    return out

roi = dark[560:1060, 100:660]
rb = bands(roi, 1, 5)
print("dark row bands (offset 560):", [(a0 + 560, a1 + 560) for a0, a1 in rb])
cb = bands(roi, 0, 5)
print("dark col bands (offset 100):", [(a0 + 100, a1 + 100) for a0, a1 in cb])
