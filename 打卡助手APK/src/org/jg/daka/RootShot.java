package org.jg.daka;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 兜底截图通道之三：su + screencap。
 * 只用于「设备上确实有 root」的情况（模拟器调试、或司机自己刷过机的手机）。
 * 手机上没有 su 就一直返回 null，不影响主通道。
 */
public class RootShot {

    static int known = -1;        // -1 未知, 0 没有, 1 有

    public static boolean available() {
        if (known >= 0) return known == 1;
        String out = run("su -c id");
        known = out.indexOf("uid=0") >= 0 ? 1 : 0;
        return known == 1;
    }

    static String run(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "sh", "-c", cmd });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while (er.readLine() != null) { /* 丢掉 */ }
            er.close();
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (p != null) p.destroy(); } catch (Throwable t) { }
        }
    }

    /** 用 root 截一张全屏图；失败返回 null */
    public static Bitmap grab(Context c) {
        if (!available()) return null;
        File f = new File(c.getCacheDir(), "rs.png");
        String path = f.getAbsolutePath();
        try { if (f.exists()) f.delete(); } catch (Throwable t) { }
        run("su -c 'screencap -p " + path + "'");
        if (!f.exists() || f.length() < 1024) return null;
        Bitmap b = null;
        try {
            b = BitmapFactory.decodeFile(path);
        } catch (Throwable t) {
            b = null;
        } finally {
            try { f.delete(); } catch (Throwable t) { }
        }
        return b;
    }
}
