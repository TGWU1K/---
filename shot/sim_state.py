# -*- coding: utf-8 -*-
"""
用 218 张真机帧（两段录屏 + 720 截图）回归验证首页状态判别：
  dialogPresent / findDakaY / successPresent / screenDark / tapChevron(只算命中点)
逻辑逐行照抄 抢单脚本/抢单v1.js 的 Python 版，目的是找「误判」。
输出每帧一行 CSV + 汇总。
"""
import os, glob, sys
from PIL import Image

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BW, BH = 720, 1600

def rgb(c): return ((c >> 16) & 255, (c >> 8) & 255, c & 255)
def lum(r, g, b): return 0.299 * r + 0.587 * g + 0.114 * b
def isWhite(t): return t[0] > 236 and t[1] > 236 and t[2] > 236
def isRed(t): return t[0] > 160 and t[1] < 100 and t[2] < 100
def isBlueBtn(t): return t[2] > 150 and t[0] < 130 and t[1] < 160 and (t[2] - t[0]) > 60

class Img:
    def __init__(self, path):
        im = Image.open(path).convert("RGB")
        if im.size != (BW, BH):
            im = im.resize((BW, BH))
        self.p = im.load()
        self.w, self.h = im.size
    def pix(self, x, y):
        x, y = int(round(x)), int(round(y))
        if x < 0 or y < 0 or x >= self.w or y >= self.h: return None
        return self.p[x, y]

def dialogPresent(im):
    hits = tot = 0
    xs = [90, 630]; ys = [500, 620, 740, 860, 980, 1030]
    for x in xs:
        for y in ys:
            tot += 1
            c = im.pix(x, y)
            if c is not None and isWhite(c): hits += 1
    return hits >= tot - 2, hits

def successPresent(im):
    rows = 0
    for y in range(700, 1121, 6):
        red = 0
        for x in range(140, 581, 6):
            c = im.pix(x, y)
            if c is not None and isRed(c): red += 1
            if red >= 5:
                rows += 1
                break
        if rows >= 2: return True, rows
    return False, rows

def findDakaY(im):
    for y in range(1150, 1401, 6):
        blue = cnt = 0
        for x in range(120, 601, 10):
            cnt += 1
            c = im.pix(x, y)
            if c is not None and isBlueBtn(c): blue += 1
        if blue > cnt * 0.7: return y
    return -1

def screenDark(im):
    s = n = 0
    for y in range(300, 1201, 120):
        for x in range(80, 641, 120):
            c = im.pix(x, y)
            if c is None: continue
            s += lum(*c); n += 1
    return (s / n) < 120 if n else True

def chevronHits(im):
    n = 0
    for y in range(940, 1101, 4):
        for x in range(60, 136, 3):
            c = im.pix(x, y)
            if c is not None and lum(*c) < 130: n += 1
    return n

def main():
    files = []
    for d in ["frames", "f5", "v2", "v2f"]:
        files += sorted(glob.glob(os.path.join(BASE, d, "*.png")))
    files += [os.path.join(BASE, "shot", "native720.jpg")]
    files += sorted(glob.glob(os.path.join(BASE, "*.png")))
    rows = []
    fake_win = []
    for f in files:
        try:
            im = Img(f)
        except Exception as e:
            print("skip", f, e); continue
        dp, dh = dialogPresent(im)
        sp, sr = successPresent(im)
        dy = findDakaY(im)
        dark = screenDark(im)
        ch = chevronHits(im)
        rel = os.path.relpath(f, BASE)
        rows.append((rel, dp, dh, sp, sr, dy, dark, ch))
        if sp: fake_win.append(rel)
    out = os.path.join(BASE, "shot", "state_report.csv")
    with open(out, "w", encoding="utf-8") as fh:
        fh.write("file,dialog,hits,success,redrows,dakaY,dark,chev\n")
        for r in rows:
            fh.write(",".join(str(v) for v in r) + "\n")
    n = len(rows)
    print("frames:", n)
    print("dialogPresent TRUE :", sum(1 for r in rows if r[1]))
    print("successPresent TRUE:", sum(1 for r in rows if r[3]), fake_win[:10])
    print("findDakaY  >=0     :", sum(1 for r in rows if r[5] >= 0))
    print("screenDark TRUE    :", sum(1 for r in rows if r[6]))
    print("chevron>=5 点      :", sum(1 for r in rows if r[7] >= 5))
    print("report ->", out)

if __name__ == "__main__":
    main()
