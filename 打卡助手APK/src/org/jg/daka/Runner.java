package org.jg.daka;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.Locale;
import java.util.Random;

/** 抢单主循环 */
public class Runner implements Runnable {

    public static volatile Runner I;

    public volatile boolean stopFlag = false;
    public volatile boolean running = false;
    public volatile String state = "待机";
    public volatile String lastWin = "";

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    private int rounds = 0, missStreak = 0, failStreak = 0, idleStreak = 0, notInApp = 0, mineTicks = 0, idleWait = 0;
    private int mockLaunches = 0;
    private int diagSaves = 0;
    private int maxRounds = 40;

    private Runner(Context c) { app = c; }

    public static synchronized void start(Context c) {
        if (I != null && I.running) return;
        Runner r = new Runner(c.getApplicationContext());
        I = r;
        r.running = true;
        new Thread(r, "daka-runner").start();
    }

    public static synchronized void stop() {
        if (I != null) I.stopFlag = true;
    }

    public static boolean isRunning() { return I != null && I.running; }

    // ---------------- 状态 ----------------
    private void setState(String s) { setState(s, Floaty.COL_RUN); }

    private void setState(final String s, final int col) {
        state = s;
        Fx.log(app, "[状态] " + s);
        ui.post(new Runnable() {
            public void run() {
                Floaty.set(s, col);
                MainActivity.refreshIfVisible();
            }
        });
    }

    // ---------------- 主循环 ----------------
    @Override
    public void run() {
        maxRounds = Cfg.rounds(app);
        rounds = missStreak = failStreak = idleStreak = notInApp = idleWait = 0;
        Fx.log(app, "=== 开始抢单：上限 " + maxRounds + " 轮，轮间隔 " + Cfg.gap(app) + "ms ===");
        setState("运行中");
        String stopReason = null;
        boolean won = false;

        try {
            while (!stopFlag) {
                if (rounds >= maxRounds) { stopReason = "到轮数上限 " + maxRounds; break; }
                if (!DakaService.alive()) { stopReason = "无障碍服务断了"; break; }

                String pkg = DakaService.I.currentPkg();
                boolean mine = pkg != null && pkg.equals(app.getPackageName());
                if (!mine && !Cfg.isAllowedPkg(app, pkg)) {
                    notInApp++;
                    // 还没跑第一轮时，用户可能正在去打卡页的路上：给足时间，别刚开就停
                    int limit = (rounds == 0) ? 300 : 20;
                    if (notInApp == 1 || notInApp % 5 == 0) {
                        setState(rounds == 0 ? "等你打开微信打卡页…" : ("不在微信（" + pkg + "）"), Floaty.COL_WARN);
                        Fx.log(app, "前台包名=" + pkg + "，等待切换到微信（" + notInApp + "/" + limit + "）");
                    }
                    if (notInApp > limit) { stopReason = "当前窗口不是微信：" + pkg; break; }
                    sleep(1000);
                    continue;
                }
                notInApp = 0;

                // 停在自己的界面上（用户刚点了开始）：不在自己家的页面上瞎点，等切到微信
                if (mine) {
                    mineTicks++;
                    if (mineTicks == 1 || mineTicks % 10 == 0) {
                        setState("等待切到微信…", Floaty.COL_WARN);
                        Fx.log(app, "前台是自己（" + pkg + "），等切到微信");
                    }
                    // 测试模式：自己把自己切走后，主动把「假打卡页」叫到前台，省得手动来回切
                    if (Cfg.testAny(app) && mockLaunches >= 0 && mockLaunches < 3 && mineTicks % 4 == 2) {
                        mockLaunches++;
                        try {
                            Intent mi = app.getPackageManager().getLaunchIntentForPackage("org.jg.mockdaka");
                            if (mi != null) {
                                mi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                app.startActivity(mi);
                                Fx.log(app, "测试模式：自动打开假打卡页");
                            } else {
                                mockLaunches = -1;   // 没装假打卡页，不再尝试
                            }
                        } catch (Throwable t) {
                            mockLaunches = -1;
                        }
                    }
                    sleep(1000);
                    continue;
                }
                mineTicks = 0;

                String r = oneRound();
                Fx.log(app, "第 " + rounds + " 轮 → " + r);

                if ("win".equals(r)) { won = true; break; }

                if ("timeout".equals(r) || "nofind".equals(r) || "shotfail".equals(r)) {
                    failStreak++;
                    if (failStreak >= Cfg.FAIL_STREAK_MAX) { stopReason = "连续 " + failStreak + " 次异常"; break; }
                } else {
                    failStreak = 0;
                }

                if ("nomatch".equals(r)) {
                    missStreak++;
                    if (missStreak >= Cfg.MISS_STREAK_MAX) { stopReason = "连续 " + missStreak + " 轮没中"; break; }
                }

                if ("idle".equals(r)) {
                    if (rounds == 0) {
                        // 一轮都还没跑成：多半是打卡页还没打开，继续等，不算「一直找不到」
                        idleStreak = 0;
                        idleWait++;
                        if (idleWait % 10 == 1) Fx.log(app, "还没看到「集港打卡」按钮，继续等（把打卡页打开就行）");
                        if (idleWait > 120) { stopReason = "一直没看到打卡页"; break; }
                    } else {
                        idleStreak++;
                        if (idleStreak >= Cfg.IDLE_STREAK_MAX) { stopReason = "一直找不到「集港打卡」按钮"; break; }
                    }
                } else {
                    idleStreak = 0;
                }

                sleep(Cfg.gap(app));
            }
        } catch (Throwable t) {
            Fx.log(app, "主循环异常：" + t);
            stopReason = "异常 " + t;
        }

        running = false;
        if (won) {
            setState("已抢到！请手动点确认", Floaty.COL_WIN);
        } else if (stopReason != null) {
            setState("已停止 · " + stopReason, Floaty.COL_IDLE);
        } else {
            setState("已停止", Floaty.COL_IDLE);
        }
        Fx.log(app, "=== 结束（" + (won ? "抢到" : String.valueOf(stopReason)) + "）共 " + rounds + " 轮 ===");
        KeepAliveService.stopSelf(app);
        ui.post(new Runnable() {
            public void run() { MainActivity.refreshIfVisible(); }
        });
    }

    // ---------------- 一轮 ----------------
    private String oneRound() {
        Bitmap bm = shoot();
        if (bm == null) return "shotfail";
        Vision.Shot s = new Vision.Shot(bm);

        // 先看有没有抢到（每次动作前都查，绝不漏判成"点确认"）
        if (Vision.successPresent(s)) {
            win(bm);
            return "win";
        }

        int dy = Vision.findDakaY(s);
        if (dy < 0) {
            int[] ch = Vision.chevron(s);
            boolean dlg = Vision.dialogPresent(s);
            if (ch != null && !dlg) {
                Fx.log(app, "结果卡：点「‹」(" + ch[0] + "," + ch[1] + ") 暗点 " + ch[2]);
                bm.recycle();
                tapPx(ch[0], ch[1]);
                sleep(400);
                return "miss";
            }
            if (ch == null && !dlg) Fx.log(app, "认不出页面，诊断 " + Vision.diag(s));
            bm.recycle();
            if (dlg) return "idle";           // 弹窗还在，交给下一轮
            return "idle";
        }

        rounds++;
        setState("第 " + rounds + " 轮 · 点打卡");
        int tx = s.X(360), ty = s.Y(dy + 25);
        bm.recycle();
        tapPx(tx, ty);

        if (!waitDialog(3000)) {
            // 第一下可能被动画/遮罩吃掉，再点一次（此时若弹窗已开，点在遮罩上不会误事）
            Fx.log(app, "第一次点完没看到弹窗，再点一次");
            Bitmap again = shoot();
            if (again != null) {
                Vision.Shot s2 = new Vision.Shot(again);
                int dy2 = Vision.findDakaY(s2);
                if (dy2 >= 0) {
                    int tx2 = s2.X(360), ty2 = s2.Y(dy2 + 25);
                    again.recycle();
                    tapPx(tx2, ty2);
                } else {
                    again.recycle();
                }
            }
            if (!waitDialog(3000)) {
                Fx.log(app, "等不到验证码弹窗");
                diagShot("等不到弹窗_轮" + rounds);
                return "timeout";
            }
        }
        return handleCaptcha();
    }

    /** 等验证码弹窗出现；看到时把那一帧和时刻留在 dlgFrame / dlgSeenAt 里给认字复用 */
    private boolean waitDialog(int ms) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            if (stopFlag) return false;
            Bitmap b = shoot();
            long tGot = System.currentTimeMillis();
            if (b != null) {
                Vision.Shot s = new Vision.Shot(b);
                boolean d = Vision.dialogPresent(s);
                if (d) {
                    dropDlgFrame();
                    dlgFrame = b;          // 不 recycle，交给 handleCaptcha 认字
                    dlgSeenAt = tGot;
                    return true;
                }
                b.recycle();
            }
            sleep(250);
        }
        return false;
    }

    /** 认字 + 按「答题总时长」预算点三格 + 等结果 */
    private String handleCaptcha() {
        Bitmap b = dlgFrame;
        long seenAt = dlgSeenAt;
        dlgFrame = null;
        dlgSeenAt = 0;
        if (b == null) {
            b = shoot();
            seenAt = System.currentTimeMillis();
        }
        if (b == null) return "shotfail";

        Vision.Shot s = new Vision.Shot(b);
        int[] ans = Vision.solve(s);
        if (ans == null) {
            Fx.log(app, "识别失败：" + Vision.lastReason);
            b.recycle();
            closeDialog();
            return "nofind";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(i == 0 ? "" : "-").append(ans[i] + 1);
        Fx.log(app, "识别 ok：" + sb + " 差异 " + Vision.f(Vision.lastBest[0]) + "/"
                + Vision.f(Vision.lastBest[1]) + "/" + Vision.f(Vision.lastBest[2]));

        int[][] cells = new int[3][2];
        for (int i = 0; i < 3; i++) {
            cells[i][0] = s.X(Vision.CELL_X[ans[i] % 3] + jit());
            cells[i][1] = s.Y(Vision.CELL_Y[ans[i] / 3] + jit());
        }
        b.recycle();

        // ---- 把"总时长"排成三个绝对时刻：读题 | 第1→2 | 第2→3 ----
        int T = Cfg.budgetMs(app);
        int[] pl = Cfg.plan(T);
        // 每段 ±12% 抖动，再按总额归一，避免"每次都一模一样"的机器指纹
        pl[0] = (int) Math.round(pl[0] * (1 + (rnd.nextDouble() * 2 - 1) * 0.12));
        pl[1] = (int) Math.round(pl[1] * (1 + (rnd.nextDouble() * 2 - 1) * 0.12));
        pl[2] = (int) Math.round(pl[2] * (1 + (rnd.nextDouble() * 2 - 1) * 0.12));
        int sum = pl[0] + pl[1] + pl[2];
        int read = sum > 0 ? (int) Math.round((double) pl[0] * T / sum) : pl[0];
        int g1 = sum > 0 ? (int) Math.round((double) pl[1] * T / sum) : pl[1];
        int g2 = T - read - g1;
        if (read < 150) read = 150;
        if (g1 < 80) g1 = 80;
        if (g2 < 80) g2 = 80;

        boolean tight = false;
        long now = System.currentTimeMillis();
        if (now - seenAt > read) {      // 取帧/认字拖过头，预算已经不够了
            tight = true;
            Fx.log(app, "预算不足：识别用了 " + (now - seenAt) + "ms（目标 " + T + "ms），立即开点");
            seenAt = now - 120;
        }
        long d1 = seenAt + read;
        long d2 = d1 + g1;
        long d3 = d2 + g2;

        if (stopFlag) return "idle";
        waitUntil(d1);
        tapPx(cells[0][0], cells[0][1]);
        if (stopFlag) return "idle";
        waitUntil(d2);
        tapPx(cells[1][0], cells[1][1]);

        // 校准模式（预算之外，多花约 1.1 秒）：核对前两下有没有变绿
        if (Cfg.calib(app)) {
            Bitmap b2 = svcFresh();
            if (b2 != null) {
                Vision.Shot s2 = new Vision.Shot(b2);
                int bad = 0;
                for (int i = 0; i < 2; i++) if (!Vision.ringGreen(s2, i)) bad++;
                b2.recycle();
                Fx.log(app, bad == 0 ? "校准：前两个圆圈都变绿了" : ("校准：有 " + bad + " 个圆圈没变绿"));
            }
        }

        if (stopFlag) return "idle";
        waitUntil(d3);
        tapPx(cells[2][0], cells[2][1]);

        long done = System.currentTimeMillis();
        Fx.log(app, "验证码 目标" + T + "ms 实际" + (done - seenAt) + "ms 读题"
                + read + "/间隔" + g1 + "+" + g2 + (tight ? "（预算不足）" : "")
                + (Cfg.calib(app) ? "（含校核）" : ""));
        return waitOutcome();
    }

    /** 等这一轮的结果 */
    private String waitOutcome() {
        long t0 = System.currentTimeMillis();
        long goneSince = 0;
        while (System.currentTimeMillis() - t0 < 10000) {
            if (stopFlag) return "idle";
            Bitmap b = shoot();
            if (b == null) { sleep(200); continue; }
            Vision.Shot s = new Vision.Shot(b);
            if (Vision.successPresent(s)) {
                win(b);
                return "win";
            }
            boolean dlg = Vision.dialogPresent(s);
            b.recycle();
            if (!dlg) {
                if (goneSince == 0) goneSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - goneSince > 600) return "nomatch";
            } else {
                goneSince = 0;
            }
            sleep(260);
        }
        return "timeout";
    }

    /** 识别失败时把弹窗关掉（点右上 X，不行就按返回） */
    private void closeDialog() {
        DakaService svc = DakaService.I;
        if (svc == null) return;
        double k = svc.scale();
        tapPx((int) Math.round(600 * k), (int) Math.round(500 * k));
        sleep(700);
        Bitmap b = shoot();
        boolean still = false;
        if (b != null) {
            Vision.Shot s = new Vision.Shot(b);
            still = Vision.dialogPresent(s);
            b.recycle();
        }
        if (still) {
            Fx.log(app, "X 没关掉，按返回键");
            svc.back();
            sleep(500);
        }
    }

    // ---------------- 抢到 ----------------
    private void win(Bitmap bm) {
        stopFlag = true;
        lastWin = "已抢到";
        setState("已抢到！请手动点确认", Floaty.COL_WIN);
        Fx.log(app, "*** 抢到单！已停手，绝不点「确认」 ***");
        String where = Fx.savePng(app, bm, "抢到");
        Fx.log(app, "截图已存：" + where);
        try { bm.recycle(); } catch (Throwable t) { }

        try {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("集港助手", "已抢到单，请手动点确认。截图在相册：" + where));
        } catch (Throwable t) { }

        ring();
    }

    private void ring() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(app, u);
            for (int i = 0; i < 3; i++) {
                if (r != null) { try { r.play(); } catch (Throwable t) { } }
                vibrate(600);
                rawSleep(900);
                if (r != null) { try { r.stop(); } catch (Throwable t) { } }
                rawSleep(300);
            }
        } catch (Throwable t) { }
        vibrate(1000);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Throwable t) { }
    }

    // ---------------- 工具 ----------------
    private int a11yBadCount = 0;
    private boolean a11yModeBad = false;
    private boolean probed = false;

    /** 弹窗第一次被看到的那一帧 + 看到它的时刻（答题预算从这里起算） */
    private Bitmap dlgFrame = null;
    private long dlgSeenAt = 0;

    /** 睡到某个绝对时刻（用于把三段间隔对齐到预算） */
    private void waitUntil(long t) {
        long d;
        while ((d = t - System.currentTimeMillis()) > 0) {
            if (stopFlag) return;
            sleep((int) Math.min(d, 50));
        }
    }

    private void dropDlgFrame() {
        if (dlgFrame != null) {
            try { dlgFrame.recycle(); } catch (Throwable t) { }
            dlgFrame = null;
        }
        dlgSeenAt = 0;
    }

    /**
     * 抓一张当前画面。无障碍通道优先；如果它一直返回空帧（部分机型/模拟器如此），
     * 就切到投屏通道，两边都试。
     */
    /** 强制要一张新画面（不复用缓存帧），用于「点完该变绿了」这类自校验 */
    private Bitmap svcFresh() {
        DakaService svc = DakaService.I;
        if (svc == null) return null;
        return svc.captureFresh(2500);
    }

    private Bitmap shoot() {
        DakaService svc = DakaService.I;
        if (svc == null) return null;
        boolean projFirst = a11yModeBad;
        Bitmap b = null;
        if (!projFirst) {
            b = svc.capture(2500);
            if (b != null) {
                a11yBadCount = 0;
                a11yModeBad = false;
                return b;
            }
            a11yBadCount++;
            if (a11yBadCount >= 3 && !a11yModeBad) {
                a11yModeBad = true;
                Fx.log(app, "无障碍截图拿不到帧，改走兜底通道");
            }
        }
        // 兜底一：root screencap（只有模拟器/刷过机的设备才有 su）
        if (RootShot.available()) {
            b = RootShot.grab(app);
            if (b != null) {
                if (!probed) {
                    probed = true;
                    probeLog(b, "root 通道");
                }
                return b;
            }
        }
        if (Projection.ready()) {
            b = Projection.grab(1500);
            if (b != null) {
                if (!probed) {
                    probed = true;
                    Projection.probe(app, 360, 1300);
                }
                return b;
            }
        }
        if (projFirst) b = svc.capture(2500);
        return b;
    }

    private void probeLog(Bitmap b, String tag) {
        try {
            double k = b.getWidth() / 720.0;
            int x = (int) Math.round(360 * k), y = (int) Math.round(1300 * k);
            if (x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) return;
            int v = b.getPixel(x, y);
            Fx.log(app, tag + "探针 (360,1300) = R" + ((v >> 16) & 255)
                    + " G" + ((v >> 8) & 255) + " B" + (v & 255)
                    + "，尺寸 " + b.getWidth() + "x" + b.getHeight());
        } catch (Throwable t) { }
    }

    private void tapPx(int x, int y) {
        DakaService svc = DakaService.I;
        if (svc == null) return;
        svc.tap(x, y);
    }

    /** 出问题的时候把"它看到的画面"存到相册，最多 3 张，方便用户直接发回来 */
    private void diagShot(String tag) {
        if (diagSaves >= 3) return;
        Bitmap db = shoot();
        if (db == null) {
            Fx.log(app, "诊断：抓不到画面");
            return;
        }
        try {
            Vision.Shot ds = new Vision.Shot(db);
            Fx.log(app, "诊断 " + Vision.diag(ds));
            String p = Fx.savePng(app, db, tag);
            Fx.log(app, "已存诊断图：" + p);
            diagSaves++;
        } catch (Throwable t) {
            Fx.log(app, "诊断图出错：" + t);
        } finally {
            try { db.recycle(); } catch (Throwable t) { }
        }
    }

    private int jit() { return rnd.nextInt(7) - 3; }

    private void sleep(int ms) {
        int left = ms;
        while (left > 0 && !stopFlag) {
            int step = Math.min(left, 100);
            try { Thread.sleep(step); } catch (InterruptedException e) { }
            left -= step;
        }
    }

    /** 不受 stopFlag 影响的等待（响铃用） */
    private void rawSleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }

    static String fmt(double[] a) {
        return String.format(Locale.US, "%.3f/%.3f/%.3f", a[0], a[1], a[2]);
    }
}
