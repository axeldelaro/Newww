#!/usr/bin/env python3
"""
KINETIX Coach v1.1.0 — Studio-Grade Biomechanical Execution Video Generator.

Generates all 42 crisp, anatomically articulated 480x480 @ 30 FPS H.264 MP4
execution videos (16 Vision AI exercises + 26 Encyclopedia movement archetypes)
at 2x supersampling (960x960 -> LANCZOS 480x480) in a Swiss Industrial
Sports-Biomechanics Studio aesthetic.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import math
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Studio Palette (Swiss Industrial Athletic Precision)
# ---------------------------------------------------------------------------
RENDER_SIZE = 960       # 2x supersampling
OUT_SIZE = 480          # Crisp final square MP4
FPS = 30
NUM_FRAMES = 150        # 5.0 seconds smooth seamless loop

BG_OBSIDIAN = (11, 12, 14)          # #0B0C0E
BG_HEADER = (17, 19, 23)            # #111317
FLOOR_DARK = (20, 22, 26)           # #14161A
GRID_LINE = (24, 27, 33)            # #181B21
BORDER_HAIRLINE = (38, 42, 49)      # #262A31
CRISP_WHITE = (245, 246, 248)       # #F5F6F8
STEEL_GRAY = (139, 146, 158)        # #8B929E
MUTED_STEEL = (93, 100, 112)        # #5D6470
SIGNAL_ORANGE = (255, 77, 28)       # #FF4D1C
SIGNAL_AMBER = (255, 134, 64)       # #FF8640
BREATH_IN_BLUE = (96, 165, 250)     # #60A5FA
FORM_GREEN = (34, 197, 94)          # #22C55E

# Athlete volumetric shading
FAR_LIMB_FILL = (54, 61, 73)        # Recessed far-side limb
FAR_LIMB_OUTLINE = (32, 36, 44)
NEAR_LIMB_FILL = (104, 114, 130)    # Primary sculpted limb
NEAR_LIMB_HI = (136, 148, 166)      # Studio rim highlight
NEAR_LIMB_OUTLINE = (34, 38, 47)
TORSO_FILL = (88, 97, 112)
TORSO_HI = (118, 129, 148)
SHORTS_FILL = (41, 46, 56)
SHOE_UPPER = (52, 58, 69)
SHOE_SOLE = (224, 228, 235)
HEAD_FILL = (114, 124, 140)
HEAD_HI = (146, 157, 176)

# Equipment steel tones
STEEL_BRIGHT = (182, 190, 204)
STEEL_MID = (126, 134, 148)
STEEL_DARK = (66, 72, 84)
PLATE_IRON = (44, 48, 56)
BENCH_PAD = (38, 42, 50)
BENCH_PAD_HI = (56, 62, 74)
MAT_COLOR = (30, 34, 42)
MAT_EDGE = (54, 60, 72)

FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
FONT_BOLD_PATH = str(FONT_DIR / "DejaVuSans-Bold.ttf")
FONT_REG_PATH = str(FONT_DIR / "DejaVuSans.ttf")
FONT_MONO_BOLD_PATH = str(FONT_DIR / "DejaVuSansMono-Bold.ttf")


def load_fonts() -> Dict[str, ImageFont.FreeTypeFont]:
    return {
        "title": ImageFont.truetype(FONT_BOLD_PATH, 34),
        "subtitle": ImageFont.truetype(FONT_REG_PATH, 21),
        "pill": ImageFont.truetype(FONT_BOLD_PATH, 19),
        "angle": ImageFont.truetype(FONT_MONO_BOLD_PATH, 24),
        "footer": ImageFont.truetype(FONT_MONO_BOLD_PATH, 16),
        "stencil": ImageFont.truetype(FONT_MONO_BOLD_PATH, 20),
    }


# ---------------------------------------------------------------------------
# Math & Kinematics Utilities
# ---------------------------------------------------------------------------
Pt = Tuple[float, float]


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def smoothstep(t: float) -> float:
    t = clamp(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def smootherstep(t: float) -> float:
    t = clamp(t, 0.0, 1.0)
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)


def pt_add(a: Pt, b: Pt) -> Pt:
    return (a[0] + b[0], a[1] + b[1])


def pt_sub(a: Pt, b: Pt) -> Pt:
    return (a[0] - b[0], a[1] - b[1])


def pt_lerp(a: Pt, b: Pt, t: float) -> Pt:
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def pt_dist(a: Pt, b: Pt) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def forward_pt(origin: Pt, length: float, angle_deg: float) -> Pt:
    """Angle in screen coords: 0=right, 90=down, -90=up, 180=left."""
    rad = math.radians(angle_deg)
    return (origin[0] + math.cos(rad) * length, origin[1] + math.sin(rad) * length)


def solve_ik_2link(p0: Pt, p2: Pt, l1: float, l2: float, bend_sign: float = 1.0) -> Pt:
    """Exact 2-link IK returning intermediate joint p1. bend_sign in {-1, +1}."""
    dx = p2[0] - p0[0]
    dy = p2[1] - p0[1]
    d = math.hypot(dx, dy)
    min_d = abs(l1 - l2) + 1.0
    max_d = (l1 + l2) - 1.0
    if d < 1e-4:
        return (p0[0], p0[1] + l1)
    dc = clamp(d, min_d, max_d)
    a = (l1 * l1 - l2 * l2 + dc * dc) / (2.0 * dc)
    h = math.sqrt(max(0.0, l1 * l1 - a * a))
    ux, uy = dx / d, dy / d
    mx = p0[0] + a * ux
    my = p0[1] + a * uy
    return (mx + bend_sign * (-uy) * h, my + bend_sign * ux * h)


def angle_3pts(a: Pt, b: Pt, c: Pt) -> float:
    """Interior angle at vertex b in degrees [0, 180]."""
    v1x, v1y = a[0] - b[0], a[1] - b[1]
    v2x, v2y = c[0] - b[0], c[1] - b[1]
    m1 = math.hypot(v1x, v1y)
    m2 = math.hypot(v2x, v2y)
    if m1 < 1e-5 or m2 < 1e-5:
        return 180.0
    cos_v = clamp((v1x * v2x + v1y * v2y) / (m1 * m2), -1.0, 1.0)
    return math.degrees(math.acos(cos_v))


def cycle_profile(
    u: float,
    ecc_end: float = 0.48,
    hold_end: float = 0.62,
    con_end: float = 0.92,
    con_first: bool = False,
) -> Tuple[float, str, str, float]:
    """
    Smooth seamless repetition curve u in [0, 1).
    Returns: (depth in [0..1], phase_label, breath_label, muscle_activation in [0.35..1.0]).
    If con_first=False: 0->1 is ECCENTRIC (descent/stretch), 1->0 is CONCENTRIC (push/pull).
    If con_first=True:  0->1 is CONCENTRIC (lift/pull), 1->0 is ECCENTRIC (controlled lower).
    """
    u = u % 1.0
    start_hold = 0.05
    if u < start_hold:
        depth = 0.0
        phase = "PRÊT"
        breath = "INSPIRER"
        act = 0.36
    elif u < ecc_end:
        p = smootherstep((u - start_hold) / (ecc_end - start_hold))
        depth = p
        if con_first:
            phase = "CONCENTRIQUE"
            breath = "EXPIRER"
            act = lerp(0.55, 1.0, p)
        else:
            phase = "EXCENTRIQUE"
            breath = "INSPIRER"
            act = lerp(0.42, 0.78, p)
    elif u < hold_end:
        depth = 1.0
        phase = "CONTRACTION" if con_first else "POINT BAS"
        breath = "EXPIRER" if con_first else "GAINÉ"
        act = 1.0 if con_first else 0.85
    elif u < con_end:
        p = smootherstep((u - hold_end) / (con_end - hold_end))
        depth = 1.0 - p
        if con_first:
            phase = "EXCENTRIQUE"
            breath = "INSPIRER"
            act = lerp(0.85, 0.40, p)
        else:
            phase = "CONCENTRIQUE"
            breath = "EXPIRER"
            act = lerp(0.92, 1.0, math.sin(p * math.pi))
    else:
        depth = 0.0
        phase = "VERROUILLAGE"
        breath = "INSPIRER"
        act = 0.42
    return depth, phase, breath, act


# ---------------------------------------------------------------------------
# Studio Backdrop (Pre-rendered once per worker for ultra-fast rendering)
# ---------------------------------------------------------------------------
_CACHED_BACKDROP: Optional[Image.Image] = None


def get_studio_backdrop() -> Image.Image:
    global _CACHED_BACKDROP
    if _CACHED_BACKDROP is not None:
        return _CACHED_BACKDROP.copy()

    # Radial subtle studio vignette using numpy for crisp smooth lighting
    yy, xx = np.mgrid[0:RENDER_SIZE, 0:RENDER_SIZE].astype(np.float32)
    cx, cy = RENDER_SIZE * 0.50, RENDER_SIZE * 0.49
    dist = np.sqrt(((xx - cx) / 480.0) ** 2 + ((yy - cy) / 460.0) ** 2)
    glow = np.clip(1.0 - dist, 0.0, 1.0) ** 1.7

    r = np.clip(BG_OBSIDIAN[0] + glow * 13.0, 0, 255).astype(np.uint8)
    g = np.clip(BG_OBSIDIAN[1] + glow * 15.0, 0, 255).astype(np.uint8)
    b = np.clip(BG_OBSIDIAN[2] + glow * 19.0, 0, 255).astype(np.uint8)
    arr = np.stack([r, g, b], axis=-1)
    img = Image.fromarray(arr, mode="RGB")
    draw = ImageDraw.Draw(img)

    # Architectural subtle studio reference grid
    for gx in range(80, RENDER_SIZE, 80):
        draw.line([(gx, 120), (gx, 840)], fill=GRID_LINE, width=1)
    for gy in range(180, 840, 80):
        draw.line([(0, gy), (RENDER_SIZE, gy)], fill=GRID_LINE, width=1)

    # Subtle vertical center calibration axis
    for y in range(132, 820, 16):
        draw.line([(480, y), (480, min(y + 7, 820))], fill=(29, 33, 41), width=2)

    # Studio floor plane
    floor_y = 824
    draw.rectangle([(0, floor_y), (RENDER_SIZE, RENDER_SIZE)], fill=FLOOR_DARK)
    draw.line([(0, floor_y), (RENDER_SIZE, floor_y)], fill=BORDER_HAIRLINE, width=3)

    # Top telemetry header background
    draw.rectangle([(0, 0), (RENDER_SIZE, 118)], fill=BG_HEADER)
    draw.line([(0, 118), (RENDER_SIZE, 118)], fill=BORDER_HAIRLINE, width=2)

    # Bottom footer band
    draw.rectangle([(0, 908), (RENDER_SIZE, RENDER_SIZE)], fill=BG_HEADER)
    draw.line([(0, 908), (RENDER_SIZE, 908)], fill=BORDER_HAIRLINE, width=2)

    _CACHED_BACKDROP = img
    return img.copy()


# ---------------------------------------------------------------------------
# Volumetric Anatomical Drawing Primitives
# ---------------------------------------------------------------------------
def build_tapered_polygon(
    p0: Pt,
    p1: Pt,
    r0: float,
    r1: float,
    bulge_pos: float = 0.35,
    bulge_left: float = 0.0,
    bulge_right: float = 0.0,
    steps: int = 12,
) -> List[Pt]:
    """Build a sculpted anatomical limb polygon between p0 and p1 with asymmetric muscle bellies."""
    dx = p1[0] - p0[0]
    dy = p1[1] - p0[1]
    length = math.hypot(dx, dy)
    if length < 1e-3:
        return [p0, p1]
    ux, uy = dx / length, dy / length
    nx, ny = -uy, ux  # left normal

    left_pts: List[Pt] = []
    right_pts: List[Pt] = []
    for i in range(steps + 1):
        t = i / steps
        cx = p0[0] + dx * t
        cy = p0[1] + dy * t
        base_r = lerp(r0, r1, t)
        # Smooth Gaussian-like anatomical belly
        bell = math.exp(-((t - bulge_pos) / 0.28) ** 2) * math.sin(t * math.pi)
        rl = base_r + bulge_left * bell
        rr = base_r + bulge_right * bell
        left_pts.append((cx + nx * rl, cy + ny * rl))
        right_pts.append((cx - nx * rr, cy - ny * rr))

    # Rounded end caps
    cap_end: List[Pt] = []
    base_ang = math.atan2(dy, dx)
    for k in range(1, 6):
        a = base_ang + math.pi * 0.5 - (k / 6.0) * math.pi
        cap_end.append((p1[0] + math.cos(a) * r1, p1[1] + math.sin(a) * r1))

    cap_start: List[Pt] = []
    for k in range(1, 6):
        a = base_ang - math.pi * 0.5 - (k / 6.0) * math.pi
        cap_start.append((p0[0] + math.cos(a) * r0, p0[1] + math.sin(a) * r0))

    return left_pts + cap_end + list(reversed(right_pts)) + cap_start


def draw_volumetric_segment(
    draw: ImageDraw.ImageDraw,
    p0: Pt,
    p1: Pt,
    r0: float,
    r1: float,
    fill: Tuple[int, int, int],
    outline: Tuple[int, int, int],
    highlight: Optional[Tuple[int, int, int]] = None,
    bulge_pos: float = 0.35,
    bulge_left: float = 0.0,
    bulge_right: float = 0.0,
    outline_w: int = 3,
) -> None:
    poly = build_tapered_polygon(p0, p1, r0, r1, bulge_pos, bulge_left, bulge_right)
    draw.polygon(poly, fill=fill, outline=outline)
    # Crisp thicker outline
    if outline_w > 1:
        draw.line(poly + [poly[0]], fill=outline, width=outline_w, joint="curve")
    # Subtle inner studio rim highlight along leading edge
    if highlight is not None and pt_dist(p0, p1) > 10:
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        L = math.hypot(dx, dy)
        nx, ny = -dy / L, dx / L
        # Offset slightly inward on left/top edge
        h0 = (p0[0] + dx * 0.14 + nx * r0 * 0.42, p0[1] + dy * 0.14 + ny * r0 * 0.42)
        h1 = (p0[0] + dx * 0.84 + nx * r1 * 0.42, p0[1] + dy * 0.84 + ny * r1 * 0.42)
        draw.line([h0, h1], fill=highlight, width=max(2, int((r0 + r1) * 0.14)))


def draw_muscle_overlay(
    overlay: ImageDraw.ImageDraw,
    p0: Pt,
    p1: Pt,
    r0: float,
    r1: float,
    side: str,  # "left", "right", "center"
    activation: float,
    is_primary: bool = True,
    bulge_pos: float = 0.40,
    t_start: float = 0.12,
    t_end: float = 0.86,
) -> None:
    """Draws a glowing Signal Orange anatomical muscle pad onto an RGBA overlay."""
    if activation <= 0.05:
        return
    a_pt = pt_lerp(p0, p1, t_start)
    b_pt = pt_lerp(p0, p1, t_end)
    dx, dy = b_pt[0] - a_pt[0], b_pt[1] - a_pt[1]
    L = math.hypot(dx, dy)
    if L < 6:
        return
    nx, ny = -dy / L, dx / L
    shift = 0.0
    if side == "left":
        shift = 0.34
    elif side == "right":
        shift = -0.34
    s0 = (a_pt[0] + nx * r0 * shift, a_pt[1] + ny * r0 * shift)
    s1 = (b_pt[0] + nx * r1 * shift, b_pt[1] + ny * r1 * shift)

    base_col = SIGNAL_ORANGE if is_primary else SIGNAL_AMBER
    alpha_glow = int(clamp(activation * (110 if is_primary else 65), 18, 130))
    alpha_core = int(clamp(activation * (225 if is_primary else 135), 40, 235))

    # Outer soft activation halo
    glow_poly = build_tapered_polygon(
        s0, s1, r0 * 0.72, r1 * 0.72, bulge_pos=bulge_pos,
        bulge_left=r0 * 0.28, bulge_right=r0 * 0.28, steps=10
    )
    overlay.polygon(glow_poly, fill=(*base_col, alpha_glow))

    # Core muscle belly
    core_poly = build_tapered_polygon(
        s0, s1, r0 * 0.52, r1 * 0.52, bulge_pos=bulge_pos,
        bulge_left=r0 * 0.22, bulge_right=r0 * 0.22, steps=10
    )
    overlay.polygon(core_poly, fill=(*base_col, alpha_core))

    # Bright striation center highlight during peak contraction
    if is_primary and activation > 0.65:
        hi_alpha = int(clamp((activation - 0.5) * 280, 30, 175))
        overlay.line([pt_lerp(s0, s1, 0.18), pt_lerp(s0, s1, 0.82)],
                     fill=(255, 196, 158, hi_alpha), width=max(2, int(r0 * 0.16)))


def draw_shoe_profile(
    draw: ImageDraw.ImageDraw,
    ankle: Pt,
    toe: Pt,
    scale: float = 1.0,
    far: bool = False,
) -> None:
    """Draws an athletic training shoe with flat sole and subtle orange heel accent."""
    dx, dy = toe[0] - ankle[0], toe[1] - ankle[1]
    L = max(1.0, math.hypot(dx, dy))
    ux, uy = dx / L, dy / L
    # Downward perpendicular toward floor (choosing direction with positive y when mostly horizontal)
    nx, ny = -uy, ux
    if ny < 0:
        nx, ny = -nx, -ny

    heel_back = (ankle[0] - ux * 16 * scale, ankle[1] - uy * 16 * scale)
    sole_heel = (heel_back[0] + nx * 20 * scale, heel_back[1] + ny * 20 * scale)
    sole_toe = (toe[0] + nx * 20 * scale, toe[1] + ny * 20 * scale)
    toe_top = (toe[0] - ux * 4 * scale + nx * 6 * scale, toe[1] - uy * 4 * scale + ny * 6 * scale)
    instep = (ankle[0] + ux * 16 * scale + nx * 2 * scale, ankle[1] + uy * 16 * scale + ny * 2 * scale)

    upper_col = FAR_LIMB_FILL if far else SHOE_UPPER
    sole_col = (118, 124, 134) if far else SHOE_SOLE
    out_col = FAR_LIMB_OUTLINE if far else NEAR_LIMB_OUTLINE

    # Upper shoe body
    upper_poly = [heel_back, instep, toe_top, sole_toe, sole_heel]
    draw.polygon(upper_poly, fill=upper_col, outline=out_col)
    # Crisp midsole line
    draw.line([sole_heel, sole_toe], fill=sole_col, width=max(3, int(6 * scale)))
    if not far:
        # Subtle Signal Orange heel counter accent
        heel_acc = pt_lerp(heel_back, sole_heel, 0.55)
        heel_acc2 = (heel_acc[0] + ux * 10 * scale, heel_acc[1] + uy * 10 * scale)
        draw.line([heel_acc, heel_acc2], fill=SIGNAL_ORANGE, width=max(2, int(4 * scale)))


def draw_head_profile(
    draw: ImageDraw.ImageDraw,
    neck: Pt,
    head_center: Pt,
    facing: float = 1.0,  # +1 right, -1 left
    scale: float = 1.0,
    tilt_deg: float = 0.0,
) -> None:
    """Draws sculpted athletic head + neck with subtle jaw/nose profile direction."""
    # Neck
    draw_volumetric_segment(
        draw, neck, pt_lerp(neck, head_center, 0.55),
        13 * scale, 11 * scale,
        fill=TORSO_FILL, outline=NEAR_LIMB_OUTLINE, highlight=TORSO_HI, outline_w=2
    )
    rx = 26 * scale
    ry = 30 * scale
    rad = math.radians(tilt_deg)
    cr, sr = math.cos(rad), math.sin(rad)

    def rot(lx: float, ly: float) -> Pt:
        return (
            head_center[0] + lx * cr - ly * sr,
            head_center[1] + lx * sr + ly * cr,
        )

    # Sculpted cranium + nose + chin profile polygon
    pts_local = []
    for k in range(20):
        a = (k / 20.0) * 2.0 * math.pi
        lx = math.cos(a) * rx
        ly = math.sin(a) * ry
        # Subtle nose bridge & chin prominence on facing side
        if lx * facing > 0 and -0.25 * ry < ly < 0.35 * ry:
            lx += facing * 4.5 * scale * math.cos((ly / (0.4 * ry)) * math.pi * 0.5)
        if lx * facing > 0 and 0.45 * ry < ly < 0.92 * ry:
            lx += facing * 3.0 * scale
        pts_local.append(rot(lx, ly))

    draw.polygon(pts_local, fill=HEAD_FILL, outline=NEAR_LIMB_OUTLINE)
    draw.line(pts_local + [pts_local[0]], fill=NEAR_LIMB_OUTLINE, width=3, joint="curve")

    # Subtle studio rim light on crown
    crown_pts = [rot(-facing * rx * 0.35, -ry * 0.72), rot(facing * rx * 0.25, -ry * 0.75)]
    draw.line(crown_pts, fill=HEAD_HI, width=max(2, int(4 * scale)))


def draw_head_front(
    draw: ImageDraw.ImageDraw,
    neck: Pt,
    head_center: Pt,
    scale: float = 1.0,
) -> None:
    """Draws sculpted symmetrical front-facing athletic head + neck."""
    draw_volumetric_segment(
        draw, neck, pt_lerp(neck, head_center, 0.55),
        14 * scale, 12 * scale,
        fill=TORSO_FILL, outline=NEAR_LIMB_OUTLINE, highlight=TORSO_HI, outline_w=2
    )
    rx = 24 * scale
    ry = 30 * scale
    box = [head_center[0] - rx, head_center[1] - ry, head_center[0] + rx, head_center[1] + ry]
    draw.ellipse(box, fill=HEAD_FILL, outline=NEAR_LIMB_OUTLINE, width=3)
    # Subtle crown highlight
    draw.arc(
        [head_center[0] - rx * 0.7, head_center[1] - ry * 0.8, head_center[0] + rx * 0.7, head_center[1] - ry * 0.1],
        200, 340, fill=HEAD_HI, width=max(2, int(4 * scale))
    )


def draw_joint_marker(draw: ImageDraw.ImageDraw, pt: Pt, active: bool = False, scale: float = 1.0) -> None:
    r = (6.5 if active else 4.5) * scale
    col = SIGNAL_ORANGE if active else (185, 192, 205)
    draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=BG_OBSIDIAN, outline=col, width=max(2, int(2.5 * scale)))
    ri = r * 0.42
    draw.ellipse([pt[0] - ri, pt[1] - ri, pt[0] + ri, pt[1] + ri], fill=col)


# ---------------------------------------------------------------------------
# Equipment Drawing Primitives
# ---------------------------------------------------------------------------
def draw_barbell_profile(
    draw: ImageDraw.ImageDraw,
    center: Pt,
    radius: float = 52.0,
    bar_path_x: Optional[float] = None,
    path_y_min: float = 200.0,
    path_y_max: float = 780.0,
) -> None:
    """Draws side-profile Olympic bumper plate + sleeve + vertical dotted bar path."""
    if bar_path_x is not None:
        for y in range(int(path_y_min), int(path_y_max), 18):
            draw.line([(bar_path_x, y), (bar_path_x, min(y + 9, int(path_y_max)))],
                      fill=(120, 128, 144), width=3)
    cx, cy = center
    # Outer calibrated bumper plate
    draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                 fill=PLATE_IRON, outline=STEEL_MID, width=4)
    # Inner bevel ring
    r2 = radius * 0.76
    draw.ellipse([cx - r2, cy - r2, cx + r2, cy + r2],
                 outline=STEEL_DARK, width=2)
    # Signal Orange calibrated inner flange ring
    r3 = radius * 0.38
    draw.ellipse([cx - r3, cy - r3, cx + r3, cy + r3],
                 fill=(30, 33, 40), outline=SIGNAL_ORANGE, width=3)
    # Chrome Olympic sleeve center
    rs = radius * 0.18
    draw.ellipse([cx - rs, cy - rs, cx + rs, cy + rs],
                 fill=STEEL_BRIGHT, outline=STEEL_DARK, width=2)


def draw_dumbbell(draw: ImageDraw.ImageDraw, hand: Pt, scale: float = 1.0, angle_deg: float = 0.0) -> None:
    """Draws a crisp matte steel hex/round dumbbell in the hand."""
    rad = math.radians(angle_deg)
    ux, uy = math.cos(rad), math.sin(rad)
    nx, ny = -uy, ux
    half_len = 16 * scale
    p1 = (hand[0] - ux * half_len, hand[1] - uy * half_len)
    p2 = (hand[0] + ux * half_len, hand[1] + uy * half_len)
    # Handle
    draw.line([p1, p2], fill=STEEL_BRIGHT, width=max(4, int(7 * scale)))
    # Dual iron heads
    r = 14 * scale
    for p in (p1, p2):
        draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r],
                     fill=PLATE_IRON, outline=STEEL_MID, width=3)
        r2 = r * 0.45
        draw.ellipse([p[0] - r2, p[1] - r2, p[0] + r2, p[1] + r2],
                     fill=STEEL_DARK, outline=STEEL_BRIGHT, width=2)


def draw_dumbbell_side_single(draw: ImageDraw.ImageDraw, hand: Pt, scale: float = 1.0) -> None:
    """Side view of dumbbell (end-on hex disc with subtle chrome center)."""
    r = 19 * scale
    draw.ellipse([hand[0] - r, hand[1] - r, hand[0] + r, hand[1] + r],
                 fill=PLATE_IRON, outline=STEEL_MID, width=3)
    r2 = 7 * scale
    draw.ellipse([hand[0] - r2, hand[1] - r2, hand[0] + r2, hand[1] + r2],
                 fill=STEEL_BRIGHT, outline=STEEL_DARK, width=2)


def draw_mat(draw: ImageDraw.ImageDraw, x0: float = 140, x1: float = 820, floor_y: float = 824) -> None:
    draw.rounded_rectangle([x0, floor_y - 12, x1, floor_y + 2], radius=5, fill=MAT_COLOR, outline=MAT_EDGE, width=2)


def draw_flat_bench(draw: ImageDraw.ImageDraw, x0: float, x1: float, top_y: float, floor_y: float = 824) -> None:
    # Steel legs
    lx1, lx2 = x0 + 48, x1 - 48
    draw.rectangle([lx1 - 8, top_y + 26, lx1 + 8, floor_y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([lx2 - 8, top_y + 26, lx2 + 8, floor_y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    # Crossbeam & feet
    draw.rectangle([lx1 - 24, floor_y - 12, lx1 + 24, floor_y], fill=STEEL_MID)
    draw.rectangle([lx2 - 24, floor_y - 12, lx2 + 24, floor_y], fill=STEEL_MID)
    draw.line([(lx1, top_y + 42), (lx2, top_y + 42)], fill=STEEL_DARK, width=10)
    # Padded upholstery
    draw.rounded_rectangle([x0, top_y, x1, top_y + 28], radius=8, fill=BENCH_PAD, outline=STEEL_MID, width=2)
    draw.line([(x0 + 10, top_y + 5), (x1 - 10, top_y + 5)], fill=BENCH_PAD_HI, width=3)


def draw_incline_bench(
    draw: ImageDraw.ImageDraw,
    pivot: Pt,
    back_len: float,
    seat_len: float,
    angle_deg: float,
    floor_y: float = 824,
) -> None:
    # Support frame
    draw.line([(pivot[0] - 110, floor_y - 6), (pivot[0] + 150, floor_y - 6)], fill=STEEL_DARK, width=14)
    draw.line([(pivot[0], pivot[1] + 12), (pivot[0], floor_y - 6)], fill=STEEL_DARK, width=14)
    back_top = forward_pt(pivot, back_len, -180 + angle_deg)
    strut_pt = pt_lerp(pivot, back_top, 0.62)
    draw.line([strut_pt, (strut_pt[0], floor_y - 6)], fill=STEEL_MID, width=10)

    # Backrest pad
    rad = math.radians(-180 + angle_deg)
    nx, ny = -math.sin(rad), math.cos(rad)  # normal toward floor
    pad_t = 24.0
    p_back = [
        pivot, back_top,
        (back_top[0] + nx * pad_t, back_top[1] + ny * pad_t),
        (pivot[0] + nx * pad_t, pivot[1] + ny * pad_t),
    ]
    draw.polygon(p_back, fill=BENCH_PAD, outline=STEEL_MID)
    draw.line(p_back + [p_back[0]], fill=STEEL_MID, width=2)

    # Seat pad
    seat_end = (pivot[0] + seat_len, pivot[1] + 12)
    p_seat = [
        (pivot[0] + 4, pivot[1] + 4),
        seat_end,
        (seat_end[0] - 4, seat_end[1] + 22),
        (pivot[0] + 4, pivot[1] + 26),
    ]
    draw.polygon(p_seat, fill=BENCH_PAD, outline=STEEL_MID)
    draw.line(p_seat + [p_seat[0]], fill=STEEL_MID, width=2)


def draw_cable_tower(
    draw: ImageDraw.ImageDraw,
    tower_x: float,
    pulley_y: float,
    hand_pt: Pt,
    floor_y: float = 824,
    weight_offset: float = 0.0,
) -> None:
    # Main steel column
    draw.rectangle([tower_x - 24, 150, tower_x + 24, floor_y], fill=(24, 27, 33), outline=STEEL_DARK, width=2)
    draw.line([(tower_x - 16, 155), (tower_x - 16, floor_y)], fill=STEEL_MID, width=4)
    draw.line([(tower_x + 16, 155), (tower_x + 16, floor_y)], fill=STEEL_MID, width=4)
    # Weight stack plates moving smoothly
    stack_base = floor_y - 44 - weight_offset
    for i in range(7):
        wy = stack_base - i * 24
        draw.rounded_rectangle([tower_x - 18, wy - 18, tower_x + 18, wy], radius=2,
                               fill=PLATE_IRON, outline=STEEL_MID, width=2)
    # Orange pin on active weight
    draw.line([(tower_x - 22, stack_base - 9), (tower_x + 22, stack_base - 9)], fill=SIGNAL_ORANGE, width=4)
    # Pulley housing & wheel
    draw.ellipse([tower_x - 16, pulley_y - 16, tower_x + 16, pulley_y + 16],
                 fill=STEEL_DARK, outline=STEEL_BRIGHT, width=3)
    draw.ellipse([tower_x - 5, pulley_y - 5, tower_x + 5, pulley_y + 5], fill=SIGNAL_ORANGE)
    # Taut steel cable line
    draw.line([(tower_x, pulley_y), hand_pt], fill=STEEL_BRIGHT, width=4)


def draw_kettlebell(draw: ImageDraw.ImageDraw, hand: Pt, bell_dir_deg: float, scale: float = 1.0) -> None:
    # Handle ring around hand, heavy sphere hanging along bell_dir_deg
    rad = math.radians(bell_dir_deg)
    ux, uy = math.cos(rad), math.sin(rad)
    bell_c = (hand[0] + ux * 36 * scale, hand[1] + uy * 36 * scale)
    # Handle arch
    h_c = (hand[0] + ux * 10 * scale, hand[1] + uy * 10 * scale)
    hr = 18 * scale
    draw.ellipse([h_c[0] - hr, h_c[1] - hr, h_c[0] + hr, h_c[1] + hr], outline=STEEL_BRIGHT, width=max(4, int(7 * scale)))
    # Heavy cast-iron sphere
    br = 26 * scale
    draw.ellipse([bell_c[0] - br, bell_c[1] - br, bell_c[0] + br, bell_c[1] + br],
                 fill=PLATE_IRON, outline=STEEL_MID, width=3)
    # Signal Orange weight band
    draw.arc([bell_c[0] - br * 0.72, bell_c[1] - br * 0.72, bell_c[0] + br * 0.72, bell_c[1] + br * 0.72],
             200, 340, fill=SIGNAL_ORANGE, width=3)


def draw_floor_shadow(draw: ImageDraw.ImageDraw, cx: float, rx: float, floor_y: float = 824) -> None:
    draw.ellipse([cx - rx, floor_y - 5, cx + rx, floor_y + 8], fill=(8, 9, 11))


# ---------------------------------------------------------------------------
# Live Joint Angle Protractor + Callout Pill
# ---------------------------------------------------------------------------
def draw_protractor(
    base_img: Image.Image,
    fonts: Dict[str, ImageFont.FreeTypeFont],
    a: Pt,
    vertex: Pt,
    c: Pt,
    label_override: Optional[str] = None,
    radius: float = 54.0,
    pill_offset_mult: float = 1.65,
    force_pill_dir: Optional[Pt] = None,
) -> None:
    """Draws crisp Signal Orange angle sector + outer arc + floating degree pill."""
    ang_deg = angle_3pts(a, vertex, c)
    ang1 = math.degrees(math.atan2(a[1] - vertex[1], a[0] - vertex[0])) % 360.0
    ang2 = math.degrees(math.atan2(c[1] - vertex[1], c[0] - vertex[0])) % 360.0

    diff = (ang2 - ang1) % 360.0
    if diff <= 180.0:
        start_a, end_a = ang1, ang1 + diff
        mid_a = ang1 + diff * 0.5
    else:
        start_a, end_a = ang2, ang2 + (360.0 - diff)
        mid_a = ang2 + (360.0 - diff) * 0.5

    overlay = Image.new("RGBA", base_img.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    vx, vy = vertex
    bbox = [vx - radius, vy - radius, vx + radius, vy + radius]

    # Translucent Signal Orange sector wedge
    odraw.pieslice(bbox, start=start_a, end=end_a, fill=(*SIGNAL_ORANGE, 58))
    # Crisp outer arc
    odraw.arc(bbox, start=start_a, end=end_a, fill=(*SIGNAL_ORANGE, 240), width=5)

    # Radial arms from vertex to arc
    p_a = (vx + math.cos(math.radians(start_a)) * radius, vy + math.sin(math.radians(start_a)) * radius)
    p_b = (vx + math.cos(math.radians(end_a)) * radius, vy + math.sin(math.radians(end_a)) * radius)
    odraw.line([vertex, p_a], fill=(*SIGNAL_ORANGE, 175), width=2)
    odraw.line([vertex, p_b], fill=(*SIGNAL_ORANGE, 175), width=2)

    # Center precision pin
    odraw.ellipse([vx - 8, vy - 8, vx + 8, vy + 8], fill=(*BG_OBSIDIAN, 255), outline=(*SIGNAL_ORANGE, 255), width=3)
    odraw.ellipse([vx - 3, vy - 3, vx + 3, vy + 3], fill=(*CRISP_WHITE, 255))

    # Pill placement smartly offset outside body
    if force_pill_dir is not None:
        bx, by = force_pill_dir
    else:
        bx = math.cos(math.radians(mid_a))
        by = math.sin(math.radians(mid_a))

    px = clamp(vx + bx * radius * pill_offset_mult, 86.0, RENDER_SIZE - 86.0)
    py = clamp(vy + by * radius * pill_offset_mult, 158.0, 868.0)

    text = label_override if label_override is not None else f"{int(round(ang_deg))}°"
    font = fonts["angle"]
    tb = odraw.textbbox((0, 0), text, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    pad_x, pad_y = 14, 8
    rw, rh = tw + pad_x * 2, th + pad_y * 2

    # Thin leader line from arc to pill
    arc_mid = (vx + math.cos(math.radians(mid_a)) * radius, vy + math.sin(math.radians(mid_a)) * radius)
    odraw.line([arc_mid, (px, py)], fill=(*SIGNAL_ORANGE, 160), width=2)

    pill_box = [px - rw / 2, py - rh / 2, px + rw / 2, py + rh / 2]
    odraw.rounded_rectangle(pill_box, radius=10, fill=(16, 18, 22, 238), outline=(*SIGNAL_ORANGE, 235), width=3)
    odraw.text((px - tw / 2, py - th / 2 - 2), text, font=font, fill=(*CRISP_WHITE, 255))

    base_img.alpha_composite(overlay)


# ---------------------------------------------------------------------------
# Studio Telemetry Header & Footer Overlay
# ---------------------------------------------------------------------------
def draw_studio_hud(
    draw: ImageDraw.ImageDraw,
    fonts: Dict[str, ImageFont.FreeTypeFont],
    title: str,
    subtitle: str,
    phase: str,
    breath: str,
    progress: float,
) -> None:
    # Left Title + Subtitle
    draw.rectangle([(24, 24), (31, 94)], fill=SIGNAL_ORANGE)
    draw.text((44, 22), title, font=fonts["title"], fill=CRISP_WHITE)
    draw.text((44, 68), subtitle, font=fonts["subtitle"], fill=STEEL_GRAY)

    # Right Stacked Pills: Phase (top) + Breath (bottom)
    pill_font = fonts["pill"]

    # Phase pill
    pb = draw.textbbox((0, 0), phase, font=pill_font)
    pw = (pb[2] - pb[0]) + 28
    px2 = RENDER_SIZE - 24
    px1 = px2 - pw
    phase_active = phase in ("CONCENTRIQUE", "CONTRACTION", "POINT BAS", "EXPLOSIF", "ISOMÉTRIE")
    p_bg = (42, 20, 14) if phase_active else (26, 29, 36)
    p_border = SIGNAL_ORANGE if phase_active else BORDER_HAIRLINE
    p_txt = SIGNAL_ORANGE if phase_active else CRISP_WHITE
    draw.rounded_rectangle([px1, 20, px2, 54], radius=8, fill=p_bg, outline=p_border, width=2)
    draw.text((px1 + 14, 26), phase, font=pill_font, fill=p_txt)

    # Breathing pill
    b_txt = f"● {breath}"
    bb = draw.textbbox((0, 0), b_txt, font=pill_font)
    bw = (bb[2] - bb[0]) + 26
    bx2 = RENDER_SIZE - 24
    bx1 = bx2 - bw
    is_exhale = breath == "EXPIRER"
    b_col = SIGNAL_ORANGE if is_exhale else BREATH_IN_BLUE
    b_bg = (36, 18, 14) if is_exhale else (16, 26, 40)
    draw.rounded_rectangle([bx1, 64, bx2, 98], radius=8, fill=b_bg, outline=b_col, width=2)
    draw.text((bx1 + 13, 70), b_txt, font=pill_font, fill=b_col)

    # Bottom Timeline Progress Bar
    bar_x0, bar_x1 = 24, RENDER_SIZE - 24
    bar_y0, bar_y1 = 924, 932
    draw.rounded_rectangle([bar_x0, bar_y0, bar_x1, bar_y1], radius=4, fill=(32, 36, 44))
    fill_x = bar_x0 + (bar_x1 - bar_x0) * clamp(progress, 0.0, 1.0)
    if fill_x > bar_x0 + 4:
        draw.rounded_rectangle([bar_x0, bar_y0, fill_x, bar_y1], radius=4, fill=SIGNAL_ORANGE)
    # Phase markers on track
    for frac in (0.48, 0.62, 0.92):
        mx = bar_x0 + (bar_x1 - bar_x0) * frac
        draw.line([(mx, bar_y0 - 2), (mx, bar_y1 + 2)], fill=BG_OBSIDIAN, width=2)

    draw.text((24, 938), "KINETIX BIOMÉCANIQUE · HORS-LIGNE", font=fonts["footer"], fill=MUTED_STEEL)
    right_lbl = "LOOP 30 FPS"
    rb = draw.textbbox((0, 0), right_lbl, font=fonts["footer"])
    draw.text((RENDER_SIZE - 24 - (rb[2] - rb[0]), 938), right_lbl, font=fonts["footer"], fill=MUTED_STEEL)


# ---------------------------------------------------------------------------
# Articulated Profile & Front Athlete Renderers
# ---------------------------------------------------------------------------
@dataclass
class ProfilePose:
    hip: Pt
    shoulder: Pt
    neck: Pt
    head: Pt
    # Near leg
    knee_n: Pt
    ankle_n: Pt
    toe_n: Pt
    # Far leg
    knee_f: Pt
    ankle_f: Pt
    toe_f: Pt
    # Near arm
    elbow_n: Pt
    wrist_n: Pt
    # Far arm
    elbow_f: Pt
    wrist_f: Pt
    facing: float = 1.0         # +1 facing right, -1 facing left
    head_tilt: float = 0.0
    scale: float = 1.0
    # Muscles glowing: dict[muscle_name, (activation, is_primary)]
    muscles: Dict[str, Tuple[float, bool]] = field(default_factory=dict)


def render_profile_athlete(base_img: Image.Image, pose: ProfilePose) -> None:
    """Renders layered volumetric profile athlete + active anatomical muscle overlays."""
    draw = ImageDraw.Draw(base_img)
    overlay = Image.new("RGBA", base_img.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    s = pose.scale
    f = pose.facing  # +1 facing right, -1 facing left

    # Determine which side of limb vector (hip->knee, shoulder->hip, etc.) is anterior/front
    # For vector going mostly downward (dy > 0), left normal (-dy, dx) points LEFT (-x).
    # So front side = "right" when facing=+1, "left" when facing=-1.
    front_side = "right" if f > 0 else "left"
    back_side = "left" if f > 0 else "right"

    # 1. FAR LEG (recessed)
    draw_volumetric_segment(
        draw, pose.hip, pose.knee_f, 27 * s, 18 * s,
        fill=FAR_LIMB_FILL, outline=FAR_LIMB_OUTLINE,
        bulge_pos=0.40,
        bulge_right=7 * s if f > 0 else 4 * s,
        bulge_left=4 * s if f > 0 else 7 * s,
    )
    draw_volumetric_segment(
        draw, pose.knee_f, pose.ankle_f, 17 * s, 10.5 * s,
        fill=FAR_LIMB_FILL, outline=FAR_LIMB_OUTLINE,
        bulge_pos=0.30,
        bulge_left=6 * s if f > 0 else 1 * s,
        bulge_right=1 * s if f > 0 else 6 * s,
    )
    draw_shoe_profile(draw, pose.ankle_f, pose.toe_f, scale=s, far=True)

    # 2. FAR ARM (recessed)
    draw_volumetric_segment(
        draw, pose.shoulder, pose.elbow_f, 15.5 * s, 12 * s,
        fill=FAR_LIMB_FILL, outline=FAR_LIMB_OUTLINE,
        bulge_pos=0.42, bulge_left=2.5 * s, bulge_right=2.5 * s,
    )
    draw_volumetric_segment(
        draw, pose.elbow_f, pose.wrist_f, 12.5 * s, 8.5 * s,
        fill=FAR_LIMB_FILL, outline=FAR_LIMB_OUTLINE,
        bulge_pos=0.25, bulge_left=2.5 * s, bulge_right=2 * s,
    )
    draw.ellipse([pose.wrist_f[0] - 9 * s, pose.wrist_f[1] - 9 * s,
                  pose.wrist_f[0] + 9 * s, pose.wrist_f[1] + 9 * s],
                 fill=FAR_LIMB_FILL, outline=FAR_LIMB_OUTLINE, width=2)

    # 3. TORSO (Pelvis -> Chest/Shoulder with athletic silhouette)
    draw_volumetric_segment(
        draw, pose.hip, pose.shoulder, 27 * s, 31 * s,
        fill=TORSO_FILL, outline=NEAR_LIMB_OUTLINE, highlight=TORSO_HI,
        bulge_pos=0.68,
        bulge_right=10 * s if f < 0 else 6 * s,
        bulge_left=6 * s if f < 0 else 10 * s,
    )
    # Athletic shorts cuff at pelvis/hip
    pelvis_top = pt_lerp(pose.hip, pose.shoulder, 0.18)
    draw_volumetric_segment(
        draw, pose.hip, pelvis_top, 28 * s, 26 * s,
        fill=SHORTS_FILL, outline=NEAR_LIMB_OUTLINE, outline_w=2
    )

    # Torso muscle overlays (note vector hip->shoulder goes UP so left normal points +x when vertical)
    torso_front = "left" if f > 0 else "right"
    torso_back = "right" if f > 0 else "left"
    if "chest" in pose.muscles:
        act, pri = pose.muscles["chest"]
        draw_muscle_overlay(odraw, pose.hip, pose.shoulder, 26 * s, 28 * s, torso_front, act, pri,
                            bulge_pos=0.55, t_start=0.56, t_end=0.92)
    if "abs" in pose.muscles:
        act, pri = pose.muscles["abs"]
        draw_muscle_overlay(odraw, pose.hip, pose.shoulder, 22 * s, 23 * s, torso_front, act, pri,
                            bulge_pos=0.45, t_start=0.14, t_end=0.60)
    if "lats" in pose.muscles:
        act, pri = pose.muscles["lats"]
        draw_muscle_overlay(odraw, pose.hip, pose.shoulder, 24 * s, 29 * s, torso_back, act, pri,
                            bulge_pos=0.55, t_start=0.32, t_end=0.88)
    if "upper_back" in pose.muscles:
        act, pri = pose.muscles["upper_back"]
        draw_muscle_overlay(odraw, pose.hip, pose.shoulder, 24 * s, 28 * s, torso_back, act, pri,
                            bulge_pos=0.60, t_start=0.58, t_end=0.95)
    if "lower_back" in pose.muscles:
        act, pri = pose.muscles["lower_back"]
        draw_muscle_overlay(odraw, pose.hip, pose.shoulder, 22 * s, 22 * s, torso_back, act, pri,
                            bulge_pos=0.45, t_start=0.10, t_end=0.48)
    if "traps" in pose.muscles:
        act, pri = pose.muscles["traps"]
        draw_muscle_overlay(odraw, pose.shoulder, pose.neck, 22 * s, 14 * s, "center", act, pri,
                            bulge_pos=0.45, t_start=0.0, t_end=0.95)

    # 4. HEAD & NECK
    draw_head_profile(draw, pose.neck, pose.head, facing=f, scale=s, tilt_deg=pose.head_tilt)

    # 5. NEAR LEG (Primary volumetric foreground)
    draw_volumetric_segment(
        draw, pose.hip, pose.knee_n, 29 * s, 19 * s,
        fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
        bulge_pos=0.38,
        bulge_right=9 * s if f > 0 else 5 * s,
        bulge_left=5 * s if f > 0 else 9 * s,
    )
    # Athletic shorts leg segment
    shorts_end = pt_lerp(pose.hip, pose.knee_n, 0.34)
    draw_volumetric_segment(
        draw, pose.hip, shorts_end, 30 * s, 25 * s,
        fill=SHORTS_FILL, outline=NEAR_LIMB_OUTLINE, outline_w=2
    )
    # Near shin
    draw_volumetric_segment(
        draw, pose.knee_n, pose.ankle_n, 18 * s, 11 * s,
        fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
        bulge_pos=0.28,
        bulge_left=8 * s if f > 0 else 1.5 * s,
        bulge_right=1.5 * s if f > 0 else 8 * s,
    )
    draw_shoe_profile(draw, pose.ankle_n, pose.toe_n, scale=s, far=False)

    # Leg muscle overlays
    if "glutes" in pose.muscles:
        act, pri = pose.muscles["glutes"]
        g_pt = pt_lerp(pose.hip, pose.knee_n, 0.20)
        g_top = pt_lerp(pose.hip, pose.shoulder, 0.16)
        draw_muscle_overlay(odraw, g_pt, g_top, 26 * s, 26 * s, torso_back, act, pri,
                            bulge_pos=0.48, t_start=0.0, t_end=1.0)
    if "quads" in pose.muscles:
        act, pri = pose.muscles["quads"]
        draw_muscle_overlay(odraw, pose.hip, pose.knee_n, 25 * s, 17 * s, front_side, act, pri,
                            bulge_pos=0.42, t_start=0.18, t_end=0.88)
    if "hamstrings" in pose.muscles:
        act, pri = pose.muscles["hamstrings"]
        draw_muscle_overlay(odraw, pose.hip, pose.knee_n, 24 * s, 16 * s, back_side, act, pri,
                            bulge_pos=0.42, t_start=0.16, t_end=0.86)
    if "calves" in pose.muscles:
        act, pri = pose.muscles["calves"]
        draw_muscle_overlay(odraw, pose.knee_n, pose.ankle_n, 17 * s, 10 * s, back_side, act, pri,
                            bulge_pos=0.30, t_start=0.08, t_end=0.72)

    # 6. NEAR ARM (Foreground)
    draw_volumetric_segment(
        draw, pose.shoulder, pose.elbow_n, 17.5 * s, 13 * s,
        fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
        bulge_pos=0.40, bulge_left=3.5 * s, bulge_right=3.5 * s,
    )
    draw_volumetric_segment(
        draw, pose.elbow_n, pose.wrist_n, 13.5 * s, 9 * s,
        fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
        bulge_pos=0.26, bulge_left=3.0 * s, bulge_right=2.5 * s,
    )
    # Fist / hand
    draw.ellipse([pose.wrist_n[0] - 10 * s, pose.wrist_n[1] - 10 * s,
                  pose.wrist_n[0] + 10 * s, pose.wrist_n[1] + 10 * s],
                 fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, width=3)

    # Arm muscle overlays
    if "delts" in pose.muscles:
        act, pri = pose.muscles["delts"]
        d_end = pt_lerp(pose.shoulder, pose.elbow_n, 0.36)
        draw_muscle_overlay(odraw, pose.shoulder, d_end, 21 * s, 15 * s, "center", act, pri,
                            bulge_pos=0.40, t_start=0.0, t_end=1.0)
    if "biceps" in pose.muscles:
        act, pri = pose.muscles["biceps"]
        draw_muscle_overlay(odraw, pose.shoulder, pose.elbow_n, 16 * s, 13 * s, front_side, act, pri,
                            bulge_pos=0.48, t_start=0.22, t_end=0.88)
    if "triceps" in pose.muscles:
        act, pri = pose.muscles["triceps"]
        draw_muscle_overlay(odraw, pose.shoulder, pose.elbow_n, 16 * s, 13 * s, back_side, act, pri,
                            bulge_pos=0.42, t_start=0.18, t_end=0.86)
    if "forearms" in pose.muscles:
        act, pri = pose.muscles["forearms"]
        draw_muscle_overlay(odraw, pose.elbow_n, pose.wrist_n, 13 * s, 9 * s, "center", act, pri,
                            bulge_pos=0.30, t_start=0.10, t_end=0.82)

    # Composite muscle glow cleanly onto base image
    base_img.alpha_composite(overlay)

    # Precision subtle joint dots
    draw2 = ImageDraw.Draw(base_img)
    for jpt in (pose.hip, pose.knee_n, pose.ankle_n, pose.shoulder, pose.elbow_n):
        draw_joint_marker(draw2, jpt, active=False, scale=s)


@dataclass
class FrontPose:
    pelvis: Pt
    chest: Pt
    neck: Pt
    head: Pt
    sh_l: Pt
    sh_r: Pt
    el_l: Pt
    el_r: Pt
    wr_l: Pt
    wr_r: Pt
    hip_l: Pt
    hip_r: Pt
    kn_l: Pt
    kn_r: Pt
    an_l: Pt
    an_r: Pt
    scale: float = 1.0
    muscles: Dict[str, Tuple[float, bool]] = field(default_factory=dict)


def render_front_athlete(base_img: Image.Image, pose: FrontPose) -> None:
    """Renders bilateral front-view sculpted athletic figure + muscle overlays."""
    draw = ImageDraw.Draw(base_img)
    overlay = Image.new("RGBA", base_img.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    s = pose.scale

    # 1. Legs
    for hip, kn, an, side_sign in ((pose.hip_l, pose.kn_l, pose.an_l, -1), (pose.hip_r, pose.kn_r, pose.an_r, 1)):
        draw_volumetric_segment(
            draw, hip, kn, 25 * s, 17 * s,
            fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
            bulge_pos=0.42, bulge_left=5 * s, bulge_right=5 * s
        )
        draw_volumetric_segment(
            draw, kn, an, 16 * s, 10.5 * s,
            fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
            bulge_pos=0.32, bulge_left=4.5 * s, bulge_right=4.5 * s
        )
        # Front-view athletic shoe
        sx, sy = an
        draw.rounded_rectangle([sx - 15 * s, sy - 6 * s, sx + 15 * s, sy + 18 * s],
                               radius=7, fill=SHOE_UPPER, outline=NEAR_LIMB_OUTLINE, width=2)
        draw.line([(sx - 14 * s, sy + 15 * s), (sx + 14 * s, sy + 15 * s)], fill=SHOE_SOLE, width=max(3, int(5 * s)))

        if "quads" in pose.muscles:
            act, pri = pose.muscles["quads"]
            draw_muscle_overlay(odraw, hip, kn, 22 * s, 15 * s, "center", act, pri, bulge_pos=0.42)
        if "calves" in pose.muscles:
            act, pri = pose.muscles["calves"]
            draw_muscle_overlay(odraw, kn, an, 15 * s, 9 * s, "center", act, pri, bulge_pos=0.30, t_start=0.10, t_end=0.72)

    # 2. V-Taper Athletic Torso
    waist_l = pt_lerp(pose.hip_l, pose.sh_l, 0.36)
    waist_r = pt_lerp(pose.hip_r, pose.sh_r, 0.36)
    waist_l = (waist_l[0] + 7 * s, waist_l[1])
    waist_r = (waist_r[0] - 7 * s, waist_r[1])
    torso_poly = [
        (pose.sh_l[0] - 8 * s, pose.sh_l[1] - 4 * s),
        (pose.neck[0], pose.neck[1] + 6 * s),
        (pose.sh_r[0] + 8 * s, pose.sh_r[1] - 4 * s),
        waist_r,
        (pose.hip_r[0] + 8 * s, pose.hip_r[1] + 12 * s),
        (pose.pelvis[0], pose.pelvis[1] + 22 * s),
        (pose.hip_l[0] - 8 * s, pose.hip_l[1] + 12 * s),
        waist_l,
    ]
    draw.polygon(torso_poly, fill=TORSO_FILL, outline=NEAR_LIMB_OUTLINE)
    draw.line(torso_poly + [torso_poly[0]], fill=NEAR_LIMB_OUTLINE, width=3, joint="curve")

    # Athletic shorts smoothly contouring hips & upper thighs
    shorts_top_y = pose.pelvis[1] - 28 * s
    shorts_bot_y = pose.pelvis[1] + 52 * s
    shorts_poly = [
        (waist_l[0] - 2 * s, shorts_top_y),
        (waist_r[0] + 2 * s, shorts_top_y),
        (pose.hip_r[0] + 25 * s, pose.hip_r[1] + 16 * s),
        (pose.hip_r[0] + 22 * s, shorts_bot_y),
        (pose.pelvis[0] + 5 * s, shorts_bot_y),
        (pose.pelvis[0], pose.pelvis[1] + 26 * s),
        (pose.pelvis[0] - 5 * s, shorts_bot_y),
        (pose.hip_l[0] - 22 * s, shorts_bot_y),
        (pose.hip_l[0] - 25 * s, pose.hip_l[1] + 16 * s),
    ]
    draw.polygon(shorts_poly, fill=SHORTS_FILL, outline=NEAR_LIMB_OUTLINE)
    draw.line(shorts_poly + [shorts_poly[0]], fill=NEAR_LIMB_OUTLINE, width=2)

    # Torso front muscles
    if "chest" in pose.muscles:
        act, pri = pose.muscles["chest"]
        c_mid = pt_lerp(pose.chest, pose.neck, 0.15)
        draw_muscle_overlay(odraw, pose.sh_l, c_mid, 18 * s, 21 * s, "center", act, pri, t_start=0.14, t_end=0.92)
        draw_muscle_overlay(odraw, pose.sh_r, c_mid, 18 * s, 21 * s, "center", act, pri, t_start=0.14, t_end=0.92)
    if "lats" in pose.muscles:
        act, pri = pose.muscles["lats"]
        draw_muscle_overlay(odraw, pose.sh_l, waist_l, 20 * s, 13 * s, "center", act, pri, t_start=0.12, t_end=0.95)
        draw_muscle_overlay(odraw, pose.sh_r, waist_r, 20 * s, 13 * s, "center", act, pri, t_start=0.12, t_end=0.95)
    if "traps" in pose.muscles:
        act, pri = pose.muscles["traps"]
        draw_muscle_overlay(odraw, pose.sh_l, pose.neck, 18 * s, 16 * s, "center", act, pri, t_start=0.05, t_end=0.95)
        draw_muscle_overlay(odraw, pose.sh_r, pose.neck, 18 * s, 16 * s, "center", act, pri, t_start=0.05, t_end=0.95)
    if "abs" in pose.muscles:
        act, pri = pose.muscles["abs"]
        abs_bot = (pose.pelvis[0], pose.pelvis[1] - 26 * s)
        draw_muscle_overlay(odraw, abs_bot, pose.chest, 18 * s, 20 * s, "center", act, pri, t_start=0.04, t_end=0.90)
    if "obliques" in pose.muscles:
        act, pri = pose.muscles["obliques"]
        draw_muscle_overlay(odraw, pose.hip_l, pose.sh_l, 14 * s, 14 * s, "center", act, pri, t_start=0.12, t_end=0.58)
        draw_muscle_overlay(odraw, pose.hip_r, pose.sh_r, 14 * s, 14 * s, "center", act, pri, t_start=0.12, t_end=0.58)

    # 3. Head
    draw_head_front(draw, pose.neck, pose.head, scale=s)

    # 4. Arms
    for sh, el, wr in ((pose.sh_l, pose.el_l, pose.wr_l), (pose.sh_r, pose.el_r, pose.wr_r)):
        draw_volumetric_segment(
            draw, sh, el, 16.5 * s, 12.5 * s,
            fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
            bulge_pos=0.40, bulge_left=3 * s, bulge_right=3 * s
        )
        draw_volumetric_segment(
            draw, el, wr, 13 * s, 8.8 * s,
            fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, highlight=NEAR_LIMB_HI,
            bulge_pos=0.28, bulge_left=2.5 * s, bulge_right=2.5 * s
        )
        draw.ellipse([wr[0] - 9.5 * s, wr[1] - 9.5 * s, wr[0] + 9.5 * s, wr[1] + 9.5 * s],
                     fill=NEAR_LIMB_FILL, outline=NEAR_LIMB_OUTLINE, width=3)

        if "delts" in pose.muscles:
            act, pri = pose.muscles["delts"]
            d_end = pt_lerp(sh, el, 0.36)
            draw_muscle_overlay(odraw, sh, d_end, 20 * s, 15 * s, "center", act, pri, t_start=0.0, t_end=1.0)
        if "biceps" in pose.muscles:
            act, pri = pose.muscles["biceps"]
            draw_muscle_overlay(odraw, sh, el, 15 * s, 12 * s, "center", act, pri, t_start=0.22, t_end=0.86)
        if "forearms" in pose.muscles:
            act, pri = pose.muscles["forearms"]
            draw_muscle_overlay(odraw, el, wr, 12 * s, 8.5 * s, "center", act, pri, t_start=0.10, t_end=0.82)

    base_img.alpha_composite(overlay)

    draw2 = ImageDraw.Draw(base_img)
    for jpt in (pose.sh_l, pose.sh_r, pose.el_l, pose.el_r, pose.kn_l, pose.kn_r):
        draw_joint_marker(draw2, jpt, active=False, scale=s)


# ---------------------------------------------------------------------------
# 42 Distinct Exercise Frame Renderers
# ---------------------------------------------------------------------------
FLOOR_Y = 824.0


def build_standing_profile_base(
    hip: Pt,
    torso_tilt_deg: float,  # 0 = vertical upright, + = leaning forward toward facing
    facing: float = 1.0,
    scale: float = 1.0,
) -> Tuple[Pt, Pt, Pt]:
    """Returns (shoulder, neck, head) from hip with exact proportions."""
    torso_len = 186 * scale
    rad = math.radians(-90.0 + facing * torso_tilt_deg)
    shoulder = (hip[0] + math.cos(rad) * torso_len, hip[1] + math.sin(rad) * torso_len)
    neck = (shoulder[0] + math.cos(rad) * 26 * scale, shoulder[1] + math.sin(rad) * 26 * scale)
    # Keep head looking slightly more forward than deep hinge
    head_rad = math.radians(-90.0 + facing * torso_tilt_deg * 0.65)
    head = (neck[0] + math.cos(head_rad) * 38 * scale, neck[1] + math.sin(head_rad) * 38 * scale)
    return shoulder, neck, head


def render_vision_squat(img: Image.Image, fonts: Dict, u: float, front_squat: bool = False) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 1.05
    ankle = (496.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 62 * s, ankle[1])
    thigh_l, shin_l = 158 * s, 152 * s

    # Squat hip trajectory: back and deep down while keeping bar over midfoot (x=502)
    if front_squat:
        hip_x = lerp(492.0, 404.0, depth)
        hip_y = lerp(FLOOR_Y - 322 * s, FLOOR_Y - 178 * s, depth)
        torso_tilt = lerp(6.0, 17.0, depth)
    else:
        hip_x = lerp(492.0, 374.0, depth)
        hip_y = lerp(FLOOR_Y - 322 * s, FLOOR_Y - 188 * s, depth)
        torso_tilt = lerp(8.0, 31.0, depth)

    hip = (hip_x, hip_y)
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    if front_squat:
        bar_pt = (shoulder[0] + 24 * s, shoulder[1] - 4 * s)
        elbow = (shoulder[0] + 92 * s, shoulder[1] + 24 * s)
        wrist = bar_pt
    else:
        bar_pt = (shoulder[0] - 12 * s, shoulder[1] - 10 * s)
        elbow = (shoulder[0] - 44 * s, shoulder[1] + 68 * s)
        wrist = bar_pt

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 505, 86)

    # Far leg slight depth offset
    hip_f = (hip[0] + 8, hip[1] - 4)
    ankle_f = (ankle[0] + 18, ankle[1] - 4)
    knee_f = solve_ik_2link(hip_f, ankle_f, thigh_l, shin_l, bend_sign=-1.0)

    muscles = {
        "quads": (act, True),
        "glutes": (act * (0.85 if front_squat else 1.0), not front_squat),
        "hamstrings": (act * 0.55, False),
        "abs": (act * 0.70, False) if front_squat else (act * 0.45, False),
        "lower_back": (act * 0.55, False),
    }

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=knee_f, ankle_f=ankle_f, toe_f=(ankle_f[0] + 58 * s, ankle_f[1]),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=bar_pt,
        facing=1.0, head_tilt=torso_tilt * 0.35, scale=s, muscles=muscles,
    )
    render_profile_athlete(img, pose)

    # Draw Olympic barbell + vertical bar path over midfoot
    draw = ImageDraw.Draw(img)
    draw_barbell_profile(draw, bar_pt, radius=48 * s, bar_path_x=bar_pt[0],
                         path_y_min=190, path_y_max=FLOOR_Y - 12)

    draw_protractor(img, fonts, hip, knee, ankle, radius=52, force_pill_dir=(0.95, -0.35))
    return phase, breath


def render_vision_pushup(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.46, 0.60, 0.90, con_first=False)
    s = 0.95
    draw = ImageDraw.Draw(img)
    draw_mat(draw, 120, 840, FLOOR_Y)
    draw_floor_shadow(draw, 480, 260)

    # Feet anchored on left, hands anchored on right
    toe_n = (206.0, FLOOR_Y - 14)
    ankle_n = (224.0, FLOOR_Y - 46)
    hand_n = (712.0, FLOOR_Y - 22)

    # Body straight rigid line rotating around toes!
    body_deg = lerp(-24.0, -5.5, depth)  # -24 deg up from horizontal at top -> -5.5 at bottom
    rad = math.radians(body_deg)
    ux, uy = math.cos(rad), math.sin(rad)

    knee_n = (ankle_n[0] + ux * 150 * s, ankle_n[1] + uy * 150 * s)
    hip = (knee_n[0] + ux * 155 * s, knee_n[1] + uy * 155 * s)
    shoulder = (hip[0] + ux * 188 * s, hip[1] + uy * 188 * s)
    neck = (shoulder[0] + ux * 26 * s, shoulder[1] + uy * 26 * s)
    head = (neck[0] + ux * 36 * s, neck[1] + uy * 36 * s)

    # Arms IK from shoulder to planted hands (elbows bend backward toward ribs)
    ua, fa = 112 * s, 106 * s
    elbow_n = solve_ik_2link(shoulder, hand_n, ua, fa, bend_sign=1.0)
    hand_f = (hand_n[0] + 18, hand_n[1] - 6)
    elbow_f = solve_ik_2link((shoulder[0] + 12, shoulder[1] - 6), hand_f, ua, fa, bend_sign=1.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee_n, ankle_n=ankle_n, toe_n=toe_n,
        knee_f=(knee_n[0] + 8, knee_n[1] - 8), ankle_f=(ankle_n[0] + 8, ankle_n[1] - 8), toe_f=(toe_n[0] + 8, toe_n[1] - 8),
        elbow_n=elbow_n, wrist_n=hand_n,
        elbow_f=elbow_f, wrist_f=hand_f,
        facing=1.0, head_tilt=85.0 + body_deg, scale=s,
        muscles={"chest": (act, True), "triceps": (act, True), "delts": (act * 0.65, False), "abs": (0.62, False)},
    )
    render_profile_athlete(img, pose)

    # Subtle alignment reference line from ankle through hip to shoulder
    draw2 = ImageDraw.Draw(img)
    draw2.line([ankle_n, shoulder], fill=(*FORM_GREEN, ), width=2)

    draw_protractor(img, fonts, shoulder, elbow_n, hand_n, radius=46, force_pill_dir=(-0.75, -0.85))
    return phase, breath


def render_vision_lunge(img: Image.Image, fonts: Dict, u: float, bulgarian: bool = False) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 0.94
    thigh_l, shin_l = 156 * s, 150 * s

    draw = ImageDraw.Draw(img)
    if bulgarian:
        # Support bench behind athlete on left
        draw_flat_bench(draw, 150, 330, FLOOR_Y - 122, FLOOR_Y)
        draw_floor_shadow(draw, 540, 120)
        ankle_front = (590.0, FLOOR_Y - 20 * s)
        ankle_back = (304.0, FLOOR_Y - 134)
        toe_back = (254.0, FLOOR_Y - 126)
        hip_x = lerp(494.0, 476.0, depth)
        hip_y = lerp(FLOOR_Y - 302 * s, FLOOR_Y - 196 * s, depth)
        torso_tilt = lerp(10.0, 20.0, depth)
    else:
        draw_floor_shadow(draw, 485, 165)
        ankle_front = (606.0, FLOOR_Y - 20 * s)
        ankle_back = (318.0, FLOOR_Y - 36 * s)
        toe_back = (348.0, FLOOR_Y - 16 * s)
        hip_x = lerp(476.0, 474.0, depth)
        hip_y = lerp(FLOOR_Y - 294 * s, FLOOR_Y - 192 * s, depth)
        torso_tilt = lerp(6.0, 12.0, depth)

    hip = (hip_x, hip_y)
    knee_front = solve_ik_2link(hip, ankle_front, thigh_l, shin_l, bend_sign=-1.0)
    knee_back = solve_ik_2link(hip, ankle_back, thigh_l, shin_l, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Arms hanging straight with dumbbells
    elbow_n = (shoulder[0] - 4 * s, shoulder[1] + 108 * s)
    wrist_n = (elbow_n[0] + 6 * s, elbow_n[1] + 102 * s)
    elbow_f = (shoulder[0] + 16 * s, shoulder[1] + 106 * s)
    wrist_f = (elbow_f[0] + 6 * s, elbow_f[1] + 102 * s)

    # Draw far dumbbell behind athlete
    draw_dumbbell(draw, wrist_f, scale=s * 0.9, angle_deg=0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee_front, ankle_n=ankle_front, toe_n=(ankle_front[0] + 60 * s, ankle_front[1]),
        knee_f=knee_back, ankle_f=ankle_back, toe_f=toe_back,
        elbow_n=elbow_n, wrist_n=wrist_n,
        elbow_f=elbow_f, wrist_f=wrist_f,
        facing=1.0, head_tilt=torso_tilt * 0.3, scale=s,
        muscles={"quads": (act, True), "glutes": (act, True), "hamstrings": (act * 0.58, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell(draw2, wrist_n, scale=s * 0.95, angle_deg=0)
    draw_protractor(img, fonts, hip, knee_front, ankle_front, radius=48, force_pill_dir=(0.95, -0.35))
    return phase, breath


def render_vision_deadlift(img: Image.Image, fonts: Dict, u: float, rdl: bool = False) -> Tuple[str, str]:
    if rdl:
        depth, phase, breath, act = cycle_profile(u, 0.50, 0.64, 0.92, con_first=False)
    else:
        # Deadlift starts from floor (bottom=1) -> concentric pull up -> eccentric down
        # Use con_first=True with depth=0 at floor? Better: depth=1 is bottom, so invert!
        up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.90, con_first=True)
        depth = 1.0 - up

    s = 0.95
    ankle = (488.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 62 * s, ankle[1])
    thigh_l, shin_l = 158 * s, 152 * s

    if rdl:
        hip_x = lerp(480.0, 376.0, depth)
        hip_y = lerp(FLOOR_Y - 322 * s, FLOOR_Y - 284 * s, depth)
        torso_tilt = lerp(6.0, 74.0, depth)
    else:
        hip_x = lerp(480.0, 368.0, depth)
        hip_y = lerp(FLOOR_Y - 322 * s, FLOOR_Y - 240 * s, depth)
        torso_tilt = lerp(5.0, 62.0, depth)

    hip = (hip_x, hip_y)
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Bar hangs directly vertically below shoulder along midfoot line
    bar_x = 532.0
    arm_len = 208 * s
    bar_y = clamp(shoulder[1] + arm_len, FLOOR_Y - 400, FLOOR_Y - 54 * s)
    bar_pt = (bar_x, bar_y)
    elbow = pt_lerp(shoulder, bar_pt, 0.52)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 505, 115)

    hip_f = (hip[0] + 10, hip[1] - 4)
    ankle_f = (ankle[0] + 16, ankle[1] - 4)
    knee_f = solve_ik_2link(hip_f, ankle_f, thigh_l, shin_l, bend_sign=-1.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=knee_f, ankle_f=ankle_f, toe_f=(ankle_f[0] + 58 * s, ankle_f[1]),
        elbow_n=elbow, wrist_n=bar_pt,
        elbow_f=(elbow[0] + 8, elbow[1] - 4), wrist_f=bar_pt,
        facing=1.0, head_tilt=torso_tilt * 0.65, scale=s,
        muscles={
            "hamstrings": (act, True),
            "glutes": (act, True),
            "lower_back": (act * (0.72 if rdl else 0.90), not rdl),
            "lats": (act * 0.55, False),
            "quads": (act * 0.45, False) if not rdl else (0.0, False),
        },
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, bar_pt, radius=52 * s, bar_path_x=bar_x, path_y_min=420, path_y_max=FLOOR_Y - 6)
    draw_protractor(img, fonts, shoulder, hip, knee, radius=52, force_pill_dir=(-0.95, -0.30))
    return phase, breath


def render_vision_overhead_press(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.38, 0.54, 0.90, con_first=True)
    s = 0.80  # Slightly zoomed out to fit full overhead barbell cleanly!
    ankle = (474.0, FLOOR_Y - 20 * s)
    thigh_l, shin_l = 158 * s, 152 * s
    hip = (468.0, FLOOR_Y - 320 * s)
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    torso_tilt = lerp(4.0, 1.0, up)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Bar moves straight vertically from clavicle height to directly over midfoot/ears!
    bar_x = lerp(shoulder[0] + 30 * s, shoulder[0] + 6 * s, up)
    bar_y = lerp(shoulder[1] + 2 * s, shoulder[1] - 196 * s, up)
    bar_pt = (bar_x, bar_y)
    ua, fa = 112 * s, 106 * s
    elbow = solve_ik_2link(shoulder, bar_pt, ua, fa, bend_sign=-1.0)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 485, 76)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4),
        toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=bar_pt,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=bar_pt,
        facing=1.0, head_tilt=lerp(-8.0, 0.0, up), scale=s,
        muscles={"delts": (act, True), "triceps": (act, True), "abs": (0.55, False), "upper_back": (act * 0.5, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, bar_pt, radius=42 * s, bar_path_x=shoulder[0] + 12 * s,
                         path_y_min=156, path_y_max=shoulder[1] + 40)
    draw_protractor(img, fonts, shoulder, elbow, bar_pt, radius=46, force_pill_dir=(0.95, 0.25))
    return phase, breath


def render_bicep_curl(img: Image.Image, fonts: Dict, u: float, hammer: bool = False) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.92, con_first=True)
    s = 0.96
    ankle = (456.0, FLOOR_Y - 20 * s)
    thigh_l, shin_l = 158 * s, 152 * s
    hip = (452.0, FLOOR_Y - 322 * s)
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, 4.0, facing=1.0, scale=s)

    # Elbow pinned close to torso!
    elbow = (shoulder[0] + lerp(4 * s, 14 * s, up), shoulder[1] + 110 * s)
    forearm_ang = lerp(84.0, -52.0, up)
    wrist = forward_pt(elbow, 106 * s, forearm_ang)

    # Far arm mirrors slightly
    elbow_f = (elbow[0] + 14, elbow[1] - 4)
    wrist_f = forward_pt(elbow_f, 106 * s, forearm_ang)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 468, 78)

    muscles = {
        "biceps": (act, True),
        "forearms": (act * (0.95 if hammer else 0.55), hammer),
    }
    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4),
        toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=elbow_f, wrist_f=wrist_f,
        facing=1.0, scale=s, muscles=muscles,
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    if hammer:
        # Neutral grip: dumbbell aligned vertically along thumb!
        draw_dumbbell(draw2, wrist, scale=s * 1.05, angle_deg=forearm_ang - 90.0)
    else:
        draw_dumbbell_side_single(draw2, wrist, scale=s * 1.05)

    draw_protractor(img, fonts, shoulder, elbow, wrist, radius=50, force_pill_dir=(0.95, 0.15))
    return phase, breath


def render_vision_tricep_dip(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.46, 0.60, 0.92, con_first=False)
    s = 0.90
    bar_y = 480.0
    draw = ImageDraw.Draw(img)

    # Back parallel dip bar stanchion
    draw.rectangle([310, bar_y - 8, 650, bar_y + 8], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([334, bar_y, 352, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([608, bar_y, 626, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw_floor_shadow(draw, 480, 155)

    hand = (496.0, bar_y)
    # Shoulder descends relative to fixed hands on bar
    sh_y = lerp(bar_y - 196 * s, bar_y - 88 * s, depth)
    sh_x = lerp(496.0, 514.0, depth)
    shoulder = (sh_x, sh_y)
    torso_tilt = lerp(10.0, 22.0, depth)
    rad = math.radians(90.0 + torso_tilt)
    hip = (shoulder[0] + math.cos(rad) * 182 * s, shoulder[1] + math.sin(rad) * 182 * s)
    neck = (shoulder[0] - math.cos(rad) * 24 * s, shoulder[1] - math.sin(rad) * 24 * s)
    head = (neck[0] + 6 * s, neck[1] - 34 * s)

    # Legs tucked/crossed neatly off floor
    knee = (hip[0] + 18 * s, hip[1] + 148 * s)
    ankle = (knee[0] - 88 * s, knee[1] + 92 * s)
    toe = (ankle[0] - 24 * s, ankle[1] + 34 * s)

    ua, fa = 112 * s, 106 * s
    elbow = solve_ik_2link(shoulder, hand, ua, fa, bend_sign=1.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] - 10, knee[1] + 4), ankle_f=(ankle[0] - 8, ankle[1] - 6), toe_f=(toe[0] - 8, toe[1] - 6),
        elbow_n=elbow, wrist_n=hand,
        elbow_f=(elbow[0] + 12, elbow[1] - 4), wrist_f=(hand[0] + 12, hand[1] - 4),
        facing=1.0, head_tilt=torso_tilt * 0.4, scale=s,
        muscles={"triceps": (act, True), "chest": (act * 0.85, True), "delts": (act * 0.55, False)},
    )
    render_profile_athlete(img, pose)

    # Foreground steel dip rail gripping under hand
    draw2 = ImageDraw.Draw(img)
    draw2.rounded_rectangle([310, bar_y + 2, 650, bar_y + 18], radius=5, fill=STEEL_MID, outline=STEEL_BRIGHT, width=2)
    draw_protractor(img, fonts, shoulder, elbow, hand, radius=46, force_pill_dir=(-0.95, -0.35))
    return phase, breath


def render_vision_plank(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # Smooth isometric breathing & core contraction pulse
    pulse = 0.5 + 0.5 * math.sin(u * 2.0 * math.pi)
    act = lerp(0.72, 1.0, pulse)
    breath = "INSPIRER" if (u % 0.5) < 0.25 else "EXPIRER"
    phase = "ISOMÉTRIE"
    s = 0.96

    draw = ImageDraw.Draw(img)
    draw_mat(draw, 115, 845, FLOOR_Y)
    draw_floor_shadow(draw, 480, 290)

    # Forearm plank: toes left, elbows/forearms planted right
    toe = (182.0, FLOOR_Y - 14)
    ankle = (200.0, FLOOR_Y - 46)
    elbow = (686.0, FLOOR_Y - 22)
    wrist = (786.0, FLOOR_Y - 20)

    # Micro breathing rib expansion (~3px) while preserving laser-straight 180° spine!
    micro_y = -4.0 * math.sin(u * 2.0 * math.pi)
    shoulder = (680.0, FLOOR_Y - 134 * s + micro_y)
    # Straight chord ankle -> shoulder
    hip = pt_lerp(ankle, shoulder, 0.57)
    knee = pt_lerp(ankle, hip, 0.50)
    neck = (shoulder[0] + 28 * s, shoulder[1] - 4 * s)
    head = (neck[0] + 38 * s, neck[1] + 4 * s)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 6, knee[1] - 8), ankle_f=(ankle[0] + 6, ankle[1] - 8), toe_f=(toe[0] + 6, toe[1] - 8),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 14, elbow[1] - 8), wrist_f=(wrist[0] + 12, wrist[1] - 8),
        facing=1.0, head_tilt=78.0, scale=s,
        muscles={"abs": (act, True), "glutes": (act * 0.85, True), "delts": (0.55, False), "quads": (0.50, False)},
    )
    render_profile_athlete(img, pose)

    # Crisp green laser alignment line ankle -> hip -> shoulder
    draw2 = ImageDraw.Draw(img)
    draw2.line([ankle, shoulder], fill=FORM_GREEN, width=3)

    draw_protractor(img, fonts, shoulder, hip, ankle, label_override="180°", radius=56, force_pill_dir=(0.0, -1.0))
    return phase, breath


def render_vision_burpee(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    """Smooth 4-stage cyclic burpee: stand -> squat hands down -> plank -> jump!"""
    s = 0.82
    draw = ImageDraw.Draw(img)
    draw_mat(draw, 140, 820, FLOOR_Y)

    # Keyframe interpolation across 5 key poses over u in [0, 1)
    # Stage 0 (0.00-0.22): Stand -> Squat hands on floor
    # Stage 1 (0.22-0.46): Squat -> Plank extended
    # Stage 2 (0.46-0.58): Plank hold
    # Stage 3 (0.58-0.78): Plank -> Squat jump-in
    # Stage 4 (0.78-1.00): Explosive vertical jump -> Stand
    def key_stand(y_off: float = 0.0, arms_up: float = 0.0) -> Dict[str, Pt]:
        an = (520.0, FLOOR_Y - 18 * s - y_off)
        kn = (524.0, an[1] - 148 * s)
        hp = (516.0, kn[1] - 152 * s)
        sh = (522.0, hp[1] - 184 * s)
        el = forward_pt(sh, 108 * s, lerp(92.0, -84.0, arms_up))
        wr = forward_pt(el, 102 * s, lerp(86.0, -88.0, arms_up))
        return {"an": an, "kn": kn, "hp": hp, "sh": sh, "el": el, "wr": wr, "tilt": 4.0}

    def key_squat() -> Dict[str, Pt]:
        an = (500.0, FLOOR_Y - 22 * s)
        hp = (412.0, FLOOR_Y - 154 * s)
        kn = solve_ik_2link(hp, an, 152 * s, 148 * s, bend_sign=-1.0)
        sh = (544.0, FLOOR_Y - 224 * s)
        wr = (618.0, FLOOR_Y - 20 * s)
        el = solve_ik_2link(sh, wr, 108 * s, 102 * s, bend_sign=1.0)
        return {"an": an, "kn": kn, "hp": hp, "sh": sh, "el": el, "wr": wr, "tilt": 54.0}

    def key_plank() -> Dict[str, Pt]:
        an = (240.0, FLOOR_Y - 40 * s)
        hp = (456.0, FLOOR_Y - 114 * s)
        kn = pt_lerp(an, hp, 0.5)
        sh = (636.0, FLOOR_Y - 184 * s)
        wr = (644.0, FLOOR_Y - 20 * s)
        el = solve_ik_2link(sh, wr, 108 * s, 102 * s, bend_sign=1.0)
        return {"an": an, "kn": kn, "hp": hp, "sh": sh, "el": el, "wr": wr, "tilt": 74.0}

    def interp_k(k1: Dict, k2: Dict, t: float) -> Dict:
        t = smootherstep(t)
        return {k: (lerp(k1[k], k2[k], t) if isinstance(k1[k], float) else pt_lerp(k1[k], k2[k], t)) for k in k1}

    k_st = key_stand(0.0, 0.0)
    k_sq = key_squat()
    k_pl = key_plank()
    k_jp = key_stand(88.0, 1.0)

    if u < 0.22:
        k = interp_k(k_st, k_sq, u / 0.22)
        phase, breath, act = "EXCENTRIQUE", "INSPIRER", 0.65
    elif u < 0.44:
        k = interp_k(k_sq, k_pl, (u - 0.22) / 0.22)
        phase, breath, act = "PLANCHE", "EXPIRER", 0.85
    elif u < 0.56:
        k = k_pl
        phase, breath, act = "POINT BAS", "GAINÉ", 0.90
    elif u < 0.74:
        k = interp_k(k_pl, k_sq, (u - 0.56) / 0.18)
        phase, breath, act = "CONCENTRIQUE", "INSPIRER", 0.85
    elif u < 0.88:
        k = interp_k(k_sq, k_jp, (u - 0.74) / 0.14)
        phase, breath, act = "EXPLOSIF", "EXPIRER", 1.0
    else:
        k = interp_k(k_jp, k_st, (u - 0.88) / 0.12)
        phase, breath, act = "RÉCEPTION", "INSPIRER", 0.55

    # Neck & head aligned with torso vector
    dx, dy = k["sh"][0] - k["hp"][0], k["sh"][1] - k["hp"][1]
    L = max(1.0, math.hypot(dx, dy))
    ux, uy = dx / L, dy / L
    neck = (k["sh"][0] + ux * 24 * s, k["sh"][1] + uy * 24 * s)
    head = (neck[0] + ux * 36 * s, neck[1] + uy * 36 * s)
    toe = (k["an"][0] + (50 * s if k["tilt"] < 60 else -18 * s), k["an"][1] + (0 if k["tilt"] < 60 else 24 * s))

    draw_floor_shadow(draw, k["hp"][0], 120)
    pose = ProfilePose(
        hip=k["hp"], shoulder=k["sh"], neck=neck, head=head,
        knee_n=k["kn"], ankle_n=k["an"], toe_n=toe,
        knee_f=(k["kn"][0] + 10, k["kn"][1] - 6), ankle_f=(k["an"][0] + 10, k["an"][1] - 6), toe_f=(toe[0] + 10, toe[1] - 6),
        elbow_n=k["el"], wrist_n=k["wr"],
        elbow_f=(k["el"][0] + 12, k["el"][1] - 6), wrist_f=(k["wr"][0] + 12, k["wr"][1] - 6),
        facing=1.0, head_tilt=k["tilt"], scale=s,
        muscles={"quads": (act, True), "chest": (act * 0.85, True), "abs": (act * 0.90, True), "delts": (act * 0.6, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, k["sh"], k["hp"], k["kn"], radius=46, force_pill_dir=(-0.55, -0.90))
    return phase, breath


def render_vision_jumping_jack(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # Two brisk athletic cycles in 5 seconds (2.5s each -> smooth rhythmic feel)
    u2 = (u * 2.0) % 1.0
    open_p = 0.5 - 0.5 * math.cos(u2 * 2.0 * math.pi)
    phase = "OUVERTURE" if u2 < 0.5 else "FERMETURE"
    breath = "INSPIRER" if u2 < 0.5 else "EXPIRER"
    act = lerp(0.45, 1.0, open_p)
    s = 0.82

    jump_h = 34.0 * math.sin(u2 * 2.0 * math.pi) ** 2
    cx = 480.0
    floor_an = FLOOR_Y - 18 * s - jump_h

    pelvis = (cx, floor_an - 298 * s)
    chest = (cx, pelvis[1] - 118 * s)
    neck = (cx, pelvis[1] - 188 * s)
    head = (cx, neck[1] - 42 * s)
    sh_l, sh_r = (cx - 58 * s, pelvis[1] - 166 * s), (cx + 58 * s, pelvis[1] - 166 * s)
    hip_l, hip_r = (cx - 32 * s, pelvis[1]), (cx + 32 * s, pelvis[1])

    # Legs abduct symmetrically
    leg_spread = lerp(34 * s, 128 * s, open_p)
    an_l, an_r = (cx - leg_spread, floor_an), (cx + leg_spread, floor_an)
    kn_l = pt_lerp(hip_l, an_l, 0.50)
    kn_r = pt_lerp(hip_r, an_r, 0.50)

    # Arms sweep cleanly from sides (102°) overhead (-72°)
    arm_ang_r = lerp(80.0, -70.0, open_p)
    arm_ang_l = 180.0 - arm_ang_r
    el_r = forward_pt(sh_r, 110 * s, arm_ang_r)
    wr_r = forward_pt(el_r, 102 * s, arm_ang_r - lerp(0, 14, open_p))
    el_l = forward_pt(sh_l, 110 * s, arm_ang_l)
    wr_l = forward_pt(el_l, 102 * s, arm_ang_l + lerp(0, 14, open_p))

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, cx, lerp(78, 145, open_p))

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"delts": (act, True), "calves": (act, True), "quads": (act * 0.65, False), "abs": (0.55, False)},
    )
    render_front_athlete(img, pose)
    draw_protractor(img, fonts, hip_r, sh_r, el_r, radius=46, force_pill_dir=(0.95, 0.35))
    return phase, breath


def render_vision_pull_up(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.42, 0.58, 0.92, con_first=True)
    s = 0.80
    cx = 480.0
    bar_y = 212.0

    draw = ImageDraw.Draw(img)
    # Steel pull-up rig uprights + crossbar
    draw.rectangle([206, bar_y, 224, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([736, bar_y, 754, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rounded_rectangle([196, bar_y - 10, 764, bar_y + 8], radius=6, fill=STEEL_MID, outline=STEEL_BRIGHT, width=2)
    draw_floor_shadow(draw, cx, 110)

    # Hands locked onto overhead wide grip
    wr_l, wr_r = (cx - 122 * s, bar_y + 4), (cx + 122 * s, bar_y + 4)

    # Body rises cleanly so chin clears bar!
    sh_y = lerp(bar_y + 188 * s, bar_y + 52 * s, up)
    sh_l, sh_r = (cx - 58 * s, sh_y), (cx + 58 * s, sh_y)
    neck = (cx, sh_y - 18 * s)
    head = (cx, neck[1] - 38 * s)
    chest = (cx, sh_y + 46 * s)
    pelvis = (cx, sh_y + 166 * s)
    hip_l, hip_r = (cx - 32 * s, pelvis[1]), (cx + 32 * s, pelvis[1])
    kn_l, kn_r = (cx - 30 * s, pelvis[1] + 150 * s), (cx + 30 * s, pelvis[1] + 150 * s)
    an_l, an_r = (cx - 20 * s, kn_l[1] + 132 * s), (cx + 20 * s, kn_r[1] + 132 * s)

    ua, fa = 112 * s, 106 * s
    el_l = solve_ik_2link(sh_l, wr_l, ua, fa, bend_sign=-1.0)
    el_r = solve_ik_2link(sh_r, wr_r, ua, fa, bend_sign=1.0)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"lats": (act, True), "biceps": (act, True), "forearms": (act * 0.65, False), "abs": (0.50, False)},
    )
    render_front_athlete(img, pose)
    draw_protractor(img, fonts, sh_r, el_r, wr_r, radius=44, force_pill_dir=(0.95, 0.25))
    return phase, breath


def render_vision_bent_row(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.92, con_first=True)
    s = 0.95
    ankle = (444.0, FLOOR_Y - 20 * s)
    thigh_l, shin_l = 158 * s, 152 * s
    hip = (376.0, FLOOR_Y - 282 * s)
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    torso_tilt = 52.0
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Bar pulled straight upward toward lower ribs/navel
    bar_low = (shoulder[0] + 12 * s, shoulder[1] + 206 * s)
    bar_high = (hip[0] + 82 * s, hip[1] - 32 * s)
    bar_pt = pt_lerp(bar_low, bar_high, up)
    ua, fa = 112 * s, 106 * s
    elbow = solve_ik_2link(shoulder, bar_pt, ua, fa, bend_sign=1.0)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 470, 120)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4),
        toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=bar_pt,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=bar_pt,
        facing=1.0, head_tilt=torso_tilt * 0.60, scale=s,
        muscles={"lats": (act, True), "upper_back": (act, True), "biceps": (act * 0.72, False), "lower_back": (0.60, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, bar_pt, radius=46 * s, bar_path_x=None)
    draw_protractor(img, fonts, shoulder, elbow, bar_pt, radius=46, force_pill_dir=(-0.75, -0.80))
    return phase, breath


def render_glute_bridge(img: Image.Image, fonts: Dict, u: float, hip_thrust: bool = False) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.42, 0.58, 0.92, con_first=True)
    s = 0.94
    draw = ImageDraw.Draw(img)
    if hip_thrust:
        draw_flat_bench(draw, 130, 344, FLOOR_Y - 148, FLOOR_Y)
        draw_floor_shadow(draw, 540, 180)
        shoulder = (336.0, FLOOR_Y - 166)
        ankle = (684.0, FLOOR_Y - 20 * s)
        hip_low = (470.0, FLOOR_Y - 82)
        hip_high = (504.0, FLOOR_Y - 182)
    else:
        draw_mat(draw, 120, 840, FLOOR_Y)
        draw_floor_shadow(draw, 480, 250)
        shoulder = (284.0, FLOOR_Y - 34)
        ankle = (684.0, FLOOR_Y - 20 * s)
        hip_low = (464.0, FLOOR_Y - 34)
        hip_high = (488.0, FLOOR_Y - 146)

    hip = pt_lerp(hip_low, hip_high, up)
    thigh_l, shin_l = 156 * s, 150 * s
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)

    # Neck/head resting on mat or leaning cleanly against bench
    dx, dy = shoulder[0] - hip[0], shoulder[1] - hip[1]
    L = max(1.0, math.hypot(dx, dy))
    ux, uy = dx / L, dy / L
    if hip_thrust:
        neck = (shoulder[0] - 24 * s, shoulder[1] - 12 * s)
        head = (neck[0] - 22 * s, neck[1] - 26 * s)
        elbow = (shoulder[0] + 64 * s, shoulder[1] + 24 * s)
        wrist = (hip[0] - 6 * s, hip[1] - 14 * s)
    else:
        neck = (shoulder[0] - 26 * s, FLOOR_Y - 30)
        head = (neck[0] - 34 * s, FLOOR_Y - 34)
        elbow = (shoulder[0] + 92 * s, FLOOR_Y - 24)
        wrist = (elbow[0] + 94 * s, FLOOR_Y - 20)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 58 * s, ankle[1]),
        knee_f=(knee[0] + 10, knee[1] - 6), ankle_f=(ankle[0] + 12, ankle[1] - 4),
        toe_f=(ankle[0] + 68 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 8, elbow[1] - 6), wrist_f=(wrist[0] + 8, wrist[1] - 6),
        facing=-1.0, head_tilt=-72.0 if not hip_thrust else -35.0, scale=s,
        muscles={"glutes": (act, True), "hamstrings": (act * 0.80, not hip_thrust), "quads": (act * 0.48, False), "abs": (0.50, False)},
    )
    render_profile_athlete(img, pose)

    if hip_thrust:
        draw2 = ImageDraw.Draw(img)
        draw_barbell_profile(draw2, (hip[0] + 4, hip[1] - 18), radius=46 * s, bar_path_x=None)

    draw_protractor(img, fonts, shoulder, hip, knee, radius=52, force_pill_dir=(0.0, -1.0))
    return phase, breath


def render_vision_leg_raise(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.92, con_first=True)
    s = 0.94
    draw = ImageDraw.Draw(img)
    draw_mat(draw, 110, 850, FLOOR_Y)
    draw_floor_shadow(draw, 440, 230)

    shoulder = (274.0, FLOOR_Y - 34)
    neck = (246.0, FLOOR_Y - 32)
    head = (210.0, FLOOR_Y - 38)
    hip = (460.0, FLOOR_Y - 34)
    elbow = (376.0, FLOOR_Y - 22)
    wrist = (474.0, FLOOR_Y - 20)

    # Legs straight rising from 12° off floor to 90° vertical
    leg_ang = lerp(-10.0, -88.0, up)
    knee = forward_pt(hip, 154 * s, leg_ang)
    ankle = forward_pt(knee, 148 * s, leg_ang + 5.0)
    toe = forward_pt(ankle, 54 * s, leg_ang - 68.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 10, knee[1] - 4), ankle_f=(ankle[0] + 10, ankle[1] - 4), toe_f=(toe[0] + 10, toe[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 6, elbow[1] - 6), wrist_f=(wrist[0] + 6, wrist[1] - 6),
        facing=-1.0, head_tilt=-78.0, scale=s,
        muscles={"abs": (act, True), "quads": (act * 0.55, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, hip, knee, radius=54, force_pill_dir=(-0.45, -0.95))
    return phase, breath


def render_vision_lateral_raise(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.92, con_first=True)
    s = 0.90
    cx = 480.0
    floor_an = FLOOR_Y - 18 * s
    pelvis = (cx, floor_an - 300 * s)
    chest = (cx, pelvis[1] - 118 * s)
    neck = (cx, pelvis[1] - 188 * s)
    head = (cx, neck[1] - 42 * s)
    sh_l, sh_r = (cx - 60 * s, pelvis[1] - 166 * s), (cx + 60 * s, pelvis[1] - 166 * s)
    hip_l, hip_r = (cx - 32 * s, pelvis[1]), (cx + 32 * s, pelvis[1])
    kn_l, kn_r = (cx - 38 * s, floor_an - 150 * s), (cx + 38 * s, floor_an - 150 * s)
    an_l, an_r = (cx - 44 * s, floor_an), (cx + 44 * s, floor_an)

    # Arms abduct cleanly from 14° to 90° horizontal with slight athletic elbow bend (15°)
    ang_r = lerp(78.0, 2.0, up)
    ang_l = 180.0 - ang_r
    el_r = forward_pt(sh_r, 110 * s, ang_r)
    wr_r = forward_pt(el_r, 102 * s, ang_r + 12.0)
    el_l = forward_pt(sh_l, 110 * s, ang_l)
    wr_l = forward_pt(el_l, 102 * s, ang_l - 12.0)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, cx, 96)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"delts": (act, True), "traps": (act * 0.48, False)},
    )
    render_front_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell(draw2, wr_l, scale=s * 0.92, angle_deg=ang_l + 90)
    draw_dumbbell(draw2, wr_r, scale=s * 0.92, angle_deg=ang_r - 90)
    draw_protractor(img, fonts, hip_r, sh_r, el_r, radius=48, force_pill_dir=(0.90, 0.55))
    return phase, breath


def render_ex_leg_press(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 0.88
    draw = ImageDraw.Draw(img)

    # 45° Leg Press Steel Frame & Angled Rails
    rail_a = (380.0, 610.0)
    rail_b = (790.0, 210.0)
    draw.line([rail_a, rail_b], fill=STEEL_DARK, width=18)
    draw.line([rail_a, rail_b], fill=STEEL_MID, width=6)
    draw.rectangle([200, FLOOR_Y - 18, 760, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.line([(720, 278), (720, FLOOR_Y - 12)], fill=STEEL_DARK, width=16)
    draw.line([(250, 680), (250, FLOOR_Y - 12)], fill=STEEL_DARK, width=16)

    # Reclined seat & backrest pad
    hip = (368.0, 672.0)
    shoulder = (224.0, 560.0)
    draw.line([(shoulder[0] - 14, shoulder[1] + 22), (hip[0] - 12, hip[1] + 24)], fill=BENCH_PAD, width=32)
    draw.line([(hip[0] - 14, hip[1] + 24), (hip[0] + 68, hip[1] + 18)], fill=BENCH_PAD, width=26)

    # Footplate sliding smoothly along 45° rail!
    sled_t = lerp(0.70, 0.24, depth)
    foot_plate = pt_lerp((474.0, 564.0), (726.0, 324.0), sled_t)
    ankle = (foot_plate[0] - 18, foot_plate[1] + 14)
    toe = (foot_plate[0] - 54, foot_plate[1] - 32)
    thigh_l, shin_l = 158 * s, 152 * s
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=1.0)

    neck = (shoulder[0] - 20 * s, shoulder[1] - 18 * s)
    head = (neck[0] - 16 * s, neck[1] - 32 * s)
    elbow = (shoulder[0] + 54 * s, shoulder[1] + 82 * s)
    wrist = (hip[0] + 16 * s, hip[1] + 6 * s)

    # Draw heavy steel footplate + weight plates
    plate_top = (foot_plate[0] - 62, foot_plate[1] - 62)
    plate_bot = (foot_plate[0] + 48, foot_plate[1] + 48)
    draw.line([plate_top, plate_bot], fill=STEEL_BRIGHT, width=14)
    draw_barbell_profile(draw, (foot_plate[0] + 48, foot_plate[1] - 42), radius=46 * s)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 10, knee[1] - 8), ankle_f=(ankle[0] + 10, ankle[1] - 8), toe_f=(toe[0] + 10, toe[1] - 8),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=elbow, wrist_f=wrist,
        facing=1.0, head_tilt=-48.0, scale=s,
        muscles={"quads": (act, True), "glutes": (act * 0.85, True), "hamstrings": (act * 0.50, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, hip, knee, ankle, radius=48, force_pill_dir=(-0.60, -0.95))
    return phase, breath


def render_ex_leg_extension(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.94
    draw = ImageDraw.Draw(img)

    # Seated Leg Extension Machine Frame
    draw.rectangle([220, FLOOR_Y - 14, 720, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([280, 420, 304, FLOOR_Y - 12], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw.rectangle([504, 598, 526, FLOOR_Y - 12], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    # Seat + backrest upholstery
    draw.rounded_rectangle([302, 578, 536, 608], radius=8, fill=BENCH_PAD, outline=STEEL_MID, width=2)
    draw.rounded_rectangle([284, 382, 316, 588], radius=8, fill=BENCH_PAD, outline=STEEL_MID, width=2)

    hip = (368.0, 560.0)
    shoulder = (336.0, 382.0)
    neck = (334.0, 354.0)
    head = (338.0, 314.0)
    knee = (518.0, 560.0)
    elbow = (352.0, 488.0)
    wrist = (440.0, 584.0)

    # Shin pivots cleanly from 88° down to 174° full extension!
    shin_ang = lerp(92.0, 5.0, up)
    ankle = forward_pt(knee, 152 * s, shin_ang)
    toe = forward_pt(ankle, 54 * s, shin_ang - 82.0)

    # Machine pivot lever arm + ankle roller pad
    draw.line([knee, ankle], fill=STEEL_MID, width=10)
    draw.ellipse([knee[0] - 14, knee[1] - 14, knee[0] + 14, knee[1] + 14], fill=STEEL_DARK, outline=STEEL_BRIGHT, width=3)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 10, knee[1] - 6), ankle_f=(ankle[0] + 10, ankle[1] - 6), toe_f=(toe[0] + 10, toe[1] - 6),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 8, elbow[1] - 4), wrist_f=(wrist[0] + 8, wrist[1] - 4),
        facing=1.0, scale=s,
        muscles={"quads": (act, True)},
    )
    render_profile_athlete(img, pose)

    # Roller pad in front of shin/ankle
    draw2 = ImageDraw.Draw(img)
    pad_pt = pt_lerp(knee, ankle, 0.88)
    draw2.ellipse([pad_pt[0] - 18, pad_pt[1] - 18, pad_pt[0] + 18, pad_pt[1] + 18],
                  fill=BENCH_PAD_HI, outline=SIGNAL_ORANGE, width=3)

    draw_protractor(img, fonts, hip, knee, ankle, radius=52, force_pill_dir=(0.70, -0.80))
    return phase, breath


def render_ex_leg_curl(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.92
    draw = ImageDraw.Draw(img)

    # Prone Leg Curl Machine Bench
    draw_flat_bench(draw, 190, 578, 560, FLOOR_Y)
    draw.rectangle([558, 560, 580, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)

    # Athlete lying face-down (head left, knees at bench edge right)
    shoulder = (352.0, 534.0)
    neck = (322.0, 530.0)
    head = (284.0, 524.0)
    hip = (524.0, 536.0)
    knee = (668.0, 548.0)
    elbow = (300.0, 594.0)
    wrist = (262.0, 622.0)

    # Shin curls upward toward glutes (172° -> 62°)
    shin_ang = lerp(8.0, -112.0, up)
    ankle = forward_pt(knee, 146 * s, shin_ang)
    toe = forward_pt(ankle, 50 * s, shin_ang + 84.0)

    # Machine pivot lever arm
    draw.line([knee, ankle], fill=STEEL_MID, width=10)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 8, knee[1] - 6), ankle_f=(ankle[0] + 8, ankle[1] - 6), toe_f=(toe[0] + 8, toe[1] - 6),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=elbow, wrist_f=wrist,
        facing=-1.0, head_tilt=-72.0, scale=s,
        muscles={"hamstrings": (act, True), "calves": (act * 0.55, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    pad_pt = pt_lerp(knee, ankle, 0.85)
    draw2.ellipse([pad_pt[0] - 17, pad_pt[1] - 17, pad_pt[0] + 17, pad_pt[1] + 17],
                  fill=BENCH_PAD_HI, outline=SIGNAL_ORANGE, width=3)
    draw_protractor(img, fonts, hip, knee, ankle, radius=52, force_pill_dir=(-0.55, -0.95))
    return phase, breath


def render_ex_calf_raise(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.36, 0.60, 0.92, con_first=True)
    s = 0.92
    draw = ImageDraw.Draw(img)

    # Elevated steel calf block
    block_top = FLOOR_Y - 54
    draw.rounded_rectangle([420, block_top, 590, FLOOR_Y], radius=6, fill=STEEL_DARK, outline=STEEL_MID, width=3)
    draw.line([(428, block_top + 6), (582, block_top + 6)], fill=SIGNAL_ORANGE, width=3)

    # Forefoot fixed on edge of block, heel drops below top -> rises high!
    toe = (520.0, block_top - 6)
    heel_y = lerp(block_top + 26, block_top - 52, up)
    ankle = (464.0, heel_y - 16)
    knee = (474.0, ankle[1] - 150 * s)
    hip = (468.0, knee[1] - 154 * s)
    shoulder, neck, head = build_standing_profile_base(hip, 3.0, facing=1.0, scale=s)
    elbow = (shoulder[0] + 4 * s, shoulder[1] + 108 * s)
    wrist = (elbow[0] + 4 * s, elbow[1] + 102 * s)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 12, ankle[1] - 4), toe_f=(toe[0] + 12, toe[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 12, elbow[1] - 4), wrist_f=(wrist[0] + 12, wrist[1] - 4),
        facing=1.0, scale=s,
        muscles={"calves": (act, True)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell(draw2, wrist, scale=s * 0.95, angle_deg=0)
    draw_protractor(img, fonts, knee, ankle, toe, radius=46, force_pill_dir=(-0.95, -0.35))
    return phase, breath


def render_ex_bench_press(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 0.96
    draw = ImageDraw.Draw(img)

    bench_y = 584.0
    draw_flat_bench(draw, 200, 616, bench_y, FLOOR_Y)
    # Rack upright behind head
    draw.rectangle([216, 240, 236, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)
    draw_floor_shadow(draw, 460, 260)

    # Athlete lying supine (head left, feet planted flat on floor right)
    shoulder = (352.0, bench_y - 24)
    neck = (318.0, bench_y - 24)
    head = (278.0, bench_y - 28)
    hip = (540.0, bench_y - 22)
    knee = (674.0, bench_y + 40)
    ankle = (674.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 58 * s, ankle[1])

    # Barbell lowering smoothly to mid-sternum (~82 deg elbow angle at chest touch)
    bar_x = lerp(366.0, 414.0, depth)
    bar_y = lerp(shoulder[1] - 200 * s, shoulder[1] - 118 * s, depth)
    bar_pt = (bar_x, bar_y)
    ua, fa = 110 * s, 104 * s
    elbow = solve_ik_2link(shoulder, bar_pt, ua, fa, bend_sign=1.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 14, ankle[1] - 4), toe_f=(toe[0] + 14, toe[1] - 4),
        elbow_n=elbow, wrist_n=bar_pt,
        elbow_f=(elbow[0] + 10, elbow[1] - 6), wrist_f=bar_pt,
        facing=-1.0, head_tilt=-84.0, scale=s,
        muscles={"chest": (act, True), "triceps": (act, True), "delts": (act * 0.65, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, bar_pt, radius=48 * s, bar_path_x=390.0, path_y_min=290, path_y_max=bench_y - 40)
    draw_protractor(img, fonts, shoulder, elbow, bar_pt, radius=46, force_pill_dir=(0.95, 0.35))
    return phase, breath


def render_ex_incline_press(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 0.90
    draw = ImageDraw.Draw(img)

    pivot = (470.0, 628.0)
    draw_incline_bench(draw, pivot, back_len=240, seat_len=110, angle_deg=36.0, floor_y=FLOOR_Y)
    draw_floor_shadow(draw, 490, 230)

    hip = (486.0, 608.0)
    rad_b = math.radians(-180.0 + 36.0)
    shoulder = (hip[0] + math.cos(rad_b) * 182 * s, hip[1] + math.sin(rad_b) * 182 * s)
    neck = (shoulder[0] + math.cos(rad_b) * 26 * s, shoulder[1] + math.sin(rad_b) * 26 * s)
    head = (neck[0] + math.cos(rad_b) * 34 * s, neck[1] + math.sin(rad_b) * 34 * s)

    knee = (624.0, 626.0)
    ankle = (636.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 56 * s, ankle[1])

    # Pressing dumbbells straight up vertically from upper chest (~82 deg elbow at bottom)
    hand_top = (shoulder[0] + 24 * s, shoulder[1] - 196 * s)
    hand_bot = (shoulder[0] + 62 * s, shoulder[1] - 106 * s)
    hand = pt_lerp(hand_top, hand_bot, depth)
    ua, fa = 110 * s, 104 * s
    elbow = solve_ik_2link(shoulder, hand, ua, fa, bend_sign=1.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 10, knee[1] - 4), ankle_f=(ankle[0] + 12, ankle[1] - 4), toe_f=(toe[0] + 12, toe[1] - 4),
        elbow_n=elbow, wrist_n=hand,
        elbow_f=(elbow[0] + 12, elbow[1] - 6), wrist_f=(hand[0] + 12, hand[1] - 6),
        facing=-1.0, head_tilt=-48.0, scale=s,
        muscles={"chest": (act, True), "delts": (act * 0.85, True), "triceps": (act * 0.72, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell_side_single(draw2, hand, scale=s * 1.1)
    draw_protractor(img, fonts, shoulder, elbow, hand, radius=46, force_pill_dir=(0.95, 0.25))
    return phase, breath


def render_ex_chest_fly(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.42, 0.58, 0.92, con_first=True)
    s = 0.88
    cx = 480.0
    floor_an = FLOOR_Y - 18 * s
    pelvis = (cx, floor_an - 294 * s)
    chest = (cx, pelvis[1] - 116 * s)
    neck = (cx, pelvis[1] - 186 * s)
    head = (cx, neck[1] - 42 * s)
    sh_l, sh_r = (cx - 60 * s, pelvis[1] - 164 * s), (cx + 60 * s, pelvis[1] - 164 * s)
    hip_l, hip_r = (cx - 32 * s, pelvis[1]), (cx + 32 * s, pelvis[1])
    kn_l, kn_r = (cx - 38 * s, floor_an - 148 * s), (cx + 38 * s, floor_an - 148 * s)
    an_l, an_r = (cx - 46 * s, floor_an), (cx + 46 * s, floor_an)

    # Arms sweeping from wide open cable stretch to crisp front convergence
    el_r = (lerp(sh_r[0] + 104 * s, sh_r[0] + 38 * s, up), lerp(sh_r[1] + 12 * s, sh_r[1] + 54 * s, up))
    wr_r = (lerp(el_r[0] + 92 * s, cx + 16 * s, up), lerp(el_r[1] + 18 * s, chest[1] + 22 * s, up))
    el_l = (2 * cx - el_r[0], el_r[1])
    wr_l = (2 * cx - wr_r[0], wr_r[1])

    draw = ImageDraw.Draw(img)
    # Dual cable stacks left and right!
    w_off = up * 72.0
    draw_cable_tower(draw, 78, 360, wr_l, FLOOR_Y, weight_offset=w_off)
    draw_cable_tower(draw, RENDER_SIZE - 78, 360, wr_r, FLOOR_Y, weight_offset=w_off)
    draw_floor_shadow(draw, cx, 100)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"chest": (act, True), "delts": (act * 0.62, False)},
    )
    render_front_athlete(img, pose)
    draw_protractor(img, fonts, hip_r, sh_r, el_r, radius=44, force_pill_dir=(0.90, -0.55))
    return phase, breath


def render_ex_lat_pulldown(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.42, 0.58, 0.92, con_first=True)
    s = 0.84
    cx = 480.0
    draw = ImageDraw.Draw(img)

    # Overhead Pulley Tower Frame
    draw.rectangle([cx - 26, 146, cx + 26, FLOOR_Y], fill=(24, 27, 33), outline=STEEL_DARK, width=2)
    draw.ellipse([cx - 18, 152, cx + 18, 188], fill=STEEL_DARK, outline=STEEL_BRIGHT, width=3)
    # Seat & thigh hold-down pad
    draw.rounded_rectangle([cx - 72, 616, cx + 72, 642], radius=8, fill=BENCH_PAD, outline=STEEL_MID, width=2)
    draw.rectangle([cx - 14, 642, cx + 14, FLOOR_Y], fill=STEEL_DARK, outline=STEEL_MID, width=2)

    bar_y = lerp(228.0, 412.0, up)
    # Taut overhead cable
    draw.line([(cx, 170), (cx, bar_y)], fill=STEEL_BRIGHT, width=4)
    # Wide lat bar
    bar_w = 178 * s
    draw.line([(cx - bar_w, bar_y + 12), (cx - bar_w * 0.65, bar_y), (cx + bar_w * 0.65, bar_y), (cx + bar_w, bar_y + 12)],
              fill=STEEL_BRIGHT, width=9)

    pelvis = (cx, 602.0)
    chest = (cx, pelvis[1] - 114 * s)
    neck = (cx, pelvis[1] - 182 * s)
    head = (cx, neck[1] - 40 * s)
    sh_l, sh_r = (cx - 60 * s, pelvis[1] - 160 * s), (cx + 60 * s, pelvis[1] - 160 * s)
    hip_l, hip_r = (cx - 34 * s, pelvis[1]), (cx + 34 * s, pelvis[1])
    kn_l, kn_r = (cx - 56 * s, pelvis[1] + 54 * s), (cx + 56 * s, pelvis[1] + 54 * s)
    an_l, an_r = (cx - 56 * s, FLOOR_Y - 18 * s), (cx + 56 * s, FLOOR_Y - 18 * s)

    wr_l, wr_r = (cx - bar_w * 0.88, bar_y + 8), (cx + bar_w * 0.88, bar_y + 8)
    ua, fa = 112 * s, 106 * s
    el_l = solve_ik_2link(sh_l, wr_l, ua, fa, bend_sign=-1.0)
    el_r = solve_ik_2link(sh_r, wr_r, ua, fa, bend_sign=1.0)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"lats": (act, True), "biceps": (act * 0.72, False), "forearms": (0.45, False)},
    )
    render_front_athlete(img, pose)

    # Thigh pads in front of knees
    draw2 = ImageDraw.Draw(img)
    draw2.rounded_rectangle([cx - 86, pelvis[1] + 18, cx - 18, pelvis[1] + 44], radius=10, fill=BENCH_PAD_HI, outline=STEEL_MID, width=2)
    draw2.rounded_rectangle([cx + 18, pelvis[1] + 18, cx + 86, pelvis[1] + 44], radius=10, fill=BENCH_PAD_HI, outline=STEEL_MID, width=2)

    draw_protractor(img, fonts, sh_r, el_r, wr_r, radius=44, force_pill_dir=(0.95, 0.35))
    return phase, breath


def render_ex_seated_cable_row(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.42, 0.58, 0.92, con_first=True)
    s = 0.90
    draw = ImageDraw.Draw(img)

    # Low cable tower on right + seated bench on left
    pulley_y = 636.0
    draw_flat_bench(draw, 220, 470, 672, FLOOR_Y)
    # Footplate
    draw.line([(680, 594), (664, 718)], fill=STEEL_MID, width=12)

    hip = (360.0, 650.0)
    torso_tilt = lerp(12.0, -4.0, up)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)
    ankle = (660.0, 668.0)
    toe = (674.0, 614.0)
    knee = solve_ik_2link(hip, ankle, 154 * s, 148 * s, bend_sign=-1.0)

    # Handle pulled smoothly from full reach to lower ribs!
    hand_far = (shoulder[0] + 194 * s, shoulder[1] + 82 * s)
    hand_in = (hip[0] + 54 * s, hip[1] - 46 * s)
    hand = pt_lerp(hand_far, hand_in, up)
    ua, fa = 110 * s, 104 * s
    elbow = solve_ik_2link(shoulder, hand, ua, fa, bend_sign=1.0)

    draw_cable_tower(draw, 818, pulley_y, hand, FLOOR_Y, weight_offset=up * 90.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 8, knee[1] - 6), ankle_f=(ankle[0] + 8, ankle[1] - 6), toe_f=(toe[0] + 8, toe[1] - 6),
        elbow_n=elbow, wrist_n=hand,
        elbow_f=(elbow[0] + 10, elbow[1] - 6), wrist_f=hand,
        facing=1.0, scale=s,
        muscles={"lats": (act, True), "upper_back": (act, True), "biceps": (act * 0.70, False), "lower_back": (0.50, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, elbow, hand, radius=46, force_pill_dir=(-0.95, 0.15))
    return phase, breath


def render_ex_face_pull(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.92
    ankle = (352.0, FLOOR_Y - 20 * s)
    hip = (356.0, FLOOR_Y - 312 * s)
    knee = solve_ik_2link(hip, ankle, 156 * s, 150 * s, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, lerp(4.0, -5.0, up), facing=1.0, scale=s)

    # Pull rope high toward forehead/ears with high flared elbows!
    hand_far = (shoulder[0] + 198 * s, shoulder[1] - 8 * s)
    hand_in = (head[0] + 28 * s, head[1] - 4 * s)
    hand = pt_lerp(hand_far, hand_in, up)
    ua, fa = 110 * s, 104 * s
    elbow = solve_ik_2link(shoulder, hand, ua, fa, bend_sign=1.0)

    draw = ImageDraw.Draw(img)
    draw_cable_tower(draw, 804, shoulder[1] - 24, hand, FLOOR_Y, weight_offset=up * 82.0)
    draw_floor_shadow(draw, 368, 86)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 14, knee[1] - 4), ankle_f=(ankle[0] + 24, ankle[1] - 4), toe_f=(ankle[0] + 80 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=hand,
        elbow_f=(elbow[0] + 10, elbow[1] - 6), wrist_f=hand,
        facing=1.0, scale=s,
        muscles={"upper_back": (act, True), "delts": (act, True), "biceps": (act * 0.52, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, elbow, hand, radius=46, force_pill_dir=(-0.60, 0.90))
    return phase, breath


def render_ex_shrugs(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.36, 0.62, 0.92, con_first=True)
    s = 0.92
    cx = 480.0
    floor_an = FLOOR_Y - 18 * s
    pelvis = (cx, floor_an - 300 * s)
    chest = (cx, pelvis[1] - 118 * s)
    neck = (cx, pelvis[1] - 188 * s)
    head = (cx, neck[1] - 42 * s)

    # Shoulders elevate crisply 28px upward!
    shrug_dy = -30.0 * up
    sh_l = (cx - 62 * s, pelvis[1] - 160 * s + shrug_dy)
    sh_r = (cx + 62 * s, pelvis[1] - 160 * s + shrug_dy)
    el_l, el_r = (sh_l[0] - 12 * s, sh_l[1] + 108 * s), (sh_r[0] + 12 * s, sh_r[1] + 108 * s)
    wr_l, wr_r = (el_l[0] - 4 * s, el_l[1] + 102 * s), (el_r[0] + 4 * s, el_r[1] + 102 * s)
    hip_l, hip_r = (cx - 34 * s, pelvis[1]), (cx + 34 * s, pelvis[1])
    kn_l, kn_r = (cx - 40 * s, floor_an - 150 * s), (cx + 40 * s, floor_an - 150 * s)
    an_l, an_r = (cx - 46 * s, floor_an), (cx + 46 * s, floor_an)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, cx, 98)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=wr_l, wr_r=wr_r,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"traps": (act, True), "forearms": (act * 0.65, False), "delts": (act * 0.45, False)},
    )
    render_front_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell(draw2, wr_l, scale=s * 1.1, angle_deg=0)
    draw_dumbbell(draw2, wr_r, scale=s * 1.1, angle_deg=0)

    # Vertical elevation arrows & readout near right shoulder
    draw_protractor(img, fonts, neck, sh_r, el_r, label_override=f"+{int(round(up * 5))} cm",
                    radius=42, force_pill_dir=(0.95, -0.55))
    return phase, breath


def render_ex_front_raise(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.56, 0.92, con_first=True)
    s = 0.94
    ankle = (430.0, FLOOR_Y - 20 * s)
    hip = (426.0, FLOOR_Y - 320 * s)
    knee = solve_ik_2link(hip, ankle, 158 * s, 152 * s, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, 4.0, facing=1.0, scale=s)

    arm_ang = lerp(80.0, -2.0, up)
    elbow = forward_pt(shoulder, 110 * s, arm_ang)
    wrist = forward_pt(elbow, 102 * s, arm_ang - 8.0)

    # Far arm rests slightly back
    elbow_f = forward_pt(shoulder, 110 * s, 88.0)
    wrist_f = forward_pt(elbow_f, 102 * s, 84.0)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 444, 82)
    draw_dumbbell_side_single(draw, wrist_f, scale=s * 0.9)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4), toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=elbow_f, wrist_f=wrist_f,
        facing=1.0, scale=s,
        muscles={"delts": (act, True), "chest": (act * 0.50, False), "abs": (0.48, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell_side_single(draw2, wrist, scale=s * 1.05)
    draw_protractor(img, fonts, hip, shoulder, elbow, radius=52, force_pill_dir=(0.85, 0.65))
    return phase, breath


def render_ex_rear_delt_fly(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.94
    ankle = (456.0, FLOOR_Y - 20 * s)
    hip = (376.0, FLOOR_Y - 284 * s)
    knee = solve_ik_2link(hip, ankle, 158 * s, 152 * s, bend_sign=-1.0)
    torso_tilt = 74.0  # Deep horizontal hinge for rear delt isolation
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Arms sweep backward/outward along scapular line
    arm_ang = lerp(90.0, 166.0, up)
    elbow = forward_pt(shoulder, 108 * s, arm_ang)
    wrist = forward_pt(elbow, 102 * s, arm_ang - 16.0)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 468, 118)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4), toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 8), wrist_f=(wrist[0] + 10, wrist[1] - 8),
        facing=1.0, head_tilt=torso_tilt * 0.68, scale=s,
        muscles={"delts": (act, True), "upper_back": (act, True), "lower_back": (0.50, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell_side_single(draw2, wrist, scale=s * 0.95)
    draw_protractor(img, fonts, (shoulder[0], shoulder[1] + 120), shoulder, elbow, radius=52, force_pill_dir=(-0.55, 0.90))
    return phase, breath


def render_ex_tricep_pushdown(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.94
    ankle = (380.0, FLOOR_Y - 20 * s)
    hip = (376.0, FLOOR_Y - 318 * s)
    knee = solve_ik_2link(hip, ankle, 158 * s, 152 * s, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, 10.0, facing=1.0, scale=s)

    # Upper arm pinned vertically to ribs; forearm extends downward from 68° to 174°
    elbow = (shoulder[0] + 12 * s, shoulder[1] + 108 * s)
    forearm_ang = lerp(-24.0, 82.0, up)
    wrist = forward_pt(elbow, 104 * s, forearm_ang)

    draw = ImageDraw.Draw(img)
    draw_cable_tower(draw, 730, 180, wrist, FLOOR_Y, weight_offset=up * 88.0)
    draw_floor_shadow(draw, 394, 84)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4), toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=wrist,
        facing=1.0, scale=s,
        muscles={"triceps": (act, True), "forearms": (act * 0.45, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, elbow, wrist, radius=48, force_pill_dir=(0.95, -0.30))
    return phase, breath


def render_ex_skull_crusher(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    depth, phase, breath, act = cycle_profile(u, 0.48, 0.62, 0.92, con_first=False)
    s = 0.92
    draw = ImageDraw.Draw(img)
    bench_y = 602.0
    draw_flat_bench(draw, 220, 616, bench_y, FLOOR_Y)
    draw_floor_shadow(draw, 460, 240)

    shoulder = (356.0, bench_y - 24)
    neck = (322.0, bench_y - 24)
    head = (284.0, bench_y - 28)
    hip = (540.0, bench_y - 22)
    knee = (670.0, bench_y + 42)
    ankle = (670.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 58 * s, ankle[1])

    # Upper arm slightly angled back (~104°) for constant triceps tension
    elbow = forward_pt(shoulder, 110 * s, -104.0)
    forearm_ang = lerp(-96.0, -206.0, depth)
    wrist = forward_pt(elbow, 104 * s, forearm_ang)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 14, ankle[1] - 4), toe_f=(toe[0] + 14, toe[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=wrist,
        facing=-1.0, head_tilt=-84.0, scale=s,
        muscles={"triceps": (act, True), "forearms": (0.42, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, wrist, radius=32 * s)
    draw_protractor(img, fonts, shoulder, elbow, wrist, radius=46, force_pill_dir=(0.95, -0.50))
    return phase, breath


def render_ex_crunch(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    up, phase, breath, act = cycle_profile(u, 0.40, 0.58, 0.92, con_first=True)
    s = 0.95
    draw = ImageDraw.Draw(img)
    draw_mat(draw, 115, 845, FLOOR_Y)
    draw_floor_shadow(draw, 480, 250)

    hip = (464.0, FLOOR_Y - 34)
    ankle = (712.0, FLOOR_Y - 20 * s)
    toe = (ankle[0] + 58 * s, ankle[1])
    knee = solve_ik_2link(hip, ankle, 156 * s, 150 * s, bend_sign=-1.0)

    # Thoracic curl: torso angle rises from -172° (flat) to -130° (curled)
    torso_ang = lerp(-173.0, -128.0, up)
    shoulder = forward_pt(hip, 184 * s, torso_ang)
    neck = forward_pt(shoulder, 26 * s, torso_ang + 18.0 * up)
    head = forward_pt(neck, 34 * s, torso_ang + 28.0 * up)

    # Hands lightly behind temples, elbows open
    elbow = forward_pt(shoulder, 94 * s, torso_ang + 62.0)
    wrist = (head[0] + 10 * s, head[1] - 12 * s)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=toe,
        knee_f=(knee[0] + 10, knee[1] - 6), ankle_f=(ankle[0] + 12, ankle[1] - 4), toe_f=(toe[0] + 12, toe[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 8), wrist_f=wrist,
        facing=-1.0, head_tilt=torso_ang + 90.0, scale=s,
        muscles={"abs": (act, True)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, hip, knee, radius=54, force_pill_dir=(0.0, -1.0))
    return phase, breath


def render_ex_russian_twist(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # Smooth rhythmic left-to-right twist
    rot = math.sin(u * 2.0 * math.pi)  # -1 (left) -> +1 (right)
    act = lerp(0.62, 1.0, abs(rot))
    phase = "ROTATION D." if rot > 0.15 else ("ROTATION G." if rot < -0.15 else "CENTRE")
    breath = "EXPIRER" if abs(rot) > 0.45 else "INSPIRER"
    s = 0.92
    cx = 480.0

    draw = ImageDraw.Draw(img)
    draw_mat(draw, 160, 800, FLOOR_Y)
    draw_floor_shadow(draw, cx, 140)

    # Seated V-sit front 3/4 view
    pelvis = (cx, FLOOR_Y - 52)
    chest = (cx + rot * 12, pelvis[1] - 102 * s)
    neck = (cx + rot * 16, pelvis[1] - 166 * s)
    head = (cx + rot * 14, neck[1] - 40 * s)

    # Shoulders foreshorten smoothly with rotation
    sh_span = 58 * s * (1.0 - 0.22 * abs(rot))
    sh_l = (cx - sh_span + rot * 18, pelvis[1] - 146 * s - rot * 8)
    sh_r = (cx + sh_span + rot * 18, pelvis[1] - 146 * s + rot * 8)

    # Legs suspended in V-balance
    hip_l, hip_r = (cx - 34 * s, pelvis[1]), (cx + 34 * s, pelvis[1])
    kn_l, kn_r = (cx - 56 * s - rot * 10, pelvis[1] - 72 * s), (cx + 56 * s - rot * 10, pelvis[1] - 72 * s)
    an_l, an_r = (cx - 32 * s - rot * 12, pelvis[1] - 18 * s), (cx + 32 * s - rot * 12, pelvis[1] - 18 * s)

    # Hands gripping weight plate moving smoothly side-to-side
    plate_pt = (cx + rot * 138 * s, pelvis[1] - 64 * s + abs(rot) * 18)
    el_l = pt_lerp(sh_l, plate_pt, 0.52)
    el_l = (el_l[0] - 24 * s, el_l[1] + 22 * s)
    el_r = pt_lerp(sh_r, plate_pt, 0.52)
    el_r = (el_r[0] + 24 * s, el_r[1] + 22 * s)

    pose = FrontPose(
        pelvis=pelvis, chest=chest, neck=neck, head=head,
        sh_l=sh_l, sh_r=sh_r, el_l=el_l, el_r=el_r, wr_l=plate_pt, wr_r=plate_pt,
        hip_l=hip_l, hip_r=hip_r, kn_l=kn_l, kn_r=kn_r, an_l=an_l, an_r=an_r,
        scale=s,
        muscles={"obliques": (act, True), "abs": (0.85, True)},
    )
    render_front_athlete(img, pose)

    # Cast-iron bumper plate held in both hands
    draw2 = ImageDraw.Draw(img)
    draw_barbell_profile(draw2, plate_pt, radius=36 * s)
    deg = int(round(abs(rot) * 45))
    draw_protractor(img, fonts, (cx, pelvis[1] - 180), pelvis, plate_pt,
                    label_override=f"{deg}°", radius=56, force_pill_dir=(0.0, -1.0))
    return phase, breath


def render_ex_mountain_climber(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # 2 full alternating stride cycles in 5 seconds
    u2 = (u * 2.0) % 1.0
    drive = 0.5 - 0.5 * math.cos(u2 * 2.0 * math.pi)
    drive_f = 1.0 - drive
    phase = "GENOU G." if u2 < 0.5 else "GENOU D."
    breath = "EXPIRER" if (u2 % 0.5) < 0.25 else "INSPIRER"
    act = lerp(0.68, 1.0, max(drive, drive_f))
    s = 0.92

    draw = ImageDraw.Draw(img)
    draw_mat(draw, 115, 845, FLOOR_Y)
    draw_floor_shadow(draw, 480, 260)

    # High plank upper body anchored on right
    wrist = (706.0, FLOOR_Y - 22)
    shoulder = (690.0, FLOOR_Y - 216 * s)
    elbow = pt_lerp(shoulder, wrist, 0.50)
    hip = (506.0, FLOOR_Y - 178 * s)
    neck = (shoulder[0] + 24 * s, shoulder[1] - 6 * s)
    head = (neck[0] + 34 * s, neck[1] + 6 * s)

    # Near leg driving knee forward toward chest, far leg extended back (alternating!)
    thigh_l, shin_l = 152 * s, 146 * s
    an_ext = (212.0, FLOOR_Y - 38)
    an_drv = (444.0, FLOOR_Y - 56)

    ankle_n = pt_lerp(an_ext, an_drv, drive)
    knee_n = solve_ik_2link(hip, ankle_n, thigh_l, shin_l, bend_sign=-1.0)
    toe_n = (ankle_n[0] - 18 * s, ankle_n[1] + 24 * s)

    ankle_f = pt_lerp(an_ext, an_drv, drive_f)
    knee_f = solve_ik_2link((hip[0] + 8, hip[1] - 6), ankle_f, thigh_l, shin_l, bend_sign=-1.0)
    toe_f = (ankle_f[0] - 18 * s, ankle_f[1] + 24 * s)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee_n, ankle_n=ankle_n, toe_n=toe_n,
        knee_f=knee_f, ankle_f=ankle_f, toe_f=toe_f,
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 12, elbow[1] - 6), wrist_f=(wrist[0] + 12, wrist[1] - 6),
        facing=1.0, head_tilt=72.0, scale=s,
        muscles={"abs": (act, True), "quads": (act * 0.90, True), "delts": (0.58, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, shoulder, hip, knee_n, radius=48, force_pill_dir=(-0.45, -0.95))
    return phase, breath


def render_ex_kettlebell_swing(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # 2 brisk athletic swings in 5s
    u2 = (u * 2.0) % 1.0
    up, phase, breath, act = cycle_profile(u2, 0.34, 0.46, 0.90, con_first=True)
    if phase == "CONCENTRIQUE":
        phase = "EXPLOSIF"
    s = 0.94

    ankle = (434.0, FLOOR_Y - 20 * s)
    thigh_l, shin_l = 156 * s, 150 * s
    # Sharp athletic hip hinge -> crisp upright lock
    hip = (lerp(340.0, 444.0, up), lerp(FLOOR_Y - 266 * s, FLOOR_Y - 318 * s, up))
    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    torso_tilt = lerp(64.0, 2.0, up)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Straight arms swinging kettlebell from between thighs to chest height!
    arm_ang = lerp(118.0, 2.0, up)
    elbow = forward_pt(shoulder, 110 * s, arm_ang)
    wrist = forward_pt(elbow, 102 * s, arm_ang)

    draw = ImageDraw.Draw(img)
    draw_floor_shadow(draw, 448, 108)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 60 * s, ankle[1]),
        knee_f=(knee[0] + 12, knee[1] - 4), ankle_f=(ankle[0] + 16, ankle[1] - 4), toe_f=(ankle[0] + 72 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 8, elbow[1] - 4), wrist_f=wrist,
        facing=1.0, head_tilt=torso_tilt * 0.45, scale=s,
        muscles={"glutes": (act, True), "hamstrings": (act, True), "lower_back": (act * 0.65, False), "abs": (0.60, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_kettlebell(draw2, wrist, bell_dir_deg=arm_ang, scale=s)
    draw_protractor(img, fonts, shoulder, hip, knee, radius=52, force_pill_dir=(-0.95, -0.35))
    return phase, breath


def render_ex_box_jump(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    s = 0.78
    draw = ImageDraw.Draw(img)

    # 60 cm Plyometric Box on right
    box_x0, box_x1 = 560.0, 770.0
    box_top = FLOOR_Y - 190.0
    draw.rounded_rectangle([box_x0, box_top, box_x1, FLOOR_Y], radius=8,
                           fill=(32, 36, 44), outline=STEEL_MID, width=3)
    draw.line([(box_x0 + 8, box_top + 10), (box_x1 - 8, box_top + 10)], fill=SIGNAL_ORANGE, width=4)
    draw.text((box_x0 + 52, box_top + 78), "60 CM", font=fonts["stencil"], fill=MUTED_STEEL)
    draw_floor_shadow(draw, 355, 88)

    thigh_l, shin_l = 156 * s, 150 * s

    # Keyframes: 0-0.22 countermovement dip, 0.22-0.46 flight arc, 0.46-0.66 land & stand on box, 0.66-1.00 smooth step back down
    if u < 0.22:
        t = smootherstep(u / 0.22)
        ankle = (352.0, FLOOR_Y - 18 * s)
        hip = (lerp(346.0, 294.0, t), lerp(FLOOR_Y - 314 * s, FLOOR_Y - 224 * s, t))
        torso_tilt = lerp(6.0, 34.0, t)
        arm_ang = lerp(84.0, 145.0, t)
        phase, breath, act = "IMPULSION", "INSPIRER", 0.72
    elif u < 0.48:
        t = (u - 0.22) / 0.26
        # Parabolic flight trajectory onto top of box!
        ax = lerp(352.0, 650.0, t)
        ay = lerp(FLOOR_Y - 18 * s, box_top - 18 * s, t) - 185.0 * math.sin(t * math.pi)
        ankle = (ax, ay)
        hip = (ax - 32 * s, ay - lerp(224 * s, 210 * s, t) - 35 * math.sin(t * math.pi))
        torso_tilt = lerp(26.0, 16.0, t)
        arm_ang = lerp(-58.0, 18.0, t)
        phase, breath, act = "EXPLOSIF", "EXPIRER", 1.0
    elif u < 0.70:
        t = smootherstep((u - 0.48) / 0.22)
        ankle = (650.0, box_top - 18 * s)
        hip = (lerp(616.0, 646.0, t), lerp(box_top - 222 * s, box_top - 314 * s, t))
        torso_tilt = lerp(24.0, 5.0, t)
        arm_ang = lerp(22.0, 84.0, t)
        phase, breath, act = "RÉCEPTION", "EXPIRER", 0.78
    else:
        t = smootherstep((u - 0.70) / 0.30)
        ankle = (lerp(650.0, 352.0, t), lerp(box_top - 18 * s, FLOOR_Y - 18 * s, t))
        hip = (ankle[0] - 6 * s, ankle[1] - 296 * s)
        torso_tilt = 6.0
        arm_ang = 84.0
        phase, breath, act = "RETOUR", "INSPIRER", 0.42

    knee = solve_ik_2link(hip, ankle, thigh_l, shin_l, bend_sign=-1.0)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)
    elbow = forward_pt(shoulder, 108 * s, arm_ang)
    wrist = forward_pt(elbow, 100 * s, arm_ang - 14.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee, ankle_n=ankle, toe_n=(ankle[0] + 56 * s, ankle[1]),
        knee_f=(knee[0] + 10, knee[1] - 4), ankle_f=(ankle[0] + 12, ankle[1] - 4), toe_f=(ankle[0] + 66 * s, ankle[1] - 4),
        elbow_n=elbow, wrist_n=wrist,
        elbow_f=(elbow[0] + 10, elbow[1] - 4), wrist_f=(wrist[0] + 10, wrist[1] - 4),
        facing=1.0, head_tilt=torso_tilt * 0.4, scale=s,
        muscles={"quads": (act, True), "glutes": (act, True), "calves": (act, True), "hamstrings": (act * 0.55, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, hip, knee, ankle, radius=44, force_pill_dir=(0.90, -0.55))
    return phase, breath


def render_ex_farmer_walk(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    # 3 smooth athletic stride cycles across 5s with scrolling floor reference markers!
    gait = (u * 3.0) * 2.0 * math.pi
    s = 0.94
    draw = ImageDraw.Draw(img)

    # Scrolling floor speed marks to give crisp forward motion feel
    shift = (u * 3.0 * 240.0) % 120.0
    for gx in range(-120, RENDER_SIZE + 120, 120):
        x = gx - shift
        draw.line([(x, FLOOR_Y + 2), (x + 28, FLOOR_Y + 2)], fill=STEEL_DARK, width=4)
    draw_floor_shadow(draw, 480, 118)

    bob = -8.0 * abs(math.sin(gait))
    hip = (480.0, FLOOR_Y - 312 * s + bob)
    shoulder, neck, head = build_standing_profile_base(hip, 4.0, facing=1.0, scale=s)

    thigh_l, shin_l = 156 * s, 150 * s
    # Near leg & far leg out of phase by pi
    an_n = (480.0 + 96 * s * math.sin(gait), FLOOR_Y - 20 * s - max(0.0, 26 * math.cos(gait)))
    kn_n = solve_ik_2link(hip, an_n, thigh_l, shin_l, bend_sign=-1.0)

    an_f = (480.0 + 96 * s * math.sin(gait + math.pi), FLOOR_Y - 20 * s - max(0.0, 26 * math.cos(gait + math.pi)))
    kn_f = solve_ik_2link(hip, an_f, thigh_l, shin_l, bend_sign=-1.0)

    # Heavy dumbbells held rock-steady at sides (packed shoulders, tall braced posture!)
    elbow_n = (shoulder[0] - 2 * s, shoulder[1] + 108 * s)
    wrist_n = (elbow_n[0] + 4 * s, elbow_n[1] + 102 * s)
    elbow_f = (shoulder[0] + 16 * s, shoulder[1] + 106 * s)
    wrist_f = (elbow_f[0] + 4 * s, elbow_f[1] + 102 * s)

    draw_dumbbell(draw, wrist_f, scale=s * 1.15, angle_deg=0)

    act = 0.85 + 0.15 * math.sin(gait * 2.0)
    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=kn_n, ankle_n=an_n, toe_n=(an_n[0] + 58 * s, an_n[1]),
        knee_f=kn_f, ankle_f=an_f, toe_f=(an_f[0] + 58 * s, an_f[1]),
        elbow_n=elbow_n, wrist_n=wrist_n,
        elbow_f=elbow_f, wrist_f=wrist_f,
        facing=1.0, scale=s,
        muscles={"forearms": (act, True), "traps": (act, True), "abs": (0.82, True), "quads": (0.55, False), "glutes": (0.55, False)},
    )
    render_profile_athlete(img, pose)

    draw2 = ImageDraw.Draw(img)
    draw_dumbbell(draw2, wrist_n, scale=s * 1.22, angle_deg=0)
    draw_protractor(img, fonts, (hip[0], hip[1] - 180), hip, shoulder,
                    label_override="GAINÉ", radius=48, force_pill_dir=(-0.95, -0.40))
    breath = "INSPIRER" if (u * 2.0) % 1.0 < 0.5 else "EXPIRER"
    return "MARCHE LESTÉE", breath


def render_ex_mobility_stretch(img: Image.Image, fonts: Dict, u: float) -> Tuple[str, str]:
    """World's Greatest Stretch: deep lunge + smooth thoracic reach opening."""
    open_p, phase, breath, act = cycle_profile(u, 0.45, 0.65, 0.92, con_first=True)
    phase = "OUVERTURE" if open_p > 0.35 else "ÉTIREMENT"
    breath = "EXPIRER" if open_p > 0.45 else "INSPIRER"
    s = 0.92

    draw = ImageDraw.Draw(img)
    draw_mat(draw, 115, 845, FLOOR_Y)
    draw_floor_shadow(draw, 470, 240)

    # Deep lunge base: back foot extended left, front foot planted right
    ankle_back = (212.0, FLOOR_Y - 34)
    toe_back = (194.0, FLOOR_Y - 14)
    ankle_front = (646.0, FLOOR_Y - 20 * s)
    toe_front = (ankle_front[0] + 58 * s, ankle_front[1])
    hip = (464.0, FLOOR_Y - 142)

    thigh_l, shin_l = 156 * s, 150 * s
    knee_front = solve_ik_2link(hip, ankle_front, thigh_l, shin_l, bend_sign=-1.0)
    knee_back = pt_lerp(hip, ankle_back, 0.52)

    # Torso opens slightly upward as near arm sweeps overhead toward sky!
    torso_tilt = lerp(64.0, 42.0, open_p)
    shoulder, neck, head = build_standing_profile_base(hip, torso_tilt, facing=1.0, scale=s)

    # Far hand planted solidly on mat inside front foot
    wrist_f = (584.0, FLOOR_Y - 20)
    elbow_f = solve_ik_2link(shoulder, wrist_f, 110 * s, 104 * s, bend_sign=1.0)

    # Near arm reaches from mat smoothly straight up toward ceiling (thoracic rotation!)
    arm_ang = lerp(74.0, -84.0, open_p)
    elbow_n = forward_pt(shoulder, 110 * s, arm_ang)
    wrist_n = forward_pt(elbow_n, 102 * s, arm_ang - 6.0)

    pose = ProfilePose(
        hip=hip, shoulder=shoulder, neck=neck, head=head,
        knee_n=knee_front, ankle_n=ankle_front, toe_n=toe_front,
        knee_f=knee_back, ankle_f=ankle_back, toe_f=toe_back,
        elbow_n=elbow_n, wrist_n=wrist_n,
        elbow_f=elbow_f, wrist_f=wrist_f,
        facing=1.0, head_tilt=lerp(42.0, -18.0, open_p), scale=s,
        muscles={"hamstrings": (act, True), "glutes": (act, True), "upper_back": (act * 0.90, True), "delts": (act * 0.55, False)},
    )
    render_profile_athlete(img, pose)
    draw_protractor(img, fonts, elbow_f, shoulder, elbow_n, radius=48, force_pill_dir=(-0.75, -0.75))
    return phase, breath


# ---------------------------------------------------------------------------
# Master Registry of ALL 42 Videos (16 Vision + 26 Encyclopedia Archetypes)
# ---------------------------------------------------------------------------
@dataclass
class VideoSpec:
    filename: str
    title: str
    subtitle: str
    renderer: Callable[[Image.Image, Dict, float], Tuple[str, str]]


VIDEO_SPECS: List[VideoSpec] = [
    # --- 16 VISION AI EXERCISES ---
    VideoSpec("vision_squat.mp4", "SQUAT BARRE", "TEMPO 3-1-1 · QUADRICEPS · FESSIERS",
              lambda img, f, u: render_vision_squat(img, f, u, front_squat=False)),
    VideoSpec("vision_pushup.mp4", "POMPES STRICTES", "TEMPO 2-1-1 · PECTORAUX · TRICEPS",
              render_vision_pushup),
    VideoSpec("vision_lunge.mp4", "FENTES HALTÈRES", "TEMPO 2-1-1 · QUADRICEPS · FESSIERS",
              lambda img, f, u: render_vision_lunge(img, f, u, bulgarian=False)),
    VideoSpec("vision_deadlift.mp4", "SOULEVÉ DE TERRE", "TEMPO 2-1-1 · CHAÎNE POSTÉRIEURE",
              lambda img, f, u: render_vision_deadlift(img, f, u, rdl=False)),
    VideoSpec("vision_overhead_press.mp4", "DÉVELOPPÉ MILITAIRE", "TEMPO 2-0-1 · DELTOÏDES · TRICEPS",
              render_vision_overhead_press),
    VideoSpec("vision_bicep_curl.mp4", "CURL BICEPS", "TEMPO 3-1-1 · BICEPS · BRACHIAL",
              lambda img, f, u: render_bicep_curl(img, f, u, hammer=False)),
    VideoSpec("vision_tricep_dip.mp4", "DIPS PARALLÈLES", "TEMPO 2-1-1 · TRICEPS · PECTORAUX",
              render_vision_tricep_dip),
    VideoSpec("vision_plank.mp4", "GAINAGE PLANCHE", "ISOMÉTRIE 45S · TRANSVERSE · ABDOS",
              render_vision_plank),
    VideoSpec("vision_burpee.mp4", "BURPEE COMPLET", "DYNAMIQUE · CORPS COMPLET · CARDIO",
              render_vision_burpee),
    VideoSpec("vision_jumping_jack.mp4", "JUMPING JACKS", "RYTHMÉ · CARDIO · ÉPAULES · MOLLETS",
              render_vision_jumping_jack),
    VideoSpec("vision_pull_up.mp4", "TRACTIONS PRONATION", "TEMPO 2-1-1 · GRAND DORSAL · BICEPS",
              render_vision_pull_up),
    VideoSpec("vision_bent_row.mp4", "ROWING PENCHÉ", "TEMPO 2-1-1 · DOS · RHOMBOÏDES",
              render_vision_bent_row),
    VideoSpec("vision_glute_bridge.mp4", "PONT FESSIER", "TEMPO 2-1-1 · GRAND FESSIER · ISCHIOS",
              lambda img, f, u: render_glute_bridge(img, f, u, hip_thrust=False)),
    VideoSpec("vision_leg_raise.mp4", "RELEVÉ DE JAMBES", "TEMPO 3-1-1 · ABDOS INFÉRIEURS",
              render_vision_leg_raise),
    VideoSpec("vision_lateral_raise.mp4", "ÉLÉVATIONS LATÉRALES", "TEMPO 2-1-1 · DELTOÏDE MOYEN",
              render_vision_lateral_raise),
    VideoSpec("vision_bulgarian_squat.mp4", "SQUAT BULGARE", "TEMPO 3-1-1 · QUADRICEPS · FESSIERS",
              lambda img, f, u: render_vision_lunge(img, f, u, bulgarian=True)),

    # --- 26 ENCYCLOPEDIA MOVEMENT ARCHETYPES ---
    VideoSpec("ex_front_squat.mp4", "FRONT SQUAT", "TEMPO 3-1-1 · QUADRICEPS · GAINAGE",
              lambda img, f, u: render_vision_squat(img, f, u, front_squat=True)),
    VideoSpec("ex_romanian_deadlift.mp4", "SOULEVÉ ROUMAIN (RDL)", "TEMPO 3-1-1 · ISCHIOS · FESSIERS",
              lambda img, f, u: render_vision_deadlift(img, f, u, rdl=True)),
    VideoSpec("ex_hip_thrust.mp4", "HIP THRUST BARRE", "TEMPO 2-1-1 · GRAND FESSIER",
              lambda img, f, u: render_glute_bridge(img, f, u, hip_thrust=True)),
    VideoSpec("ex_leg_press.mp4", "PRESSE À CUISSES 45°", "TEMPO 3-1-1 · QUADRICEPS · FESSIERS",
              render_ex_leg_press),
    VideoSpec("ex_leg_extension.mp4", "LEG EXTENSION", "TEMPO 2-1-1 · QUADRICEPS ISOLATION",
              render_ex_leg_extension),
    VideoSpec("ex_leg_curl.mp4", "LEG CURL ALLONGÉ", "TEMPO 3-1-1 · ISCHIO-JAMBIERS",
              render_ex_leg_curl),
    VideoSpec("ex_calf_raise.mp4", "EXTENSIONS MOLLETS", "TEMPO 2-2-1 · GASTROCNÉMIENS · SOLÉAIRE",
              render_ex_calf_raise),
    VideoSpec("ex_bench_press.mp4", "DÉVELOPPÉ COUCHÉ", "TEMPO 3-1-1 · PECTORAUX · TRICEPS",
              render_ex_bench_press),
    VideoSpec("ex_incline_press.mp4", "DÉVELOPPÉ INCLINÉ", "TEMPO 3-1-1 · HAUT PECTORAUX · DELTS",
              render_ex_incline_press),
    VideoSpec("ex_chest_fly.mp4", "ÉCARTÉS POULIE", "TEMPO 3-1-1 · GRAND PECTORAL",
              render_ex_chest_fly),
    VideoSpec("ex_lat_pulldown.mp4", "TIRAGE VERTICAL", "TEMPO 2-1-1 · GRAND DORSAL · BICEPS",
              render_ex_lat_pulldown),
    VideoSpec("ex_seated_cable_row.mp4", "TIRAGE HORIZONTAL", "TEMPO 2-1-1 · DOS · TRAPÈZES MOYENS",
              render_ex_seated_cable_row),
    VideoSpec("ex_face_pull.mp4", "FACE PULL POULIE", "TEMPO 2-1-1 · DELTOÏDE POST. · COIFFE",
              render_ex_face_pull),
    VideoSpec("ex_shrugs.mp4", "SHRUGS TRAPÈZES", "TEMPO 2-1-1 · TRAPÈZES SUPÉRIEURS",
              render_ex_shrugs),
    VideoSpec("ex_front_raise.mp4", "ÉLÉVATIONS FRONTALES", "TEMPO 2-1-1 · DELTOÏDE ANTÉRIEUR",
              render_ex_front_raise),
    VideoSpec("ex_rear_delt_fly.mp4", "OISEAU BUSTE PENCHÉ", "TEMPO 2-1-1 · DELTOÏDE POSTÉRIEUR",
              render_ex_rear_delt_fly),
    VideoSpec("ex_hammer_curl.mp4", "CURL MARTEAU", "TEMPO 2-1-1 · BRACHIAL · AVANT-BRAS",
              lambda img, f, u: render_bicep_curl(img, f, u, hammer=True)),
    VideoSpec("ex_tricep_pushdown.mp4", "EXTENSIONS POULIE", "TEMPO 2-1-1 · TRICEPS BRACHIAL",
              render_ex_tricep_pushdown),
    VideoSpec("ex_skull_crusher.mp4", "BARRE AU FRONT", "TEMPO 3-1-1 · LONGUE PORTION TRICEPS",
              render_ex_skull_crusher),
    VideoSpec("ex_crunch.mp4", "CRUNCH ABDOMINAL", "TEMPO 2-1-1 · GRAND DROIT ABDOS",
              render_ex_crunch),
    VideoSpec("ex_russian_twist.mp4", "RUSSIAN TWIST", "RYTHMÉ · OBLIQUES · GAINAGE",
              render_ex_russian_twist),
    VideoSpec("ex_mountain_climber.mp4", "MOUNTAIN CLIMBERS", "DYNAMIQUE · ABDOS · CARDIO",
              render_ex_mountain_climber),
    VideoSpec("ex_kettlebell_swing.mp4", "KETTLEBELL SWING", "EXPLOSIF · FESSIERS · ISCHIOS",
              render_ex_kettlebell_swing),
    VideoSpec("ex_box_jump.mp4", "BOX JUMP PLYO", "EXPLOSIF · PUISSANCE JAMBES",
              render_ex_box_jump),
    VideoSpec("ex_farmer_walk.mp4", "FARMER'S WALK", "CONTINU · GRIP · TRAPÈZES · GAINAGE",
              render_ex_farmer_walk),
    VideoSpec("ex_mobility_stretch.mp4", "MOBILITÉ & ÉTIREMENT", "FLUIDE 4-4 · HANCHES · THORACIQUE",
              render_ex_mobility_stretch),
]


def render_single_frame(spec: VideoSpec, fonts: Dict, frame_idx: int) -> Image.Image:
    u = frame_idx / float(NUM_FRAMES)
    canvas = get_studio_backdrop().convert("RGBA")
    phase, breath = spec.renderer(canvas, fonts, u)
    draw = ImageDraw.Draw(canvas)
    draw_studio_hud(draw, fonts, spec.title, spec.subtitle, phase, breath, u)
    rgb = canvas.convert("RGB")
    return rgb.resize((OUT_SIZE, OUT_SIZE), Image.Resampling.LANCZOS)


def generate_one_video(args_tuple: Tuple[int, str, Optional[str]]) -> Tuple[str, int, float]:
    spec_idx, out_dir_str, preview_png_dir = args_tuple
    spec = VIDEO_SPECS[spec_idx]
    t0 = time.time()
    fonts = load_fonts()
    out_path = Path(out_dir_str) / spec.filename

    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo",
        "-vcodec", "rawvideo",
        "-s", f"{OUT_SIZE}x{OUT_SIZE}",
        "-pix_fmt", "rgb24",
        "-r", str(FPS),
        "-i", "-",
        "-c:v", "libx264",
        "-profile:v", "baseline",
        "-level", "3.0",
        "-pix_fmt", "yuv420p",
        "-crf", "24",
        "-preset", "medium",
        "-movflags", "+faststart",
        str(out_path),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    assert proc.stdin is not None

    try:
        for i in range(NUM_FRAMES):
            frame_img = render_single_frame(spec, fonts, i)
            if preview_png_dir and spec.filename == "vision_squat.mp4" and i == 75:
                frame_img.save(Path(preview_png_dir) / "video_check_squat.png")
            if preview_png_dir and spec.filename == "ex_bench_press.mp4" and i == 80:
                frame_img.save(Path(preview_png_dir) / "video_check_bench.png")
            if preview_png_dir and spec.filename == "vision_pull_up.mp4" and i == 72:
                frame_img.save(Path(preview_png_dir) / "video_check_pullup.png")
            if preview_png_dir and spec.filename == "ex_chest_fly.mp4" and i == 72:
                frame_img.save(Path(preview_png_dir) / "video_check_chestfly.png")
            proc.stdin.write(frame_img.tobytes())
    finally:
        proc.stdin.close()

    ret = proc.wait()
    if ret != 0:
        raise RuntimeError(f"ffmpeg failed ({ret}) for {spec.filename}")

    size_bytes = out_path.stat().st_size
    return spec.filename, size_bytes, time.time() - t0


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate KINETIX Coach biomechanical execution MP4 videos.")
    parser.add_argument(
        "--out-dir",
        default=str(Path(__file__).resolve().parent.parent / "app/src/main/assets/videos"),
        help="Output directory for generated .mp4 files",
    )
    parser.add_argument("--only", nargs="*", help="Optional subset of filenames to render")
    parser.add_argument("--preview-dir", default="/tmp", help="Directory to save sample verification PNGs")
    parser.add_argument("--workers", type=int, default=max(1, os.cpu_count() or 2))
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    indices = list(range(len(VIDEO_SPECS)))
    if args.only:
        only_set = set(args.only)
        indices = [i for i, s in enumerate(VIDEO_SPECS) if s.filename in only_set]

    print(f"Generating {len(indices)} studio-grade MP4 videos -> {out_dir} (workers={args.workers})...")
    t_all = time.time()
    tasks = [(i, str(out_dir), args.preview_dir) for i in indices]

    total_bytes = 0
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        for fname, sz, dt in pool.map(generate_one_video, tasks):
            total_bytes += sz
            print(f"  [OK] {fname:28s}  {sz / 1024:6.1f} KB  ({dt:.2f}s)")

    print(
        f"Done! {len(indices)} videos generated in {time.time() - t_all:.1f}s "
        f"(total {total_bytes / (1024 * 1024):.2f} MB)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
