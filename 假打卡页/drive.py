# -*- coding: utf-8 -*-
"""
drive.py —— 把 抢单脚本/抢单v1.js 的核心循环用 Python 原样复刻一遍，
对着模拟器里的「假打卡页」真跑：截图 -> 像素判别 -> 点击 -> 等结果。

用途：验证 ①四个页面判别器 ②字形匹配 ③状态机顺序 ④点击坐标
不依赖 AutoJs6，因为这台模拟器是 x86_64，跑不了 AutoJs6。

用法：
  python drive.py --win-round 0 --rounds 2
  python drive.py --win-round 2 --rounds 3
"""
import os, sys, time, subprocess, random
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MUMU = r"D:\Netease\MuMu Player 12\nx_main"
TMP = r"C:\tmp\ljq"
PULL = os.path.join(TMP, "cur.png")
OUTTXT = os.path.join(TMP, "_adb_out.txt")
LOG = os.path.join(TMP, "drive.log")

BW, BH = 720, 1600
MATCH_THR = 0.18
MATCH_MARGIN = 0.08

D_BOX = (72, 465, 647, 1050)
RING_X = [249, 360, 470]
RING_Y = 638
RING_R = 37
CELL_X = [181, 359, 538]
CELL_Y = [863, 976]
DAKA_X, DAKA_Y = 360, 1250


def log(m):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), m)
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def adb(cmd, pull_to=None, timeout=60):
    """所有输出走文件（沙箱禁管道），返回文本"""
    argv = [os.path.join(MUMU, "MuMuManager.exe"), "adb", "-v", "0", "-c", cmd]
    with open(OUTTXT, "wb") as f:
        try:
            subprocess.run(argv, cwd=MUMU, stdout=f, stderr=subprocess.STDOUT,
                           timeout=timeout)
        except subprocess.TimeoutExpired:
            log("adb 超时: " + cmd)
            return ""
    with open(OUTTXT, "rb") as f:
        return f.read().decode("utf-8", "replace")


def grab(name="cur"):
    adb("shell screencap -p /sdcard/%s.png" % name)
    adb("pull /sdcard/%s.png %s" % (name, PULL))
    for _ in range(20):
        if os.path.exists(PULL) and os.path.getsize(PULL) > 1000:
            break
        time.sleep(0.1)
    from PIL import Image
    im = Image.open(PULL).convert("RGB")
    if im.size != (BW, BH):
        im = im.resize((BW, BH))
    return im


def tap(x, y):
    adb("shell input tap %d %d" % (round(x), round(y)))
    return (round(x), round(y))


# ---------------------------------------------------------------- 像素判别（照抄 JS）
def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def isWhite(c):
    return c[0] > 236 and c[1] > 236 and c[2] > 236


def isRed(c):
    return c[0] > 160 and c[1] < 100 and c[2] < 100


def isBlueBtn(c):
    return c[2] > 150 and c[0] < 130 and c[1] < 160 and (c[2] - c[0]) > 60


def dialogPresent(im):
    p = im.load()
    hits = tot = 0
    for x in (90, 630):
        for y in (500, 620, 740, 860, 980, 1030):
            tot += 1
            if isWhite(p[x, y]):
                hits += 1
    return hits >= tot - 2


def successPresent(im):
    p = im.load()
    rows = 0
    y = 700
    while y <= 1120:
        red = 0
        x = 140
        while x <= 580:
            if isRed(p[x, y]):
                red += 1
            if red >= 5:
                rows += 1
                break
            x += 6
        if rows >= 2:
            return True
        y += 6
    return False


def findDakaY(im):
    p = im.load()
    y = 1150
    while y <= 1400:
        blue = cnt = 0
        x = 120
        while x <= 600:
            cnt += 1
            if isBlueBtn(p[x, y]):
                blue += 1
            x += 10
        if blue > cnt * 0.7:
            return y
        y += 6
    return -1


def chevronCentroid(im):
    p = im.load()
    sx = sy = n = 0
    for y in range(952, 1047, 2):
        for x in range(62, 129, 2):
            if lum(p[x, y]) < 130:
                sx += x
                sy += y
                n += 1
    if n < 12:
        return None, n
    return (round(sx / n), round(sy / n)), n


# ---------------------------------------------------------------- 字形
GS = 24


def glyphVec(im, cx, cy, white_ink):
    p = im.load()
    half = 22
    x0, y0 = round(cx - half), round(cy - half)
    w = h = half * 2
    mask = [[0] * w for _ in range(h)]
    minx, maxx, miny, maxy = w, -1, h, -1
    for y in range(h):
        for x in range(w):
            c = p[x0 + x, y0 + y]
            ink = (lum(c) > 205) if white_ink else (lum(c) < 140)
            if ink:
                mask[y][x] = 1
                minx = min(minx, x); maxx = max(maxx, x)
                miny = min(miny, y); maxy = max(maxy, y)
    if maxx < 0 or (maxx - minx) < 4 or (maxy - miny) < 10:
        return None
    gw, gh = maxx - minx + 1, maxy - miny + 1
    out = []
    for gy in range(GS):
        for gx in range(GS):
            sx = minx + (gx * gw) // GS
            sy = miny + (gy * gh) // GS
            out.append(mask[sy][sx])
    return out


def dist(a, b):
    if not a or not b:
        return 1.0
    return sum(1 for i in range(len(a)) if a[i] != b[i]) / len(a)


def readGlyphs(im):
    tops = [glyphVec(im, RING_X[i], RING_Y, True) for i in range(3)]
    cells = [glyphVec(im, CELL_X[c], CELL_Y[r], False) for r in range(2) for c in range(3)]
    for k in range(3):
        if not tops[k]:
            return None, "上方第%d个没切到" % (k + 1)
    for k in range(6):
        if not cells[k]:
            return None, "候选第%d个没切到" % (k + 1)
    return (tops, cells), None


def mapTops(g):
    tops, cells = g
    used, order, scores = {}, [], []
    for i in range(3):
        cand = sorted([(dist(tops[i], cells[j]), j) for j in range(6) if j not in used])
        if not cand:
            return None, "候选不够"
        d0, j0 = cand[0]
        d1 = cand[1][0] if len(cand) > 1 else 1.0
        if d0 > MATCH_THR:
            return None, "第%d个匹配不上 %.2f" % (i + 1, d0)
        if d1 - d0 < MATCH_MARGIN:
            return None, "第%d个有歧义 %.2f/%.2f" % (i + 1, d0, d1)
        used[j0] = True
        order.append(j0)
        scores.append(d0)
    return (order, scores), None


def tapCell(idx):
    r, c = idx // 3, idx % 3
    tap(CELL_X[c] + random.uniform(-4, 4), CELL_Y[r] + random.uniform(-4, 4))


def ringGreen(im, i):
    p = im.load()
    cx, cy, rr = RING_X[i], RING_Y, RING_R - 8
    for (x, y) in ((cx - rr, cy), (cx + rr, cy), (cx, cy - rr), (cx, cy + rr)):
        c = p[x, y]
        if c[1] > 150 and (c[1] - c[2]) > 40 and (c[1] - c[0]) > 60:
            return True
    return False


def waitOutcome(im, limit_s=10.0):
    t0 = time.time()
    closed_at = 0
    while time.time() - t0 < limit_s:
        time.sleep(0.35)
        im = grab()
        if successPresent(im):
            return "win", im
        if dialogPresent(im):
            closed_at = 0
        else:
            if not closed_at:
                closed_at = time.time()
            elif time.time() - closed_at > 0.6:
                return "nomatch", im
    return "timeout", im


def launch(win_round, seed):
    adb("shell am force-stop org.jg.mockdaka")
    time.sleep(0.4)
    adb("shell pm clear org.jg.mockdaka")
    adb("shell am start -n org.jg.mockdaka/.MainActivity --ei winRound %d --ei seed %d"
        % (win_round, seed))
    time.sleep(1.5)


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--win-round", type=int, default=0)
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no-launch", action="store_true")
    a = ap.parse_args()

    open(LOG, "w", encoding="utf-8").close()
    log("=== drive.py 开始 winRound=%d rounds=%d seed=%d ===" % (a.win_round, a.rounds, a.seed))
    if not a.no_launch:
        launch(a.win_round, a.seed)

    rounds = miss_streak = 0
    while rounds < a.rounds:
        rounds += 1
        im = grab()
        if successPresent(im):
            log("第%d轮开始前就检测到成功窗（不该出现）" % rounds)
            return
        if dialogPresent(im):
            log("第%d轮：残留验证码弹窗，先处理" % rounds)
        y = findDakaY(im)
        log("第%d轮：findDakaY=%d dialog=%s success=%s"
            % (rounds, y, dialogPresent(im), successPresent(im)))
        if y < 0:
            cen, n = chevronCentroid(im)
            if cen:
                log("  结果卡：点 ‹ 重心%s（%d 点）" % (str(cen), n))
                tap(cen[0], cen[1])
            else:
                log("  结果卡：暗点只有 %d，用兜底坐标 (94,1010)" % n)
                tap(94, 1010)
            time.sleep(1.0)
            rounds -= 1
            continue

        tap(DAKA_X, y)
        log("  点了集港打卡 (%d,%d)" % (DAKA_X, y))

        # 等验证码弹窗（WAIT_DIALOG_MS 4000）
        t0 = time.time()
        got = False
        while time.time() - t0 < 4.0:
            time.sleep(0.3)
            im2 = grab()
            if successPresent(im2):
                log("*** 抢到！停止，绝不点确认 ***")
                return
            if dialogPresent(im2):
                got = True
                break
        if not got:
            log("  没等到验证码弹窗")
            continue

        g, err = readGlyphs(im2)
        if err:
            log("  识别失败：" + err)
            continue
        m, err = mapTops(g)
        if err:
            log("  匹配失败：" + err)
            continue
        order, scores = m
        log("  顺序 按钮#%s  差异 %s" % ("-".join(str(i + 1) for i in order),
                                    "/".join("%.2f" % s for s in scores)))
        for i in range(3):
            pre = grab()
            if successPresent(pre):
                log("*** 抢到！停止 ***")
                return
            tapCell(order[i])
            time.sleep(random.uniform(0.62, 0.90))
            post = grab()
            if successPresent(post):
                log("*** 抢到！停止 ***")
                return
            if i < 2 and not ringGreen(post, i):
                log("  第%d下没变绿（可能点空）" % (i + 1))
            elif i == 2:
                log("  第3下后弹窗已关闭（提交中），不查变绿")

        r, im3 = waitOutcome(pop_im := grab())
        log("  结果：%s" % r)
        if r == "win":
            log("*** 抢到！停止，绝不点确认 ***")
            return
        if r == "nomatch":
            miss_streak += 1
        time.sleep(0.8)

    log("=== 跑完 %d 轮，结束 ===" % rounds)


if __name__ == "__main__":
    main()
