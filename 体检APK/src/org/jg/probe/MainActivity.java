package org.jg.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 集港体检：一键把「这台手机能不能自动打卡」测出来 */
public class MainActivity extends Activity {

    static final int REQ_PROJ = 2001;
    public static MainActivity I;

    TextView status, logView;
    View target;
    int[] loc = new int[2];

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        I = this;
        setContentView(buildUi());
        Logx.add("集港体检已打开");
        if (!ProbeService.alive()) Logx.add("提示：无障碍没开，截图和点击测试会跳过");
    }

    View buildUi() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(20), dp(14), dp(24));
        sc.addView(root);

        TextView title = new TextView(this);
        title.setText("集港体检 v1.0");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.parseColor("#666666"));
        status.setText("状态：待检");
        root.addView(status);

        root.addView(btn("① 打开无障碍设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this, "找到「集港体检」打开它", Toast.LENGTH_LONG).show();
                } catch (Throwable t) { }
            }
        }));

        root.addView(btn("② 开始体检（点一下就好）", new View.OnClickListener() {
            @Override public void onClick(View v) { runCheck(); }
        }));

        root.addView(btn("③ 测投屏（会弹授权，点「立即开始」）", new View.OnClickListener() {
            @Override public void onClick(View v) { askProj(); }
        }));

        root.addView(btn("④ 抓打卡页画面（点我后 5 秒内切到打卡页，别动）",
                new View.OnClickListener() {
            @Override public void onClick(View v) { grabNow(); }
        }));

        root.addView(btn("⑤ 复制报告到剪切板", new View.OnClickListener() {
            @Override public void onClick(View v) { copyReport(); }
        }));

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setText("下面的方框是点击靶子，体检时会自动点它一次，点中会变绿。");
        root.addView(hint);

        target = new View(this);
        target.setBackgroundColor(Color.parseColor("#9E9E9E"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(60));
        lp.topMargin = dp(6);
        root.addView(target, lp);
        target.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ProbeService.targetHit = true;
                target.setBackgroundColor(Color.parseColor("#09C362"));
                Logx.add("靶子被点中了");
            }
        });

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#222222"));
        logView.setPadding(0, dp(14), 0, 0);
        root.addView(logView);

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Logx.clear(); flushLog(); }
        });
        root.addView(clear);
        return sc;
    }

    Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    public void flushLog() {
        try {
            logView.setText(Logx.all());
            status.setText("状态：" + (ProbeService.alive() ? "无障碍已连接" : "无障碍未开"));
        } catch (Throwable t) { }
    }

    void append(String s) {
        Probe.setReport(Probe.report() + s + "\n");
    }

    // ---------------- 体检 ----------------

    void runCheck() {
        if (busy) { Toast.makeText(this, "正在跑，稍等", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        Logx.add("=== 开始体检 ===");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Probe.setReport("");
                    append("【集港体检】" + Probe.deviceBlock(MainActivity.this));
                    append("无障碍服务 " + (ProbeService.alive() ? "已连接 ✔" : "没开 ✘"));
                    try { append("前台是 " + (ProbeService.alive() ? ProbeService.I.currentPkg() : "?")); } catch (Throwable t) { }

                    // 点击测试
                    if (ProbeService.alive()) {
                        ProbeService.targetHit = false;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { target.getLocationOnScreen(loc); }
                        });
                        Thread.sleep(200);
                        int cx = loc[0] + dp(60), cy = loc[1] + dp(30);
                        boolean disp = ProbeService.I.tap(cx, cy);
                        Thread.sleep(600);
                        append("模拟点击 " + (ProbeService.targetHit ? "成功 ✔（靶子变绿）"
                                : (disp ? "手势发出了但没点中 ✘" : "手势发不出去 ✘")));
                        // 靶子还在的话复原颜色
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                target.setBackgroundColor(Color.parseColor("#9E9E9E"));
                            }
                        });
                    }

                    append("")
                        ;
                    append(Probe.shotBlock());
                    Logx.add("截图测试完成");

                    if (ProbeService.alive()) {
                        append("界面结构：" + ProbeService.I.treeReport());
                        Logx.add("节点树读取完成");
                    }

                    append("权限 " + Probe.permBlock(MainActivity.this));
                    append(Probe.wechat(MainActivity.this));
                    finishReport("体检完成");
                } catch (Throwable t) {
                    Logx.add("体检出错 " + t);
                    finishReport("体检出错（结果可能不全）");
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    // ---------------- 投屏 ----------------

    void askProj() {
        try {
            MediaProjectionManager m = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
        } catch (Throwable t) {
            Logx.add("投屏授权请求失败 " + t);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJ) return;
        if (res != RESULT_OK || data == null) {
            Logx.add("投屏授权被拒绝");
            append("投屏 用户拒绝了授权 ✘");
            return;
        }
        Logx.add("拿到投屏授权");
        ProjService.start(this, res, data);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(1200);
                    if (!Proj.ready()) {
                        append("投屏 开不起来 ✘ " + Proj.err());
                        finishReport("投屏测试结束");
                        return;
                    }
                    long t0 = SystemClock.elapsedRealtime();
                    Bitmap a = Proj.grab(t0, 2500);
                    Thread.sleep(900);
                    long t1 = SystemClock.elapsedRealtime();
                    Bitmap b2 = Proj.grab(t1, 2500);
                    StringBuilder sb = new StringBuilder("投屏 ");
                    if (a == null && b2 == null) {
                        sb.append("拿不到帧 ✘ 帧数=").append(Proj.frames()).append(' ').append(Proj.err());
                    } else {
                        sb.append("可用 ✔ 帧数=").append(Proj.frames());
                        if (a != null && b2 != null) {
                            int diff = diffPct(a, b2);
                            sb.append(" 两帧差异").append(diff).append("%");
                            sb.append(diff < 1 ? "（帧不更新 ✘）" : "（帧会更新 ✔）");
                        } else {
                            sb.append(" 只有一边拿到帧");
                        }
                    }
                    append(sb.toString());
                    if (a != null) a.recycle();
                    if (b2 != null) b2.recycle();
                    finishReport("投屏测试结束");
                } catch (Throwable t) {
                    Logx.add("投屏测试出错 " + t);
                }
            }
        }).start();
    }

    static int diffPct(Bitmap a, Bitmap b) {
        if (a == null || b == null) return 0;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 100;
        int n = 0, d = 0;
        for (int y = 0; y < a.getHeight(); y += 12) {
            for (int x = 0; x < a.getWidth(); x += 12) {
                int p = a.getPixel(x, y), q = b.getPixel(x, y);
                n++;
                if (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) > 12
                        || Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) > 12
                        || Math.abs((p & 255) - (q & 255)) > 12) d++;
            }
        }
        return n == 0 ? 0 : (int) Math.round(d * 100.0 / n);
    }

    // ---------------- 抓打卡页画面（第 7 项：连抓两张） ----------------

    void grabNow() {
        if (busy) { Toast.makeText(this, "正在跑，稍等", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        Toast.makeText(this, "5 秒后开始抓，请立刻切到打卡页并保持不动", Toast.LENGTH_LONG).show();
        Logx.add("抓取打卡页：5 秒后开始，期间请切到打卡页");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 5; i >= 1; i--) {
                        Logx.add("倒计时 " + i + "（现在切到打卡页）");
                        Thread.sleep(1000);
                    }
                    Bitmap a = grabOne();
                    String viaA = lastVia;
                    int lumA = a == null ? -1 : Probe.meanLum(a);
                    Thread.sleep(1000);
                    Bitmap b = grabOne();
                    String viaB = lastVia;
                    int lumB = b == null ? -1 : Probe.meanLum(b);

                    if (a == null && b == null) {
                        append("【打卡页画面】两张都没抓到 ✘（第1张 " + viaA + "，第2张 " + viaB
                                + "）——先做②无障碍、③投屏，再来抓");
                        finishReport("抓屏失败");
                        return;
                    }

                    append("【打卡页画面】这台手机 " + Probe.deviceShort(MainActivity.this));

                    if (a != null) {
                        String p = Probe.savePng(MainActivity.this, a, "打卡页_第1张");
                        append("第1张 " + a.getWidth() + "x" + a.getHeight() + "，亮度 " + lumA
                                + (lumA < 12 ? "（全黑 → 这条取图路不可用 ✘）" : " ✔")
                                + "，通道 " + viaA);
                        append("  存到：" + p);
                        append("  画面分析：" + Probe.analyze(a));
                    } else {
                        append("第1张 没抓到 ✘（" + viaA + "）");
                    }

                    if (b != null) {
                        String p2 = Probe.savePng(MainActivity.this, b, "打卡页_第2张");
                        append("第2张 " + b.getWidth() + "x" + b.getHeight() + "，亮度 " + lumB
                                + (lumB < 12 ? "（全黑 → 这条取图路不可用 ✘）" : " ✔")
                                + "，通道 " + viaB);
                        append("  存到：" + p2);
                    } else {
                        append("第2张 没抓到 ✘（" + viaB + "）");
                    }

                    if (a != null && b != null) {
                        int d = diffPct(a, b);
                        append("两张相隔 1 秒，画面差异 " + d + "%"
                                + (d < 1
                                    ? "（一模一样 → 这台手机的截图是「旧画面」，取图算死的 ✘）"
                                    : "（画面在动 → 截图是活的 ✔）"));
                    }

                    append("把相册里「打卡页_第1张 / 打卡页_第2张」这两张发给我（微信私聊，别发群里）。");
                    if (ProbeService.alive()) append("该屏结构：" + ProbeService.I.treeReport());

                    if (a != null) a.recycle();
                    if (b != null) b.recycle();
                    grabDone = true;
                    finishReport("打卡页抓取完成");
                } catch (Throwable t) {
                    Logx.add("抓屏出错 " + t);
                    finishReport("抓屏出错（结果可能不全）");
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    String lastVia = "";

    /** 取一帧：先无障碍，失败再投屏 */
    Bitmap grabOne() {
        Bitmap b = null;
        lastVia = "";
        try {
            if (ProbeService.alive()) {
                b = ProbeService.I.shot(3000);
                lastVia = "无障碍";
            } else {
                lastVia = "无障碍没开";
            }
        } catch (Throwable t) {
            lastVia = "无障碍出错";
        }
        if (b == null) {
            try {
                if (Proj.ready()) {
                    b = Proj.grab(SystemClock.elapsedRealtime(), 2500);
                    lastVia = "投屏";
                } else {
                    lastVia = lastVia + "/投屏没授权";
                }
            } catch (Throwable t) {
                lastVia = lastVia + "/投屏出错";
            }
        }
        return b;
    }

    // ---------------- 收尾 ----------------

    void finishReport(String why) {
        final String text = withTail(Probe.report());
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Probe.copy(MainActivity.this, text);
                Probe.copy(MainActivity.this, text);
                Logx.add(why + "，报告已复制到剪切板");
                Logx.add("报告长度 " + text.length() + " 字");
                flushLog();
                Toast.makeText(MainActivity.this, "报告已复制，去微信粘贴", Toast.LENGTH_LONG).show();
            }
        });
    }

    void copyReport() {
        String text = withTail(Probe.report());
        if (text.length() == 0) {
            Toast.makeText(this, "还没有报告，先点②", Toast.LENGTH_SHORT).show();
            return;
        }
        Probe.copy(this, text);
        Logx.add("报告已复制（" + text.length() + " 字）");
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private boolean busy = false;
    private boolean grabDone = false;

    private String withTail(String text) {
        return text + "\n\n【第④步】打卡页抓图："
                + (grabDone ? "已抓 2 张，存进相册（Pictures/集港体检），请把两张图一起发回"
                            : "未执行 —— 这份报告里没有打卡页画面，请先点「④ 抓打卡页画面」再复制");
    }
}
