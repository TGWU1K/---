package org.jg.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 体检用的无障碍服务：只做三件事——拿帧、打手势、读窗口结构 */
public class ProbeService extends AccessibilityService {

    public static volatile ProbeService I;
    public static volatile boolean targetHit = false;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        I = this;
        Logx.add("无障碍服务已连接");
    }

    @Override public void onDestroy() {
        I = null;
        super.onDestroy();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        I = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    public static boolean alive() { return I != null; }

    public int sw() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    public int sh() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    public String currentPkg() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || !w.isActive()) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) {
                    CharSequence p = r.getPackageName();
                    if (p != null) return p.toString();
                }
            }
        } catch (Throwable t) { }
        return "?";
    }

    /** 无障碍截图（API 30+）。失败/黑帧都会体现在返回值里 */
    public Bitmap shot(int timeoutMs) {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> out = new AtomicReference<Bitmap>(null);
        final AtomicReference<String> why = new AtomicReference<String>("");
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult r) {
                            try {
                                Bitmap hb = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(),
                                        r.getColorSpace());
                                if (hb != null) out.set(hb.copy(Bitmap.Config.ARGB_8888, false));
                                try { r.getHardwareBuffer().close(); } catch (Throwable t) { }
                            } catch (Throwable t) {
                                why.set("转换失败 " + t);
                            } finally {
                                latch.countDown();
                            }
                        }

                        @Override public void onFailure(int code) {
                            why.set("截图失败 code=" + code);
                            latch.countDown();
                        }
                    });
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            why.set("截图异常 " + t);
        }
        Bitmap b = out.get();
        if (b == null) Logx.add("shot() -> null " + why.get());
        return b;
    }

    public boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 50);
            GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
            final CountDownLatch latch = new CountDownLatch(1);
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g1) { latch.countDown(); }
                @Override public void onCancelled(GestureDescription g1) { latch.countDown(); }
            }, new Handler(Looper.getMainLooper()));
            latch.await(1500, TimeUnit.MILLISECONDS);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读当前所有窗口的结构摘要（不记录文字内容） */
    public String treeReport() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            sb.append("窗口数 ").append(ws == null ? 0 : ws.size());
            if (ws == null || ws.isEmpty()) return sb.toString();
            for (int i = 0; i < ws.size() && i < 4; i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                String pkg = root == null ? "?" : String.valueOf(root.getPackageName());
                Cnt c = new Cnt();
                if (root != null) walk(root, c, 0, 6);
                sb.append("\n· 窗口").append(i)
                        .append(" 类型=").append(typeName(w.getType()))
                        .append(w.isActive() ? "(活动)" : "")
                        .append(" 包=").append(shortPkg(pkg))
                        .append(" 节点=").append(c.nodes)
                        .append(" 有字=").append(c.withText)
                        .append(" 字数=").append(c.chars)
                        .append(" 深").append(c.depth);
                if (!c.classes.isEmpty()) {
                    sb.append(" 关键类=");
                    for (int k = 0; k < c.classes.size() && k < 6; k++) {
                        if (k > 0) sb.append(",");
                        sb.append(c.classes.get(k));
                    }
                }
                if (c.webview) sb.append(" [含WebView]");
            }
        } catch (Throwable t) {
            sb.append(" 读取异常 ").append(t);
        }
        return sb.toString();
    }

    static String typeName(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "应用";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "输入法";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "系统";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "无障碍浮层";
            default: return "其他" + t;
        }
    }

    static String shortPkg(String p) {
        if (p == null) return "?";
        if (p.startsWith("com.tencent.mm")) return "微信";
        return p;
    }

    static class Cnt {
        int nodes = 0, withText = 0, chars = 0, depth = 0;
        boolean webview = false;
        List<String> classes = new ArrayList<String>();
    }

    void walk(AccessibilityNodeInfo n, Cnt c, int d, int maxDepth) {
        if (n == null || c.nodes > 600) return;
        c.nodes++;
        if (d > c.depth) c.depth = d;
        CharSequence cs = n.getText();
        if (cs != null && cs.length() > 0) {
            c.withText++;
            c.chars += cs.length();
        }
        CharSequence cls = n.getClassName();
        if (cls != null) {
            String s = cls.toString();
            if (s.contains("WebView") || s.contains("webview")) c.webview = true;
            if (d <= 2 && !c.classes.contains(s)) {
                int dot = s.lastIndexOf('.');
                c.classes.add(dot > 0 ? s.substring(dot + 1) : s);
            }
        }
        if (d >= maxDepth) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                walk(n.getChild(i), c, d + 1, maxDepth);
            } catch (Throwable t) { }
        }
    }
}
