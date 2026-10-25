// 集港节点树抓取 v1
// 用途：只抓「当前这一屏」的界面节点树，结果放进剪切板（同时存一份到 /sdcard）
// 用法：AutoJs6 里开好无障碍 → 运行本脚本 → 出现悬浮条 → 切到微信小程序要抓的那一页 → 点「抓」
// 悬浮条：抓=抓这一屏并把累积内容放进剪切板 / 存=再复制一次 / 清=清空累积 / ×=退出

var CFG = {
    MAX_DEPTH: 14,     // 树最多往下走几层
    MAX_LINES: 420,    // 每屏最多输出多少行，防剪切板塞爆
    MAX_TEXT: 40,      // 单个节点的文字最多留几个字
    MAX_WIN: 4,        // 最多看几个窗口
    MAX_SAMPLE: 30     // 文字样本最多列几条
};

var buf = [];          // 累积的报告段（抓多屏会一段段叠加）
var stat = null;       // 统计
var win = null;        // 悬浮窗
var seq = 0;           // 抓取序号

function pad2(n) { return (n < 10 ? "0" : "") + n; }

function nowText() {
    var d = new Date();
    return (d.getMonth() + 1) + "-" + pad2(d.getDate()) + " " +
        pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
}

function stamp() {
    var d = new Date();
    return d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate()) + "_" +
        pad2(d.getHours()) + pad2(d.getMinutes()) + pad2(d.getSeconds());
}

function s(v) { return v == null ? "" : String(v); }

function cut(t) {
    t = s(t).replace(/\s+/g, " ").replace(/^\s+|\s+$/g, "");
    if (t.length > CFG.MAX_TEXT) t = t.substring(0, CFG.MAX_TEXT) + "…";
    return t;
}

function ind(d) {
    var r = "";
    for (var i = 0; i < d; i++) r += "  ";
    return r;
}

function shortCls(c) {
    if (!c) return "?";
    var i = c.lastIndexOf(".");
    return i >= 0 ? c.substring(i + 1) : c;
}

function resetStat() {
    stat = { total: 0, text: 0, digit: 0, click: 0, maxDepth: 0, web: 0, canvas: 0,
             digits: [], samples: [], cls: {} };
}

function safeStr(fn) {
    try { return s(fn()); } catch (e) { return ""; }
}

function nodeLine(n, depth) {
    var cls = safeStr(function () { return n.getClassName(); });
    var txt = safeStr(function () { return n.getText(); });
    var desc = safeStr(function () { return n.getContentDescription(); });
    var id = safeStr(function () { return n.getViewIdResourceName(); });
    var clk = false, vis = true, r = null;
    try { clk = n.isClickable(); } catch (e) { }
    try { vis = n.isVisibleToUser(); } catch (e) { }
    try {
        r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
    } catch (e) { r = null; }

    var line = ind(depth) + "[" + shortCls(cls) + "]";
    if (id) line += " #" + id;
    if (txt) line += ' "' + cut(txt) + '"';
    if (desc) line += ' d="' + cut(desc) + '"';
    if (r) line += " " + r.left + "," + r.top + "," + r.right + "," + r.bottom;
    if (clk) line += " [可点]";
    if (!vis) line += " [不可见]";

    // 统计
    stat.total++;
    stat.cls[cls] = (stat.cls[cls] || 0) + 1;
    if (txt.length > 0) {
        stat.text++;
        var t = txt.replace(/\s+/g, "");
        if (/^[0-9]+$/.test(t)) {
            stat.digit++;
            if (stat.digits.length < 30) stat.digits.push(t);
        }
        if (stat.samples.length < CFG.MAX_SAMPLE) stat.samples.push(cut(txt));
    }
    if (clk) stat.click++;
    if (depth > stat.maxDepth) stat.maxDepth = depth;
    if (cls.indexOf("WebView") >= 0) stat.web++;
    if (cls.indexOf("Canvas") >= 0 || cls.indexOf("Surface") >= 0) stat.canvas++;
    return line;
}

function walk(n, depth, out) {
    if (!n) return;
    if (depth > CFG.MAX_DEPTH) return;
    if (out.length >= CFG.MAX_LINES) return;
    out.push(nodeLine(n, depth));
    var cc = 0;
    try { cc = n.getChildCount(); } catch (e) { cc = 0; }
    for (var i = 0; i < cc; i++) {
        if (out.length >= CFG.MAX_LINES) break;
        var c = null;
        try { c = n.getChild(i); } catch (e) { c = null; }
        if (c) walk(c, depth + 1, out);
    }
}

function capture() {
    var ok = false;
    try { ok = auto.service != null; } catch (e) { ok = false; }
    if (!ok) {
        toast("无障碍没连上，先去设置里开 AutoJs6 的无障碍");
        return;
    }
    seq++;
    resetStat();
    var L = [];
    L.push("=== 集港节点树报告 #" + seq + " ===");
    L.push("时间 " + nowText());

    try {
        var dm = context.getResources().getDisplayMetrics();
        L.push("机型 " + device.brand + " " + device.model + " / Android " + device.release +
            " (API " + device.sdkInt + ")");
        L.push("屏幕 " + dm.widthPixels + "x" + dm.heightPixels + " 密度 " + dm.densityDpi);
    } catch (e) { }

    var pkg = "", act = "";
    try { pkg = s(currentPackage()); } catch (e) { }
    try { act = s(currentActivity()); } catch (e) { }
    L.push("前台 " + pkg + " / " + act);

    var wins = null, nw = 0;
    try {
        wins = auto.service.getWindows();
        nw = wins == null ? 0 : wins.size();
    } catch (e) { wins = null; nw = 0; }
    L.push("【窗口】共 " + nw + " 个" + (nw > CFG.MAX_WIN ? "（只列前 " + CFG.MAX_WIN + " 个）" : ""));

    for (var i = 0; i < nw && i < CFG.MAX_WIN; i++) {
        var wi = null, root = null, type = -1;
        try { wi = wins.get(i); } catch (e) { wi = null; }
        if (!wi) continue;
        try { type = wi.getType(); } catch (e) { }
        try { root = wi.getRoot(); } catch (e) { root = null; }
        if (!root) {
            L.push("");
            L.push("W" + (i + 1) + " type=" + type + " 根节点为空（读不到这一窗）");
            continue;
        }
        var wp = "";
        try { wp = s(root.getPackageName()); } catch (e) { }
        var before = stat.total;
        var out = [];
        walk(root, 1, out);
        L.push("");
        L.push("W" + (i + 1) + " type=" + type + " pkg=" + wp + " 节点=" + (stat.total - before) +
            (out.length >= CFG.MAX_LINES ? "（已截断）" : ""));
        for (var k = 0; k < out.length; k++) L.push(out[k]);
    }

    L.push("");
    L.push("【统计】节点 " + stat.total + " / 有文字 " + stat.text + " / 纯数字文本 " + stat.digit +
        " / 可点 " + stat.click + " / 最大深度 " + stat.maxDepth);
    L.push("WebView 节点 " + stat.web + " / Canvas 类节点 " + stat.canvas);

    var arr = [];
    for (var c in stat.cls) arr.push([c, stat.cls[c]]);
    arr.sort(function (a, b) { return b[1] - a[1]; });
    var top = [];
    for (var j = 0; j < arr.length && j < 8; j++) top.push(shortCls(arr[j][0]) + "(" + arr[j][1] + ")");
    L.push("类名 " + top.join(" "));

    if (stat.digits.length > 0) L.push("纯数字文本样本 " + stat.digits.join(","));
    else L.push("没有纯数字文本节点");
    if (stat.samples.length > 0) L.push("文字样本 " + stat.samples.join(" | "));

    var text = L.join("\n");
    buf.push(text);
    var all = buf.join("\n\n");
    setClip(all);
    try { files.write("/sdcard/集港节点树_" + stamp() + "_" + seq + ".txt", text); } catch (e) { }
    toast("已复制 " + all.length + " 字（第 " + seq + " 屏）");
}

function makeFloaty() {
    var can = false;
    try { can = floaty.checkPermission(); } catch (e) { can = true; }
    if (!can) {
        toast("需要悬浮窗权限，正在打开设置");
        try { floaty.requestPermission(); } catch (e) { }
        return false;
    }
    try {
        win = floaty.window(
            <vertical bg="#CC111111" padding="3">
                <button id="grab" text="抓" w="52" h="38" textSize="12sp" textColor="#ffffff" bg="#CC2B56D1" />
                <button id="copy" text="存" w="52" h="38" textSize="12sp" textColor="#ffffff" bg="#CC333333" />
                <button id="clr" text="清" w="52" h="38" textSize="12sp" textColor="#ffffff" bg="#CC333333" />
                <button id="cls" text="×" w="52" h="30" textSize="12sp" textColor="#ffffff" bg="#CC882222" />
            </vertical>
        );
    } catch (e) {
        toast("悬浮窗创建失败：" + e);
        return false;
    }
    try { win.setPosition(device.width - 68, Math.floor(device.height * 0.26)); } catch (e) { }

    win.grab.on("click", function () {
        threads.start(function () {
            try { capture(); } catch (e) { toast("抓取失败：" + e); }
        });
    });
    win.copy.on("click", function () {
        var all = buf.join("\n\n");
        if (all.length == 0) { toast("还没抓过"); return; }
        setClip(all);
        toast("已复制 " + all.length + " 字");
    });
    win.clr.on("click", function () { buf = []; seq = 0; toast("已清空"); });
    win.cls.on("click", function () {
        try { win.close(); } catch (e) { }
        exit();
    });
    return true;
}

function main() {
    var ok = false;
    try { ok = auto.service != null; } catch (e) { ok = false; }
    if (!ok) {
        toast("先去无障碍里给 AutoJs6 打开服务，再运行本脚本");
        try {
            app.startActivity({ action: "android.settings.ACCESSIBILITY_SETTINGS" });
        } catch (e) { }
        return;
    }
    if (!makeFloaty()) {
        // 没悬浮窗权限时，先抓一次，至少不白跑
        capture();
        return;
    }
    toast("悬浮条已出现：切到小程序页面后点「抓」");
    // 挂住脚本，别退出
    setInterval(function () { }, 1000);
}

main();
