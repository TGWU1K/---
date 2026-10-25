/**
 * 集港抢单助手 v1  (AutoJs6 / Rhino 语法)
 * ---------------------------------------------------------------
 * 职责边界（只做这一段）：
 *   司机自己进到「北斗集港服务」打卡页，点悬浮球「开始」
 *   → 循环：点【集港打卡】→ 识别验证码 → 按顺序点 3 下 → 等结果
 *   → 没中：继续下一轮
 *   → 抢到（出现「空车进场位置」+ 红色「确认」）：停止 + 炸响，等司机自己点确认
 *
 * 不点「确认」、不进/出小程序、不切页面。
 *
 * 【第一遍请保持 DEBUG = true】只识别不点击，跑 3 轮看日志判断识别准不准；
 * 日志在 /sdcard/Download/jg_daka_log.txt
 */

var CFG = {
    DEBUG: true,              // true=只识别不点击
    DEBUG_MAX: 3,             // DEBUG 模式识别几次后自动停
    MAX_ROUNDS: 40,           // 最多跑多少轮
    MAX_FAIL_STREAK: 5,       // 连续识别失败几次就停
    MAX_MISS_STREAK: 20,      // 连续 20 轮没中（正常走完但没摇到单）就停 —— 红线要求
    WAIT_DIALOG_MS: 4000,     // 点完集港打卡后等验证码弹窗
    WAIT_RESULT_MS: 10000,    // 提交后等结果
    GAP_MS: 800,              // 轮间隔
    TAP_GAP_MIN: 620,         // 点数字间隔（模拟人手）
    TAP_GAP_MAX: 900,
    MATCH_THR: 0.18,          // 字形差异阈值（实测同字 0.04~0.11，异字 ≥0.29）
    MATCH_MARGIN: 0.08        // 第一名要比第二名好这么多（实测最好 margin 0.18）
};

// 基准坐标：720x1600 真机截图实测，其他分辨率按比例换算
var BW = 720, BH = 1600;
var SX = device.width / BW, SY = device.height / BH;
function X(v) { return Math.round(v * SX); }
function Y(v) { return Math.round(v * SY); }

var D = {
    boxX0: 72, boxY0: 465, boxX1: 647, boxY1: 1050,   // 弹窗白框
    ringY: 638, ringXs: [249, 360, 470], ringR: 37,    // 上方 3 个圆圈
    cellRows: [863, 976],                              // 两行按钮中心
    cellCols: [181, 359, 538],                         // 三列按钮中心
    dakaX: 360, dakaY: 1250                            // 集港打卡按钮
};

var LOG_PATH = "/sdcard/Download/jg_daka_log.txt";
var PIC_DIR = "/sdcard/Pictures/集港助手/";
var running = false, roundNo = 0, winCount = 0, debugCount = 0, expectResult = false;

// ------------------------------------------------------------ 日志
function pad2(n) { return n < 10 ? "0" + n : "" + n; }
function nowStr() {
    var d = new Date();
    return (d.getMonth() + 1) + "-" + d.getDate() + " " +
        pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
}
function log(m) {
    var line = "[" + nowStr() + "] " + m;
    console.log(line);
    try { files.append(LOG_PATH, line + "\n"); } catch (e) { }
}

// ------------------------------------------------------------ 存图到相册 / 复制到剪贴板
// Android 10+ 直接往 /sdcard/Pictures 写会被拦，优先走 MediaStore，失败再退回直接写
function savePic(img, name) {
    var fn = name + ".png";
    try {
        if (device.sdkInt >= 29) {
            var values = new android.content.ContentValues();
            values.put("_display_name", fn);
            values.put("mime_type", "image/png");
            values.put("relative_path", "Pictures/集港助手");
            var res = context.getContentResolver();
            var uri = res.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                var tmp = files.join(files.getSdcardPath(), "jg_tmp.png");
                images.save(img, tmp);
                var bytes = files.readBytes(tmp);
                var os = res.openOutputStream(uri);
                os.write(bytes); os.flush(); os.close();
                files.remove(tmp);
                log("已存相册：Pictures/集港助手/" + fn);
                return uri.toString();
            }
        }
    } catch (e1) { log("MediaStore 存图失败，改用直接写：" + e1); }

    try {
        files.createWithDirs(PIC_DIR);
        var p = PIC_DIR + fn;
        images.save(img, p);
        try { media.scanFile(p); } catch (e2) {
            try {
                var it = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                it.setData(android.net.Uri.fromFile(new java.io.File(p)));
                context.sendBroadcast(it);
            } catch (e3) { }
        }
        log("已存文件：" + p);
        return p;
    } catch (e4) { log("存图失败：" + e4); }
    return null;
}

function copyText(t) {
    try { setClipboard(t); toast("已复制到剪贴板，直接粘贴到微信"); } catch (e) { }
}

function summaryText(res) {
    return "集港抢单助手 " + nowStr() + "\n结果：" + res +
        "\n轮数：" + roundNo + "  中签：" + winCount;
}

// ------------------------------------------------------------ 颜色工具
function R(c) { return (c >> 16) & 255; }
function Gc(c) { return (c >> 8) & 255; }
function B(c) { return c & 255; }
function isWhite(c) { return R(c) > 236 && Gc(c) > 236 && B(c) > 236; }
function isRed(c) { return R(c) > 160 && Gc(c) < 100 && B(c) < 100; }
function isOrange(c) { return R(c) > 200 && Gc(c) > 120 && Gc(c) < 200 && B(c) < 90; }
function isBlueBtn(c) { return B(c) > 150 && R(c) < 130 && Gc(c) < 160 && (B(c) - R(c)) > 60; }

// ------------------------------------------------------------ 识别
var shot = null;
function grab() {
    try { shot = images.captureScreen(); return shot; } catch (e) { return null; }
}
function pix(img, x, y) {
    x = Math.round(x); y = Math.round(y);
    if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return -1;
    return images.pixel(img, x, y);
}

// 验证码弹窗在白框左右留白处取样（内容区永远到不了这几列）
function dialogPresent(img) {
    var hits = 0, total = 0;
    var xs = [X(90), X(630)], ys = [500, 620, 740, 860, 980, 1030];
    for (var i = 0; i < xs.length; i++) {
        for (var j = 0; j < ys.length; j++) {
            total++;
            if (isWhite(pix(img, xs[i], Y(ys[j])))) hits++;
        }
    }
    return hits >= total - 2;   // 12 个点里 10 个白
}

// 只在需要时兜底：连续多久看不到打卡按钮才停（结果弹窗/加载中会短暂挡住按钮）
var noBtnSince = 0;

// 抢到：成行的红字（确认）
function successPresent(img) {
    var rows = 0;
    for (var y = 700; y <= 1120; y += 6) {
        var red = 0;
        for (var x = 140; x <= 580; x += 6) {
            if (isRed(pix(img, x, y))) red++;
            if (red >= 5) { rows++; break; }
        }
        if (rows >= 2) return true;
    }
    return false;
}

// 下方集港打卡按钮（大蓝块）→ 中心 y，找不到 -1
function findDakaY(img) {
    for (var y = Y(1150); y <= Y(1400); y += Math.max(4, Math.round(6 * SY))) {
        var blue = 0, cnt = 0;
        for (var x = X(120); x <= X(600); x += Math.max(6, Math.round(10 * SX))) {
            cnt++;
            if (isBlueBtn(pix(img, x, y))) blue++;
        }
        if (blue > cnt * 0.7) return y;
    }
    return -1;
}

// ------------------------------------------------------------ 结果卡上的「‹」返回
// 打卡没中后，结果卡左上角有「‹」，不点它就一直停在这张卡上
function screenDark(img) {
    var s = 0, n = 0;
    for (var y = Y(300); y <= Y(1200); y += Math.max(40, Y(120))) {
        for (var x = X(80); x <= X(640); x += Math.max(40, X(120))) {
            var c = pix(img, x, y);
            if (c == -1) continue;
            s += lum(c); n++;
        }
    }
    return n ? (s / n) < 120 : true;
}

// 在卡片左上角区域找「‹」的暗色像素，取重心点击；找不到就用实测兜底坐标
// 窗口按 20+ 张结果卡帧回归过：真卡里「‹」的暗像素包围盒恒为 x 86..102 / y 994..1024（720 基准），
// 窄窗(62..128 / 952..1046)内暗点数 32~36；打卡页同窗口只有 0~2 点。
function tapChevron(img) {
    var sx = 0, sy = 0, n = 0;
    for (var y = Y(952); y <= Y(1046); y += Math.max(2, Math.round(2 * SY))) {
        for (var x = X(62); x <= X(128); x += Math.max(2, Math.round(2 * SX))) {
            var c = pix(img, x, y);
            if (c != -1 && lum(c) < 130) { sx += x; sy += y; n++; }
        }
    }
    if (n < 12) { click(X(94), Y(1010)); return "兜底坐标(94,1010)"; }
    var cx = Math.round(sx / n), cy = Math.round(sy / n);
    click(cx, cy);
    return "重心(" + cx + "," + cy + ") " + n + " 点";
}

// ------------------------------------------------------------ 字形
var GS = 24;                     // 归一化尺寸
function lum(c) { return 0.299 * R(c) + 0.587 * Gc(c) + 0.114 * B(c); }

/**
 * 在 (cx,cy) 周围 44x44 窗口里提取字形，返回 24x24 的 0/1 数组
 * whiteInk=true：白字彩色底（上方圆圈）；false：深字浅底（下方按钮）
 */
function glyphVec(img, cx, cy, whiteInk) {
    var half = Math.round(22 * SX);   // 基准 720 宽下 44x44 窗口
    var x0 = Math.round(cx - half), y0 = Math.round(cy - half);
    var w = half * 2, h = half * 2;
    var mask = [], minX = w, maxX = -1, minY = h, maxY = -1;
    for (var y = 0; y < h; y++) {
        for (var x = 0; x < w; x++) {
            var c = pix(img, x0 + x, y0 + y);
            var ink = false;
            if (c != -1) {
                if (whiteInk) ink = lum(c) > 205;
                else ink = lum(c) < 140;
            }
            mask.push(ink ? 1 : 0);
            if (ink) {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
    }
    if (maxX < 0 || (maxX - minX) < 4 || (maxY - minY) < 10) return null;
    var gw = maxX - minX + 1, gh = maxY - minY + 1;
    var out = [];
    for (var gy = 0; gy < GS; gy++) {
        for (var gx = 0; gx < GS; gx++) {
            var sx = minX + Math.floor(gx * gw / GS);
            var sy = minY + Math.floor(gy * gh / GS);
            out.push(mask[sy * w + sx]);
        }
    }
    return out;
}

function dist(a, b) {
    if (!a || !b) return 1;
    var s = 0;
    for (var i = 0; i < a.length; i++) if (a[i] != b[i]) s++;
    return s / a.length;
}

function readGlyphs(img) {
    var tops = [], cells = [];
    for (var i = 0; i < 3; i++) tops.push(glyphVec(img, X(D.ringXs[i]), Y(D.ringY), true));
    for (var r = 0; r < 2; r++) {
        for (var c = 0; c < 3; c++) {
            cells.push(glyphVec(img, X(D.cellCols[c]), Y(D.cellRows[r]), false));
        }
    }
    for (var k = 0; k < 3; k++) if (!tops[k]) return { err: "上方第" + (k + 1) + "个没切到" };
    for (var k2 = 0; k2 < 6; k2++) if (!cells[k2]) return { err: "候选第" + (k2 + 1) + "个没切到" };
    return { tops: tops, cells: cells };
}

// 3 个上方数字各自匹配一个按钮（互不重复）
function mapTops(g) {
    var used = {}, order = [], scores = [];
    for (var i = 0; i < 3; i++) {
        var cand = [];
        for (var j = 0; j < 6; j++) {
            if (!used[j]) cand.push({ j: j, d: dist(g.tops[i], g.cells[j]) });
        }
        cand.sort(function (a, b) { return a.d - b.d; });
        if (!cand.length) return { err: "候选不够" };
        var p = cand[0], second = cand.length > 1 ? cand[1].d : 1;
        if (p.d > CFG.MATCH_THR) return { err: "第" + (i + 1) + "个匹配不上 " + p.d.toFixed(2) };
        if (second - p.d < CFG.MATCH_MARGIN && cand.length > 1) {
            return { err: "第" + (i + 1) + "个有歧义 " + p.d.toFixed(2) + "/" + second.toFixed(2) };
        }
        used[p.j] = true; order.push(p.j); scores.push(p.d);
    }
    return { order: order, scores: scores };
}

// ------------------------------------------------------------ 点击
function rand(a, b) { return a + Math.random() * (b - a); }
function tapCell(idx) {
    var r = idx < 3 ? 0 : 1, c = idx % 3;
    var x = X(D.cellCols[c]) + rand(-4, 4) * SX;
    var y = Y(D.cellRows[r]) + rand(-4, 4) * SY;
    click(Math.round(x), Math.round(y));
}
// 上方第 i 个圆圈是否变绿（点对了变绿）
function ringGreen(img, i) {
    var cx = X(D.ringXs[i]), cy = Y(D.ringY), rr = D.ringR * SX - 8 * SX;
    var pts = [[cx - rr, cy], [cx + rr, cy], [cx, cy - rr], [cx, cy + rr]];
    for (var k = 0; k < pts.length; k++) {
        var c = pix(img, pts[k][0], pts[k][1]);
        if (c == -1) continue;
        if (Gc(c) > 150 && (Gc(c) - B(c)) > 40 && (Gc(c) - R(c)) > 60) return true;  // 实测绿 9,195,98；蓝 43,127,255
    }
    return false;
}

// ------------------------------------------------------------ 一轮
function waitOutcome() {
    var t0 = Date.now(), closedAt = 0;
    while (running && Date.now() - t0 < CFG.WAIT_RESULT_MS) {
        sleep(260);
        var im = grab();
        if (!im) continue;
        if (successPresent(im)) return "win";
        // 弹窗消失 = 已经提交出去了（结果弹窗是另一个位置，不会误判成验证码弹窗）
        if (dialogPresent(im)) { if (closedAt) closedAt = 0; }
        else { if (!closedAt) closedAt = Date.now(); if (Date.now() - closedAt > 600) return "nomatch"; }
    }
    return "timeout";
}

function handleCaptcha(img) {
    var g = readGlyphs(img);
    if (g.err) { log("识别失败：" + g.err); return "fail"; }
    var m = mapTops(g);
    if (m.err) { log("匹配失败：" + m.err); return "fail"; }
    var seq = m.order.map(function (i) { return i + 1; }).join("-");
    log("顺序 按钮#" + seq + "  差异 " + m.scores[0].toFixed(2) + "/" +
        m.scores[1].toFixed(2) + "/" + m.scores[2].toFixed(2));

    if (CFG.DEBUG) {
        debugCount++;
        log("DEBUG 不点击。第 " + debugCount + "/" + CFG.DEBUG_MAX + " 次识别");
        try { savePic(img, "验证码_" + debugCount); } catch (e2) { }
        if (debugCount >= CFG.DEBUG_MAX) stopRun("DEBUG 识别完成，请把日志和 jg_dbg 图发我");
        return "debug";
    }

    for (var i = 0; i < 3; i++) {
        var pre = grab();
        if (pre && successPresent(pre)) return "win";     // 抢到了，绝不再点
        tapCell(m.order[i]);
        sleep(rand(CFG.TAP_GAP_MIN, CFG.TAP_GAP_MAX));
        var post = grab();
        if (post && successPresent(post)) return "win";
        if (post && !ringGreen(post, i)) log("第 " + (i + 1) + " 下没变绿（可能点空）");
    }
    log("三点完成，等结果");
    return waitOutcome();
}

function runLoop() {
    log("=== 开始 DEBUG=" + CFG.DEBUG + " 屏幕" + device.width + "x" + device.height);
    var failStreak = 0, attempts = 0, missStreak = 0;
    while (running) {
        try {
            if (currentPackage() != "com.tencent.mm") { stopRun("不在微信（页面被切走）"); return; }
            var img = grab();
            if (!img) { stopRun("截图失败"); return; }

            if (successPresent(img)) { onWin(); return; }

            if (dialogPresent(img)) {
                var r = handleCaptcha(img);
                if (r == "win") { onWin(); return; }
                if (r == "fail") {
                    failStreak++;
                    if (failStreak >= CFG.MAX_FAIL_STREAK) { stopRun("连续识别失败"); return; }
                } else if (r == "nomatch") {
                    failStreak = 0;
                    expectResult = true;
                    missStreak++;
                    log("第 " + attempts + " 轮：没中（连续 " + missStreak + "/" + CFG.MAX_MISS_STREAK + "）");
                    if (missStreak >= CFG.MAX_MISS_STREAK) { stopRun("连续 " + missStreak + " 轮没中"); return; }
                } else if (r == "timeout") {
                    log("第 " + attempts + " 轮：等结果超时，按没中处理");
                }
                sleep(CFG.GAP_MS);
                continue;
            }

            var y = findDakaY(img);
            if (y < 0) {
                if (expectResult && !screenDark(img)) {
                    var how = tapChevron(img);
                    log("结果卡：点左上角 ‹ 返回（" + how + "）");
                    expectResult = false; noBtnSince = 0;
                    sleep(900); continue;
                }
                if (!noBtnSince) noBtnSince = Date.now();
                log("没找到集港打卡按钮（可能还在加载/结果弹窗）");
                if (Date.now() - noBtnSince > 15000) {
                    if (!screenDark(img)) {     // 最后一招：当结果卡处理一次
                        log("兜底：再点一次 ‹");
                        tapChevron(img); sleep(900); noBtnSince = Date.now(); continue;
                    }
                    stopRun("15 秒没看到打卡按钮"); return;
                }
                sleep(1000); continue;
            }
            noBtnSince = 0; expectResult = false;
            failStreak = 0;
            attempts++; roundNo = attempts;
            log("第 " + attempts + " 轮：点集港打卡");
            click(X(D.dakaX) + Math.round(rand(-6, 6) * SX), y + Math.round(rand(-2, 2) * SY));

            // 等验证码弹窗
            var t0 = Date.now(), got = false;
            while (running && Date.now() - t0 < CFG.WAIT_DIALOG_MS) {
                sleep(240);
                var im2 = grab();
                if (!im2) continue;
                if (successPresent(im2)) { onWin(); return; }
                if (dialogPresent(im2)) { got = true; break; }
            }
            if (!got) { log("第 " + attempts + " 轮：没等到验证码弹窗"); failStreak++; }
            if (failStreak >= CFG.MAX_FAIL_STREAK) { stopRun("连续没等到验证码"); return; }
            if (attempts >= CFG.MAX_ROUNDS) { stopRun("到达轮数上限"); return; }
            sleep(CFG.GAP_MS);
        } catch (e) {
            log("异常：" + e);
            sleep(1000);
        }
    }
}

// ------------------------------------------------------------ 抢到
function onWin() {
    winCount++;
    running = false;
    log("*** 抢到！出现【空车进场位置】，已停止，请手动点确认 ***");
    try { files.append(LOG_PATH, "*** WIN " + nowStr() + " ***\n"); } catch (e) { }
    try {
        var sv = images.captureScreen();
        if (sv) savePic(sv, "抢到_" + nowStr().replace(/[ :\-]/g, ""));
    } catch (e2) { }
    copyText(summaryText("抢到了！去点「确认」"));
    if (ui) {
        ui.run(function () {
            stat.setText("抢到了！去点确认");
            stat.setTextColor(colors.parseColor("#FF3B30"));
        });
    }
    threads.start(function () {
        var tg = null;
        try { tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100); } catch (e) { }
        for (var k = 0; k < 3; k++) {
            try { if (tg) tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000); } catch (e) { }
            try { device.vibrate(700); } catch (e) { }
            sleep(1800);
        }
        try { device.cancelVibration(); } catch (e) { }
    });
}

// ------------------------------------------------------------ 悬浮球
var ui = null, stat = null, btnStart = null;

function stopRun(reason) {
    if (!running) return;
    running = false;
    log("停止：" + reason + "（共 " + roundNo + " 轮，中 " + winCount + "）");
    copyText(summaryText(reason));
    if (ui) {
        ui.run(function () {
            stat.setText("已停：" + reason);
            btnStart.setText("开始");
        });
    }
}

function setStatus(t) {
    if (ui) ui.run(function () { stat.setText(t); });
}

function buildUI() {
    ui = floaty.window(
        <frame w="252" h="116" bg="#E6102432" gravity="top|left">
            <vertical padding="10">
                <text id="stat" text="就绪" textColor="#FFFFFF" textSize="12sp" />
                <horizontal marginTop="6">
                    <button id="btnStart" text="开始" w="100" h="40" textSize="14sp" />
                    <button id="btnX" text="×" w="42" h="40" textSize="16sp" marginLeft="8" />
                </horizontal>
            </vertical>
        </frame>
    );
    ui.setPosition(12, 300);
    stat = ui.stat;
    btnStart = ui.btnStart;

    var lx = 0, ly = 0, ox = 0, oy = 0;
    var MD = android.view.MotionEvent.ACTION_DOWN, MM = android.view.MotionEvent.ACTION_MOVE;
    ui.setTouchable(true);
    ui.setOnTouchListener(function (v, e) {
        if (e.getAction() == MD) {
            lx = e.getRawX(); ly = e.getRawY(); ox = ui.getX(); oy = ui.getY();
            return true;
        } else if (e.getAction() == MM) {
            ui.setPosition(ox + (e.getRawX() - lx), oy + (e.getRawY() - ly));
            return true;
        }
        return true;
    });

    btnStart.click(function () {
        if (running) { stopRun("手动停止"); return; }
        running = true; roundNo = 0; debugCount = 0;
        btnStart.setText("停止");
        setStatus("运行中…");
        threads.start(function () { runLoop(); });
    });
    ui.btnX.click(function () {
        if (running) stopRun("关闭悬浮窗");
        ui.close();
    });
}

// ------------------------------------------------------------ 启动
(function () {
    files.createWithDirs(LOG_PATH);
    log("===== 脚本启动 =====");
    if (!requestScreenCapture()) {
        alert("没拿到截图权限，退出。\n重新运行并点『立即开始』。");
        exit();
    }
    if (typeof floaty == "undefined" || !floaty.checkPermission()) {
        toast("请给本应用『悬浮窗』权限");
        try { floaty.requestPermission(); } catch (e) { }
    }
    buildUI();
    log("悬浮球就位，点『开始』");
})();
