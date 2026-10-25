package org.jg.daka;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 无障碍服务：负责截图、模拟点击、跟踪前台包名 */
public class DakaService extends AccessibilityService {

    public static volatile DakaService I;
    public volatile String fgPkg = null;

    // 截图回调必须走自己的线程：如果回调投递到主线程，而调用方又在主线程里等 latch，
    // 就会出现「谁也等不到谁」的死锁（表现是抓屏要等 40 秒然后失败）。
    private static android.os.HandlerThread SHOT_TH = null;
    private static android.os.Handler SHOT_H = null;

    private static synchronized android.os.Handler shotHandler() {
        if (SHOT_H == null) {
            SHOT_TH = new android.os.HandlerThread("daka-shot");
            SHOT_TH.start();
            SHOT_H = new android.os.Handler(SHOT_TH.getLooper());
        }
        return SHOT_H;
    }

    private final Executor EX = new Executor() {
        public void execute(Runnable r) {
            shotHandler().post(r);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        I = this;
        Fx.log(this, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String p = e.getPackageName().toString();
            if (!p.equals(fgPkg)) fgPkg = p;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent i) {
        if (I == this) I = null;
        return super.onUnbind(i);
    }

    public static boolean alive() { return I != null; }

    /**
     * 当前真正在前台的包名。优先问窗口列表（比事件可靠：事件丢了就一直是旧值），
     * 拿不到再退回事件里记下的那个。
     */
    public String currentPkg() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    if (w == null || !w.isActive()) continue;
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
                }
            }
        } catch (Throwable t) {
            Fx.log(this, "取前台窗口失败：" + t);
        }
        return fgPkg;
    }

    public double scale() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels / 720.0;
    }

    /** Android 12+ 两次 takeScreenshot 至少隔 1 秒；真机实测第 2、3 张会秒失败 */
    private static final int MIN_SHOT_GAP = 1100;
    private static volatile long lastShotAt = 0;
    private static Bitmap lastShot = null;
    /** 最近一次截图失败的原始错误码（诊断用） */
    public static volatile String lastShotErr = "";

    /** 截一张全屏；失败返回 null。太快会复用上一次的画面，避免被系统限速挡住 */
    public synchronized Bitmap capture(int timeoutMs) {
        return captureInternal(timeoutMs, true);
    }

    /**
     * 一定要新画面（例如「刚点完，圆圈该变绿了」这种自校验）。
     * 会老老实实等够 1.1 秒的系统限速，绝不复用缓存帧。
     */
    public synchronized Bitmap captureFresh(int timeoutMs) {
        return captureInternal(timeoutMs, false);
    }

    private Bitmap captureInternal(int timeoutMs, boolean allowCache) {
        if (Build.VERSION.SDK_INT < 30) return null;
        if (allowCache && lastShot != null && System.currentTimeMillis() - lastShotAt < MIN_SHOT_GAP) {
            try {
                return lastShot.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) { }
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            long wait = MIN_SHOT_GAP - (System.currentTimeMillis() - lastShotAt);
            if (lastShotAt > 0 && wait > 0) sleep((int) Math.min(wait + 60, 2000L));
            Bitmap b = captureRaw(timeoutMs);
            if (b == null) { sleep(200); continue; }
            lastShotAt = System.currentTimeMillis();
            int m = meanLum(b);
            if (m >= 12) {
                if (lastShot != null) lastShot.recycle();
                try { lastShot = b.copy(Bitmap.Config.ARGB_8888, false); } catch (Throwable t) { lastShot = null; }
                return b;
            }
            // 全黑/极暗：极少数机型会偶发返回空帧，收掉重来
            Fx.log(this, "截图疑似空帧（均亮 " + m + "），重试 " + (attempt + 1));
            b.recycle();
        }
        if (!lastShotErr.equals("")) Fx.log(this, "截图失败详情：" + lastShotErr);
        return null;
    }

    private static int meanLum(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        long sum = 0; int n = 0;
        for (int y = 0; y < h; y += 16) {
            for (int x = 0; x < w; x += 16) {
                int c = b.getPixel(x, y);
                sum += (299 * ((c >> 16) & 255) + 587 * ((c >> 8) & 255) + 114 * (c & 255)) / 1000;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    private Bitmap captureRaw(int timeoutMs) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (I == null) return null;
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] out = new Bitmap[1];
            final int[] err = new int[1];
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, EX, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        try {
                            HardwareBuffer hb = r.getHardwareBuffer();
                            Bitmap hbm = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                            if (hbm != null) {
                                // 硬件位图必须 copy 成软件位图才能读像素。
                                // 注意：Canvas.drawBitmap 画不了 HARDWARE 位图（会抛异常），
                                // 所以先用 copy，copy 失败才退到 Canvas（对部分机型有效）。
                                Bitmap soft = null;
                                try {
                                    soft = hbm.copy(Bitmap.Config.ARGB_8888, false);
                                } catch (Throwable t) { soft = null; }
                                if (soft == null) {
                                    try {
                                        soft = Bitmap.createBitmap(hbm.getWidth(), hbm.getHeight(),
                                                Bitmap.Config.ARGB_8888);
                                        new Canvas(soft).drawBitmap(hbm, 0f, 0f, null);
                                    } catch (Throwable t) {
                                        err[0] = -10;
                                        soft = null;
                                    }
                                }
                                out[0] = soft;
                                hbm.recycle();
                            }
                            hb.close();
                        } catch (Throwable t) {
                            err[0] = -9;
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int code) {
                        err[0] = code;
                        latch.countDown();
                    }
                });
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                err[0] = -8;
            }
            if (out[0] != null) { lastShotErr = ""; return out[0]; }
            lastShotErr = "err=" + err[0];
            if (err[0] == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) sleep(1150);
            else sleep(200);
        }
        return null;
    }

    /** 点一下（像素坐标） */
    public boolean tap(int x, int y) {
        if (I == null) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription gd = new GestureDescription.Builder().addStroke(st).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        try {
            boolean sent = dispatchGesture(gd, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) { ok[0] = true; latch.countDown(); }
                @Override
                public void onCancelled(GestureDescription g) { latch.countDown(); }
            }, null);
            if (!sent) return false;
            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return false;
        }
        return ok[0];
    }

    public void back() {
        try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable t) { }
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }
}
