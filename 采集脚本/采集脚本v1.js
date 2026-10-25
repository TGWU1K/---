/* ============================================================
 * 北斗集港 · 抢单流程采集脚本  v1.0
 * ------------------------------------------------------------
 * 作用：只观察、只截图、只读控件树，不点击任何按钮。
 *       司机怎么点，它就怎么记。采完自动打包成一个 zip。
 *
 * 适用：某安卓手机 (—) / Android 13 / 720x1600
 * 运行环境：AutoJs6
 *
 * 用法：
 *   1. 微信里把「北斗集港服务」打卡页调出来，保持在前台
 *   2. 运行本脚本 → 系统弹「开始录制或投放您的屏幕」
 *      → 点「立即开始」（这个每次运行都要点一次，正常）
 *   3. 点面板上的「开始」，开始记录
 *   4. 正常抢单 5~10 分钟（最好包含至少一次真的抢到）
 *   5. 点「停止」，等 10 秒左右，弹出 zip 路径
 *   6. 把 zip 用微信发给对方
 * ============================================================ */

var CFG = {
    maxMinutes: 10,        // 最长采集分钟数（到时自动停）
    intervalMs: 350,       // 截图间隔
    maxJpg: 200,           // 最多存多少张 jpg
    softLimit: 60,         // 前 N 张每次变化都存，之后每 afterSoftN 次变化存 1 张
    afterSoftN: 3,
    maxPng: 15,            // 验证码弹窗原图最多存几张
    maxDump: 120,          // 控件树最多 dump 几次
    dumpGapMs: 1500,       // 两次 dump 的最小间隔
    maxOcr: 5,             // OCR 最多试几次（很慢，只做可行性验证）
    jpegQ: 86,
    dialogWhiteRatio: 0.55,
    changeRatio: 0.16,
    changeThr: 45
};

var T0 = Date.now();
var runStart = 0;
var running = false;
var finished = false;
var stats = {
    caps: 0, capFails: 0, black: 0, changes: 0,
    shots: 0, pngs: 0, dumps: 0, ocrs: 0, dialogs: 0
};
var capMs = [];
var baseDir = "";
var logPath = "";
var ocrPath = "";

/* ---------------- 小工具 ---------------- */

function pad(n) { return (n < 10 ? "0" : "") + n; }
function pad3(n) { return ("00" + n).slice(-3); }
function indent(n) { var s = ""; for (var i = 0; i < n; i++) s += "  "; return s; }

function tsName(ts) {
    var d = new Date(ts);
    return pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + pad3(d.getMilliseconds());
}

function tsFull(ts) {
    var d = new Date(ts);
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " +
        pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds()) + "." + pad3(d.getMilliseconds());
}

function log(s) {
    try { files.append(logPath, tsFull(Date.now()) + "  " + s + "\n"); } catch (e) { }
}

function setStatus(s) {
    try { win.status.setText(s); } catch (e) { }
}

/* ---------------- 目录 ---------------- */

function pickDir() {
    var d = new Date();
    var name = "dk_probe_" + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) +
        "_" + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds());
    var list = [
        files.join(files.getSdcardPath(), "Download", name),
        files.join(files.cwd(), name)
    ];
    for (var i = 0; i < list.length; i++) {
        try {
            files.ensureDir(list[i] + "/x");
            return list[i];
        } catch (e) { }
    }
    return files.cwd();
}

/* ---------------- 像素采样 ---------------- */

function signature(img) {
    var w = img.getWidth(), h = img.getHeight();
    var arr = [];
    for (var gy = 0; gy < 12; gy++) {
        for (var gx = 0; gx < 8; gx++) {
            var x = Math.floor(w * (gx + 0.5) / 8);
            var y = Math.floor(h * (0.08 + 0.92 * (gy + 0.5) / 12));
            try { arr.push(images.pixel(img, x, y)); } catch (e) { arr.push(0); }
        }
    }
    return arr;
}

function diffRatio(a, b) {
    if (!a || !b || a.length !== b.length) return 1;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
        var d = Math.abs(colors.red(a[i]) - colors.red(b[i])) +
            Math.abs(colors.green(a[i]) - colors.green(b[i])) +
            Math.abs(colors.blue(a[i]) - colors.blue(b[i]));
        if (d > CFG.changeThr) diff++;
    }
    return diff / a.length;
}

function isBlack(img) {
    var w = img.getWidth(), h = img.getHeight();
    var n = 0, dark = 0;
    for (var i = 0; i < 5; i++) {
        for (var j = 0; j < 5; j++) {
            var c = images.pixel(img, Math.floor(w * (i + 0.5) / 5), Math.floor(h * (j + 0.5) / 5));
            n++;
            if (colors.red(c) < 12 && colors.green(c) < 12 && colors.blue(c) < 12) dark++;
        }
    }
    return dark === n;
}

/* 中心区域白色占比：用来判断「安全验证」弹窗有没有出现 */
function whiteRatio(img) {
    var w = img.getWidth(), h = img.getHeight();
    var x0 = Math.floor(w * 0.15), x1 = Math.floor(w * 0.85);
    var y0 = Math.floor(h * 0.30), y1 = Math.floor(h * 0.66);
    var total = 0, white = 0;
    for (var i = 0; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
            var x = x0 + Math.floor((x1 - x0) * (j + 0.5) / 10);
            var y = y0 + Math.floor((y1 - y0) * (i + 0.5) / 10);
            var c = images.pixel(img, x, y);
            total++;
            if (colors.red(c) > 224 && colors.green(c) > 224 && colors.blue(c) > 224) white++;
        }
    }
    return white / total;
}

/* ---------------- 控件树 ---------------- */

function dumpTree() {
    var out = [];
    var root = null;
    try { root = auto.root; } catch (e) { }
    if (!root) return "【无法获取无障碍根节点】请确认 AutoJs6 的无障碍服务已开启\n";
    var count = { n: 0 };
    walk(root, 0, out, count);
    out.unshift("【控件树】共 " + count.n + " 个节点（只列有文字/描述/WebView 的）\n");
    return out.join("\n");
}

function walk(node, depth, out, count) {
    if (!node || depth > 30 || count.n > 4000) return;
    count.n++;
    var cls = "", txt = "", desc = "", id = "", b = "";
    try { cls = node.className() || ""; } catch (e) { }
    try { txt = node.text() || ""; } catch (e) { }
    try { desc = node.desc() || ""; } catch (e) { }
    try { id = node.id() || ""; } catch (e) { }
    try {
        var bb = node.bounds();
        b = "[" + bb.left + "," + bb.top + "][" + bb.right + "," + bb.bottom + "]";
    } catch (e) { }
    var keep = txt !== "" || desc !== "" || cls.indexOf("WebView") >= 0 || cls.indexOf("TextView") >= 0;
    if (keep) {
        out.push(indent(depth) + "d" + depth + " " + cls + " " + id + " t=\"" + txt + "\" d=\"" + desc + "\" " + b);
    }
    var n = 0;
    try { n = node.childCount(); } catch (e) { }
    for (var i = 0; i < n; i++) {
        var c = null;
        try { c = node.child(i); } catch (e) { }
        if (c) walk(c, depth + 1, out, count);
    }
}

/* ---------------- OCR 试探（可选，失败不影响） ---------------- */

function tryOcr(img) {
    var res = { engine: "-", ok: false, text: "", ms: 0 };
    var t = Date.now();
    try {
        if (typeof gmlkit !== "undefined" && gmlkit && gmlkit.ocr) {
            res.text = String(gmlkit.ocr(img, "zh"));
            res.engine = "gmlkit";
            res.ok = true;
        }
    } catch (e) { res.text = "gmlkit失败:" + e; }
    if (!res.ok) {
        try {
            if (typeof ocr !== "undefined" && ocr && ocr.recognize) {
                var arr = ocr.recognize(img);
                var parts = [];
                for (var i = 0; i < arr.length; i++) {
                    try {
                        parts.push(String(arr[i].text) + "@" + String(arr[i].bounds) + "(" + String(arr[i].confidence) + ")");
                    } catch (e2) {
                        parts.push(String(arr[i]));
                    }
                }
                res.engine = "ocr.recognize";
                res.text = parts.join(" | ");
                res.ok = true;
            }
        } catch (e) { res.text += " ocr.recognize失败:" + e; }
    }
    if (!res.ok) {
        try {
            if (typeof ocr !== "undefined" && ocr && ocr.recognizeText) {
                res.text = String(ocr.recognizeText(img));
                res.engine = "ocr.recognizeText";
                res.ok = true;
            }
        } catch (e) { res.text += " ocr.recognizeText失败:" + e; }
    }
    res.ms = Date.now() - t;
    return res;
}

/* ---------------- 采集主循环（跑在子线程） ---------------- */

function captureLoop() {
    var lastSig = null;
    var lastDumpTs = 0;
    var changesSinceShot = 0;
    var ocrDone = 0;
    var dumpLock = false;
    var lastStatusTs = 0;
    var wasDialog = false;

    while (running) {
        var iterStart = Date.now();
        if (iterStart - runStart > CFG.maxMinutes * 60000) {
            log("达到最长采集时间 " + CFG.maxMinutes + " 分钟，自动停止");
            running = false;
            break;
        }
        if (!device.isScreenOn()) {
            sleep(800);
            continue;
        }

        var img = null;
        var tCap = Date.now();
        try {
            img = captureScreen();
        } catch (e) {
            stats.capFails++;
            log("截图异常：" + e);
            if (stats.capFails > 10) { running = false; }
            sleep(600);
            continue;
        }
        if (!img) {
            stats.capFails++;
            log("截图返回空");
            sleep(600);
            continue;
        }
        capMs.push(Date.now() - tCap);
        stats.caps++;

        try {
            if (isBlack(img)) {
                stats.black++;
                if (stats.black === 1) log("检测到黑屏截图（可能是防截屏或息屏）");
                img.recycle();
                sleep(400);
                continue;
            }

            var sig = signature(img);
            var changed = lastSig ? (diffRatio(sig, lastSig) > CFG.changeRatio) : true;
            var wr = whiteRatio(img);

            if (changed) {
                stats.changes++;
                lastSig = sig;
                changesSinceShot++;

                var dialogLikely = wr > CFG.dialogWhiteRatio;
                var dialogRising = dialogLikely && !wasDialog;
                wasDialog = dialogLikely;

                var shouldShot = false;
                if (stats.shots < CFG.softLimit) shouldShot = true;
                else if (changesSinceShot >= CFG.afterSoftN) shouldShot = true;
                if (stats.shots >= CFG.maxJpg) shouldShot = false;

                if (dialogRising) {
                    stats.dialogs++;
                    if (stats.pngs < CFG.maxPng) {
                        var pPath = files.join(baseDir, "png_" + pad3(++stats.pngs) + "_" + tsName(Date.now()) + ".png");
                        images.save(img, pPath, "png");
                        log("弹窗原图 " + files.getName(pPath) + "  白色占比=" + wr.toFixed(2));
                    }
                    if (ocrDone < CFG.maxOcr) {
                        ocrDone++;
                        var r = tryOcr(img);
                        stats.ocrs++;
                        files.append(ocrPath,
                            "[" + tsFull(Date.now()) + "] 引擎=" + r.engine + " 成功=" + r.ok +
                            " 耗时=" + r.ms + "ms 白色占比=" + wr.toFixed(2) + "\n" +
                            "  输出: " + r.text + "\n\n");
                        log("OCR试探 引擎=" + r.engine + " 成功=" + r.ok + " " + r.ms + "ms");
                    }
                }

                if (shouldShot) {
                    var jPath = files.join(baseDir, "jpg_" + pad3(++stats.shots) + "_" + tsName(Date.now()) + ".jpg");
                    images.save(img, jPath, "jpg", CFG.jpegQ);
                    changesSinceShot = 0;
                    log("变化 → 存图 " + files.getName(jPath) + "  白色占比=" + wr.toFixed(2));
                }

                var now = Date.now();
                if (!dumpLock && stats.dumps < CFG.maxDump && now - lastDumpTs > CFG.dumpGapMs) {
                    dumpLock = true;
                    lastDumpTs = now;
                    try {
                        var tree = dumpTree();
                        var dPath = files.join(baseDir, "dump_" + pad3(++stats.dumps) + "_" + tsName(now) + ".txt");
                        files.write(dPath, "屏幕尺寸 " + device.width + "x" + device.height + "\n白色占比 " + wr.toFixed(2) + "\n\n" + tree);
                    } catch (e) { log("控件树异常：" + e); }
                    dumpLock = false;
                }
            }

            if (Date.now() - lastStatusTs > 900) {
                lastStatusTs = Date.now();
                setStatus("● 采集中 " + stats.shots);
            }
            img.recycle();
        } catch (e) {
            log("循环异常：" + e);
            try { if (img) img.recycle(); } catch (e2) { }
        }

        var spent = Date.now() - iterStart;
        if (spent < CFG.intervalMs) sleep(CFG.intervalMs - spent);
    }
    finished = true;
}

/* ---------------- 汇总 ---------------- */

function pkgVer(pkg) {
    try {
        var pi = context.getPackageManager().getPackageInfo(pkg, 0);
        return String(pi.versionName);
    } catch (e) { return "未安装/读不到"; }
}

function avg(arr) {
    if (!arr.length) return 0;
    var s = 0;
    for (var i = 0; i < arr.length; i++) s += arr[i];
    return Math.round(s / arr.length);
}

function buildSummary() {
    var L = [];
    if (!runStart) runStart = T0;
    var all = files.listDir(baseDir);
    var dumpFiles = [], jpgs = [], pngs = [];
    for (var i = 0; i < all.length; i++) {
        var n = all[i];
        if (n.indexOf("dump_") === 0) dumpFiles.push(n);
        else if (n.indexOf("jpg_") === 0) jpgs.push(n);
        else if (n.indexOf("png_") === 0) pngs.push(n);
    }

    L.push("========================================");
    L.push("北斗集港 · 抢单流程采集报告");
    L.push("生成时间：" + tsFull(Date.now()));
    L.push("========================================");
    L.push("");
    L.push("【设备】");
    L.push("  品牌型号：" + device.brand + " " + device.model);
    L.push("  Android：" + device.sdkInt);
    L.push("  屏幕：" + device.width + "x" + device.height + "  密度=" + (context.getResources().getDisplayMetrics().densityDpi));
    L.push("  AutoJs6：" + (function () { try { return app.versionName; } catch (e) { return "?"; } })());
    L.push("  微信：" + pkgVer("com.tencent.mm"));
    L.push("  无障碍服务：" + (auto.service ? "已开启" : "未开启（控件树没采到，不影响截图）"));
    L.push("");
    L.push("【采集概况】");
    L.push("  开始：" + tsFull(runStart));
    L.push("  结束：" + tsFull(Date.now()));
    L.push("  时长：" + Math.round((Date.now() - runStart) / 1000) + " 秒");
    L.push("  截图成功：" + stats.caps + " 次，平均 " + avg(capMs) + " ms，失败 " + stats.capFails + " 次，黑屏 " + stats.black + " 次");
    L.push("  画面变化：" + stats.changes + " 次");
    L.push("  存 jpg：" + jpgs.length + " 张");
    L.push("  疑似弹窗（白色占比>" + CFG.dialogWhiteRatio + "）：" + stats.dialogs + " 次，存 png 原图 " + pngs.length + " 张");
    L.push("  控件树 dump：" + dumpFiles.length + " 份");
    L.push("  OCR 试探：" + stats.ocrs + " 次（结果见 ocr.txt）");
    L.push("");
    L.push("【自检结论】");

    // 关键字命中统计
    var kw = ["集港打卡", "安全验证", "请按上方数字顺序", "未取到集港区位", "确认",
        "接单", "订单", "已达上限", "操作过于频繁", "抢单", "重新加载", "请选择打卡区域"];
    var hits = {};
    for (var k = 0; k < kw.length; k++) hits[kw[k]] = 0;
    var digitLines = [];
    var digitCount = 0;
    for (var d = 0; d < dumpFiles.length; d++) {
        var txt = "";
        try { txt = files.read(files.join(baseDir, dumpFiles[d])); } catch (e) { continue; }
        for (var k2 = 0; k2 < kw.length; k2++) {
            if (txt.indexOf(kw[k2]) >= 0) hits[kw[k2]]++;
        }
        var re = /t="([0-9])"/g, m;
        while ((m = re.exec(txt)) !== null) {
            digitCount++;
            if (digitLines.length < 12) digitLines.push(m[1] + " @ " + dumpFiles[d]);
        }
    }
    for (var k3 = 0; k3 < kw.length; k3++) {
        L.push("  控件树含「" + kw[k3] + "」：" + (hits[kw[k3]] > 0 ? ("是（" + hits[kw[k3]] + " 份 dump）") : "否"));
    }
    L.push("  控件树里单字符数字节点：" + digitCount + " 个");
    if (digitLines.length) {
        L.push("    样例：");
        for (var dl = 0; dl < digitLines.length; dl++) L.push("      " + digitLines[dl]);
    }
    L.push("");
    if (hits["集港打卡"] === 0) {
        L.push("  【推断】控件树读不到小程序里的文字 → 正式脚本要用截图识别（模板匹配），");
        L.push("          这条路可行，但需要 jpg 里能看清数字，请确认 png 原图清晰。");
    } else {
        L.push("  【推断】控件树能读到小程序文字 → 正式脚本可以直接按文字定位，最稳。");
    }
    L.push("");
    L.push("【文件说明】");
    L.push("  jpg_XXX_时间.jpg  ：画面每次明显变化就存一张（观察整体流程用）");
    L.push("  png_XXX_时间.png  ：疑似验证码/结果弹窗时的原图（做模板匹配用）");
    L.push("  dump_XXX_时间.txt ：该时刻的完整控件树（判断能不能读到文字）");
    L.push("  ocr.txt           ：设备上 OCR 的试探结果（判断要不要用 OCR）");
    L.push("  log.txt           ：时间线原始日志");
    L.push("");
    return L.join("\n");
}

/* ---------------- 打包 ---------------- */

function zipFolder(folderPath, zipPath) {
    var src = new java.io.File(folderPath);
    var fos = new java.io.FileOutputStream(zipPath);
    var zos = new java.util.zip.ZipOutputStream(fos);
    var buf = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, 32768);
    var list = src.listFiles();
    for (var i = 0; i < list.length; i++) {
        var f = list[i];
        if (!f.isFile()) continue;
        zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
        var fis = new java.io.FileInputStream(f);
        var n;
        while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
        fis.close();
        zos.closeEntry();
    }
    zos.close();
    fos.close();
}

function finish() {
    try { win.close(); } catch (e) { }
    var summary = buildSummary();
    try { files.write(files.join(baseDir, "SUMMARY.txt"), summary); } catch (e) { }
    var zipPath = baseDir + ".zip";
    var ok = true;
    try { zipFolder(baseDir, zipPath); } catch (e) {
        ok = false;
        zipPath = "打包失败：" + e + "\n目录：" + baseDir;
    }
    var brief = [];
    brief.push("时长 " + Math.round((Date.now() - runStart) / 1000) + " 秒，截图 " + stats.caps +
        " 张（平均 " + avg(capMs) + " ms）");
    brief.push("存 jpg " + stats.shots + " 张，弹窗原图 " + stats.pngs + " 张，控件树 " + stats.dumps + " 份");
    brief.push("疑似验证码弹窗 " + stats.dialogs + " 次");

    var msg = "采集完成\n\n";
    if (ok) {
        msg += "文件已保存到：\n" + zipPath + "\n\n";
        msg += "请用微信把上面这个 .zip 文件发给对方（文件传输助手也行）。\n\n";
    } else {
        msg += zipPath + "\n\n请把整个文件夹压缩后发送。\n\n";
    }
    msg += brief.join("\n") + "\n\n详细结论见 SUMMARY.txt（可直接截图发对方）。";
    dialogs.alert("采集完成", msg);
    exit();
}

/* ---------------- 界面 ---------------- */

var win = floaty.window(
    <frame w="210" h="96" bg="#E6141B2B">
        <vertical padding="8">
            <text id="status" text="● 未开始" textColor="#FFFFFF" textSize="12sp" />
            <horizontal marginTop="4">
                <button id="btnStart" text="开始" w="92" h="34" textSize="12sp" bg="#FF2C7CF6" textColor="#FFFFFF" />
                <button id="btnStop" text="停止" w="92" h="34" textSize="12sp" bg="#FF39435A" textColor="#FFFFFF" />
            </horizontal>
        </vertical>
    </frame>
);

try { win.setPosition(16, 150); } catch (e) { }

baseDir = pickDir();
files.ensureDir(baseDir + "/x");
logPath = files.join(baseDir, "log.txt");
ocrPath = files.join(baseDir, "ocr.txt");
try { files.write(logPath, ""); } catch (e) { }
try { files.write(ocrPath, ""); } catch (e) { }

/* 截图（录屏）授权：必须在主线程、启动时申请一次 */
var okCap = false;
try { okCap = requestScreenCapture(false); } catch (e) { log("申请截图权限异常：" + e); }
if (!okCap) {
    dialogs.alert("没有截图权限",
        "脚本需要「录屏」授权才能截图。\n\n" +
        "请重新运行脚本，在系统弹窗里点「立即开始」。");
    exit();
}
try { device.keepScreenOn(15 * 60 * 1000); } catch (e) { }
log("截图权限已获得");

log("脚本启动 v1.0  目录=" + baseDir);
log("设备 " + device.brand + " " + device.model + " Android" + device.sdkInt + " " + device.width + "x" + device.height);
log("无障碍=" + (auto.service ? "开" : "关"));

var startBtn = null, stopBtn = null;
try { startBtn = win.btnStart; } catch (e) { }
try { stopBtn = win.btnStop; } catch (e) { }

if (startBtn) {
    startBtn.click(function () {
        if (running) return;
        setStatus("● 准备中…");
        runStart = Date.now();
        running = true;
        finished = false;
        log("开始采集");
        threads.start(function () {
            try { captureLoop(); } catch (e) { log("采集线程异常：" + e); finished = true; }
        });
    });
}

if (stopBtn) {
    stopBtn.click(function () {
        if (!running) {
            if (finished) return;
            toast("还没开始");
            return;
        }
        running = false;
        setStatus("● 正在收尾…");
        log("手动停止");
    });
}

/* 主线程：等采集结束 */
while (!finished) {
    sleep(300);
}
try { device.cancelKeepingAwake(); } catch (e) { }
finish();
