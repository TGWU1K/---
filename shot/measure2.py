import numpy as np
from PIL import Image

im = Image.open(r"C:\Users\admin\Desktop\连点器\shot\native720.jpg").convert("RGB")
a = np.asarray(im).astype(int)
H, W, _ = a.shape
r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

X0, X1, Y0, Y1 = 72, 647, 465, 1050   # dialog white box
def sub(mask):
    m = np.zeros_like(mask)
    m[Y0:Y1, X0:X1] = mask[Y0:Y1, X0:X1]
    return m

blue = sub((b > 180) & (r < 120) & (g > 70) & (g < 190))
ys, xs = np.nonzero(blue)
print("blue bbox", xs.min(), xs.max(), ys.min(), ys.max(), "n", len(xs))
cols = sorted(set(xs.tolist()))
# split into 3 groups
groups, cur = [], [cols[0]]
for c in cols[1:]:
    if c - cur[-1] > 25:
        groups.append((cur[0], cur[-1])); cur = [c]
    else:
        cur.append(c)
groups.append((cur[0], cur[-1]))
print("blue col groups", groups)
for gi, (c0, c1) in enumerate(groups, 1):
    m = blue.copy(); m[:, :c0] = False; m[:, c1+1:] = False
    yy, xx = np.nonzero(m)
    print(f"  circle{gi}: cx={xx.mean():.0f} cy={yy.mean():.0f} "
          f"x[{xx.min()}-{xx.max()}] y[{yy.min()}-{yy.max()}] "
          f"r~{(xx.max()-xx.min()+1)/2:.0f}")

# gray button cells: uniform light gray
gray = (np.abs(r-g) < 8) & (np.abs(g-b) < 8) & (r > 232) & (r < 250)
gray = sub(gray)
yy, xx = np.nonzero(gray)
rows = sorted(set(yy.tolist()))
rbands, cur = [], [rows[0]]
for y in rows[1:]:
    if y - cur[-1] > 12:
        rbands.append((cur[0], cur[-1])); cur = [y]
    else:
        cur.append(y)
rbands.append((cur[0], cur[-1]))
print("button row bands", rbands)

dark = sub((r < 120) & (g < 120) & (b < 120))
for ri, (y0, y1) in enumerate(rbands, 1):
    m = gray.copy(); m[:y0] = False; m[y1+1:] = False
    yy2, xx2 = np.nonzero(m)
    cs = sorted(set(xx2.tolist()))
    cbands, cur = [], [cs[0]]
    for x in cs[1:]:
        if x - cur[-1] > 12:
            cbands.append((cur[0], cur[-1])); cur = [x]
        else:
            cur.append(x)
    cbands.append((cur[0], cur[-1]))
    print(f"row{ri} y[{y0}-{y1}] cy={(y0+y1)//2} cells:", [
        (f"x{c0}-{c1}", f"cx{(c0+c1)//2}") for c0, c1 in cbands])
    for ci, (c0, c1) in enumerate(cbands, 1):
        m = dark.copy(); m[:y0] = False; m[y1+1:] = False
        m[:, :c0] = False; m[:, c1+1:] = False
        yy3, xx3 = np.nonzero(m)
        if len(xx3):
            print(f"    cell{ri}{ci} digit cx={xx3.mean():.0f} cy={yy3.mean():.0f} "
                  f"h={yy3.max()-yy3.min()+1} w={xx3.max()-xx3.min()+1}")
