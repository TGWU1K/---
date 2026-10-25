package org.jg.mockdaka;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Random;

/**
 * 假打卡页 v1 —— 用来在模拟器里验证抢单脚本的整套像素逻辑。
 *
 * 故意按 720x1600 真机实测坐标画：
 *   验证码弹窗白框      x 72..647  y 465..1050
 *   上方圆圈            r=37 cy=638 cx=249/360/470
 *   下方 6 个按钮       160x94，行中心 y=863/976，列中心 x=181/359/538
 *   结果卡「‹」         暗像素包围盒 x 86..102 / y 994..1024
 *   集港打卡按钮        大蓝块，中心约 (360,1313)
 *
 * 启动参数（intent extra）：
 *   winRound  第几轮提交后摇中（0=永不中）  默认 0
 *   seed      随机种子，方便复现            默认 0
 *
 * 所有事件同时写 logcat(tag=MOCKDAKA) 和 files/events.txt
 */
public class MainActivity extends Activity {
    private MockView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int winRound = getIntent().getIntExtra("winRound", 0);
        int seed = getIntent().getIntExtra("seed", 0);
        view = new MockView(this, winRound, seed);
        setContentView(view);
        view.log("start winRound=" + winRound + " seed=" + seed);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) {
            // 全屏沉浸：内容坐标 = 屏幕坐标
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (view != null) view.log("activity_destroy");
    }

    // ===================================================================
    static class MockView extends View {
        static final int ST_DAKA = 0, ST_CAPTCHA = 1, ST_LOADING = 2,
                ST_RESULT = 3, ST_SUCCESS = 4, ST_CONFIRMED = 5;

        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface tf = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
        final Typeface tfb = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        final Handler h = new Handler(Looper.getMainLooper());
        final Random rnd;
        final int winRound;
        final String logPath;

        int state = ST_DAKA;
        int round = 0;
        int[] tops = new int[3];
        int[] cands = new int[6];
        int[] answer = new int[3];     // tops[i] 在 cands 里的下标
        int[] cellState = new int[6];  // 0 未点 1 绿(对) 2 红(错)
        int tapped = 0;
        String jgText = "J26022";
        int touchCount = 0;

        MockView(Context c, int winRound, int seed) {
            super(c);
            this.winRound = winRound;
            this.rnd = new Random(seed == 0 ? System.currentTimeMillis() : seed);
            this.logPath = c.getExternalFilesDir(null) + "/events.txt";
            setBackgroundColor(0xFF000000);
            p.setStyle(Paint.Style.FILL);
            tp.setTypeface(tf);
        }

        // ---------------------------------------------------------- 日志
        void log(String m) {
            String line = "r" + round + " " + m;
            Log.i("MOCKDAKA", line);
            try {
                FileOutputStream f = new FileOutputStream(logPath, true);
                f.write((line + "\n").getBytes("UTF-8"));
                f.close();
            } catch (Throwable ignored) { }
        }

        // ---------------------------------------------------------- 坐标换算
        float sx, sy;
        void updateScale() {
            sx = getWidth() / 720f;
            sy = getHeight() / 1600f;
        }
        float SX(float v) { return v * sx; }
        float SY(float v) { return v * sy; }

        void fill(Canvas c, int color) { p.setColor(color); }
        void rect(Canvas c, float x0, float y0, float x1, float y1, int color) {
            p.setColor(color);
            c.drawRect(SX(x0), SY(y0), SX(x1), SY(y1), p);
        }
        void round(Canvas c, float x0, float y0, float x1, float y1, float r, int color) {
            p.setColor(color);
            c.drawRoundRect(new RectF(SX(x0), SY(y0), SX(x1), SY(y1)), SX(r), SY(r), p);
        }
        void text(Canvas c, String s, float cx, float cy, float size, int color, boolean bold) {
            tp.setTypeface(bold ? tfb : tf);
            tp.setColor(color);
            tp.setTextSize(SX(size));
            tp.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float base = SY(cy) - (fm.ascent + fm.descent) / 2f;
            c.drawText(s, SX(cx), base, tp);
        }
        void textLeft(Canvas c, String s, float x, float cy, float size, int color, boolean bold) {
            tp.setTypeface(bold ? tfb : tf);
            tp.setColor(color);
            tp.setTextSize(SX(size));
            tp.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float base = SY(cy) - (fm.ascent + fm.descent) / 2f;
            c.drawText(s, SX(x), base, tp);
        }

        // ---------------------------------------------------------- 验证码生成
        void newCaptcha() {
            ArrayList<Integer> pool = new ArrayList<Integer>();
            for (int i = 0; i < 10; i++) pool.add(i);
            java.util.Collections.shuffle(pool, rnd);
            // 6 个互不相同的候选，前 3 个当作上方数字
            for (int i = 0; i < 6; i++) cands[i] = pool.get(i);
            for (int i = 0; i < 3; i++) tops[i] = cands[i];
            // 打乱候选顺序，并算出答案下标
            for (int i = 5; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = cands[i]; cands[i] = cands[j]; cands[j] = t;
            }
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 6; j++) if (cands[j] == tops[i]) answer[i] = j;
            }
            for (int i = 0; i < 6; i++) cellState[i] = 0;
            tapped = 0;
            log("captcha tops=" + tops[0] + "," + tops[1] + "," + tops[2]
                    + " cands=" + cands[0] + "," + cands[1] + "," + cands[2] + ","
                    + cands[3] + "," + cands[4] + "," + cands[5]
                    + " answer(button#)=" + (answer[0] + 1) + "," + (answer[1] + 1) + "," + (answer[2] + 1));
        }

        // ---------------------------------------------------------- 几何常量
        static final float[] RING_X = {249, 360, 470};
        static final float RING_Y = 638, RING_R = 37;
        static final float[] CELL_X = {181, 359, 538};
        static final float[] CELL_Y = {863, 976};
        static final float CELL_W = 160, CELL_H = 94;
        static final float DAKA_X0 = 100, DAKA_Y0 = 1264, DAKA_X1 = 620, DAKA_Y1 = 1360;

        // ---------------------------------------------------------- 触摸
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            float x = e.getX() / sx, y = e.getY() / sy;
            touchCount++;
            log("touch " + (int) x + "," + (int) y + " state=" + state);
            switch (state) {
                case ST_DAKA:
                    if (x >= DAKA_X0 && x <= DAKA_X1 && y >= DAKA_Y0 && y <= DAKA_Y1) {
                        round++;
                        log("daka_tap round=" + round);
                        state = ST_CAPTCHA;
                        newCaptcha();
                        log("captcha_shown");
                        invalidate();
                    }
                    break;
                case ST_CAPTCHA: {
                    int idx = cellAt(x, y);
                    if (idx < 0) break;
                    if (idx == answer[tapped]) {
                        cellState[idx] = 1;
                        tapped++;
                        log("cell " + (idx + 1) + " correct " + tapped + "/3");
                        if (tapped >= 3) {
                            submit();
                        } else {
                            invalidate();
                        }
                    } else {
                        cellState[idx] = 2;
                        log("cell " + (idx + 1) + " WRONG (want " + (answer[tapped] + 1) + ")");
                        state = ST_LOADING;
                        invalidate();
                        h.postDelayed(new Runnable() {
                            public void run() {
                                state = ST_RESULT;
                                log("result miss (wrong tap)");
                                invalidate();
                            }
                        }, 700);
                    }
                    break;
                }
                case ST_RESULT:
                    // 结果卡「‹」区域（放宽一点，真机是 86..102 / 994..1024）
                    if (x >= 20 && x <= 170 && y >= 950 && y <= 1060) {
                        log("chevron_tap -> daka");
                        state = ST_DAKA;
                        invalidate();
                    }
                    break;
                case ST_SUCCESS:
                    if (x >= 160 && x <= 560 && y >= 880 && y <= 970) {
                        log("!!! CONFIRM TAPPED (脚本本该禁止)");
                        state = ST_CONFIRMED;
                        invalidate();
                    }
                    break;
                default:
                    break;
            }
            return true;
        }

        int cellAt(float x, float y) {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 3; c++) {
                    int i = r * 3 + c;
                    float cx = CELL_X[c], cy = CELL_Y[r];
                    if (Math.abs(x - cx) <= CELL_W / 2 && Math.abs(y - cy) <= CELL_H / 2) return i;
                }
            }
            return -1;
        }

        void submit() {
            state = ST_LOADING;
            invalidate();
            log("submit 3/3 waiting result");
            h.postDelayed(new Runnable() {
                public void run() {
                    if (winRound > 0 && round == winRound) {
                        state = ST_SUCCESS;
                        jgText = "J" + (26000 + rnd.nextInt(900));
                        log("result WIN " + jgText);
                    } else {
                        state = ST_RESULT;
                        log("result miss");
                    }
                    invalidate();
                }
            }, 1200);
        }

        // ---------------------------------------------------------- 绘制
        @Override
        protected void onDraw(Canvas c) {
            updateScale();
            drawStatusBar(c);
            drawNavBar(c);
            drawMap(c);
            drawBottomCard(c);

            if (state == ST_CAPTCHA || state == ST_LOADING) {
                if (state == ST_CAPTCHA) {
                    dim(c, 0x55000000);
                    drawCaptchaDialog(c);
                } else {
                    dim(c, 0x55000000);
                    drawLoading(c);
                }
            } else if (state == ST_RESULT) {
                drawResultCard(c);
            } else if (state == ST_SUCCESS) {
                dim(c, 0x77000000);
                drawSuccess(c);
            } else if (state == ST_CONFIRMED) {
                c.drawColor(0xFF202020);
                text(c, "已点确认（测试用，脚本绝不该走到这）", 360, 700, 30, 0xFFFF6B6B, true);
                text(c, "touch=" + touchCount, 360, 760, 26, 0xFFBBBBBB, false);
            }
            drawHud(c);
        }

        void dim(Canvas c, int argb) {
            p.setColor(argb);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }

        void drawStatusBar(Canvas c) {
            rect(c, 0, 0, 720, 64, 0xFF141414);
            textLeft(c, "12:30", 30, 32, 26, 0xFFEDEDED, false);
            text(c, "5G", 660, 32, 24, 0xFFEDEDED, false);
            text(c, "82", 690, 32, 24, 0xFFEDEDED, false);
        }

        void drawNavBar(Canvas c) {
            rect(c, 0, 64, 720, 160, 0xFFFFFFFF);
            textLeft(c, "‹", 30, 116, 46, 0xFF222222, false);
            text(c, "北斗集港服务", 360, 116, 30, 0xFF1A1A1A, true);
            round(c, 556, 92, 692, 142, 25, 0x00000000);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(SX(2));
            p.setColor(0xFFDDDDDD);
            c.drawRoundRect(new RectF(SX(556), SY(92), SX(692), SY(142)), SX(25), SY(25), p);
            p.setStyle(Paint.Style.FILL);
            text(c, "•••", 596, 117, 22, 0xFF333333, true);
            text(c, "◎", 652, 117, 26, 0xFF333333, false);
        }

        void drawMap(Canvas c) {
            rect(c, 0, 160, 720, 1100, 0xFFE9EFE9);
            // 绿地
            round(c, 20, 300, 260, 520, 18, 0xFFDCEDD8);
            round(c, 430, 620, 700, 830, 18, 0xFFDCEDD8);
            // 水系
            round(c, 300, 200, 700, 300, 14, 0xFFD3E6F4);
            // 道路（浅灰，刻意不用深色，避免污染「‹」检测窗口）
            p.setColor(0xFFD6DEE2);
            p.setStrokeWidth(SX(20));
            Path pa = new Path();
            pa.moveTo(SX(0), SY(430)); pa.lineTo(SX(720), SY(600));
            c.drawPath(pa, p);
            Path pb = new Path();
            pb.moveTo(SX(180), SY(160)); pb.lineTo(SX(300), SY(1100));
            c.drawPath(pb, p);
            p.setColor(0xFFCBD6DB);
            p.setStrokeWidth(SX(8));
            Path pc = new Path();
            pc.moveTo(SX(0), SY(700)); pc.lineTo(SX(720), SY(880));
            c.drawPath(pc, p);
            // 地标文字
            text(c, "青岛港物流智慧场站", 360, 352, 28, 0xFF6B7B6B, true);
            text(c, "前湾港区", 150, 470, 24, 0xFF7A8A7A, false);
            text(c, "长荣三期北站", 250, 620, 24, 0xFF7A8A7A, false);
            text(c, "港捷丰三期重箱站", 470, 700, 24, 0xFF7A8A7A, false);
        }

        void drawBottomCard(Canvas c) {
            round(c, 0, 1100, 720, 1620, 26, 0xFFFFFFFF);
            textLeft(c, "鲁A00000", 40, 1152, 42, 0xFF1A1A1A, true);
            textLeft(c, "2026-11-22 到期", 320, 1150, 26, 0xFF999999, false);
            textLeft(c, "请选择打卡区域", 40, 1198, 26, 0xFF666666, false);
            // 区域选择框
            round(c, 40, 1210, 680, 1256, 8, 0xFFF4F5F7);
            textLeft(c, "0755  港捷丰三期重箱站", 58, 1233, 26, 0xFF333333, false);
            text(c, "⌄", 648, 1233, 30, 0xFF888888, false);
            // 集港打卡按钮（大蓝块）
            round(c, DAKA_X0, DAKA_Y0, DAKA_X1, DAKA_Y1, 12, 0xFF2B56D1);
            text(c, "集港打卡", 360, (DAKA_Y0 + DAKA_Y1) / 2, 36, 0xFFFFFFFF, true);
            text(c, "北斗刷新时间 2026-10-24 08:29:03", 360, 1400, 24, 0xFF999999, false);
            text(c, "1781s 后刷新", 360, 1440, 24, 0xFF999999, false);
        }

        void drawCaptchaDialog(Canvas c) {
            round(c, 72, 465, 647, 1050, 18, 0xFFFDFDFD);
            text(c, "安全验证", 360, 515, 34, 0xFF1A1A1A, true);
            // 上方 3 个圈
            for (int i = 0; i < 3; i++) {
                // 上方第 i 个按顺序被点过就成为绿（点对变绿，真机行为）
                int col = (i < tapped) ? 0xFF09C362 : 0xFF2B7FFF;
                p.setColor(col);
                c.drawCircle(SX(RING_X[i]), SY(RING_Y), SX(RING_R), p);
                text(c, "" + tops[i], RING_X[i], RING_Y, 40, 0xFFFFFFFF, false);
            }
            text(c, "请按上方数字顺序，依次点击下方数字", 360, 742, 28, 0xFF888888, false);
            for (int r = 0; r < 2; r++) {
                for (int col = 0; col < 3; col++) {
                    int i = r * 3 + col;
                    int bg = 0xFFF5F5F5;
                    if (cellState[i] == 1) bg = 0xFF09C362;
                    if (cellState[i] == 2) bg = 0xFFFFE0E0;
                    round(c, CELL_X[col] - CELL_W / 2, CELL_Y[r] - CELL_H / 2,
                            CELL_X[col] + CELL_W / 2, CELL_Y[r] + CELL_H / 2, 8, bg);
                    text(c, "" + cands[i], CELL_X[col], CELL_Y[r], 40, 0xFF3C3C3C, false);
                }
            }
        }

        void drawLoading(Canvas c) {
            round(c, 160, 640, 560, 880, 18, 0xFFFDFDFD);
            p.setColor(0xFF2B7FFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(SX(6));
            c.drawCircle(SX(360), SY(720), SX(26), p);
            p.setStyle(Paint.Style.FILL);
            text(c, "正在加载...", 360, 800, 30, 0xFF666666, false);
        }

        void drawResultCard(Canvas c) {
            // 底部结果卡：从 y≈985 起，左上角带「‹」
            round(c, 20, 985, 700, 1580, 22, 0xFFF7F8FA);
            // 「‹」精确画在实测包围盒 x86..102 / y994..1024 里
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(SX(5));
            p.setColor(0xFF2B2B2B);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path ch = new Path();
            ch.moveTo(SX(102), SY(995));
            ch.lineTo(SX(86), SY(1009));
            ch.lineTo(SX(102), SY(1024));
            c.drawPath(ch, p);
            p.setStyle(Paint.Style.FILL);
            textLeft(c, "鲁A00000", 144, 1010, 34, 0xFF1A1A1A, true);
            textLeft(c, "2026-11-22 到期", 320, 1008, 24, 0xFF999999, false);
            // 橙色感叹号
            p.setColor(0xFFF5A623);
            c.drawCircle(SX(360), SY(1140), SX(34), p);
            text(c, "!", 360, 1140, 46, 0xFFFFFFFF, true);
            text(c, "未取到集港区位。", 360, 1230, 32, 0xFF333333, false);
            text(c, "港捷丰三期重箱站", 360, 1282, 32, 0xFF333333, false);
            text(c, "点击左上角 ‹ 返回", 360, 1400, 24, 0xFFAAAAAA, false);
        }

        void drawSuccess(Canvas c) {
            round(c, 100, 560, 620, 1010, 20, 0xFFFDFDFD);
            text(c, "抢单成功", 360, 620, 32, 0xFF1A1A1A, true);
            text(c, "空车进场位置", 360, 690, 30, 0xFF666666, false);
            text(c, jgText, 360, 760, 52, 0xFF2B7FFF, true);
            text(c, "请在装货前完成确认", 360, 830, 26, 0xFF999999, false);
            // 红色确认按钮（脚本绝不能点）
            round(c, 160, 880, 560, 970, 14, 0xFFE53935);
            text(c, "确认", 360, 925, 40, 0xFFFFFFFF, true);
        }

        void drawHud(Canvas c) {
            String st;
            switch (state) {
                case ST_DAKA: st = "打卡页"; break;
                case ST_CAPTCHA: st = "验证码"; break;
                case ST_LOADING: st = "加载中"; break;
                case ST_RESULT: st = "结果卡(没中)"; break;
                case ST_SUCCESS: st = "抢到!"; break;
                default: st = "已确认"; break;
            }
            textLeft(c, "MOCK 轮" + round + " " + st + " win@" + winRound, 20, 186, 22, 0xFF9AA79A, false);
        }
    }
}
