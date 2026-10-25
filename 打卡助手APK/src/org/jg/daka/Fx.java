package org.jg.daka;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 日志与存图 */
public class Fx {
    public static final String TAG = "DAKA";

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    public static void log(Context c, String s) {
        Log.i(TAG, s);
        if (c == null) return;
        try {
            File f = new File(c.getFilesDir(), "run.log");
            FileOutputStream o = new FileOutputStream(f, true);
            o.write((stamp() + "  " + s + "\n").getBytes("UTF-8"));
            o.close();
        } catch (Throwable t) {
            Log.w(TAG, "写日志失败 " + t);
        }
    }

    public static String readLog(Context c) {
        try {
            File f = new File(c.getFilesDir(), "run.log");
            if (!f.exists()) return "(还没有日志)";
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            String s = new String(b, 0, Math.max(n, 0), "UTF-8");
            if (s.length() > 20000) s = "…（只显示最后 20000 字）\n" + s.substring(s.length() - 20000);
            return s;
        } catch (Throwable t) {
            return "(读日志失败 " + t + ")";
        }
    }

    public static void clearLog(Context c) {
        try { new File(c.getFilesDir(), "run.log").delete(); } catch (Throwable t) { }
    }

    /** 存一张 PNG 到相册 Pictures/集港助手/ */
    public static String savePng(Context c, Bitmap bm, String prefix) {
        String name = prefix + "_" + new SimpleDateFormat("MMdd_HHmmss", Locale.CHINA).format(new Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/集港助手");
                Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "存图失败(insert)";
                OutputStream os = cr.openOutputStream(u);
                bm.compress(Bitmap.CompressFormat.PNG, 95, os);
                os.close();
                return "Pictures/集港助手/" + name;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "集港助手");
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream o = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.PNG, 95, o);
            o.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "存图失败 " + t;
        }
    }

    /** 导出日志到 Download/集港助手/日志.txt */
    public static String exportLog(Context c) {
        byte[] data = readLog(c).getBytes(java.nio.charset.Charset.forName("UTF-8"));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "日志.txt");
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/集港助手");
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "导出失败(insert)";
                OutputStream os = cr.openOutputStream(u);
                os.write(data);
                os.close();
                return "Download/集港助手/日志.txt";
            }
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/集港助手");
            dir.mkdirs();
            File f = new File(dir, "日志.txt");
            FileOutputStream o = new FileOutputStream(f, false);
            o.write(data);
            o.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "导出失败 " + t;
        }
    }
}
