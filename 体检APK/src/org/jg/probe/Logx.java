package org.jg.probe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 简单的内存日志 + 落盘 */
public class Logx {

    static final List<String> lines = new ArrayList<String>();

    public static void add(String s) {
        lines.add(s);
        Log.i("PROBE", s);
        try {
            MainActivity.I.runOnUiThread(new Runnable() {
                @Override public void run() { MainActivity.I.flushLog(); }
            });
        } catch (Throwable t) { }
    }

    public static String all() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    public static void clear() { lines.clear(); }

    public static void save(Context c, String text) {
        try {
            File dir = new File(c.getExternalFilesDir(null), "报告");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "体检报告_" + ts + ".txt");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(text.getBytes("UTF-8"));
            fo.close();
            add("报告已存到 " + f.getAbsolutePath());
        } catch (Throwable t) {
            add("报告落盘失败 " + t);
        }
    }
}
