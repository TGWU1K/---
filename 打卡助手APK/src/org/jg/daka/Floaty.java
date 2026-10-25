package org.jg.daka;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** 悬浮球：显示状态，点一下开始/停止，可拖动 */
public class Floaty {
    public static final int COL_RUN = 0xFF2E7D32;
    public static final int COL_IDLE = 0xFF546E7A;
    public static final int COL_WARN = 0xFFEF6C00;
    public static final int COL_WIN = 0xFFC62828;

    static WindowManager wm;
    static TextView tv;
    static WindowManager.LayoutParams lp;
    static final Handler H = new Handler(Looper.getMainLooper());

    static String lastText = "集港助手";
    static int lastColor = COL_IDLE;
    static boolean shown = false;

    static int downX, downY;
    static float touchX, touchY;
    static boolean moved;
    static long downT;

    public static boolean canShow(Context c) {
        try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    public static void show(final Context c) {
        if (!canShow(c)) return;
        H.post(new Runnable() {
            public void run() {
                if (shown) { set(lastText, lastColor); return; }
                try {
                    wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                    tv = new TextView(c);
                    tv.setText(lastText);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(24);                     // 悬浮球字号 12 -> 24sp（放大 2 倍）
                    tv.setPadding(40, 20, 40, 20);           // 内边距同步放大，球体跟着变大
                    tv.setBackground(bg(lastColor));
                    lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.x = 10;
                    lp.y = 380;
                    tv.setOnTouchListener(new View.OnTouchListener() {
                        public boolean onTouch(View v, MotionEvent e) {
                            switch (e.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    downX = lp.x;
                                    downY = lp.y;
                                    touchX = e.getRawX();
                                    touchY = e.getRawY();
                                    moved = false;
                                    downT = System.currentTimeMillis();
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    int dx = (int) (e.getRawX() - touchX);
                                    int dy = (int) (e.getRawY() - touchY);
                                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                                    lp.x = downX + dx;
                                    lp.y = downY + dy;
                                    try { wm.updateViewLayout(tv, lp); } catch (Throwable t) { }
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    if (!moved && System.currentTimeMillis() - downT < 800) onClick(c);
                                    return true;
                                default:
                                    return false;
                            }
                        }
                    });
                    wm.addView(tv, lp);
                    shown = true;
                } catch (Throwable t) {
                    Fx.log(c, "悬浮球创建失败 " + t);
                }
            }
        });
    }

    static GradientDrawable bg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(40);
        return g;
    }

    static void onClick(Context c) {
        if (Runner.isRunning()) {
            Runner.stop();
            set("正在停止…", COL_WARN);
        } else {
            MainActivity.startRun(c);
        }
    }

    public static void set(final String text, final int color) {
        lastText = text;
        lastColor = color;
        H.post(new Runnable() {
            public void run() {
                if (tv == null) return;
                try {
                    tv.setText(text);
                    tv.setBackground(bg(color));
                } catch (Throwable t) { }
            }
        });
    }

    public static void hide() {
        H.post(new Runnable() {
            public void run() {
                try {
                    if (wm != null && tv != null) wm.removeView(tv);
                } catch (Throwable t) { }
                tv = null;
                shown = false;
            }
        });
    }
}
