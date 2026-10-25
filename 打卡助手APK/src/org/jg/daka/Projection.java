package org.jg.daka;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;

/**
 * 兜底截图通道：MediaProjection 投屏抓帧。
 * 无障碍的 takeScreenshot 在某些机型/模拟器上会返回全黑帧，这时靠这里。
 */
public class Projection {

    static MediaProjection mp;
    static VirtualDisplay vd;
    static ImageReader reader;
    static HandlerThread ht;
    static Handler h;
    static int W, H, DPI;

    static volatile Bitmap last;
    static volatile long lastTs;
    static volatile String err = "";

    public static String lastErr() { return err; }

    public static synchronized boolean ready() { return reader != null; }

    public static synchronized void stop() {
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        try { if (reader != null) reader.close(); } catch (Throwable t) { }
        try { if (mp != null) mp.stop(); } catch (Throwable t) { }
        vd = null; reader = null; mp = null;
        Bitmap b = last; last = null;
        try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Throwable t) { }
    }

    /** 在拿到用户授权之后调用（必须已有前台服务，否则会 SecurityException） */
    public static synchronized boolean start(Context c, int code, Intent data) {
        if (reader != null) return true;
        try {
            MediaProjectionManager m = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection p = m.getMediaProjection(code, data);
            if (p == null) { err = "getMediaProjection 返回空"; return false; }

            p.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stop(); }
            }, new Handler(c.getMainLooper()));

            // 注意：必须用「真实屏幕尺寸」而不是 getDisplayMetrics()。
            // 后者会扣掉导航栏（实测 720x1532），投屏出来的画面会被纵向压扁，
            // 于是按 720x1600 算出来的点击坐标就全偏了。
            DisplayMetrics dm = new DisplayMetrics();
            try {
                android.view.WindowManager wm = (android.view.WindowManager)
                        c.getSystemService(Context.WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(dm);
            } catch (Throwable t) {
                dm = c.getResources().getDisplayMetrics();
            }
            W = dm.widthPixels; H = dm.heightPixels; DPI = dm.densityDpi;

            ht = new HandlerThread("proj");
            ht.start();
            h = new Handler(ht.getLooper());

            reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        Bitmap b = toBitmap(img);
                        if (b != null) {
                            Bitmap old = last;
                            last = b;
                            lastTs = SystemClock.elapsedRealtime();
                            if (old != null && !old.isRecycled() && old != b) {
                                try { old.recycle(); } catch (Throwable t) { }
                            }
                        }
                    } catch (Throwable t) {
                        err = "取帧异常 " + t;
                    } finally {
                        try { if (img != null) img.close(); } catch (Throwable t) { }
                    }
                }
            }, h);

            vd = p.createVirtualDisplay("daka", W, H, DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            if (vd == null) { err = "createVirtualDisplay 返回空"; stop(); return false; }
            mp = p;
            err = "";
            Fx.log(c, "投屏通道已开：" + W + "x" + H + " dpi" + DPI);
            return true;
        } catch (Throwable t) {
            err = "投屏启动失败 " + t;
            Fx.log(c, err);
            stop();
            return false;
        }
    }

    /** RGBA_8888 的 Image 转 Bitmap（换掉 R/B 的顺序） */
    static Bitmap toBitmap(Image img) {
        int w = img.getWidth(), hh = img.getHeight();
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer buf = pl.getBuffer();
        int rowStride = pl.getRowStride();
        int pixStride = pl.getPixelStride();
        int[] px = new int[w * hh];
        if (pixStride == 4 && rowStride == w * 4) {
            buf.rewind();
            buf.asIntBuffer().get(px);
        } else {
            byte[] row = new byte[rowStride];
            for (int y = 0; y < hh; y++) {
                buf.position(y * rowStride);
                int n = Math.min(rowStride, buf.remaining());
                buf.get(row, 0, n);
                for (int x = 0; x < w; x++) {
                    int o = x * pixStride;
                    int r = row[o] & 255, g = row[o + 1] & 255, b = row[o + 2] & 255, a = row[o + 3] & 255;
                    px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        if (pixStride == 4 && rowStride == w * 4) {
            // 直接从缓冲区读进来的是 RGBA 字节序，被当成 ARGB 解释了，交换 R/B
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                int a = (v >>> 24) & 255, r = (v >>> 16) & 255, g = (v >>> 8) & 255, b = v & 255;
                px[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
        Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, hh);
        return b;
    }

    /** 取一张当前画面；没有帧或超时返回 null */
    public static Bitmap grab(int timeoutMs) {
        if (reader == null) return null;
        long t0 = SystemClock.elapsedRealtime();
        long end = t0 + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            Bitmap b = last;
            if (b != null && !b.isRecycled() && lastTs >= t0) {
                return b.copy(Bitmap.Config.ARGB_8888, false);
            }
            try { Thread.sleep(40); } catch (InterruptedException e) { }
        }
        Bitmap b = last;
        if (b != null && !b.isRecycled()) return b.copy(Bitmap.Config.ARGB_8888, false);
        return null;
    }

    /** 首帧自检：把某个点的颜色记下来，方便确认颜色通道没搞反 */
    public static void probe(Context c, int x720, int y720) {
        Bitmap b = last;
        if (b == null) return;
        double k = b.getWidth() / 720.0;
        int x = (int) Math.round(x720 * k), y = (int) Math.round(y720 * k);
        if (x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) return;
        int v = b.getPixel(x, y);
        Fx.log(c, String.format("首帧探针 (%d,%d) = R%d G%d B%d", x, y,
                (v >> 16) & 255, (v >> 8) & 255, v & 255));
    }
}
