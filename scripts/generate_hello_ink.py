#!/usr/bin/env python3
"""Generate single-stroke SDF + 16-bit arc-time calligraphy fields for HelloScreen.

Produces:
  - hello_main_ink.png      (1062x810: 2-layer crossing-free SDF + 16-bit time for "LiquidBounce")
  - hello_subtitle_ink.png  (1062x810: 2-layer crossing-free SDF + 16-bit time for "nextgen for Android")
  - hello_build_ink.png     (512x1080: 8x5 grid of 64x108 2-layer SDF + 16-bit time rollerball glyphs
                             imitating the handwritten pen style of hello_credit.png)
  - hello_main.png / hello_main_smooth.png / hello_subtitle.png / hello_subtitle_smooth.png
"""
from pathlib import Path
import math
import numpy as np
from PIL import Image
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parent.parent
GUI = ROOT / "src/main/resources/assets/liquidbounce/textures/gui"

STAGE_W, STAGE_H = 1062, 405
CELL_W, CELL_H = 64, 108
BUILD_COLS, BUILD_ROWS = 8, 5
BASE_R_BUILD = 1.46


def catmull_rom_chain(pts, samples_per_seg=44, alpha=0.5):
    pts = [np.array(p, dtype=np.float64) for p in pts]
    if len(pts) == 2:
        t = np.linspace(0.0, 1.0, samples_per_seg)[:, None]
        return (1.0 - t) * pts[0] + t * pts[1]
    p0 = pts[0] + (pts[0] - pts[1])
    pn = pts[-1] + (pts[-1] - pts[-2])
    ext = [p0] + pts + [pn]
    out = []
    for i in range(len(ext) - 3):
        P0, P1, P2, P3 = ext[i], ext[i + 1], ext[i + 2], ext[i + 3]

        def tj(ti, pa, pb):
            d = math.hypot(pb[0] - pa[0], pb[1] - pa[1])
            return ti + max(d, 0.5) ** alpha

        t0 = 0.0
        t1 = tj(t0, P0, P1)
        t2 = tj(t1, P1, P2)
        t3 = tj(t2, P2, P3)
        n = samples_per_seg if i < len(ext) - 4 else samples_per_seg + 1
        ts = np.linspace(t1, t2, n, endpoint=(i == len(ext) - 4))
        for t in ts:
            A1 = (t1 - t) / (t1 - t0) * P0 + (t - t0) / (t1 - t0) * P1
            A2 = (t2 - t) / (t2 - t1) * P1 + (t - t1) / (t2 - t1) * P2
            A3 = (t3 - t) / (t3 - t2) * P2 + (t - t2) / (t3 - t2) * P3
            B1 = (t2 - t) / (t2 - t0) * A1 + (t - t0) / (t2 - t0) * A2
            B2 = (t3 - t) / (t3 - t1) * A2 + (t - t1) / (t3 - t1) * A3
            C = (t2 - t) / (t2 - t1) * B1 + (t - t1) / (t2 - t1) * B2
            out.append(C)
    return np.array(out, dtype=np.float64)


def build_stroke(xy_pts, base_r=2.60, taper_start=0.48, taper_end=0.45, taper_px=13.0):
    pts3 = [(p[0], p[1], p[2] if len(p) > 2 else base_r) for p in xy_pts]
    dense = catmull_rom_chain(pts3, samples_per_seg=44)
    diffs = np.diff(dense[:, :2], axis=0)
    seg_lens = np.hypot(diffs[:, 0], diffs[:, 1])
    arc = np.concatenate([[0.0], np.cumsum(seg_lens)])
    total = max(arc[-1], 1e-4)
    num = max(int(math.ceil(total / 0.30)) + 1, 8)
    u_arc = np.linspace(0.0, total, num)
    xs = np.interp(u_arc, arc, dense[:, 0])
    ys = np.interp(u_arc, arc, dense[:, 1])
    rs = np.interp(u_arc, arc, dense[:, 2])
    tp = min(taper_px, total * 0.30)
    if tp > 0.4:
        s_in = np.clip(u_arc / tp, 0.0, 1.0)
        s_in = s_in * s_in * (3.0 - 2.0 * s_in)
        s_out = np.clip((total - u_arc) / tp, 0.0, 1.0)
        s_out = s_out * s_out * (3.0 - 2.0 * s_out)
        rs = rs * (taper_start + (1.0 - taper_start) * s_in) * (taper_end + (1.0 - taper_end) * s_out)
    return np.column_stack([xs, ys, rs, u_arc]), total


def split_into_two_non_crossing_layers(strokes, tube_dist=5.8, pen_lift_px=12.0):
    total_timeline = 0.0
    for idx, (_, length) in enumerate(strokes):
        if idx > 0:
            total_timeline += pen_lift_px
        total_timeline += length
    if total_timeline <= 0.0:
        return [np.zeros((0, 4)), np.zeros((0, 4))], 1.0

    chunks = []
    cursor = 0.0
    chunk_len_px = 38.0
    for s_id, (arr, length) in enumerate(strokes):
        if s_id > 0:
            cursor += pen_lift_px
        s_norm = (cursor + arr[:, 3]) / total_timeline
        full = np.column_stack([arr[:, 0], arr[:, 1], arr[:, 2], s_norm])
        dx = np.gradient(full[:, 0])
        dy = np.gradient(full[:, 1])
        ang = np.unwrap(np.arctan2(dy, dx))
        dang = np.abs(np.gradient(ang))
        n_pts = len(full)
        step = max(int(round(chunk_len_px / 0.30)), 20)
        cuts = [0]
        last_cut = 0
        for k in range(12, n_pts - 12):
            if k - last_cut >= 18 and dang[k] > 0.14 and dang[k] >= dang[k - 1] and dang[k] >= dang[k + 1]:
                cuts.append(k)
                last_cut = k
            elif k - last_cut >= step:
                cuts.append(k)
                last_cut = k
        cuts.append(n_pts - 1)
        for c_idx in range(len(cuts) - 1):
            a, b = cuts[c_idx], cuts[c_idx + 1]
            if b > a:
                chunks.append((full[a:b + 1], s_id, c_idx))
        cursor += length

    trees = [cKDTree(c[0][:, :2]) for c in chunks]
    bboxes = [
        (c[0][:, 0].min() - tube_dist, c[0][:, 1].min() - tube_dist,
         c[0][:, 0].max() + tube_dist, c[0][:, 1].max() + tube_dist)
        for c in chunks
    ]
    adj = [[] for _ in chunks]
    for i in range(len(chunks)):
        bx0, by0, bx1, by1 = bboxes[i]
        for j in range(i + 1, len(chunks)):
            if chunks[i][1] == chunks[j][1] and abs(chunks[i][2] - chunks[j][2]) <= 1:
                p_prev = chunks[i][0]
                p_next = chunks[j][0]
                v1 = p_prev[-1, :2] - p_prev[max(0, len(p_prev) - 10), :2]
                v2 = p_next[min(len(p_next) - 1, 9), :2] - p_next[0, :2]
                n1 = np.linalg.norm(v1)
                n2 = np.linalg.norm(v2)
                if n1 > 1e-3 and n2 > 1e-3 and np.dot(v1, v2) / (n1 * n2) < -0.45:
                    adj[i].append(j)
                    adj[j].append(i)
                continue
            ox0, oy0, ox1, oy1 = bboxes[j]
            if bx1 < ox0 or ox1 < bx0 or by1 < oy0 or oy1 < by0:
                continue
            dists, _ = trees[i].query(chunks[j][0][::2, :2], k=1)
            if dists.min() < tube_dist:
                adj[i].append(j)
                adj[j].append(i)

    layer = [-1] * len(chunks)
    for i in range(len(chunks)):
        used = {layer[nb] for nb in adj[i] if layer[nb] != -1}
        pref = layer[i - 1] if (i > 0 and chunks[i][1] == chunks[i - 1][1] and layer[i - 1] != -1) else 0
        if pref not in used:
            layer[i] = pref
        else:
            layer[i] = 1 - pref

    layer_pts = [[], []]
    for idx, (pts, s_id, _) in enumerate(chunks):
        L = layer[idx]
        layer_pts[L].append(pts)
        if idx > 0 and chunks[idx - 1][1] == s_id and layer[idx - 1] != L:
            prev_pts = chunks[idx - 1][0]
            v1 = prev_pts[-1, :2] - prev_pts[max(0, len(prev_pts) - 10), :2]
            v2 = pts[min(len(pts) - 1, 9), :2] - pts[0, :2]
            if np.dot(v1, v2) >= 0.0:
                bridge = min(14, len(prev_pts), len(pts))
                layer_pts[L].append(prev_pts[-bridge:])
                layer_pts[layer[idx - 1]].append(pts[:bridge])

    return [np.vstack(lp) if lp else np.zeros((0, 4)) for lp in layer_pts], total_timeline


def bake_dual_layer_field(w, h, strokes, max_dist=11.9375, pen_lift_px=12.0, border_pad=0):
    layers, total_arc = split_into_two_non_crossing_layers(strokes, pen_lift_px=pen_lift_px)
    tex = np.zeros((h * 2, w, 4), dtype=np.uint8)
    tex[:, :, 0] = 255
    tex[:, :, 3] = 255
    for L in range(2):
        pts = layers[L]
        y_off = L * h
        if len(pts) == 0:
            continue
        margin = 14
        x0 = max(border_pad, int(math.floor(pts[:, 0].min())) - margin)
        x1 = min(w - border_pad, int(math.ceil(pts[:, 0].max())) + margin + 1)
        y0 = max(border_pad, int(math.floor(pts[:, 1].min())) - margin)
        y1 = min(h - border_pad, int(math.ceil(pts[:, 1].max())) + margin + 1)
        if x1 <= x0 or y1 <= y0:
            continue
        sub_yy, sub_xx = np.mgrid[y0:y1, x0:x1]
        sub_grid = np.column_stack([sub_xx.ravel().astype(np.float64), sub_yy.ravel().astype(np.float64)])
        tree = cKDTree(pts[:, :2])
        dists, idxs = tree.query(sub_grid, k=1, distance_upper_bound=14.5)
        valid = np.isfinite(dists) & (idxs < len(pts))
        sd_arr = np.full(len(sub_grid), max_dist, dtype=np.float64)
        s_arr = np.zeros(len(sub_grid), dtype=np.float64)
        if valid.any():
            v_idx = idxs[valid]
            sd_arr[valid] = dists[valid] - pts[v_idx, 2]
            s_arr[valid] = pts[v_idx, 3]
        r_byte = np.clip(np.rint((sd_arr + 4.0) * 16.0), 0, 255).astype(np.uint8).reshape(y1 - y0, x1 - x0)
        s_u16 = np.clip(np.rint(s_arr * 65535.0), 0, 65535).astype(np.uint16).reshape(y1 - y0, x1 - x0)
        tex[y_off + y0:y_off + y1, x0:x1, 0] = r_byte
        tex[y_off + y0:y_off + y1, x0:x1, 1] = ((s_u16 >> 8) & 255).astype(np.uint8)
        tex[y_off + y0:y_off + y1, x0:x1, 2] = (s_u16 & 255).astype(np.uint8)
    return tex, total_arc


def hello_gradient_rgb(w, h):
    stops_u = np.array([0.00, 0.15, 0.25, 0.35, 0.45, 0.55, 0.68, 0.80, 0.90, 1.00])
    stops_rgb = np.array([
        [ 96, 111, 250],
        [108, 101, 244],
        [116,  95, 240],
        [138,  91, 231],
        [179,  91, 214],
        [213,  92, 195],
        [240,  98, 161],
        [250, 116, 134],
        [252, 136, 113],
        [254, 157,  92],
    ], dtype=np.float64)
    u = np.linspace(0.0, 1.0, w)
    rgb_row = np.column_stack([np.interp(u, stops_u, stops_rgb[:, c]) for c in range(3)])
    return np.broadcast_to(np.rint(rgb_row).clip(0, 255).astype(np.uint8)[None, :, :], (h, w, 3)).copy()


def eval_ink_plate(tex, total_arc, progress=1.0, r_nom=2.60, aa=1.15, rgb=None):
    h = tex.shape[0] // 2
    w = tex.shape[1]
    cov_max = np.zeros((h, w), dtype=np.float64)
    for L in range(2):
        sub = tex[L * h:(L + 1) * h]
        sd = sub[:, :, 0].astype(np.float64) / 16.0 - 4.0
        s = (sub[:, :, 1].astype(np.float64) * 256.0 + sub[:, :, 2].astype(np.float64)) / 65535.0
        d_final = sd.copy()
        if progress < 0.9999:
            ds_px = (s - progress) * total_arc
            ahead = ds_px > 0.0
            radial = np.maximum(sd[ahead] + r_nom, 0.0)
            d_final[ahead] = np.maximum(sd[ahead], np.hypot(radial, ds_px[ahead]) - r_nom)
        if aa <= 0.05:
            cov = (d_final <= 0.0).astype(np.float64)
        else:
            t = np.clip((d_final + aa * 0.5) / aa, 0.0, 1.0)
            cov = 1.0 - t * t * (3.0 - 2.0 * t)
        cov_max = np.maximum(cov_max, cov)
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[:, :, :3] = 255 if rgb is None else rgb
    out[:, :, 3] = np.rint(cov_max * 255.0).clip(0, 255).astype(np.uint8)
    return out


# ==============================================================================
# 1. "LiquidBounce" (1062 x 405) — Single-stroke continuous calligraphy
# ==============================================================================
MAIN_STROKES_SPEC = [
    ([(214, 51, 2.2), (221, 58, 2.6), (215, 84, 2.75), (186, 130, 2.8), (138, 188, 2.8),
      (76, 245, 2.75), (26, 274, 2.65), (10, 276, 2.5), (15, 263, 2.55), (48, 232, 2.65),
      (102, 194, 2.65), (156, 174, 2.6), (218, 165, 2.05)], 2.65, 0.45, 0.36, 12.0),
    ([(256, 141, 2.3), (249, 160, 2.6), (254, 165, 2.55), (263, 152, 2.1)], 2.55, 0.50, 0.40, 8.0),
    ([(281, 63, 2.3), (277, 75, 2.6), (274, 86, 2.2)], 2.55, 0.55, 0.50, 6.0),
    ([(302, 122, 2.3), (286, 125, 2.6), (274, 141, 2.65), (280, 151, 2.6), (296, 140, 2.6),
      (304, 124, 2.7), (286, 164, 2.8), (256, 228, 2.8), (216, 308, 2.75), (178, 376, 2.65),
      (161, 394, 2.5), (159, 384, 2.45), (172, 344, 2.55), (199, 276, 2.0)], 2.65, 0.50, 0.35, 12.0),
    ([(309, 146, 2.15), (324, 129, 2.55), (319, 147, 2.55), (331, 147, 2.55),
      (344, 129, 2.55), (340, 147, 2.55), (351, 145, 2.5),
      (361, 130, 2.55), (357, 146, 2.55), (369, 143, 2.5),
      (388, 127, 2.55), (373, 145, 2.6), (387, 141, 2.6),
      (416, 88, 2.65), (444, 35, 2.55),
      (416, 88, 2.65), (388, 144, 2.6), (381, 173, 2.5), (386, 176, 2.05)], 2.58, 0.45, 0.38, 10.0),
    ([(370, 106, 2.2), (367, 114, 2.1)], 2.25, 0.55, 0.55, 4.5),
    ([(398, 166, 2.05), (432, 154, 2.55), (458, 134, 2.65),
      (486, 74, 2.68), (513, 15, 2.58),
      (486, 74, 2.68), (458, 134, 2.65),
      (476, 94, 2.48), (498, 88, 2.52), (495, 103, 2.48), (474, 111, 2.48),
      (499, 113, 2.52), (496, 128, 2.52), (472, 133, 2.48), (500, 124, 2.42),
      (524, 107, 2.48), (510, 124, 2.52), (524, 124, 2.52), (530, 109, 2.48), (540, 107, 2.42),
      (548, 100, 2.5), (544, 119, 2.52), (555, 118, 2.5), (566, 99, 2.5), (563, 116, 2.5),
      (577, 98, 2.5), (574, 114, 2.5), (583, 96, 2.5), (594, 95, 2.5), (591, 112, 2.5), (603, 107, 2.45),
      (621, 90, 2.5), (611, 106, 2.52), (627, 105, 2.5),
      (649, 92, 2.52), (659, 79, 2.48), (647, 78, 2.48), (640, 95, 2.55), (659, 98, 2.55),
      (703, 83, 2.5), (758, 60, 1.85)], 2.55, 0.42, 0.28, 14.0),
]

# ==============================================================================
# 2. "nextgen for Android" (1062 x 405) — Single-stroke continuous calligraphy
# ==============================================================================
SUB_STROKES_SPEC = [
    ([(384, 211, 2.0), (401, 187, 2.42), (394, 207, 2.45), (413, 187, 2.42), (408, 206, 2.45),
      (425, 196, 2.42), (432, 185, 2.38), (423, 185, 2.38), (418, 202, 2.45), (431, 204, 2.42),
      (445, 186, 2.42), (440, 204, 2.45), (451, 198, 2.38),
      (467, 166, 2.48), (455, 202, 2.48), (467, 200, 2.42),
      (487, 183, 2.45), (473, 198, 2.48), (484, 202, 2.48), (492, 185, 2.52),
      (472, 216, 2.62), (432, 248, 2.58), (381, 266, 1.85)], 2.46, 0.45, 0.32, 12.0),
    ([(434, 202, 1.95), (451, 186, 1.95)], 2.0, 0.45, 0.45, 5.5),
    ([(454, 177, 1.95), (478, 173, 1.95)], 2.0, 0.45, 0.45, 5.5),
    ([(496, 196, 2.05), (511, 184, 2.38), (502, 184, 2.38), (498, 198, 2.42), (512, 198, 2.42),
      (526, 183, 2.42), (521, 198, 2.42), (538, 183, 2.42), (536, 197, 2.42), (550, 188, 1.85)], 2.4, 0.45, 0.35, 9.0),
    ([(564, 199, 2.05), (626, 155, 2.52), (669, 118, 2.52), (664, 138, 2.58),
      (613, 180, 2.62), (543, 226, 2.62), (493, 245, 2.52), (497, 230, 2.48),
      (549, 199, 2.52), (609, 183, 2.52),
      (640, 168, 2.45), (628, 183, 2.48), (643, 182, 2.48), (650, 168, 2.45),
      (667, 163, 2.45), (664, 178, 2.48), (680, 168, 2.45), (728, 156, 1.85)], 2.52, 0.42, 0.30, 12.0),
    ([(738, 194, 2.05), (798, 138, 2.62), (854, 86, 2.58), (816, 138, 2.62), (780, 188, 2.58),
      (760, 169, 2.45), (792, 162, 2.48), (822, 160, 2.48),
      (833, 156, 2.48), (825, 174, 2.48), (844, 156, 2.48), (839, 174, 2.48),
      (866, 155, 2.48), (851, 173, 2.52), (867, 170, 2.52),
      (908, 115, 2.6), (947, 63, 2.52), (908, 115, 2.6), (867, 170, 2.55), (856, 191, 2.5), (878, 175, 2.42),
      (898, 156, 2.45), (893, 172, 2.48), (911, 157, 2.42),
      (935, 151, 2.45), (924, 167, 2.48), (939, 166, 2.48), (945, 152, 2.45),
      (962, 150, 2.45), (956, 166, 2.48), (970, 162, 2.42),
      (994, 149, 2.48), (979, 167, 2.52), (997, 163, 2.52),
      (1025, 118, 2.6), (1052, 74, 2.52), (1025, 118, 2.6), (997, 163, 2.55), (976, 195, 2.5), (996, 187, 1.95)], 2.52, 0.42, 0.35, 12.0),
    ([(989, 104, 2.25), (986, 114, 2.15)], 2.35, 0.55, 0.55, 5.0),
]


# ==============================================================================
# 3. 40 Rollerball Handwritten Glyphs (`#`, `0-9`, `a-z`, `.`, `-`, `+`)
#    Imitating `by cleverpeople622` in hello_credit.png
# ==============================================================================
def slant_narrow(pts, x_shift=4.0):
    out = []
    for p in pts:
        x, y = p[0], p[1]
        r = p[2] if len(p) > 2 else BASE_R_BUILD
        xc = 10.0 + (x - 14.0) * 0.74
        xs = xc + (76.0 - y) * 0.34 + x_shift
        out.append((xs, y, r))
    return out


RAW_BUILD_GLYPHS = [
    ('#', [[(20, 20), (18, 77)], [(31, 18), (29, 76)], [(11, 43), (39, 38)], [(10, 58), (38, 53)]], 27.0),
    ('0', [[(28, 20), (18, 31), (15, 55), (19, 74), (28, 75), (35, 56), (33, 30), (27, 20)]], 22.0),
    ('1', [[(19, 30), (26, 19), (25, 49), (24, 76)]], 17.0),
    ('2', [[(17, 29), (26, 19), (33, 24), (30, 39), (15, 74), (25, 72), (37, 67)]], 22.5),
    ('3', [[(17, 25), (27, 19), (33, 27), (28, 44), (21, 47), (31, 50), (35, 63), (27, 75), (16, 73)]], 22.0),
    ('4', [[(27, 21), (14, 56), (37, 53)], [(30, 19), (29, 77)]], 23.0),
    ('5', [[(20, 21), (18, 45), (28, 44), (35, 55), (29, 73), (17, 75)], [(20, 21), (35, 19)]], 22.0),
    ('6', [[(31, 18), (21, 43), (17, 65), (21, 75), (31, 71), (33, 59), (24, 55), (18, 62)]], 21.5),
    ('7', [[(15, 24), (35, 20), (25, 49), (18, 77)]], 21.5),
    ('8', [[(28, 20), (19, 24), (18, 36), (32, 53), (33, 68), (24, 76), (16, 68), (21, 52), (32, 34), (28, 20)]], 22.0),
    ('9', [[(33, 28), (25, 19), (17, 30), (19, 44), (29, 42), (34, 27), (29, 54), (24, 77)]], 22.0),
    ('a', [[(30, 47), (19, 47), (16, 65), (21, 75), (30, 63), (31, 47), (31, 75)]], 20.5),
    ('b', [[(18, 12), (16, 74), (23, 49), (32, 54), (30, 71), (19, 75)]], 20.5),
    ('c', [[(31, 48), (20, 46), (16, 62), (21, 75), (32, 70)]], 18.5),
    ('d', [[(30, 47), (19, 50), (16, 66), (22, 75), (31, 63), (33, 12), (31, 75)]], 21.0),
    ('e', [[(17, 61), (31, 54), (29, 45), (19, 48), (16, 66), (23, 75), (33, 69)]], 19.0),
    ('f', [[(31, 16), (23, 15), (20, 46), (19, 77)], [(14, 46), (30, 44)]], 17.5),
    ('g', [[(31, 47), (19, 49), (16, 65), (23, 73), (31, 61), (32, 47), (30, 86), (21, 96), (15, 91)]], 21.0),
    ('h', [[(18, 12), (16, 75), (19, 55), (27, 46), (32, 53), (31, 75)]], 21.0),
    ('i', [[(22, 46), (21, 75)], [(23, 31), (22, 36)]], 14.0),
    ('j', [[(25, 46), (23, 86), (16, 96), (13, 91)], [(26, 31), (25, 36)]], 15.5),
    ('k', [[(18, 12), (16, 76)], [(33, 44), (18, 61), (33, 75)]], 20.5),
    ('l', [[(22, 12), (20, 72), (25, 75)]], 14.5),
    ('m', [[(14, 46), (13, 75), (16, 54), (23, 46), (25, 74), (28, 53), (35, 46), (36, 75)]], 27.5),
    ('n', [[(17, 45), (16, 75), (18, 55), (26, 45), (32, 51), (31, 75), (36, 71)]], 21.0),
    ('o', [[(26, 45), (17, 54), (17, 71), (25, 75), (33, 64), (31, 48), (25, 45)]], 19.5),
    ('p', [[(18, 46), (16, 96)], [(18, 53), (26, 46), (33, 55), (29, 71), (18, 73)]], 20.5),
    ('q', [[(31, 47), (19, 49), (16, 65), (23, 73), (31, 62), (32, 47), (30, 96)]], 21.0),
    ('r', [[(18, 46), (17, 75), (20, 55), (27, 45), (32, 48)]], 17.5),
    ('s', [[(31, 48), (21, 46), (17, 54), (31, 64), (28, 74), (16, 73)]], 19.0),
    ('t', [[(23, 22), (21, 72), (28, 74)], [(15, 45), (32, 43)]], 18.0),
    ('u', [[(17, 45), (16, 69), (22, 75), (31, 64), (32, 45), (31, 75), (36, 71)]], 21.0),
    ('v', [[(16, 45), (22, 75), (33, 44)]], 19.0),
    ('w', [[(14, 45), (17, 74), (23, 52), (28, 74), (36, 45)]], 23.5),
    ('x', [[(16, 46), (32, 75)], [(32, 45), (16, 75)]], 19.5),
    ('y', [[(16, 46), (22, 73), (32, 46), (26, 86), (17, 96), (13, 92)]], 20.0),
    ('z', [[(16, 47), (32, 46), (16, 74), (33, 73)]], 19.5),
    ('.', [[(21, 69), (20, 76)]], 11.5),
    ('-', [[(15, 56), (33, 53)]], 19.0),
    ('+', [[(24, 40), (23, 70)], [(14, 56), (33, 54)]], 20.5),
]


def main():
    assert len(RAW_BUILD_GLYPHS) == BUILD_COLS * BUILD_ROWS
    GUI.mkdir(parents=True, exist_ok=True)

    main_strokes = [build_stroke(*spec) for spec in MAIN_STROKES_SPEC]
    sub_strokes = [build_stroke(*spec) for spec in SUB_STROKES_SPEC]

    main_tex, main_arc = bake_dual_layer_field(STAGE_W, STAGE_H, main_strokes)
    sub_tex, sub_arc = bake_dual_layer_field(STAGE_W, STAGE_H, sub_strokes)

    Image.fromarray(main_tex, "RGBA").save(GUI / "hello_main_ink.png", optimize=True)
    Image.fromarray(sub_tex, "RGBA").save(GUI / "hello_subtitle_ink.png", optimize=True)

    grad = hello_gradient_rgb(STAGE_W, STAGE_H)
    Image.fromarray(eval_ink_plate(main_tex, main_arc, 1.0, r_nom=2.60, aa=0.0, rgb=grad), "RGBA").save(
        GUI / "hello_main.png", optimize=True
    )
    Image.fromarray(eval_ink_plate(main_tex, main_arc, 1.0, r_nom=2.60, aa=1.15, rgb=grad), "RGBA").save(
        GUI / "hello_main_smooth.png", optimize=True
    )
    Image.fromarray(eval_ink_plate(sub_tex, sub_arc, 1.0, r_nom=2.50, aa=0.0, rgb=grad), "RGBA").save(
        GUI / "hello_subtitle.png", optimize=True
    )
    Image.fromarray(eval_ink_plate(sub_tex, sub_arc, 1.0, r_nom=2.50, aa=1.15, rgb=grad), "RGBA").save(
        GUI / "hello_subtitle_smooth.png", optimize=True
    )

    sheet_w = CELL_W * BUILD_COLS
    sheet_h = CELL_H * BUILD_ROWS
    build_tex = np.zeros((sheet_h * 2, sheet_w, 4), dtype=np.uint8)
    build_tex[:, :, 0] = 255
    build_tex[:, :, 3] = 255
    glyph_meta = []
    for idx, (ch, raw_list, adv) in enumerate(RAW_BUILD_GLYPHS):
        col, row = idx % BUILD_COLS, idx // BUILD_COLS
        g_strokes = [
            build_stroke(slant_narrow(rp), base_r=BASE_R_BUILD, taper_start=0.48, taper_end=0.42, taper_px=6.5)
            for rp in raw_list
        ]
        cell_tex, g_arc = bake_dual_layer_field(CELL_W, CELL_H, g_strokes, pen_lift_px=5.0, border_pad=2)
        x0, y0 = col * CELL_W, row * CELL_H
        build_tex[y0:y0 + CELL_H, x0:x0 + CELL_W] = cell_tex[0:CELL_H]
        build_tex[sheet_h + y0:sheet_h + y0 + CELL_H, x0:x0 + CELL_W] = cell_tex[CELL_H:2 * CELL_H]
        glyph_meta.append((ch, col, row, adv, int(round(g_arc))))

    Image.fromarray(build_tex, "RGBA").save(GUI / "hello_build_ink.png", optimize=True)

    print(f"MAIN_ARC_PX = {int(round(main_arc))}")
    print(f"SUB_ARC_PX  = {int(round(sub_arc))}")
    print("BUILD_GLYPHS:")
    for ch, col, row, adv, garc in glyph_meta:
        print(f"  '{ch}': col={col}, row={row}, adv={adv}f, arc={garc}")


if __name__ == "__main__":
    main()
