import numpy as np
from PIL import Image

im = Image.open(r"C:\Users\admin\Desktop\连点器\shot\native720.jpg").convert("RGB")
a = np.asarray(im).astype(int)
r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

def bbox_dark(x0, x1, y0, y1):
    m = (r < 140) & (g < 140) & (b < 140)
    m = m[y0:y1, x0:x1]
    yy, xx = np.nonzero(m)
    return (x0 + xx.min(), x0 + xx.max(), y0 + yy.min(), y0 + yy.max())

def bbox_light(x0, x1, y0, y1):
    m = (r > 200) & (g > 200) & (b > 220)
    m = m[y0:y1, x0:x1]
    yy, xx = np.nonzero(m)
    return (x0 + xx.min(), x0 + xx.max(), y0 + yy.min(), y0 + yy.max())

def norm_glyph(box, invert=False, size=32):
    x0, x1, y0, y1 = box
    sub = a[y0:y1 + 1, x0:x1 + 1]
    lum = sub.mean(axis=2)
    if invert:
        m = lum > 200
    else:
        m = lum < 140
    ys, xs = np.nonzero(m)
    if len(xs) == 0:
        return None
    m = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    im2 = Image.fromarray((m * 255).astype(np.uint8)).resize((size, size), Image.LANCZOS)
    v = np.asarray(im2).astype(float) / 255.0
    return v

colx = [(101, 261), (279, 440), (458, 618)]
rowy = [(816, 910), (929, 1023)]
circx = [(213, 286), (323, 396), (433, 506)]
circy = (601, 675)

tiles = []   # (label, vec)
tops = ["2", "5", "1"]
pool = [["2", "8", "1"], ["6", "9", "5"]]

for ri, (y0, y1) in enumerate(rowy):
    for ci, (x0, x1) in enumerate(colx):
        bx = bbox_dark(x0, x1, y0, y1)
        v = norm_glyph(bx)
        tiles.append((pool[ri][ci], v, "btn"))
        print("btn", ri, ci, pool[ri][ci], bx, "w", bx[1]-bx[0]+1, "h", bx[3]-bx[2]+1)

for ci, (x0, x1) in enumerate(circx):
    bx = bbox_light(x0 + 10, x1 - 10, circy[0] + 10, circy[1] - 10)
    v = norm_glyph(bx, invert=True)
    tiles.append((tops[ci], v, "top"))
    print("top", ci, tops[ci], bx)

print("\nunique glyphs:", sorted(set(t[0] for t in tiles)))
uniq = {}
for lab, v, kind in tiles:
    uniq.setdefault(lab, v)

labs = sorted(uniq)
print("pairwise mean-abs-diff (lower=differ):")
print("     " + "".join(f"{l:>7}" for l in labs))
for l1 in labs:
    row = f"{l1:>4} "
    for l2 in labs:
        d = np.abs(uniq[l1] - uniq[l2]).mean()
        row += f"{d:7.4f}"
    print(row)

print("\nself consistency (each sample vs its label template):")
for lab, v, kind in tiles:
    d = np.abs(v - uniq[lab]).mean()
    print(f"  {kind} {lab}: {d:.5f}")
