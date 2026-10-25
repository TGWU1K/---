package org.jg.probe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

/** 体检逻辑：能测的都在这里，结果拼成一段可直接粘到微信的文字 */
public class Probe {

    static String report = "";

    public static String report() { return report; }

    public static void setReport(String s) { report = s; }

    // ---------------- 设备信息 ----------------

    public static String deviceBlock(Context c) {
        StringBuilder sb = new StringBuilder();
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        int d = c.getResources().getDisplayMetrics().densityDpi;
        int pw = w, ph = h;
        try {
            DisplayMetrics rm = new DisplayMetrics();
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(rm);
            pw = rm.widthPixels;
            ph = rm.heightPixels;
        } catch (Throwable t) { }
        sb.append("型号 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        sb.append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')');
        sb.append("\n逻辑屏幕 ").append(w).append('x').append(h).append(" @").append(d).append("dpi");
        sb.append("\n物理屏幕 ").append(pw).append('x').append(ph);
        if (pw != w || ph != h) sb.append("（和逻辑值不一样，点坐标要按物理值算！）");
        String disp = String.valueOf(Build.DISPLAY);
        if (disp.toLowerCase().contains("harmony") || disp.toLowerCase().contains("hongmeng")) sb.append("\n系统签名含鸿蒙：").append(disp);
        sb.append("\nABI ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        sb.append("\n芯片 ").append(Build.HARDWARE);
        return sb.toString();
    }

    /** 一行话的机型，用在采样报告的小标题里 */
    public static String deviceShort(Context c) {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        return Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                + " / 逻辑 " + w + "x" + h;
    }

    // ---------------- 无障碍截图 ----------------

    public static String shotBlock() {
        ProbeService svc = ProbeService.I;
        if (svc == null) return "无障碍服务没连上，跳过截图测试";
        if (Build.VERSION.SDK_INT < 30) return "Android " + Build.VERSION.SDK_INT + "：没有无障碍截图接口（需 API30+）";
        StringBuilder sb = new StringBuilder();
        long sum = 0;
        int ok = 0;
        for (int i = 1; i <= 3; i++) {
            long t0 = System.currentTimeMillis();
            Bitmap b = svc.shot(3000);
            long ms = System.currentTimeMillis() - t0;
            sum += ms;
            if (b == null) {
                sb.append("\n第").append(i).append("张：拿不到（").append(ms).append("ms）");
                continue;
            }
            int lum = meanLum(b);
            boolean black = lum < 12;
            if (!black) ok++;
            sb.append("\n第").append(i).append("张：").append(b.getWidth()).append('x').append(b.getHeight())
                    .append(" 亮度").append(lum).append(black ? " 全黑" : " 正常")
                    .append(" 耗时").append(ms).append("ms");
            b.recycle();
        }
        sb.append("\n结论：无障碍截图").append(ok > 0 ? "可用（" + ok + "/3 非黑）" : "不可用（3/3 全黑或拿不到）");
        sb.append("，平均 ").append(sum / 3).append("ms");
        return sb.toString();
    }

    public static int meanLum(Bitmap b) {
        if (b == null) return 0;
        long s = 0;
        int n = 0;
        for (int y = 0; y < b.getHeight(); y += 16) {
            for (int x = 0; x < b.getWidth(); x += 16) {
                int v = b.getPixel(x, y);
                s += (((v >> 16) & 255) * 30 + ((v >> 8) & 255) * 59 + (v & 255) * 11) / 100;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (s / n);
    }

    // ---------------- 画面几何分析 ----------------

    public static String analyze(Bitmap b) {
        if (b == null) return "没有画面";
        int w = b.getWidth(), h = b.getHeight();
        int[] rowBlue = new int[h];
        int blueTop = -1, blueBot = -1, blueLeft = -1, blueRight = -1;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 4) {
                int v = b.getPixel(x, y);
                int r = (v >> 16) & 255, g = (v >> 8) & 255, bb = v & 255;
                if (bb > 150 && r < 130 && g < 160 && bb - r > 60) {
                    rowBlue[y]++;
                    if (blueLeft < 0 || x < blueLeft) blueLeft = x;
                    if (x > blueRight) blueRight = x;
                }
            }
        }
        int thr = (w / 4) / 2;
        for (int y = 0; y < h; y += 2) {
            if (rowBlue[y] >= thr) {
                if (blueTop < 0) blueTop = y;
                blueBot = y;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("画面 ").append(w).append('x').append(h).append(" 亮度").append(meanLum(b));
        if (blueTop >= 0) {
            sb.append("\n蓝色按钮区 y=").append(blueTop).append('~').append(blueBot)
                    .append("（占屏高 ").append(pct(blueTop, h)).append("~").append(pct(blueBot, h)).append("%）")
                    .append(" x=").append(blueLeft).append('~').append(blueRight);
        } else {
            sb.append("\n没找到蓝色按钮区");
        }
        // 白框：找连续较宽的白色水平带（验证码弹窗/结果卡）
        int whiteTop = -1, whiteBot = -1;
        for (int y = 0; y < h; y += 2) {
            int cnt = 0;
            for (int x = 0; x < w; x += 4) {
                int v = b.getPixel(x, y);
                if (((v >> 16) & 255) > 236 && ((v >> 8) & 255) > 236 && (v & 255) > 236) cnt++;
            }
            if (cnt > (w / 4) * 0.55) {
                if (whiteTop < 0) whiteTop = y;
                whiteBot = y;
            }
        }
        if (whiteTop >= 0) {
            sb.append("\n白色大块 y=").append(whiteTop).append('~').append(whiteBot)
                    .append("（").append(pct(whiteTop, h)).append("~").append(pct(whiteBot, h)).append("%）");
        }
        return sb.toString();
    }

    static int pct(int v, int total) {
        return total == 0 ? 0 : (int) Math.round(v * 100.0 / total);
    }

    // ---------------- 权限与目标应用 ----------------

    public static String permBlock(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("悬浮窗 ").append(canOverlay(c) ? "有" : "没有");
        sb.append(" / 通知 ").append(notifOn(c) ? "有" : "没有");
        sb.append(" / 电池白名单 ").append(batteryOk(c) ? "有" : "没有");
        return sb.toString();
    }

    public static boolean canOverlay(Context c) {
        try { return android.provider.Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    public static boolean notifOn(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
        } catch (Throwable t) { }
        return false;
    }

    public static boolean batteryOk(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) { return false; }
    }

    public static String wechat(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo("com.tencent.mm", 0);
            return "微信 已装 v" + pi.versionName;
        } catch (Throwable t) {
            return "微信 没装或读不到版本";
        }
    }

    public static boolean a11yEnabled(Context c) {
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled() && ProbeService.alive();
        } catch (Throwable t) { return false; }
    }

    // ---------------- 存图 + 剪贴板 ----------------

    public static String savePng(Context c, Bitmap b, String name) {
        final String fileName = name + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/集港体检");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = c.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "存图失败（相册拒绝写入）";
                java.io.OutputStream os = c.getContentResolver().openOutputStream(uri);
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, cv, null, null);
                return "相册 → Pictures/集港体检/" + fileName;
            }
            java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "集港体检");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, fileName);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            try {
                android.media.MediaScannerConnection.scanFile(c,
                        new String[] { f.getAbsolutePath() }, null, null);
            } catch (Throwable t) { }
            return "相册 → Pictures/集港体检/" + fileName;
        } catch (Throwable t) {
            return "存图失败 " + t;
        }
    }

    public static void copy(Context c, String s) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("集港体检报告", s));
        } catch (Throwable t) { }
    }
}
