import numpy as np
from PIL import Image

img = Image.open(r"C:\Users\admin\Desktop\连点器\shot\native720.jpg").convert("RGB")
A = np.asarray(img).astype(float)
L = 0.299 * A[:, :, 0] + 0.587 * A[:, :, 1] + 0.114 * A[:, :, 2]
GS = 24

def glyph(cx, cy, white_ink, half=22):
    x0, y0 = cx - half, cy - half
    win = L[y0:y0 + 2 * half, x0:x0 + 2 * half]
    ink = (win > 205) if white_ink else (win < 140)
    ys, xs = np.nonzero(ink)
    if len(xs) == 0:
        return None
    minx, maxx, miny, maxy = xs.min(), xs.max(), ys.min(), ys.max()
    gw, gh = maxx - minx + 1, maxy - miny + 1
    out = np.zeros((GS, GS))
    for gy in range(GS):
        for gx in range(GS):
            sx = minx + int(gx * gw / GS)
            sy = miny + int(gy * gh / GS)
            out[gy, gx] = ink[sy, sx]
    return out

tops = [glyph(249, 638, True), glyph(360, 638, True), glyph(470, 638, True)]
cells = []
for ry in (863, 976):
    for cx in (181, 359, 538):
        cells.append(glyph(cx, ry, False))

print("tops ok", all(t is not None for t in tops), "cells ok", all(c is not None for c in cells))
print("\ndist matrix rows=tops(2,5,1) cols=cells(2,8,1,6,9,5)")
for i, t in enumerate(tops):
    row = []
    for j, c in enumerate(cells):
        row.append(np.mean(t != c))
    print(f"  top{i+1}: " + " ".join(f"{v:.3f}" for v in row) +
          f"   -> best #{int(np.argmin(row))+1} ({min(row):.3f})")

# greedy like the JS
used, order, scores = set(), [], []
for i in range(3):
    cand = sorted([(np.mean(tops[i] != cells[j]), j) for j in range(6) if j not in used])
    d, j = cand[0]
    second = cand[1][0] if len(cand) > 1 else 1.0
    used.add(j); order.append(j + 1); scores.append((d, second - d))
print("\nJS 贪心顺序:", order, "  score/margin:", [(round(a,3), round(b,3)) for a,b in scores])
