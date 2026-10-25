# -*- coding: utf-8 -*-
"""用抢单v1.js / Vision.java 里同一套判别器，跑司机真机三张干净截图"""
import sys
from PIL import Image

BASE_W = 720.0


def load(p):
    return Image.open(p).convert('RGB')


def mk(im):
    W, H = im.size
    px = im.load()
    s = W / BASE_W
    def at(x720, y720):
        x = int(x720 * s); y = int(y720 * s)
        x = max(0, min(W - 1, x)); y = max(0, min(H - 1, y))
        return px[x, y]
    def raw(x, y):
        return px[max(0, min(W - 1, x)), max(0, min(H - 1, y))]
    return W, H, s, at, raw


def is_white(c):
    return c[0] > 236 and c[1] > 236 and c[2] > 236


def is_red(c):
    r, g, b = c
    return r > 160 and g < 100 and b < 100


def is_blue_btn(c):
    r, g, b = c
    return b > 150 and r < 130 and g < 160 and (b - r) > 60


def lum(c):
    return (299 * c[0] + 587 * c[1] + 114 * c[2]) // 1000


def dialog_present(at):
    hits = 0
    for y in (500, 620, 740, 860, 980, 1030):
        for x in (90, 630):
            if is_white(at(x, y)):
                hits += 1
    return hits, hits >= 10


def find_daka_y(at):
    for y in range(1150, 1400, 6):
        cnt = 0; blue = 0
        for x in range(120, 600, 10):
            cnt += 1
            if is_blue_btn(at(x, y)):
                blue += 1
        if cnt and blue > cnt * 0.7:
            return y
    return -1


def success_present(at):
    red_rows = 0
    detail = []
    for y in range(700, 1120, 6):
        n = 0
        for x in range(140, 580, 6):
            if is_red(at(x, y)):
                n += 1
        if n >= 5:
            red_rows += 1
            detail.append((y, n))
    return red_rows, red_rows >= 2, detail[:6]


def chevron(raw, s):
    n = 0; sx = 0; sy = 0
    for y in range(int(952 * s), int(1046 * s), 2):
        for x in range(int(62 * s), int(128 * s), 2):
            if lum(raw(x, y)) < 130:
                n += 1; sx += x; sy += y
    if n >= 12:
        return n, (sx // n, sy // n)
    return n, None


for p in sys.argv[1:]:
    im = load(p)
    W, H, s, at, raw = mk(im)
    dp = dialog_present(at)
    dy = find_daka_y(at)
    sp = success_present(at)
    ch = chevron(raw, s)
    print(p.split('\\')[-1], f'{W}x{H}')
    print(f'  dialogPresent hits={dp[0]} -> {dp[1]}')
    print(f'  findDakaY = {dy}')
    print(f'  successPresent redRows={sp[0]} -> {sp[1]}' + (f' 头几行 {sp[2]}' if sp[2] else ''))
    print(f'  chevron 暗点 = {ch[0]} 重心 = {ch[1]}')
