#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Android on-device tracking SDK + on-device data platform (data hub) architecture.
Generates architecture.svg and architecture.drawio from one shared layout (1:1 geometry)."""
import html
from xml.sax.saxutils import escape

W, H = 1120, 900
F = "PingFang SC, Microsoft YaHei, Noto Sans CJK SC, sans-serif"
INK = "#1F2328"
MUT = "#475467"
PUR, BLU, GRN, GRY = "#6941C6", "#175CD3", "#067647", "#667085"

N = {}

def nd(nid, x, y, w, h, cls, lines):
    N[nid] = dict(id=nid, x=x, y=y, w=w, h=h, cls=cls, lines=lines)

def ndl(nid, x, y, w, h, cls, label, lx, ly, anchor):
    N[nid] = dict(id=nid, x=x, y=y, w=w, h=h, cls=cls, lines=[], label=label,
                  lx=lx, ly=ly, anchor=anchor)

CLS = {
    "dev":    ("#FFFFFF", "#D0D5DD", 1.6),
    "bp":     ("#FBFAFF", "#C3B2F0", 1.2),
    "plat":   ("#F6FEF9", "#6EE7A8", 1.2),
    "cloudb": ("#F9FAFB", "#98A2B3", 1.2),
    "sdk":    ("#FFFFFF", PUR, 1.3),
    "core":   ("#EFF8FF", BLU, 1.5),
    "dp":     ("#ECFDF3", GRN, 1.5),
    "cln":    ("#FFFFFF", "#98A2B3", 1.3),
}
CONT = ("dev", "bp", "plat", "cloudb")

ndl("dev", 16, 48, 1068, 696, "dev", "Android 设备", 28, 64, "start")
ndl("bp", 36, 86, 1044, 326, "bp", "业务进程（宿主 App · 可多进程接入）", 44, 99, "start")
ndl("plat", 36, 434, 1044, 288, "plat",
    "数据平台进程 · 端侧数据中台（系统数据服务 · 常驻）", 44, 447, "start")
ndl("cloudb", 36, 752, 1044, 120, "cloudb", "云侧（仅接口约定 · 非本期实现范围）", 44, 765, "start")

COLS = [(36, 220), (236, 220), (436, 220), (636, 220), (836, 220)]
TXT1 = ["init(config)", "track(event, props)", "trackList(events)",
        "setUserProfile / flush()", "optOut / optIn / 开关"]
TXT2 = ["Activity/PV 自动采集", "点击/曝光 采样", "Crash/ANR 捕获",
        "性能/网络 采集", "业务手动埋点封装"]
TXT3 = ["内存队列（有界）", "Room 磁盘队列（分片）", "公共属性补齐 / 去重",
        "过期 / 容量淘汰", "丢弃计数 / 降级"]
for i, (cx, cw) in enumerate(COLS, 1):
    nd(f"N{i}", cx, 106, cw, 34, "sdk", [(TXT1[i-1], False)])
    nd(f"A{i}", cx, 184, cw, 34, "sdk", [(TXT2[i-1], False)])
    nd(f"Q{i}", cx, 260, cw, 34, "sdk", [(TXT3[i-1], False)])

nd("CC", 56, 322, 300, 44, "core", [("ChannelCore 通道内核（业务进程侧）", True),
                                    ("路由 · 重试退避 · 流控 · 压缩加密", False)])
nd("DB1", 396, 322, 300, 44, "core", [("通道本地兜底库（Room 分片）", True),
                                      ("先落盘后传输 · 重启续传", False)])
nd("DG", 736, 322, 240, 44, "core", [("降级内存缓冲（有界丢弃）", False)])

nd("PR", 56, 458, 300, 56, "dp", [("DataProvider 系统数据服务模块", True),
                                  ("ContentProvider · Binder 接入", False),
                                  ("权限·签名校验·协议 v1·四值结果码", False)])
nd("PE", 396, 458, 300, 56, "dp", [("端侧处理引擎", True),
                                   ("校验 · 去重 · 合并 · 采样", False),
                                   ("公共属性补齐 · 会话生成", False)])
nd("ST", 736, 458, 300, 56, "dp", [("端侧统一存储（Room 分区）", True),
                                   ("明细 · 会话 · 设备本地留存", False),
                                   ("TTL · 配额 · 容量淘汰", False)])
nd("SC", 56, 546, 300, 56, "dp", [("上报调度器", True),
                                  ("实时批量 · 网络策略（Wi-Fi/蜂窝）", False),
                                  ("退避重试 · 断网续传", False)])
nd("MON", 396, 546, 300, 56, "dp", [("端侧自监控 · 降级控制", True),
                                    ("丢弃率 · 成功率 · 延迟统计", False),
                                    ("健康度降级 · 渐进放量", False)])
nd("LQ", 736, 546, 300, 56, "dp", [("本地查询 queryTrack()", True),
                                   ("端侧看板 / 智能体消费", False),
                                   ("只读 · 授权访问", False)])
nd("UC", 56, 650, 640, 48, "dp", [("云侧上报客户端", True),
                                  ("批量 · gzip · AES-GCM · HMAC 签名 · 断点续传", False)])
nd("CG", 76, 792, 320, 56, "cln", [("云端接收网关（接口约定）", True),
                                   ("批量鉴权 · 幂等去重 · 存储", False)])
nd("CC2", 436, 792, 320, 56, "cln", [("云端配置中心", True),
                                     ("限流 · 采样 · 降级参数下发", False)])
nd("CD", 796, 792, 240, 56, "cln", [("云端数据服务（下游）", True),
                                    ("看板 · 检索 · 分析", False)])

nd("sw1", 510, 16, 18, 13, "sdk", [])
nd("sw2", 668, 16, 18, 13, "core", [])
nd("sw3", 760, 16, 18, 13, "dp", [])

def fr(nid, px, py):
    n = N[nid]
    return (round((px - n["x"]) / n["w"], 4), round((py - n["y"]) / n["h"], 4))

E = []
def ed(eid, s, d, ep, np_, wps=None, color=PUR, dash=None, wdt=1.6):
    E.append(dict(id=eid, s=s, d=d, ep=ep, np=np_, wps=wps or [],
                  color=color, dash=dash, w=wdt))

for i, (cx, cw) in enumerate(COLS, 1):
    c = cx + cw / 2
    ed(f"n{i}a{i}", f"N{i}", f"A{i}", (c, 140), (c, 184))
    ed(f"a{i}q{i}", f"A{i}", f"Q{i}", (c, 218), (c, 260))

for i in range(1, 6):
    c = COLS[i-1][0] + COLS[i-1][1] / 2
    ed(f"busq{i}", f"Q{i}", "CC", (c, 294), (206, 322), [(c, 308), (206, 308)])
ed("q5dg", "Q5", "DG", (946, 294), (856, 322), [(946, 308), (856, 308)])
ed("ccdb", "CC", "DB1", (356, 344), (396, 344), color=BLU)
ed("ipcf", "CC", "PR", (126, 366), (126, 458), color=BLU, wdt=2.0)
ed("ipcb", "PR", "CC", (246, 458), (246, 366), color=BLU, dash="7 5")
ed("prpe", "PR", "PE", (356, 486), (396, 486), color=GRN)
ed("pest", "PE", "ST", (696, 486), (736, 486), color=GRN)
ed("stlq", "ST", "LQ", (886, 514), (886, 546), color=GRN)
ed("stsc", "ST", "SC", (766, 514), (206, 546), [(766, 530), (206, 530)], color=GRN)
ed("pemon", "PE", "MON", (546, 514), (546, 546), color=GRY, dash="5 4", wdt=1.3)
ed("monsc", "MON", "SC", (396, 574), (356, 574), color=GRN)
ed("scuc", "SC", "UC", (206, 602), (206, 650), color=GRN)
ed("monuc", "MON", "UC", (546, 602), (546, 650), color=GRY, dash="5 4", wdt=1.3)
ed("uccg", "UC", "CG", (236, 698), (236, 792), color=GRN, wdt=2.2)
ed("cc2mon", "CC2", "MON", (596, 792), (696, 574),
   [(596, 716), (716, 716), (716, 574)], color=BLU, dash="7 5")
ed("cgcd", "CG", "CD", (396, 830), (916, 848),
   [(416, 830), (416, 864), (916, 864)], color=GRY, dash="5 4", wdt=1.3)

LBLS = [
    ("① 接口层", 1064, 97.5, "end", MUT),
    ("② 适配层", 1064, 175.5, "end", MUT),
    ("③ 队列/存储", 1064, 253.5, "end", MUT),
    ("④ 跨进程通道", 1064, 421, "end", MUT),
    ("insert（批量事件·protobuf+gzip）", 296, 397, "middle", BLU),
    ("结果码 · 配置/灰度下发", 360, 424.5, "start", BLU),
    ("授权读取", 862, 531, "middle", GRN),
    ("待报读取", 478, 521, "middle", GRN),
    ("指标", 556, 534, "start", GRY),
    ("降级/放量", 376, 622, "middle", GRN),
    ("自报指标", 558, 630, "start", GRY),
    ("HTTPS 上报（批量·压缩·加密·签名）", 250, 726, "start", GRN),
    ("配置 / 灰度 / 降级参数下发", 728, 700, "start", BLU),
    ("转发 / 存储", 666, 858, "middle", GRY),
    ("SDK（业务进程）", 534, 22.5, "start", INK),
    ("跨进程通道", 692, 22.5, "start", INK),
    ("端侧数据中台", 784, 22.5, "start", INK),
    ("云侧（上下文）", 932, 22.5, "start", GRY),
]

# ============================ SVG ============================
def svg_text(x, y, s, anchor="middle", size=12, color=INK, bold=False):
    fw = ' font-weight="600"' if bold else ""
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}" '
            f'fill="{color}"{fw} font-family="{F}" '
            f'dominant-baseline="central">{html.escape(s)}</text>')

def build_svg():
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}" font-family="{F}">']
    o.append(f'<rect width="{W}" height="{H}" fill="#FFFFFF"/>')
    for c in (PUR, BLU, GRN, GRY):
        o.append(f'<marker id="m{c[1:]}" markerWidth="8" markerHeight="8" refX="7" '
                 f'refY="3.5" orient="auto"><path d="M0,0L7,3.5L0,7Z" fill="{c}"/></marker>')
    for nid in CONT:
        n = N[nid]
        fill, stroke, sw = CLS[n["cls"]]
        dash = ' stroke-dasharray="7 5"' if n["cls"] == "cloudb" else ""
        o.append(f'<rect x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" rx="4"{dash}/>')
        o.append(svg_text(n["lx"], n["ly"], n["label"], n["anchor"], 12.5, MUT, True))
    for e in E:
        pts = [e["ep"]] + e["wps"] + [e["np"]]
        s = " ".join(f"{x},{y}" for x, y in pts)
        dash = f' stroke-dasharray="{e["dash"]}"' if e["dash"] else ""
        o.append(f'<polyline points="{s}" fill="none" stroke="{e["color"]}" '
                 f'stroke-width="{e["w"]}"{dash} marker-end="url(#m{e["color"][1:]})"/>')
    for nid, n in N.items():
        if nid in CONT or not n["lines"]:
            continue
        fill, stroke, sw = CLS[n["cls"]]
        o.append(f'<rect x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" rx="6"/>')
        k = len(n["lines"])
        cy = n["y"] + n["h"] / 2
        for i, (t, b) in enumerate(n["lines"]):
            o.append(svg_text(n["x"] + n["w"] / 2, cy + (i - (k - 1) / 2) * 20,
                              t, "middle", 12, INK, b))
    for t, x, y, anc, col in LBLS:
        o.append(svg_text(x, y, t, anc, 11, col))
    o.append(svg_text(24, 26, "Android 端侧埋点 SDK · 端侧数据中台整体架构（Spec-Kit · 001）",
                      "start", 15, INK, True))
    o.append(svg_text(56, 378, "协议：protobuf + gzip · 自定义 permission + signature 校验 · 四值结果码",
                      "start", 11, MUT))
    o.append(f'<line x1="880" y1="22.5" x2="906" y2="22.5" stroke="{GRY}" '
             f'stroke-width="1.6" stroke-dasharray="7 5"/>')
    o.append('</svg>')
    return "\n".join(o)

# ============================ draw.io ============================
def drawio_value(n):
    return "<br>".join(("<b>%s</b>" % t) if b else t for t, b in n["lines"])

def drawio_style(n):
    fill, stroke, sw = CLS[n["cls"]]
    st = f"rounded=1;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};"
    st += f"strokeWidth={sw};fontColor={INK};fontSize=12;arcSize=12;"
    return st

def drawio_container_style(n):
    fill, stroke, sw = CLS[n["cls"]]
    st = (f"rounded=1;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};"
          f"strokeWidth={sw};fontColor={MUT};fontSize=12.5;fontStyle=1;verticalAlign=top;"
          f"align=left;arcSize=3;spacingTop=6;spacingLeft=12;")
    if n["cls"] == "cloudb":
        st += "dashed=1;dashPattern=7 5;"
    return st

def pt_style(t, x, y, anchor, color, size=11):
    wpx = int(len(t) * 12.5) if any(ord(c) > 0x2E7F for c in t) else int(len(t) * 7)
    if anchor == "middle":
        gx, al = x - wpx // 2, "center"
    elif anchor == "end":
        gx, al = x - wpx, "right"
    else:
        gx, al = x, "left"
    return (f'<mxCell value="{escape(t)}" style="text;html=1;align={al};verticalAlign=middle;'
            f'resizable=0;points=[];strokeColor=none;fillColor=none;fontColor={color};'
            f'fontSize={size};" vertex="1" parent="1">'
            f'<mxGeometry x="{gx}" y="{y - 9}" width="{wpx}" height="18" as="geometry"/></mxCell>')

def build_drawio():
    cells = []
    for nid in CONT:
        n = N[nid]
        cells.append(
            f'<mxCell id="{nid}" value="{escape(n["label"])}" '
            f'style="{drawio_container_style(n)}" vertex="1" parent="1">'
            f'<mxGeometry x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" as="geometry"/></mxCell>')
    for nid, n in N.items():
        if nid in CONT or not n["lines"]:
            continue
        cells.append(
            f'<mxCell id="{nid}" value="{escape(drawio_value(n))}" '
            f'style="{drawio_style(n)}" vertex="1" parent="1">'
            f'<mxGeometry x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" as="geometry"/></mxCell>')
    for e in E:
        ex, ey = fr(e["s"], *e["ep"])
        nx_, ny = fr(e["d"], *e["np"])
        st = (f"edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;jettySize=auto;orthogonalLoop=0;"
              f"exitX={ex};exitY={ey};exitDx=0;exitDy=0;entryX={nx_};entryY={ny};entryDx=0;entryDy=0;"
              f"endArrow=block;endFill=1;endSize=6;strokeColor={e['color']};strokeWidth={e['w']};")
        if e["dash"]:
            st += f"dashed=1;dashPattern={e['dash']};"
        pts = "".join(f'<mxPoint x="{x}" y="{y}"/>' for x, y in e["wps"])
        geo = '<mxGeometry relative="1" as="geometry">'
        if pts:
            geo += f'<Array as="points">{pts}</Array>'
        geo += "</mxGeometry>"
        cells.append(
            f'<mxCell id="{e["id"]}" value="" style="{st}" edge="1" parent="1" '
            f'source="{e["s"]}" target="{e["d"]}">{geo}</mxCell>')
    for t, x, y, anc, col in LBLS:
        cells.append(pt_style(t, x, y, anc, col))
    for sid in ("sw1", "sw2", "sw3"):
        n = N[sid]
        cells.append(
            f'<mxCell id="{sid}" value="" style="rounded=0;whiteSpace=wrap;html=1;'
            f'fillColor={CLS[n["cls"]][1]};strokeColor=none;" vertex="1" parent="1">'
            f'<mxGeometry x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" as="geometry"/></mxCell>')
    cells.append(
        f'<mxCell id="sw4" value="" style="line;strokeWidth=1.5;strokeColor={GRY};'
        f'dashed=1;dashPattern=7 5;" vertex="1" parent="1">'
        f'<mxGeometry x="880" y="20" width="26" height="5" as="geometry"/></mxCell>')
    cells.append(
        f'<mxCell id="title" value="{escape("Android 端侧埋点 SDK · 端侧数据中台整体架构（Spec-Kit · 001）")}" '
        f'style="text;html=1;align=left;verticalAlign=middle;strokeColor=none;fillColor=none;'
        f'fontColor={INK};fontSize=15;fontStyle=1;" vertex="1" parent="1">'
        f'<mxGeometry x="24" y="14" width="480" height="24" as="geometry"/></mxCell>')
    cells.append(
        f'<mxCell id="note" value="{escape("协议：protobuf + gzip · 自定义 permission + signature 校验 · 四值结果码")}" '
        f'style="text;html=1;align=left;verticalAlign=middle;strokeColor=none;fillColor=none;'
        f'fontColor={MUT};fontSize=11;" vertex="1" parent="1">'
        f'<mxGeometry x="56" y="369" width="520" height="18" as="geometry"/></mxCell>')

    body = "\n    ".join(cells)
    return f'''<mxfile host="app.diagrams.net" version="24.7.0">
  <diagram id="arch-001" name="Android端侧埋点SDK与数据中台架构">
    <mxGraphModel dx="1422" dy="900" grid="0" gridSize="10" guides="1" tooltips="1"
                  connect="1" arrows="1" fold="1" page="1" pageScale="1"
                  pageWidth="{W}" pageHeight="{H}" math="0" shadow="0">
      <root>
        <mxCell id="0"/>
        <mxCell id="1" parent="0"/>
        {body}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
'''

import pathlib
here = pathlib.Path(__file__).parent
here.joinpath("architecture.svg").write_text(build_svg(), encoding="utf-8")
here.joinpath("architecture.drawio").write_text(build_drawio(), encoding="utf-8")
print("written:", here / "architecture.svg", here / "architecture.drawio")
