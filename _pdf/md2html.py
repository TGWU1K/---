# -*- coding: utf-8 -*-
"""把 使用说明.md 转成排版好的 HTML（供 Chrome 打印成 PDF）"""
import io
import os
import re

SRC = r"C:\Users\admin\Desktop\连点器\使用说明.md"
OUT = r"C:\Users\admin\Desktop\连点器\_pdf\使用说明.html"

os.makedirs(os.path.dirname(OUT), exist_ok=True)


def inline(t):
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"`(.+?)`", r"<code>\1</code>", t)
    return t


lines = io.open(SRC, encoding="utf-8").read().splitlines()
body = []
i = 0
n = len(lines)
while i < n:
    ln = lines[i].rstrip()
    s = ln.strip()
    if not s:
        i += 1
        continue
    if s == "---":
        body.append('<div class="gap"></div>')
        i += 1
        continue
    if s.startswith("# "):
        body.append("<h1>%s</h1>" % inline(s[2:]))
        i += 1
        continue
    if s.startswith("## "):
        body.append("<h2>%s</h2>" % inline(s[3:]))
        i += 1
        continue
    if s.startswith("### "):
        body.append("<h3>%s</h3>" % inline(s[4:]))
        i += 1
        continue
    m = re.match(r"!\[(.*?)\]\((.*?)\)", s)
    if m:
        alt, src = m.group(1), m.group(2).replace("\\", "/")
        if not re.match(r"^(file:|https?:|data:)", src):
            src = "file:///" + os.path.join(os.path.dirname(SRC), src).replace("\\", "/")
        body.append('<figure><img src="%s" alt="%s"><figcaption>%s</figcaption></figure>'
                    % (src, alt, alt))
        i += 1
        continue
    if s.startswith("|"):
        rows = []
        while i < n and lines[i].strip().startswith("|"):
            row = [c.strip() for c in lines[i].strip().strip("|").split("|")]
            rows.append(row)
            i += 1
        head = rows[0]
        data = [r for r in rows[1:] if not re.match(r"^[-: ]+$", r[0])]
        t = ['<table><thead><tr>']
        for c in head:
            t.append("<th>%s</th>" % inline(c))
        t.append("</tr></thead><tbody>")
        for r in data:
            t.append("<tr>")
            for c in r:
                t.append("<td>%s</td>" % inline(c))
            t.append("</tr>")
        t.append("</tbody></table>")
        body.append("".join(t))
        continue
    if s.startswith("> "):
        body.append("<blockquote>%s</blockquote>" % inline(s[2:]))
        i += 1
        continue
    if re.match(r"^- ", s):
        items = []
        while i < n and re.match(r"^- ", lines[i].strip()):
            items.append("<li>%s</li>" % inline(lines[i].strip()[2:]))
            i += 1
        body.append("<ul>%s</ul>" % "".join(items))
        continue
    if re.match(r"^\d+\. ", s):
        items = []
        while i < n and re.match(r"^\d+\. ", lines[i].strip()):
            items.append("<li>%s</li>" % inline(re.sub(r"^\d+\. ", "", lines[i].strip())))
            i += 1
        body.append("<ol>%s</ol>" % "".join(items))
        continue
    if s.startswith("*") and s.endswith("*") and not s.startswith("**"):
        body.append('<p class="ver">%s</p>' % inline(s.strip("*")))
        i += 1
        continue
    body.append("<p>%s</p>" % inline(s))
    i += 1

CSS = """
@page { size: A4; margin: 16mm 15mm 14mm 15mm; }
* { box-sizing: border-box; }
body { font-family: "Microsoft YaHei", "Segoe UI", sans-serif; font-size: 11pt;
       line-height: 1.65; color: #222; margin: 0; }
h1 { font-size: 21pt; text-align: center; margin: 0 0 4mm 0; letter-spacing: 1px; }
h2 { font-size: 15pt; margin: 7mm 0 3mm 0; padding-left: 3mm;
     border-left: 4px solid #2b56d1; color: #1a2a5a; page-break-after: avoid; }
h3 { font-size: 12.5pt; margin: 5mm 0 2mm 0; }
p { margin: 2.2mm 0; }
ul, ol { margin: 2mm 0 2mm 0; padding-left: 7mm; }
li { margin: 1.2mm 0; }
strong { color: #111; }
code { font-family: Consolas, monospace; background: #f2f3f5; padding: 0 1mm;
       border-radius: 2px; font-size: 10pt; }
figure { margin: 3mm 0 4mm 0; text-align: center; page-break-inside: avoid; }
figure img { max-width: 100%; max-height: 120mm; border: 1px solid #e3e3e3;
             border-radius: 4px; }
figcaption { font-size: 8.5pt; color: #888; margin-top: 1.5mm; }
table { width: 100%; border-collapse: collapse; margin: 3mm 0 4mm 0;
        font-size: 10pt; page-break-inside: avoid; }
th, td { border: 1px solid #dcdcdc; padding: 2mm 2.5mm; text-align: left; vertical-align: top; }
th { background: #f4f6fa; font-weight: 600; }
blockquote { margin: 3mm 0; padding: 2.5mm 4mm; background: #f3f7ff;
             border-left: 3px solid #7ba4e8; color: #33456b; font-size: 10pt; }
div.gap { height: 2mm; border-bottom: 1px solid #e8e8e8; margin: 4mm 0; }
p.ver { text-align: center; color: #999; font-size: 9pt; margin-top: 8mm; }
"""

html = ("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        "<title>集港打卡助手 使用说明</title><style>%s</style></head><body>%s</body></html>"
        % (CSS, "\n".join(body)))

io.open(OUT, "w", encoding="utf-8").write(html)
print("html ->", OUT, len(html), "chars")
