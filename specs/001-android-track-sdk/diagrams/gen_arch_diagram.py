#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the Android tracking SDK architecture diagram as SVG + draw.io (1:1 geometry)."""
import html
from xml.sax.saxutils import escape

W, H = 1120, 1010
F = "PingFang SC, Microsoft YaHei, Noto Sans CJK SC, sans-serif"
INK = "#1F2328"
MUT = "#475467"
PUR, BLU, GRN, GRY = "#6941C6", "#175CD3", "#067647", "#667085"

# ---------------- nodes: id -> (x, y, w, h, class, lines) ----------------
# lines: list of (text, bold)  -> centered vertically
N = {}

def nd(nid, x, y, w, h, cls, lines):
    N[nid] = dict(id=nid, x=x, y=y, w=w, h=h, cls=cls, lines=lines)

def ndl(nid, x, y, w, h, cls, label, lx, ly, anchor, size=12, color=MUT, bold=False):
    N[nid] = dict(id=nid, x=x, y=y, w=w, h=h, cls=cls, lines=[], label=label,
                  lx=lx, ly=ly, anchor=anchor, size=size, color=color, bold=bold)

CLS = {
    "dev":   ("#FFFFFF", "#D0D5DD", 1.6),
    "layer": ("#FBFAFF", "#C3B2F0", 1.2),
    "chan":  ("#F5FAFF", "#7EB2E8", 1.2),
    "srv":   ("#F6FEF9", "#6EE7A8", 1.2),
    "sdk":   ("#FFFFFF", PUR, 1.3),
    "core":  ("#EFF8FF", BLU, 1.5),
    "dp":    ("#ECFDF3", GRN, 1.5),
    "svrn":  ("#FFFFFF", GRN, 1.3),
    "axis":  ("#F9FAFB", GRY, 1.3),
}

# containers
ndl("dev", 16, 48, 1068, 560, "dev", "Android 设备（业务进程 + 数据平台进程）", 28, 64, "start")
ndl("L1", 36, 86, 1044, 64, "layer", "① 接口层", 1064, 97.5, "end")
ndl("L2", 36, 164, 1044, 64, "layer", "② 适配层", 1064, 175.5, "end")
ndl("L3", 36, 242, 1044, 64, "layer", "③ 队列/存储", 1064, 253.5, "end")
ndl("L4", 36, 320, 1044, 248, "chan", "④ 跨进程通道", 1064, 331.5, "end")
ndl("srv", 16, 646, 1068, 340, "srv", "数据中台 / 服务端（接收 · 实时处理 · 存储与查询）", 28, 662, "start")

COLS = [(36, 220), (236, 220), (436, 220), (636, 220), (836, 220)]
ROW1, ROW2, ROW3 = 106, 184, 260
TXT1 = ["init(config)", "track(event, props)", "trackList(events)", "setUserProfile / flush()", "optOut / optIn / 开关"]
TXT2 = ["Activity/PV 自动采集", "点击/曝光 采样", "Crash/ANR 捕获", "性能/网络 采集", "业务手动埋点封装"]
TXT3 = ["内存队列（有界）", "Room 磁盘队列（分片）", "公共属性补齐 / 去重", "过期 / 容量淘汰", "丢弃计数 / 降级"]
for i, (cx, cw) in enumerate(COLS, 1):
    nd(f"N{i}", cx, ROW1, cw, 34, "sdk", [(TXT1[i-1], False)])
    nd(f"A{i}", cx, ROW2, cw, 34, "sdk", [(TXT2[i-1], False)])
    nd(f"Q{i}", cx, ROW3, cw, 34, "sdk", [(TXT3[i-1], False)])

nd("CC", 56, 350, 380, 100, "core", [("ChannelCore 通道内核", True),
                                     ("路由 · 重试退避 · 流控", False),
                                     ("压缩加密 · 去重 · 分片", False)])
nd("DB1", 56, 474, 220, 44, "core", [("通道本地兜底库（磁盘队列）", False)])
nd("DG", 312, 474, 220, 44, "core", [("降级内存缓冲（有界丢弃）", False)])
nd("PR", 582, 350, 240, 100, "dp", [("DataProvider 系统服务模块", True),
                                    ("ContentProvider · Binder", False),
                                    ("权限校验 · 合并窗口 · 协议 v1", False)])
nd("US", 582, 474, 240, 44, "dp", [("上报客户端（批量/压缩/退避）", False)])
nd("DS", 852, 350, 212, 168, "dp", [("平台本地库（Room）", True),
                                    ("未上报缓冲 · 断网续传", False),
                                    ("状态查询 queryTrack()", False)])

nd("GW", 46, 700, 150, 44, "svrn", [("接入网关", True), ("鉴权·限流·解压校验", False)])
nd("KA", 256, 700, 180, 44, "svrn", [("Kafka 事件缓冲", False)])
nd("FL", 486, 700, 180, 44, "svrn", [("Flink / Spark 实时清洗", False)])
nd("CH", 716, 700, 170, 44, "svrn", [("ClickHouse / Doris", True), ("明细仓", False)])
nd("LB", 46, 784, 180, 44, "svrn", [("负载均衡 · 多机房容灾", False)])
nd("MY", 256, 784, 180, 44, "svrn", [("点位/元数据（MySQL）", False)])
nd("ES", 486, 784, 180, 44, "svrn", [("Elasticsearch 行为检索", False)])
nd("BI", 716, 784, 170, 44, "svrn", [("BI 看板 / 查询 API", False)])
nd("OF", 916, 784, 158, 44, "svrn", [("离线数仓（Hive）", False)])
nd("XA", 456, 868, 170, 44, "axis", [("x-axis 冷备 / 回放源", False)])

# legend swatches (top strip)
nd("sw1", 410, 16, 18, 13, "sdk", [])
nd("sw2", 600, 16, 18, 13, "core", [])
nd("sw3", 810, 16, 18, 13, "dp", [])

# ---------------- edges: (id, src, dst, exit_pt, entry_pt, wps, color, dash, w) ----
# points are absolute; fractions derived from node rects
def fr(nid, px, py):
    n = N[nid]
    return (round((px - n["x"]) / n["w"], 4), round((py - n["y"]) / n["h"], 4))

E = []
def ed(eid, s, d, ep, np_, wps=None, color=PUR, dash=None, wdt=1.6):
    E.append(dict(id=eid, s=s, d=d, ep=ep, np=np_, wps=wps or [],
                  color=color, dash=dash, w=wdt))

for i in range(1, 6):
    cx = COLS[i-1][0] + 110
    ed(f"n{i}a{i}", f"N{i}", f"A{i}", (cx, 140), (cx, 184))
    ed(f"a{i}q{i}", f"A{i}", f"Q{i}", (cx, 218), (cx, 260))

ed("busq1", "Q1", "CC", (126, 294), (246, 350), [(126, 312), (246, 312)])
ed("busq2", "Q2", "CC", (326, 294), (246, 350), [(326, 312), (246, 312)])
ed("busq3", "Q3", "CC", (526, 294), (246, 350), [(526, 312), (246, 312)])
ed("busq4", "Q4", "CC", (726, 294), (246, 350), [(726, 312), (246, 312)])
ed("busq5", "Q5", "CC", (946, 294), (246, 350), [(946, 312), (246, 312)])

ed("ipcf", "CC", "PR", (436, 400), (582, 400), color=BLU)
ed("ipcr", "PR", "CC", (582, 424), (436, 424), color=BLU)
ed("ccdb", "CC", "DB1", (166, 450), (166, 474), color=BLU)
ed("ccdg", "CC", "DG", (422, 450), (422, 474), color=BLU)
ed("prds", "PR", "DS", (822, 400), (852, 400), color=GRN)
ed("dsus", "DS", "US", (852, 500), (822, 500), color=GRN)
ed("uplink", "US", "LB", (722, 518), (46, 806),
   [(722, 686), (30, 686), (30, 806)], color=GRN, wdt=2.2)

ed("lbgw", "LB", "GW", (136, 784), (136, 744), color=GRN)
ed("gwka", "GW", "KA", (196, 722), (256, 722), color=GRN)
ed("kafl", "KA", "FL", (436, 722), (486, 722), color=GRN)
ed("flch", "FL", "CH", (666, 722), (716, 722), color=GRN)
ed("gwmy", "GW", "MY", (171, 744), (346, 784), [(171, 764), (346, 764)], color=GRN)
ed("fles", "FL", "ES", (576, 744), (576, 784), color=GRN)
ed("chbi", "CH", "BI", (801, 744), (801, 784), color=GRN)
ed("chof", "CH", "OF", (851, 744), (995, 784), [(851, 758), (995, 758)], color=GRN)
ed("bial", "BI", "OF", None, None, color=GRN)  # placeholder removed below
E.pop()
ed("bial", "BI", "OF", (886, 806), (916, 806), color=GRN)
ed("kaxa", "KA", "XA", (436, 732), (461, 868), [(461, 732)], color=GRY,
   dash="7 5", wdt=1.4)

# ---------------- standalone edge labels ----------------
LBLS = [
    ("跨进程 IPC", 509, 392, "middle", BLU),
    ("配置 / 灰度 / 降级下发", 509, 440, "middle", BLU),
    ("读取", 837, 490, "middle", GRN),
    ("HTTPS 上报（批量 / 加密 / 签名）", 450, 678, "middle", GRN),
    ("批量写入", 226, 712, "middle", GRN),
    ("订阅", 461, 712, "middle", GRN),
    ("实时入仓", 691, 710, "middle", GRN),
    ("点位 / 元数据", 250, 754, "middle", GRN),
    ("清洗结果", 566, 768, "end", GRN),
    ("T+1", 920, 750, "middle", GRN),
    ("冷备 / 回放", 451, 850, "end", GRY),
    ("SDK 接口 / 采集 / 缓冲", 434, 27, "start", INK),
    ("跨进程通道（业务进程）", 624, 27, "start", INK),
    ("数据平台 / 服务端", 834, 27, "start", INK),
    ("异步 / 容灾", 1000, 27, "start", GRY),
]

# ============================ SVG ============================
def svg_text(x, y, s, anchor="middle", size=12, color=INK, bold=False):
    fw = ' font-weight="600"' if bold else ""
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}" '
            f'fill="{color}"{fw} font-family="{F}" '
            f'dominant-baseline="central">{html.escape(s)}</text>')

def node_cy(n):
    return n["y"] + n["h"] / 2

def build_svg():
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}" font-family="{F}">']
    o.append(f'<rect width="{W}" height="{H}" fill="#FFFFFF"/>')
    for c in (PUR, BLU, GRN, GRY):
        o.append(f'<marker id="m{c[1:]}" markerWidth="8" markerHeight="8" refX="7" '
                 f'refY="3.5" orient="auto"><path d="M0,0L7,3.5L0,7Z" fill="{c}"/></marker>')
    # containers first
    for nid in ("dev", "srv", "L1", "L2", "L3", "L4"):
        n = N[nid]
        fill, stroke, sw = CLS[n["cls"]]
        o.append(f'<rect x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" rx="4"/>')
        o.append(svg_text(n["lx"], n["ly"], n["label"], n["anchor"], 12.5, MUT, True))
    # edges
    for e in E:
        pts = [e["ep"]] + e["wps"] + [e["np"]]
        s = " ".join(f"{x},{y}" for x, y in pts)
        dash = f' stroke-dasharray="{e["dash"]}"' if e["dash"] else ""
        o.append(f'<polyline points="{s}" fill="none" stroke="{e["color"]}" '
                 f'stroke-width="{e["w"]}"{dash} marker-end="url(#m{e["color"][1:]})"/>')
    # nodes
    for nid, n in N.items():
        if nid in ("dev", "srv", "L1", "L2", "L3", "L4"):
            continue
        fill, stroke, sw = CLS[n["cls"]]
        dash = ' stroke-dasharray="7 5"' if n["cls"] == "axis" else ""
        o.append(f'<rect x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" rx="6"{dash}/>')
        k = len(n["lines"])
        cy = node_cy(n)
        for i, (t, b) in enumerate(n["lines"]):
            y = cy + (i - (k - 1) / 2) * 20
            o.append(svg_text(n["x"] + n["w"] / 2, y, t, "middle", 12, INK, b))
    # edge labels
    for t, x, y, anc, col in LBLS:
        o.append(svg_text(x, y, t, anc, 11, col))
    # title + note
    o.append(svg_text(24, 26, "Android 端侧埋点 SDK 整体架构（Spec-Kit · 001）", "start", 15, INK, True))
    o.append(svg_text(56, 560, "协议：protobuf + gzip · 自定义 permission + signature 校验",
                      "start", 11, MUT))
    o.append('</svg>')
    return "\n".join(o)

# ============================ draw.io ============================
def drawio_value(n):
    return "<br>".join(("<b>%s</b>" % t) if b else t for t, b in n["lines"])

def drawio_style(n):
    fill, stroke, sw = CLS[n["cls"]]
    st = f"rounded=1;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};"
    st += f"strokeWidth={sw};fontColor={INK};fontSize=12;arcSize=12;"
    if n["cls"] == "axis":
        st += "dashed=1;dashPattern=7 5;"
    return st

def drawio_container_style(n, align):
    fill, stroke, sw = CLS[n["cls"]]
    st = (f"rounded=1;whiteSpace=wrap;html=1;fillColor={fill};strokeColor={stroke};"
          f"strokeWidth={sw};fontColor={MUT};fontSize=12.5;fontStyle=1;verticalAlign=top;"
          f"align={align};arcSize=3;spacingTop=6;")
    st += "spacingRight=16;" if align == "right" else "spacingLeft=12;"
    return st

def pt_style(t, x, y, anchor, color):
    wpx = int(len(t) * 12.5) if any(ord(c) > 0x2E7F for c in t) else int(len(t) * 7)
    if anchor == "middle":
        gx, al = x - wpx // 2, "center"
    elif anchor == "end":
        gx, al = x - wpx, "right"
    else:
        gx, al = x, "left"
    return (f'<mxCell value="{escape(t)}" style="text;html=1;align={al};verticalAlign=middle;'
            f'resizable=0;points=[];strokeColor=none;fillColor=none;fontColor={color};'
            f'fontSize=11;" vertex="1" parent="1">'
            f'<mxGeometry x="{gx}" y="{y - 9}" width="{wpx}" height="18" as="geometry"/></mxCell>')

def build_drawio():
    cells = []
    for nid in ("dev", "srv", "L1", "L2", "L3", "L4"):
        n = N[nid]
        align = "right" if nid.startswith("L") else "left"
        cells.append(
            f'<mxCell id="{nid}" value="{escape(n["label"])}" '
            f'style="{drawio_container_style(n, align)}" vertex="1" parent="1">'
            f'<mxGeometry x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" as="geometry"/></mxCell>')
    for nid, n in N.items():
        if nid in ("dev", "srv", "L1", "L2", "L3", "L4") or not n["lines"]:
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
        geo = f'<mxGeometry relative="1" as="geometry">'
        if pts:
            geo += f'<Array as="points">{pts}</Array>'
        geo += "</mxGeometry>"
        cells.append(
            f'<mxCell id="{e["id"]}" value="" style="{st}" edge="1" parent="1" '
            f'source="{e["s"]}" target="{e["d"]}">{geo}</mxCell>')
    for t, x, y, anc, col in LBLS:
        cells.append(pt_style(t, x, y, anc, col))
    # legend swatches as plain colored rects + dashed line sample
    for sid, col in (("sw1", PUR), ("sw2", BLU), ("sw3", GRN)):
        n = N[sid]
        cells.append(
            f'<mxCell id="{sid}" value="" style="rounded=0;whiteSpace=wrap;html=1;'
            f'fillColor={col};strokeColor=none;" vertex="1" parent="1">'
            f'<mxGeometry x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" as="geometry"/></mxCell>')
    cells.append(
        '<mxCell id="sw4" value="" style="line;strokeWidth=1.5;strokeColor=%s;dashed=1;dashPattern=7 5;" '
        'vertex="1" parent="1"><mxGeometry x="968" y="22" width="26" height="4" as="geometry"/></mxCell>' % GRY)
    cells.append(
        f'<mxCell id="title" value="{escape("Android 端侧埋点 SDK 整体架构（Spec-Kit · 001）")}" '
        f'style="text;html=1;align=left;verticalAlign=middle;strokeColor=none;fillColor=none;'
        f'fontColor={INK};fontSize=15;fontStyle=1;" vertex="1" parent="1">'
        f'<mxGeometry x="24" y="14" width="380" height="24" as="geometry"/></mxCell>')
    cells.append(
        f'<mxCell id="note" value="{escape("协议：protobuf + gzip · 自定义 permission + signature 校验")}" '
        f'style="text;html=1;align=left;verticalAlign=middle;strokeColor=none;fillColor=none;'
        f'fontColor={MUT};fontSize=11;" vertex="1" parent="1">'
        f'<mxGeometry x="56" y="549" width="460" height="18" as="geometry"/></mxCell>')

    body = "\n    ".join(cells)
    return f'''<mxfile host="app.diagrams.net" version="24.7.0">
  <diagram id="arch-001" name="Android埋点SDK整体架构">
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
