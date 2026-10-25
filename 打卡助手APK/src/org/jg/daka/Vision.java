package org.jg.daka;

import android.graphics.Bitmap;

/**
 * 全部识别逻辑。坐标一律用 720×1600 基准，运行时按 screenshotWidth/720 缩放。
 * 与 抢单v1.js / drive.py 里的实现一一对应。
 */
public class Vision {
    public static final int BASE_W = 720, BASE_H = 1600;

    static final int RING_Y = 638, RING_R = 37;
    static final int[] RING_X = {249, 360, 470};
    static final int[] CELL_X = {181, 359, 538};
    static final int[] CELL_Y = {863, 976};

    static final double MATCH_THR = 0.18;
    static final double MATCH_MARGIN = 0.08;

    public static String lastReason = "";
    public static double[] lastBest = new double[3];

    /** 一张截图的像素缓存 + 坐标换算 */
    public static class Shot {
        public final int w, h;
        public final int[] px;
        public final double s;

        public Shot(Bitmap bm) {
            w = bm.getWidth();
            h = bm.getHeight();
            px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            s = w / (double) BASE_W;
        }

        public int X(int bx) {
            int v = (int) Math.round(bx * s);
            return v < 0 ? 0 : (v >= w ? w - 1 : v);
        }

        public int Y(int by) {
            int v = (int) Math.round(by * s);
            return v < 0 ? 0 : (v >= h ? h - 1 : v);
        }

        public int at(int x, int y) { return px[y * w + x]; }

        public int lum(int x, int y) {
            int c = px[y * w + x];
            return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
        }
    }

    // ---------- 颜色 ----------
    public static boolean isWhite(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 236 && g > 236 && b > 236;
    }

    public static boolean isRed(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 160 && g < 100 && b < 100;
    }

    public static boolean isBlueBtn(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return b > 150 && r < 130 && g < 160 && (b - r) > 60;
    }

    public static boolean isGreen(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return g > 150 && (g - b) > 40 && (g - r) > 60;
    }

    // ---------- 四个判别器 ----------
    /** 验证码弹窗在不在（白框上的白点） */
    public static boolean dialogPresent(Shot s) {
        int[] ys = {500, 620, 740, 860, 980, 1030};
        int[] xs = {90, 630};
        int hits = 0;
        for (int by : ys) for (int bx : xs) if (isWhite(s.at(s.X(bx), s.Y(by)))) hits++;
        return hits >= 10;
    }

    /** 成功弹窗的红「确认」按钮在不在 */
    public static boolean successPresent(Shot s) {
        int rows = 0;
        for (int by = 700; by <= 1120; by += 6) {
            int cnt = 0;
            for (int bx = 140; bx <= 580; bx += 6) if (isRed(s.at(s.X(bx), s.Y(by)))) cnt++;
            if (cnt >= 5) {
                rows++;
                if (rows >= 2) return true;
            }
        }
        return false;
    }

    /** 找蓝色「集港打卡」按钮，返回基准 y；找不到 -1 */
    public static int findDakaY(Shot s) {
        int samples = 0;
        for (int bx = 120; bx <= 600; bx += 10) samples++;
        for (int by = 1150; by <= 1400; by += 6) {
            int cnt = 0;
            for (int bx = 120; bx <= 600; bx += 10) if (isBlueBtn(s.at(s.X(bx), s.Y(by)))) cnt++;
            if (cnt > samples * 0.7) return by;
        }
        return -1;
    }

    /** 结果卡左上「‹」的重心（像素坐标）；没有返回 null */
    public static int[] chevron(Shot s) {
        int n = 0;
        long sx = 0, sy = 0;
        for (int by = 952; by <= 1046; by += 2) {
            for (int bx = 62; bx <= 128; bx += 2) {
                int X = s.X(bx), Y = s.Y(by);
                if (s.lum(X, Y) < 130) {
                    n++;
                    sx += X;
                    sy += Y;
                }
            }
        }
        if (n < 12) return null;
        return new int[]{(int) (sx / n), (int) (sy / n), n};
    }

    /** 第 idx 个圆圈有没有变绿 */
    public static boolean ringGreen(Shot s, int idx) {
        int cx = RING_X[idx], cy = RING_Y, rr = RING_R - 8;
        int[][] pts = {{cx, cy - rr}, {cx, cy + rr}, {cx - rr, cy}, {cx + rr, cy}};
        for (int[] p : pts) if (isGreen(s.at(s.X(p[0]), s.Y(p[1])))) return true;
        return false;
    }

    // ---------- 字形 ----------
    static boolean[] vec(Shot s, int bcx, int bcy, boolean whiteInk) {
        int x0 = s.X(bcx - 22), x1 = s.X(bcx + 22), y0 = s.Y(bcy - 22), y1 = s.Y(bcy + 22);
        int W = x1 - x0 + 1, H = y1 - y0 + 1;
        if (W <= 0 || H <= 0) return null;
        boolean[] ink = new boolean[W * H];
        int minX = 1 << 30, maxX = -1, minY = 1 << 30, maxY = -1;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int l = s.lum(x0 + x, y0 + y);
                boolean on = whiteInk ? (l > 205) : (l < 140);
                ink[y * W + x] = on;
                if (on) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return null;
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        if (bw < 4 * s.s || bh < 10 * s.s) return null;
        boolean[] v = new boolean[24 * 24];
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 24; i++) {
                int xx = minX + (int) Math.round((bw - 1) * (i / 23.0));
                int yy = minY + (int) Math.round((bh - 1) * (j / 23.0));
                v[j * 24 + i] = ink[yy * W + xx];
            }
        }
        return v;
    }

    static double dist(boolean[] a, boolean[] b) {
        int bad = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) bad++;
        return bad / (double) a.length;
    }

    static String f(double d) { return String.format(java.util.Locale.US, "%.3f", d); }

    /** 返回三个答案的候选下标（0..5），失败返回 null 并把原因写进 lastReason */
    public static int[] solve(Shot s) {
        lastReason = "";
        boolean[][] tops = new boolean[3][];
        for (int i = 0; i < 3; i++) {
            tops[i] = vec(s, RING_X[i], RING_Y, true);
            if (tops[i] == null) {
                lastReason = "上方第" + (i + 1) + "个字取不到";
                return null;
            }
        }
        boolean[][] cand = new boolean[6][];
        for (int j = 0; j < 6; j++) {
            cand[j] = vec(s, CELL_X[j % 3], CELL_Y[j / 3], false);
            if (cand[j] == null) {
                lastReason = "候选第" + (j + 1) + "格取不到";
                return null;
            }
        }
        double[][] d = new double[3][6];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 6; j++) d[i][j] = dist(tops[i], cand[j]);

        boolean[] used = new boolean[6];
        int[] ans = new int[3];
        for (int i = 0; i < 3; i++) {
            int bj = -1;
            double bd = 9;
            for (int j = 0; j < 6; j++) if (!used[j] && d[i][j] < bd) { bd = d[i][j]; bj = j; }
            if (bj < 0) { lastReason = "候选不够分"; return null; }
            double d1 = 9;
            for (int j = 0; j < 6; j++) if (!used[j] && j != bj && d[i][j] < d1) d1 = d[i][j];
            if (bd > MATCH_THR) { lastReason = "第" + (i + 1) + "个字匹配不上 d=" + f(bd); return null; }
            if (d1 - bd < MATCH_MARGIN) { lastReason = "第" + (i + 1) + "个字有歧义 d=" + f(bd) + " d1=" + f(d1); return null; }
            used[bj] = true;
            ans[i] = bj;
            lastBest[i] = bd;
        }
        return ans;
    }
}
