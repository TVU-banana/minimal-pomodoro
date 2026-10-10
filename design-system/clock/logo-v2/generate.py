#!/usr/bin/env python3
"""Generate the review-only Clock icon proposals from shared vector geometry."""

from __future__ import annotations

import html
import json
import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]
ANDROID = "http://schemas.android.com/apk/res/android"
INK = "#202322"
PAPER = "#F8F8F5"
MUTED = "#646965"
BLUE = "#315BCB"
DARK = "#1B1B1D"
DARK_INK = "#F2F2F3"
DARK_MUTED = "#B8B8BC"
DARK_BLUE = "#ABC2FF"


def number(value: float) -> str:
    return f"{value:.3f}".rstrip("0").rstrip(".")


def circle(x: float, y: float, radius: float) -> str:
    x, y, r = number(x), number(y), number(radius)
    return f"M{x},{number(float(y)-radius)}a{r},{r} 0 1 1 0,{number(2*radius)}a{r},{r} 0 1 1 0,{number(-2*radius)}Z"


def rectangle(x: float, y: float, w: float, h: float, radius: float) -> str:
    r = min(radius, w / 2, h / 2)
    n = number
    return (
        f"M{n(x+r)},{n(y)}H{n(x+w-r)}a{n(r)},{n(r)} 0 0 1 {n(r)},{n(r)}"
        f"V{n(y+h-r)}a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(r)}H{n(x+r)}"
        f"a{n(r)},{n(r)} 0 0 1 {n(-r)},{n(-r)}V{n(y+r)}"
        f"a{n(r)},{n(r)} 0 0 1 {n(r)},{n(-r)}Z"
    )


def rounded_polygon(points: list[tuple[float, float]], inset: float) -> str:
    """Round each corner with a quadratic segment without changing the keyline."""
    corners = []
    for i, p in enumerate(points):
        previous, following = points[i - 1], points[(i + 1) % len(points)]
        a = math.dist(previous, p)
        b = math.dist(following, p)
        cut = min(inset, a * 0.4, b * 0.4)
        incoming = tuple(p[j] + (previous[j] - p[j]) * cut / a for j in (0, 1))
        outgoing = tuple(p[j] + (following[j] - p[j]) * cut / b for j in (0, 1))
        corners.append((incoming, p, outgoing))
    out = f"M{number(corners[0][0][0])},{number(corners[0][0][1])}"
    for incoming, p, outgoing in corners:
        out += (
            f"L{number(incoming[0])},{number(incoming[1])}"
            f"Q{number(p[0])},{number(p[1])} {number(outgoing[0])},{number(outgoing[1])}"
        )
    return out + "Z"


def gear(outer: float, valley: float, corner: float) -> str:
    points = []
    for tooth in range(6):
        center = -90 + tooth * 60
        for offset, radius in [(-10, outer), (10, outer), (18, valley), (42, valley)]:
            angle = math.radians(center + offset)
            points.append((12 + radius * math.cos(angle), 12 + radius * math.sin(angle)))
    return rounded_polygon(points, corner)


def stroke(d: str, width: float = 1.8) -> dict:
    return {"d": d, "fill": "none", "stroke": "currentColor", "stroke-width": number(width),
            "stroke-linecap": "round", "stroke-linejoin": "round"}


def fill(d: str, evenodd: bool = False) -> dict:
    result = {"d": d, "fill": "currentColor"}
    if evenodd:
        result["fill-rule"] = "evenodd"
    return result


CHECK_HOLE = rounded_polygon([
    (6.65, 11.8), (8.05, 10.4), (10.65, 13), (16.25, 7.4),
    (17.65, 8.8), (10.65, 15.8),
], 0.48)
HAND_HOLE = rounded_polygon([
    (10.9, 6.8), (13.1, 6.8), (13.1, 11.4), (17.05, 13.7),
    (15.95, 15.6), (10.9, 12.7),
], 0.5)


def common_icons(weight: float, soft: bool = False, solid: bool = False) -> dict:
    cap = 1.25 if soft else 0.65
    play = rounded_polygon([(7.2, 4.3), (20.2, 12), (7.2, 19.7)], cap)
    icons = {
        "ic_play_outline": [fill(play)] if solid else [stroke(play, weight)],
        "ic_play_primary": [fill(play)],
        "ic_pause_primary": [fill(rectangle(6, 4.2, 4.2, 15.6, cap)) ,
                             fill(rectangle(13.8, 4.2, 4.2, 15.6, cap))],
        "ic_stop_primary": [fill(rectangle(5.7, 5.7, 12.6, 12.6, 2 if soft else 1.25))],
        "ic_add_preset_thin": [stroke("M12,4.5V19.5M4.5,12H19.5", 2.35 if solid else weight)],
        "ic_chevron_left": [stroke("M14.7,5.5L8.2,12L14.7,18.5", 2.35 if solid else weight)],
        "ic_chevron_right": [stroke("M9.3,5.5L15.8,12L9.3,18.5", 2.35 if solid else weight)],
        "ic_more": [fill(circle(12, y, 1.55 if solid else 1.35)) for y in (5.5, 12, 18.5)],
        "ic_habit_check": [fill(circle(12, 12, 10) + CHECK_HOLE, True)],
        "ic_habit_unchecked": [stroke(circle(12, 12, 10 - (1.8 if solid else weight - 0.35) / 2),
                                      1.8 if solid else weight - 0.35)],
        "ic_theme_sun": [stroke(circle(12, 12, 4), weight),
                         stroke("M12,2.6V4M12,20V21.4M2.6,12H4M20,12H21.4"
                                "M5.35,5.35L6.35,6.35M17.65,17.65L18.65,18.65"
                                "M5.35,18.65L6.35,17.65M17.65,6.35L18.65,5.35", weight)],
        "ic_theme_moon": [stroke("M20.5,13.1A8.6,8.6 0 1 1 10.9,3.5"
                                 "A6.35,6.35 0 0 0 20.5,13.1Z", weight)],
        "ic_delete_outline": [stroke("M4.7,6.4H19.3M9,6.4V3.6H15V6.4"
                                     "M6.3,6.4L7,18.6Q7.1,20.4 9,20.4H15"
                                     "Q16.9,20.4 17,18.6L17.7,6.4M10.1,10V16.9M13.9,10V16.9", weight)],
        "ic_manage_tags": [stroke("M13.3,3.8H6.3A2.5,2.5 0 0 0 3.8,6.3V13.3"
                                  "L11.15,20.65Q12,21.5 12.85,20.65L20.65,12.85"
                                  "Q21.5,12 20.65,11.15L13.3,3.8Z", weight),
                           fill(circle(8, 8, 1.25))],
    }
    if solid:
        tag = rounded_polygon([(4, 4), (12.8, 4), (21, 12.2), (12.2, 21), (4, 12.8)], 1.2)
        icons["ic_manage_tags"] = [fill(tag + circle(8.1, 8.1, 1.55), True)]
        icons["ic_theme_sun"] = [fill(circle(12, 12, 4.6)),
                                 stroke("M12,2.5V4M12,20V21.5M2.5,12H4M20,12H21.5"
                                        "M5.3,5.3L6.3,6.3M17.7,17.7L18.7,18.7"
                                        "M5.3,18.7L6.3,17.7M17.7,6.3L18.7,5.3", 2)]
        icons["ic_theme_moon"] = [fill("M20.5,13.1A8.6,8.6 0 1 1 10.9,3.5"
                                       "A6.35,6.35 0 0 0 20.5,13.1Z")]
        body = rectangle(6, 6.5, 12, 14, 1.8)
        slot1 = rectangle(9.1, 9.5, 1.7, 7.7, 0.85)
        slot2 = rectangle(13.2, 9.5, 1.7, 7.7, 0.85)
        icons["ic_delete_outline"] = [fill(body + slot1 + slot2, True),
                                      fill(rectangle(4, 4.7, 16, 2, 0.9)),
                                      fill(rectangle(9, 2.5, 6, 2.2, 0.7))]
    return icons


SUITES = []

a = common_icons(1.8)
a.update({
    "brand-mark": [stroke("M12,3A9,9 0 1 0 21,12", 2.25),
                   stroke("M12,7.15V12L16,14.3", 2.25),
                   fill(rounded_polygon([(17.25,3.4),(20.6,6.75),(17.8,7.1)], 0.35))],
    "ic_nav_timer": [stroke(circle(12, 13.6, 7.5)),
                     stroke("M9.3,2.8H14.7M12,2.8V6.1M18.8,6.8L20.2,5.4"
                            "M12,9.4V13.6L15.1,15.4")],
    "ic_nav_habits": [stroke("M20.1,11.3V17.6A2.6,2.6 0 0 1 17.5,20.2H6.5"
                             "A2.6,2.6 0 0 1 3.9,17.6V6.4A2.6,2.6 0 0 1 6.5,3.8H13.2"
                             "M8.2,11.2L11.7,14.7L20.1,6.3")],
    "ic_nav_reports": [stroke("M4.2,4.2V18.3A1.5,1.5 0 0 0 5.7,19.8H20.3"
                              "M7.4,14.4L11.25,10.25L14.65,12.5L20,6.25")],
    "ic_nav_settings": [stroke(gear(9.25, 7.15, 0.48), 1.7),
                        stroke(circle(12, 12, 3.05), 1.7)],
})
a["ic_notification_timer"] = [stroke(circle(12, 12, 8.5), 2),
                               stroke("M12,6.6V12L15.6,14.1", 2)]
SUITES.append({"id": "A", "folder": "a-precision", "name": "精密刻度", "english": "PRECISION",
               "weight": "1.8", "style": "克制线条 · 精密仪表", "icons": a,
               "description": "开口时环与精细线条，延续 Clock 的安静和秩序。",
               "detail": "秒表、开放勾选框、趋势图、六齿齿轮。最贴近现有界面。"})

b = common_icons(2, soft=True)
hourglass = "M7.2,3.5H16.8V6.3C16.8,9 13.3,9.5 13.3,12C13.3,14.5 16.8,15 16.8,17.7V20.5H7.2V17.7C7.2,15 10.7,14.5 10.7,12C10.7,9.5 7.2,9 7.2,6.3Z"
b.update({
    "brand-mark": [stroke(hourglass, 2.1), stroke("M6,3.5H18M6,20.5H18", 2.1),
                   fill(rounded_polygon([(9.3,6.8),(14.7,6.8),(12,9.15)], 0.4)),
                   fill(rounded_polygon([(9.3,17.5),(12,15.5),(14.7,17.5)], 0.4))],
    "ic_nav_timer": [stroke(hourglass, 2), stroke("M6,3.5H18M6,20.5H18", 2),
                     stroke("M9.6,17.5L12,15.6L14.4,17.5", 1.75)],
    "ic_nav_habits": [stroke(rectangle(3.8, 5.1, 16.4, 15.1, 3.2), 2),
                      stroke("M8,3V7M16,3V7M4,9.4H20M8.35,14.2L10.7,16.55L15.65,11.6", 2)],
    "ic_nav_reports": [stroke("M4,18.5H20", 2),
                       fill(rectangle(5.3, 11.3, 3.1, 4.2, 1.2)),
                       fill(rectangle(10.45, 5.2, 3.1, 10.3, 1.2)),
                       fill(rectangle(15.6, 8.1, 3.1, 7.4, 1.2))],
    "ic_nav_settings": [stroke("M3.6,6H7.3M12.7,6H20.4M3.6,12H12.3M17.7,12H20.4"
                               "M3.6,18H5.3M10.7,18H20.4", 2),
                        stroke(circle(10, 6, 2.3), 2),
                        stroke(circle(15, 12, 2.3), 2),
                        stroke(circle(8, 18, 2.3), 2)],
})
b["ic_notification_timer"] = [stroke(hourglass, 2), stroke("M6,3.5H18M6,20.5H18", 2)]
SUITES.append({"id": "B", "folder": "b-rhythm", "name": "温润日序", "english": "RHYTHM",
               "weight": "2.0", "style": "柔和几何 · 日常陪伴", "icons": b,
               "description": "用沙漏表达时间流动，用圆角与日历表达每天的积累。",
               "detail": "沙漏、日历勾选、圆角柱图、调节滑杆。更温和、亲近。"})

c = common_icons(2.2, solid=True)
timer_hand = rounded_polygon([(10.95,9.05),(13.05,9.05),(13.05,13.1),
                              (16.1,14.85),(15.05,16.65),(10.95,14.25)], 0.45)
c.update({
    "brand-mark": [fill(circle(12, 12, 9.65) + HAND_HOLE, True)],
    "ic_nav_timer": [fill(circle(12, 13.9, 7.55) + timer_hand, True),
                     fill(rectangle(8.9, 2.3, 6.2, 2.2, 0.8)),
                     fill(rectangle(11, 4.1, 2, 3.4, 0.45)),
                     fill(rounded_polygon([(17.55,6.1),(19.3,4.6),(20.75,6.25),(19,7.75)],0.45))],
    "ic_nav_habits": [fill(rectangle(3.4, 3.4, 17.2, 17.2, 3.4) + CHECK_HOLE, True)],
    "ic_nav_reports": [fill(rectangle(3.8, 12, 4, 8.2, 1.1)),
                       fill(rectangle(10, 4, 4, 16.2, 1.1)),
                       fill(rectangle(16.2, 8, 4, 12.2, 1.1))],
    "ic_nav_settings": [fill(gear(9.5, 7.3, 0.65) + circle(12, 12, 3.5), True)],
})
c["ic_notification_timer"] = [fill(circle(12, 12, 9) + HAND_HOLE, True)]
SUITES.append({"id": "C", "folder": "c-silhouette", "name": "实心留白", "english": "SILHOUETTE",
               "weight": "filled", "style": "清晰轮廓 · 留白识别", "icons": c,
               "description": "靠轮廓与镂空传达含义，缩小后仍保持明确的视觉分量。",
               "detail": "实心秒表、镂空勾选、三柱图、实心齿轮。小尺寸辨识更强。"})

NAMES = {
    "brand-mark": "应用标志", "ic_clock": "应用图标", "ic_nav_timer": "计时",
    "ic_nav_habits": "打卡", "ic_nav_reports": "记录", "ic_nav_settings": "设置",
    "ic_play_outline": "开始任务", "ic_play_primary": "继续", "ic_pause_primary": "暂停",
    "ic_stop_primary": "结束", "ic_add_preset_thin": "添加", "ic_chevron_left": "上一页",
    "ic_chevron_right": "下一页", "ic_manage_tags": "标签", "ic_delete_outline": "删除",
    "ic_more": "更多", "ic_theme_sun": "浅色主题", "ic_theme_moon": "深色主题",
    "ic_habit_check": "已完成", "ic_habit_unchecked": "未完成", "ic_notification_timer": "计时通知",
}
NAV = ["ic_nav_timer", "ic_nav_habits", "ic_nav_reports", "ic_nav_settings"]
ACTIONS = ["ic_play_outline", "ic_play_primary", "ic_pause_primary", "ic_stop_primary",
           "ic_add_preset_thin", "ic_chevron_left", "ic_chevron_right", "ic_manage_tags",
           "ic_delete_outline", "ic_more", "ic_theme_sun", "ic_theme_moon",
           "ic_habit_check", "ic_habit_unchecked", "ic_notification_timer"]
ALIASES = {
    "ic_add": "ic_add_preset_thin", "ic_play": "ic_play_primary",
    "ic_play_tag": "ic_play_primary", "ic_play_white": "ic_play_primary",
    "ic_pause": "ic_pause_primary", "ic_pause_white": "ic_pause_primary",
    "ic_stop": "ic_stop_primary", "ic_stop_white": "ic_stop_primary",
    "ic_stop_light": "ic_stop_primary",
}


def body(paths: list[dict], color: str | None = None) -> str:
    parts = []
    for path in paths:
        attrs = dict(path)
        if color:
            attrs = {k: color if v == "currentColor" else v for k, v in attrs.items()}
        parts.append("<path " + " ".join(f'{k}="{html.escape(v, quote=True)}"'
                                         for k, v in attrs.items()) + "/>")
    return "\n".join(parts)


def standalone(paths: list[dict], title: str, size: int = 24,
               description: str = "", color: str | None = None) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}" role="img" aria-labelledby="title desc">\n'
        f'<title id="title">{html.escape(title)}</title>\n'
        f'<desc id="desc">{html.escape(description)}</desc>\n'
        f'{body(paths, color)}\n</svg>\n'
    )


def inline(suite: dict, key: str, size: int = 24) -> str:
    return f'<svg viewBox="0 0 24 24" width="{size}" height="{size}" aria-hidden="true">{body(suite["icons"][key])}</svg>'


def native(paths: list[dict], width: str, height: str, viewport: int, color: str) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<vector xmlns:android="{ANDROID}" android:width="{width}" '
             f'android:height="{height}" android:viewportWidth="{viewport}" '
             f'android:viewportHeight="{viewport}">']
    keys = {"d": "pathData", "fill": "fillColor", "stroke": "strokeColor",
            "stroke-width": "strokeWidth", "stroke-linecap": "strokeLineCap",
            "stroke-linejoin": "strokeLineJoin", "fill-rule": "fillType"}
    for path in paths:
        attrs = []
        for key, value in path.items():
            if key in ("fill", "stroke"):
                value = color if value == "currentColor" else value
                value = "@android:color/transparent" if value == "none" else value
            elif key == "fill-rule":
                value = "evenOdd"
            attrs.append(f'android:{keys[key]}="{html.escape(value, quote=True)}"')
        lines.append("    <path " + " ".join(attrs) + " />")
    return "\n".join(lines + ["</vector>", ""])


def scale_path(d: str, factor: float, offset: float) -> str:
    """Bake a uniform affine transform into SVG path coordinates for Android parity."""
    tokens = re.findall(r"[AaCcHhLlMmQqSsTtVvZz]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", d)
    arities = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4,
               "T": 2, "A": 7}
    result, i, command = [], 0, None
    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            result.append(command)
            i += 1
            if command.upper() == "Z":
                continue
        count = arities[command.upper()]
        values = [float(x) for x in tokens[i:i+count]]
        for j, value in enumerate(values):
            if command.upper() == "A" and j in (2, 3, 4):
                values[j] = value
            elif command.upper() == "A" and j in (0, 1):
                values[j] = value * factor
            else:
                values[j] = value * factor + (offset if command.isupper() else 0)
        result.append(",".join(number(x) for x in values))
        i += count
    return " ".join(result)


def launcher_paths(suite: dict, color: str) -> list[dict]:
    paths = []
    for path in suite["icons"]["brand-mark"]:
        result = dict(path)
        result["d"] = scale_path(path["d"], 2.7, 21.6)
        if "stroke-width" in result:
            result["stroke-width"] = number(float(result["stroke-width"]) * 2.7)
        result = {k: color if v == "currentColor" else v for k, v in result.items()}
        paths.append(result)
    return paths


def text(x: float, y: float, content: str, size: float = 16, color: str = INK,
         weight: int = 400, anchor: str = "start") -> str:
    return (f'<text x="{x}" y="{y}" fill="{color}" font-size="{size}" '
            f'font-weight="{weight}" text-anchor="{anchor}">{html.escape(content)}</text>')


def placed(suite: dict, key: str, x: float, y: float, size: float, color: str) -> str:
    return f'<g transform="translate({x},{y}) scale({size/24})">{body(suite["icons"][key], color)}</g>'


def brand_tile(suite: dict, x: int, y: int, size: int = 96) -> str:
    return (f'<g transform="translate({x},{y}) scale({size/108})">'
            f'<rect width="108" height="108" rx="24" fill="{BLUE}"/>'
            f'{body(launcher_paths(suite, "#FFFFFF"))}</g>')


def navigation_strip(suite: dict, x: int, y: int, width: int = 344,
                     dark: bool = False, icon_size: int = 20, labels: bool = True) -> str:
    background, muted, primary, soft = (
        (DARK, DARK_MUTED, DARK_BLUE, "#26334E") if dark
        else (PAPER, MUTED, BLUE, "#E9EEFC")
    )
    parts = [f'<rect x="{x}" y="{y}" width="{width}" height="64" rx="14" fill="{background}"/>']
    cell = (width - 32) / 4
    parts.append(f'<rect x="{x+18}" y="{y+6}" width="{cell-4}" height="52" rx="12" fill="{soft}"/>')
    for i, key in enumerate(NAV):
        cx = x + 16 + cell * (i + 0.5)
        color = primary if i == 0 else muted
        parts.append(placed(suite, key, cx - icon_size / 2, y + 12, icon_size, color))
        if labels:
            parts.append(text(cx, y + 48, NAMES[key], 11, color, 500, "middle"))
    return "".join(parts)


def board() -> str:
    parts = [f'<rect width="1408" height="1152" fill="{PAPER}"/>',
             text(56, 80, "Clock", 46, weight=600),
             text(232, 78, "三套 SVG 图标方案", 24, weight=500),
             text(1352, 78, "2026.10  /  DESIGN EDITION", 13, MUTED, anchor="end"),
             text(58, 123, "让图形先说清功能，再呈现性格。", 17, MUTED)]
    for index, suite in enumerate(SUITES):
        y = 168 + index * 306
        parts.append(f'<rect x="40" y="{y}" width="1328" height="282" rx="22" fill="#FFFFFF" stroke="#E0E3DD"/>')
        parts.extend([text(72, y+44, suite["id"], 15, BLUE, 600),
                      text(72, y+87, suite["name"], 26, weight=600),
                      text(72, y+118, suite["english"], 12, MUTED, 500),
                      text(72, y+160, suite["style"].split(" · ")[0], 15, MUTED),
                      text(72, y+185, suite["style"].split(" · ")[1], 15, MUTED)])
        parts.append(brand_tile(suite, 318, y+40, 104))
        parts.append(text(370, y+172, "应用标志", 14, MUTED, anchor="middle"))
        for key, cx in zip(NAV, [612, 810, 1008, 1206]):
            parts.append(placed(suite, key, cx-29, y+49, 58, INK))
            parts.append(text(cx, y+137, NAMES[key], 15, MUTED, anchor="middle"))
        parts.append(text(488, y+226, "20dp", 12, MUTED))
        parts.append(navigation_strip(suite, 566, y+182, 344))
        parts.append(navigation_strip(suite, 962, y+182, 344, dark=True))
    parts.append(text(56, 1130, "独立 SVG  /  单色可用  /  原生 Android 资源随附", 13, MUTED))
    return svg_artboard(1408, 1152, "Clock — three icon directions", "".join(parts))


def svg_artboard(width: int, height: int, title: str, content: str) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}" '
            'font-family="PingFang SC, Helvetica Neue, Arial, sans-serif">'
            f'<title>{html.escape(title)}</title>{content}</svg>\n')


def sheet(suite: dict) -> str:
    parts = [f'<rect width="1280" height="940" fill="{PAPER}"/>',
             text(48, 69, f'{suite["id"]} / {suite["name"]}', 32, weight=600),
             text(48, 105, suite["description"], 16, MUTED),
             text(1232, 68, suite["english"], 13, MUTED, anchor="end"),
             brand_tile(suite, 70, 150, 120), text(130, 302, "应用标志", 14, MUTED, anchor="middle")]
    for key, cx in zip(NAV, [430, 650, 870, 1090]):
        parts.append(placed(suite, key, cx-34, 174, 68, INK))
        parts.append(text(cx, 282, NAMES[key], 15, MUTED, anchor="middle"))
    parts.append(f'<path d="M48,340H1232" stroke="#E0E3DD"/>')
    for i, key in enumerate(ACTIONS):
        x, y = 48 + (i % 8) * 150, 392 + (i // 8) * 110
        parts.append(placed(suite, key, x+48, y, 34, INK))
        parts.append(text(x+65, y+65, NAMES[key], 13, MUTED, anchor="middle"))
    parts.append(text(48, 679, "导航 · 实际 20dp", 14, MUTED))
    parts.append(navigation_strip(suite, 48, 702, 360))
    parts.append(navigation_strip(suite, 448, 702, 360, dark=True))
    parts.append(text(877, 715, "尺寸检查", 14, MUTED))
    for size, x in zip([16,20,24,32], [882,951,1026,1114]):
        parts.append(placed(suite, "ic_nav_timer", x, 737, size, INK))
        parts.append(text(x+size/2, 800, str(size), 12, MUTED, anchor="middle"))
    parts.append(text(48, 886, suite["detail"], 16, MUTED))
    return svg_artboard(1280, 940, f'Clock {suite["id"]} {suite["name"]}', "".join(parts))


def html_preview() -> str:
    cards = []
    for suite in SUITES:
        folder = suite["folder"]
        core = "".join(
            f'<a class="icon-cell" href="{folder}/svg/{key}.svg" download '
            f'aria-label="下载{html.escape(NAMES[key])} SVG" title="{html.escape(NAMES[key])}">'
            f'{inline(suite,key,44)}<span class="glyph-label">{NAMES[key]}</span></a>'
            for key in NAV
        )
        tools = "".join(
            f'<a class="tool-cell" href="{folder}/svg/{key}.svg" download title="{NAMES[key]}" '
            f'aria-label="下载{NAMES[key]} SVG">{inline(suite,key)}'
            f'<span class="glyph-label">{NAMES[key]}</span></a>' for key in ACTIONS
        )
        stages = []
        for theme in ["light","dark"]:
            navbar = "".join(
                f'<button class="nav-item {"is-selected" if i==0 else ""}" '
                f'aria-label="{NAMES[key]}" aria-pressed="{"true" if i==0 else "false"}">'
                f'{inline(suite,key,20)}<span class="glyph-label">{NAMES[key]}</span></button>'
                for i,key in enumerate(NAV)
            )
            stages.append(
                f'<section class="stage {theme}" aria-label="{theme} 主题场景">'
                f'<div class="scene-top"><span class="scene-caption">{"浅色" if theme=="light" else "深色"}</span>'
                f'<div class="scene-toolbar">{inline(suite,"ic_theme_sun",20)}'
                f'{inline(suite,"ic_theme_moon",20)}{inline(suite,"ic_manage_tags",20)}'
                f'{inline(suite,"ic_add_preset_thin",20)}</div></div>'
                f'<div class="scene-row"><div><b>读一章书</b><small>45min · 阅读</small></div>'
                f'{inline(suite,"ic_play_outline",28)}</div>'
                f'<div class="scene-row habit-row">{inline(suite,"ic_habit_check",24)}'
                f'<span>每天阅读</span>{inline(suite,"ic_more",20)}</div>'
                f'<nav class="navigation" aria-label="{suite["id"]} {theme} 导航预览">{navbar}</nav></section>'
            )
        cards.append(
            f'<article class="proposal" id="{suite["id"]}"><header class="proposal-head">'
            f'<div><span class="direction">{suite["id"]} / {suite["english"]}</span>'
            f'<h2>{suite["name"]}</h2><p>{suite["style"]}</p></div>'
            f'<a class="app-icon" href="{folder}/svg/ic_clock.svg" download '
            f'aria-label="下载应用图标 SVG"><img src="{folder}/svg/ic_clock.svg" alt="{suite["name"]}应用标志"></a></header>'
            f'<div class="core-icons">{core}</div><p class="rationale">{suite["description"]}</p>'
            f'<div class="stages">{"".join(stages)}</div>'
            f'<details class="asset-detail"><summary>全部操作图标 · 15 枚</summary><div class="tools">{tools}</div></details>'
            f'<footer class="proposal-foot"><a href="{folder}/sheet.svg" target="_blank">整套 SVG 画板 ↗</a>'
            f'<a href="downloads/clock-{suite["id"].lower()}-svg-android.zip" download>下载 {suite["id"]} 套资源 ↓</a></footer></article>'
        )
    return '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Clock · 三套 SVG 图标提案</title><style>
:root{color-scheme:light;--paper:#F8F8F5;--ink:#202322;--muted:#646965;--line:#E0E3DD;--blue:#315BCB;--nav-size:20px}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue",sans-serif}
button,a,summary{-webkit-tap-highlight-color:transparent}a{color:inherit;text-decoration:none}button{font:inherit;color:inherit;cursor:pointer}button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--blue);outline-offset:4px}svg{display:block;flex-shrink:0}
.masthead,.workbench{max-width:1560px;margin:0 auto;padding-left:32px;padding-right:32px}.masthead{padding-top:40px;padding-bottom:26px}.eyebrow{font-size:12px;letter-spacing:.08em;color:var(--muted);margin:0 0 12px}.title-row{display:flex;justify-content:space-between;align-items:center;gap:20px}h1{font-size:clamp(28px,3vw,40px);font-weight:600;letter-spacing:-.03em;margin:0}h1 span{font-weight:400;font-size:.55em;margin-left:14px;letter-spacing:0}.download-all{font-size:13px;padding:12px 16px;border:1px solid var(--line);border-radius:10px;white-space:nowrap}.subtitle{font-size:15px;color:var(--muted);margin:14px 0 0;line-height:1.6}
.controls{display:flex;align-items:center;flex-wrap:wrap;gap:16px;margin:25px 0 0}.control-group{display:flex;align-items:center;gap:3px;padding:3px;border:1px solid var(--line);border-radius:10px}.control-group button{border:0;background:transparent;padding:10px 12px;border-radius:7px;min-height:44px;font-size:13px;color:var(--muted)}.control-group button[aria-pressed=true]{background:#fff;color:var(--blue);box-shadow:0 0 0 1px #E0E3DD}.size-control button{min-width:44px}.control-label{font-size:12px;color:var(--muted);margin-left:9px;margin-right:8px}.label-toggle{font-size:13px;display:flex;align-items:center;gap:8px;min-height:44px;cursor:pointer}.label-toggle input{accent-color:var(--blue);width:16px;height:16px}.workbench{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;padding-bottom:40px}
.proposal{background:white;border:1px solid var(--line);border-radius:18px;overflow:hidden}.proposal-head{display:flex;align-items:center;justify-content:space-between;padding:26px 24px 16px;gap:10px}.direction{color:var(--blue);font-size:11px;letter-spacing:.1em}.proposal h2{font-size:24px;margin:12px 0 7px;font-weight:600}.proposal-head p{font-size:12px;color:var(--muted);margin:0}.app-icon{width:80px;height:80px;border-radius:18px;overflow:hidden;flex-shrink:0}.app-icon img{display:block;width:100%;height:100%}.core-icons{display:grid;grid-template-columns:repeat(4,1fr);padding:22px 14px 14px;gap:8px}.icon-cell{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:15px;min-height:95px;border-radius:8px}.glyph-label{font-size:12px;color:var(--muted)}.icon-cell:hover,.tool-cell:hover{background:var(--paper)}.rationale{font-size:13px;line-height:1.7;color:var(--muted);margin:9px 24px 22px;min-height:44px}
.stages{margin:0 14px;display:grid;gap:12px}.stage{--bg:#F8F8F5;--fg:#202322;--secondary:#646965;--primary:#315BCB;--soft:#E9EEFC;--rule:#E0E3DD;background:var(--bg);color:var(--fg);border:1px solid var(--rule);border-radius:12px;overflow:hidden}.stage.dark{--bg:#1B1B1D;--fg:#F2F2F3;--secondary:#B8B8BC;--primary:#ABC2FF;--soft:#26334E;--rule:#37373B}.scene-top{display:flex;align-items:center;justify-content:space-between;padding:15px 14px}.scene-caption{font-size:10px;color:var(--secondary)}.scene-toolbar{display:flex;gap:13px;color:var(--secondary)}.scene-row{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:67px;margin:0 14px;border-top:1px solid var(--rule)}.scene-row b{font-size:14px;font-weight:500}.scene-row small{display:block;font-size:11px;color:var(--secondary);margin-top:5px}.scene-row.habit-row{min-height:56px;justify-content:flex-start;color:var(--primary)}.habit-row span{color:var(--fg);font-size:13px}.habit-row>svg:last-child{margin-left:auto;color:var(--secondary)}.navigation{border-top:1px solid var(--rule);display:grid;grid-template-columns:repeat(4,1fr);gap:4px;padding:6px 12px}.nav-item{border:0;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:4px;min-height:48px;padding:6px 4px;background:transparent;border-radius:12px;color:var(--secondary)}.nav-item.is-selected{background:var(--soft);color:var(--primary)}.nav-item svg{width:var(--nav-size);height:var(--nav-size)}.nav-item .glyph-label{color:inherit;font-size:11px}
.asset-detail{border-top:1px solid var(--line);margin:18px 24px 0;padding-top:8px}.asset-detail summary{font-size:12px;color:var(--muted);cursor:pointer;padding:12px 0;min-height:44px}.tools{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:5px}.tool-cell{min-height:69px;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:10px;border-radius:6px}.tool-cell span{font-size:10px}.proposal-foot{display:flex;justify-content:space-between;gap:10px;padding:17px 24px 23px;font-size:12px;color:var(--muted)}.proposal-foot a:last-child{color:var(--blue)}.hide-labels .glyph-label{visibility:hidden}.hide-labels .nav-item .glyph-label{display:none}.hide-labels .nav-item{min-height:48px}.mode-light .stage.dark,.mode-dark .stage.light{display:none}.mode-dark .stage.dark{display:block}
.notes{max-width:1560px;margin:0 auto;padding:0 32px 36px;font-size:12px;color:var(--muted);line-height:1.8}.notes a{text-decoration:underline;text-underline-offset:3px}.notes p{margin:5px 0}.nav-message{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);font-size:13px;padding:10px 16px;background:var(--ink);color:white;border-radius:10px;pointer-events:none;opacity:0}.nav-message.visible{opacity:1}
@media(max-width:1120px){.workbench{grid-template-columns:1fr;max-width:760px}.masthead,.notes{max-width:760px}.stages{grid-template-columns:1fr 1fr}.proposal-head{padding-top:22px}.rationale{min-height:0}.app-icon{width:76px;height:76px}.core-icons{padding-left:50px;padding-right:50px}.mode-light .stages,.mode-dark .stages{grid-template-columns:1fr}.tools{grid-template-columns:repeat(8,minmax(0,1fr))}}
@media(max-width:560px){.masthead,.workbench,.notes{padding-left:16px;padding-right:16px}.masthead{padding-top:26px;position:relative}.title-row{display:block}.download-all{position:absolute;right:16px;top:16px;min-height:44px;border:0;padding:10px;font-size:11px}h1 span{display:block;margin:8px 0 0;font-size:16px}.subtitle{font-size:13px}.stages{grid-template-columns:1fr}.core-icons{padding-left:14px;padding-right:14px}.controls{gap:10px}.control-group button{padding:8px 10px}.control-label{display:none}.tools{grid-template-columns:repeat(5,minmax(0,1fr))}.proposal-foot{padding-left:20px;padding-right:20px}.icon-cell svg{width:40px;height:40px}.scene-toolbar{gap:14px}}
@media(prefers-reduced-motion:no-preference){.nav-item{transition:background-color .16s,color .16s}}
</style></head><body class="mode-both"><header class="masthead"><p class="eyebrow">CLOCK / DESIGN EDITION</p><div class="title-row"><h1>把时间，画得更清楚。<span>三套 SVG 图标提案</span></h1><a class="download-all" href="downloads/clock-logo-v2-all.zip" download>全部资源 ↓</a></div><p class="subtitle">沿用暖灰与靛蓝。每套都包含应用标志、四个导航入口和十五枚操作图标。</p><div class="controls"><div class="control-group" aria-label="主题预览"><button data-mode="both" aria-pressed="true">并排</button><button data-mode="light" aria-pressed="false">浅色</button><button data-mode="dark" aria-pressed="false">深色</button></div><div class="control-group size-control" aria-label="导航图标大小"><span class="control-label">导航</span><button data-size="20" aria-pressed="true">20</button><button data-size="24" aria-pressed="false">24</button><button data-size="32" aria-pressed="false">32</button></div><label class="label-toggle"><input id="hide-labels" type="checkbox">隐藏标注</label></div></header><main class="workbench">''' + "".join(cards) + '''</main><footer class="notes"><p>点击图形下载独立 SVG。导航可切换选中状态；场景是提案预览。</p><p><a href="README.md">设计说明与替换对应表</a> · <a href="overview.svg" target="_blank">SVG 总览</a> · <a href="qa/contact-sheet.png" target="_blank">小尺寸检查图</a></p></footer><div class="nav-message" role="status" aria-live="polite"></div><script>
document.querySelectorAll('[data-mode]').forEach(button=>button.addEventListener('click',()=>{document.querySelectorAll('[data-mode]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));document.body.classList.remove('mode-light','mode-dark','mode-both');document.body.classList.add('mode-'+button.dataset.mode)}));
document.querySelectorAll('[data-size]').forEach(button=>button.addEventListener('click',()=>{document.querySelectorAll('[data-size]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));document.documentElement.style.setProperty('--nav-size',button.dataset.size+'px')}));
document.getElementById('hide-labels').addEventListener('change',event=>document.body.classList.toggle('hide-labels',event.target.checked));
document.querySelectorAll('.nav-item').forEach(button=>button.addEventListener('click',()=>{button.closest('.navigation').querySelectorAll('button').forEach(b=>{b.classList.toggle('is-selected',b===button);b.setAttribute('aria-pressed',String(b===button))})}));
</script></body></html>'''


def main() -> None:
    source = PROJECT / "app/src/main/res/drawable"
    sizes = {}
    for filename in source.glob("ic_*.xml"):
        root = ET.parse(filename).getroot()
        sizes[filename.stem] = (root.get(f"{{{ANDROID}}}width"), root.get(f"{{{ANDROID}}}height"))
    manifest = {"version": 1, "canvas": 24, "native_navigation_display_dp": 20,
                "license": "GPL-3.0-or-later", "suites": [], "resource_aliases": ALIASES}
    for suite in SUITES:
        folder = ROOT / suite["folder"]
        svg_dir, native_dir = folder / "svg", folder / "android-res/drawable"
        svg_dir.mkdir(parents=True, exist_ok=True)
        native_dir.mkdir(parents=True, exist_ok=True)
        mapping = []
        for key, paths in suite["icons"].items():
            label = NAMES[key]
            (svg_dir / f"{key}.svg").write_text(standalone(paths, f'Clock {suite["id"]} — {label}',
                description=f'{suite["name"]}; original path geometry; monochrome, themeable.'), encoding="utf-8")
            if key == "brand-mark":
                continue
            width, height = sizes[key]
            color = "@color/clock_on_surface"
            if key.startswith("ic_nav_") or key.startswith("ic_chevron_"):
                color = "?android:attr/textColorPrimary"
            if key in ["ic_play_primary", "ic_pause_primary", "ic_stop_primary", "ic_habit_check"]:
                color = "@color/clock_primary"
            elif key in ["ic_add_preset_thin", "ic_manage_tags", "ic_more", "ic_habit_unchecked"]:
                color = "@color/clock_on_surface_muted"
            elif key == "ic_delete_outline":
                color = "@color/clock_danger"
            elif key == "ic_notification_timer":
                color = "#FFFFFFFF"
            (native_dir / f"{key}.xml").write_text(native(paths, width, height, 24, color), encoding="utf-8")
            mapping.append({"name": key, "label": label, "width": width, "height": height,
                            "svg": f"svg/{key}.svg", "resource": f"android-res/drawable/{key}.xml"})
        for alias, original in ALIASES.items():
            width, height = sizes[alias]
            (native_dir / f"{alias}.xml").write_text(native(suite["icons"][original], width, height,
                                                       24, "@android:color/white"), encoding="utf-8")
        foreground = launcher_paths(suite, "#FFFFFF")
        monochrome = launcher_paths(suite, "#000000")
        background = [{"d": "M0,0H108V108H0Z", "fill": BLUE}]
        application = background + foreground
        (svg_dir / "ic_clock.svg").write_text(standalone(application,
            f'Clock {suite["id"]} — 应用图标', 108,
            "Full-bleed adaptive artwork. The launcher supplies its own mask."), encoding="utf-8")
        adaptive = folder / "adaptive"
        adaptive.mkdir(exist_ok=True)
        for name, paths in [("foreground",foreground),("monochrome",monochrome),("background",background)]:
            (adaptive / f"{name}.svg").write_text(standalone(paths, f'Clock {suite["id"]} {name}',108), encoding="utf-8")
            (native_dir / f"ic_clock_{name}.xml").write_text(native(paths,"108dp","108dp",108,"#FFFFFFFF"), encoding="utf-8")
        (native_dir / "ic_clock.xml").write_text(native(application,"48dp","48dp",108,BLUE), encoding="utf-8")
        for api in [26,33]:
            resource_dir = folder / f"android-res/drawable-anydpi-v{api}"
            resource_dir.mkdir(exist_ok=True)
            mono = '    <monochrome android:drawable="@drawable/ic_clock_monochrome" />\n' if api == 33 else ""
            (resource_dir / "ic_clock.xml").write_text(
                f'<adaptive-icon xmlns:android="{ANDROID}">\n'
                '    <background android:drawable="@drawable/ic_clock_background" />\n'
                '    <foreground android:drawable="@drawable/ic_clock_foreground" />\n'
                f'{mono}</adaptive-icon>\n', encoding="utf-8")
        (folder / "sheet.svg").write_text(sheet(suite), encoding="utf-8")
        mapping.append({"name":"ic_clock", "label":"应用图标", "svg":"svg/ic_clock.svg",
                        "resource":"android-res/drawable/ic_clock.xml"})
        manifest["suites"].append({key:value for key,value in suite.items() if key!="icons"} | {"mapping":mapping})
    (ROOT / "overview.svg").write_text(board(), encoding="utf-8")
    (ROOT / "index.html").write_text(html_preview(), encoding="utf-8")
    (ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n",encoding="utf-8")
    print(f"Generated {len(SUITES)} suites; 21 standalone SVGs and 34 Android XMLs per suite.")


if __name__ == "__main__":
    main()
