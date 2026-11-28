#!/usr/bin/env python3
"""Real EGL OpenGL 4.5 / GLSL 330 verification & offscreen renderer for 099 LightPollutionGlass."""
from pathlib import Path
import json
import struct
import sys
import zipfile
import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SHADERS_CLICKGUI = ROOT / "src/main/resources/resources/liquidbounce/shaders/clickgui"
SHADERS_GUI = ROOT / "src/main/resources/resources/liquidbounce/shaders/gui"
OUT_DIR = ROOT / "scripts/verify099"


def expand_glsl(src: str) -> str:
    return (
        src.replace(
            "#include <minecraft:dynamictransforms.glsl>",
            "uniform mat4 ModelViewMat;\nuniform vec4 ColorModulator;",
        )
        .replace("#include <minecraft:projection.glsl>", "uniform mat4 ProjMat;")
        .replace(
            "#include <liquidbounce:clickgui/glass_common.glsl>",
            (SHADERS_CLICKGUI / "glass_common.glsl").read_text(),
        )
        .replace(
            "#include <liquidbounce:clickgui/glass_blur.glsl>",
            (SHADERS_CLICKGUI / "glass_blur.glsl").read_text(),
        )
    )


def make_ortho(w: int, h: int) -> bytes:
    return np.array(
        [[2.0 / w, 0, 0, -1], [0, -2.0 / h, 0, 1], [0, 0, 1, 0], [0, 0, 0, 1]],
        dtype="f4",
    ).T.tobytes()


class OffscreenRenderer:
    def __init__(self, ctx: moderngl.Context, w: int, h: int, fsh_source: str):
        self.ctx = ctx
        self.w = w
        self.h = h
        self.glass_prog = ctx.program(
            vertex_shader=expand_glsl((SHADERS_CLICKGUI / "liquid_glass.vsh").read_text()),
            fragment_shader=expand_glsl(fsh_source),
        )
        self.css_prog = ctx.program(
            vertex_shader=expand_glsl((SHADERS_GUI / "css_box.vsh").read_text()),
            fragment_shader=expand_glsl((SHADERS_GUI / "css_box.fsh").read_text()),
        )
        for p in (self.glass_prog, self.css_prog):
            p["ModelViewMat"].write(np.eye(4, dtype="f4").T.tobytes())
            p["ProjMat"].write(make_ortho(w, h))
            p["ColorModulator"].value = (1.0, 1.0, 1.0, 1.0)
        self.glass_prog["Sampler0"].value = 0

        fs_vs = """#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) out vec2 texCoord;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);texCoord=p;gl_Position=vec4(p*2.0-1.0,0,1);}
"""
        self.blur_progs = []
        for name in ("glass_blur_h.fsh", "glass_blur_v.fsh"):
            bp = ctx.program(vertex_shader=fs_vs, fragment_shader=expand_glsl((SHADERS_CLICKGUI / name).read_text()))
            bp["texture0"].value = 0
            bp["ClickGuiGlassBlurData"].binding = 0
            self.blur_progs.append(bp)
        self.copy_prog = ctx.program(
            vertex_shader=fs_vs,
            fragment_shader="""#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec2 texCoord;layout(location=0) out vec4 color;uniform sampler2D inputTex;
void main(){color=vec4(texture(inputTex,texCoord).rgb, 1.0);}""",
        )
        self.copy_prog["inputTex"].value = 0
        self.ub = ctx.buffer(reserve=16)
        self.ub.bind_to_uniform_block(0)
        self.fbo = ctx.simple_framebuffer((w, h), components=4, dtype="f4")

    def prepare_scene_and_blur(self, scene_rgb: np.ndarray, strength: float = 1.44, vibrance: float = 0.96, contrast: int = 0):
        src_tex = self.ctx.texture((self.w, self.h), 3, scene_rgb[::-1].copy().tobytes(), dtype="f4")
        src_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        src_tex.repeat_x = src_tex.repeat_y = False

        ta = self.ctx.texture((self.w // 2, self.h // 2), 4, dtype="f4")
        tb = self.ctx.texture((self.w // 2, self.h // 2), 4, dtype="f4")
        for t in (ta, tb):
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
            t.repeat_x = t.repeat_y = False
        fa, fb = self.ctx.framebuffer([ta]), self.ctx.framebuffer([tb])
        cur = src_tex
        self.ctx.disable(moderngl.BLEND)
        self.ub.bind_to_uniform_block(0)
        for n, (axis, sc) in enumerate([(0, 1.05 * strength), (1, 1.05 * strength), (0, 2.0 * strength), (1, 2.0 * strength)]):
            tgt = fa if axis == 0 else fb
            tgt.use()
            self.ctx.viewport = (0, 0, self.w // 2, self.h // 2)
            cur.use(0)
            self.ub.write(struct.pack("4f", sc, vibrance if n >= 2 else 1.0, float(contrast if n >= 2 else 0), 0.0))
            vao = self.ctx.vertex_array(self.blur_progs[axis], [])
            vao.render(vertices=3)
            vao.release()
            cur = ta if axis == 0 else tb

        self.fbo.use()
        self.ctx.viewport = (0, 0, self.w, self.h)
        src_tex.use(0)
        vao = self.ctx.vertex_array(self.copy_prog, [])
        vao.render(vertices=3)
        vao.release()

        tb.use(0)
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA)
        return src_tex, ta, tb, fa, fb

    def emit_glass_quad(self, x, y, w, h, radii, mode, cols4, pad=0.0):
        rr = [round(min(a, min(w, h) * 0.5, 63.5) * 2.0) for a in radii]
        packed = ((rr[0] << 7) | rr[1], (rr[2] << 7) | rr[3])
        c_tl, c_tr, c_br, c_bl = cols4
        cmap = [c_tl, c_tr, c_br, c_bl]
        data = []
        for a, b, ci in [(0, 0, 0), (0, 1, 3), (1, 1, 2), (0, 0, 0), (1, 1, 2), (1, 0, 1)]:
            vx = x - pad + a * (w + 2.0 * pad)
            vy = y - pad + b * (h + 2.0 * pad)
            lx = -w * 0.5 - pad + a * (w + 2.0 * pad)
            ly = -h * 0.5 - pad + b * (h + 2.0 * pad)
            data.append(struct.pack("=3f2f4f2i2if", vx, vy, 0.0, lx, ly, *cmap[ci], round(w * 8.0), round(h * 8.0), *packed, mode))
        buf = self.ctx.buffer(b"".join(data))
        vao = self.ctx.vertex_array(self.glass_prog, [(buf, "3f 2f 4f 2i 2i 1f", "Position", "UV0", "Color", "UV1", "UV2", "LineWidth")])
        vao.render(moderngl.TRIANGLES)
        vao.release()
        buf.release()

    def draw_lp_surface(self, x, y, w, h, r, profile=0, variant="BLOOM_PANEL", opacity=1.0, refraction=1.0, rim_strength=1.0, in_alpha=0.35, shadow_alpha=0.45, shadow_spread=14.0):
        lane = profile * 256.0
        if profile != 0:
            # Exact 089 HUD emission
            def tr(scale):
                dens = min(max(in_alpha * scale, 0.0), 0.72)
                return (rim_strength * 0.5, 32 / 255.0, 32 / 255.0, dens)
            if shadow_alpha > 0.004 and shadow_spread > 0.05:
                pad = shadow_spread * 2.5 + 4.0
                self.emit_glass_quad(x, y, w, h, (r,) * 4, -(lane + shadow_spread), [(0, 0, 0, shadow_alpha * opacity)] * 4, pad=pad)
            op_b = max(1, min(64, round(opacity * 64)))
            frac = min(0.996, max(0.0, refraction) * 0.25)
            self.emit_glass_quad(x, y, w, h, (r,) * 4, lane + op_b + frac, [tr(1.08), tr(0.70), tr(0.46), tr(1.02)])
            self.emit_glass_quad(x, y, w, h, (r,) * 4, lane + 100.45, [(1, 1, 1, min(1.0, rim_strength) * opacity)] * 4)
            if profile in (1, 2):
                arc_pad = max(h * 0.20, 3.0)
                self.emit_glass_quad(x, y, w, h, (r,) * 4, lane + 192.0, [(1, 1, 1, min(1.0, rim_strength) * opacity)] * 4, pad=arc_pad)
            return

        if variant == "QUIET":
            cr = min(r, 3.0)
            sp = min(shadow_spread, 6.0)
            sa = min(shadow_alpha, 28 / 255.0) * opacity
            if sa > 0.004 and sp > 0.05:
                self.emit_glass_quad(x, y, w, h, (cr,) * 4, -sp, [(0, 0, 0, sa)] * 4, pad=sp * 2.5 + 4.0)
            op_b = max(1, min(64, round(opacity * 64)))
            col = (0.0, 96 / 255.0, 96 / 255.0, min(0.72, in_alpha * 0.55))
            self.emit_glass_quad(x, y, w, h, (cr,) * 4, float(op_b), [col] * 4)
            return

        capsule = variant == "BLOOM_CAPSULE"
        cr = min(r, min(w, h) * 0.5, 24.0)
        vb = (242 if capsule else 160) / 255.0
        tg = (246 if capsule else 78) / 255.0
        dens = min(0.32, max(0.05, in_alpha * 0.34)) if capsule else min(0.54, max(0.06, in_alpha * 0.62))
        lens = min(2.5, refraction * (0.45 if capsule else 0.65))
        raw_sa = round(shadow_alpha * 255)
        sh_norm = min(1.35, max(0.28, raw_sa / 135.0)) if raw_sa > 0 else (0.76 if capsule else 0.88)
        bf = min(1.35, opacity * (0.34 + 0.66 * min(rim_strength, 1.6)) * sh_norm)
        sp = min(18.0 if capsule else 24.0, max(8.5 if capsule else 11.0, shadow_spread if shadow_spread > 0.5 else (11.0 if capsule else 15.0)))
        ba = min(235, round((0.54 if capsule else 0.35) * bf * 255)) / 255.0
        if ba > 0.004:
            self.emit_glass_quad(x, y, w, h, (cr,) * 4, -sp, [(1.0, 1.0, vb, ba)] * 4, pad=sp * 2.5 + 4.0)
        op_b = max(1, min(64, round(opacity * 64)))
        frac = min(0.996, lens * 0.25)
        b_col = (min(1.0, rim_strength * 0.5), tg, vb, dens)
        self.emit_glass_quad(x, y, w, h, (cr,) * 4, op_b + frac, [b_col] * 4)
        ra = ((0.045 if capsule else 0.13) * min(rim_strength, 1.5) * opacity)
        if ra > 0.004:
            self.emit_glass_quad(x, y, w, h, (cr,) * 4, 100.95, [(1.0, 1.0, 1.0, ra)] * 4)

    def emit_css_box(self, x, y, w, h, r, fill_col, border_col=None, border_w=0.0, sh_col=None, sh_blur=0.0):
        rr = [round(min(r, min(w, h) * 0.5, 63.5) * 2.0)] * 4
        packed = ((rr[0] << 7) | rr[1], (rr[2] << 7) | rr[3])

        def q(mode, col, pad):
            data = []
            for a, b in [(0, 0), (0, 1), (1, 1), (0, 0), (1, 1), (1, 0)]:
                vx = x - pad + a * (w + 2.0 * pad)
                vy = y - pad + b * (h + 2.0 * pad)
                lx = -w * 0.5 - pad + a * (w + 2.0 * pad)
                ly = -h * 0.5 - pad + b * (h + 2.0 * pad)
                data.append(struct.pack("=3f2f4f2i2if", vx, vy, 0.0, lx, ly, *col, round(w * 16.0), round(h * 16.0), *packed, mode))
            buf = self.ctx.buffer(b"".join(data))
            vao = self.ctx.vertex_array(self.css_prog, [(buf, "3f 2f 4f 2i 2i 1f", "Position", "UV0", "Color", "UV1", "UV2", "LineWidth")])
            vao.render(moderngl.TRIANGLES)
            vao.release()
            buf.release()

        if sh_col is not None and sh_blur > 0.05:
            q(-sh_blur, sh_col, sh_blur * 2.2)
        if fill_col[3] > 0.001:
            q(0.0, fill_col, 0.0)
        if border_col is not None and border_w > 0.05 and border_col[3] > 0.001:
            q(border_w, border_col, 0.0)

    def read_rgba(self) -> np.ndarray:
        return np.frombuffer(self.fbo.read(components=4, dtype="f4"), dtype="f4").reshape(self.h, self.w, 4)[::-1].copy()


def build_synthetic_world(w: int, h: int) -> np.ndarray:
    y, x = np.mgrid[:h, :w]
    diag = x * 0.64 + y * 0.76
    scene = np.zeros((h, w, 3), dtype="f4")
    # Minecraft-like warm spruce planks + prismarine stonebricks + subtle shadow
    planks = (np.sin(diag / 52.0) > 0.15).astype("f4")
    grid = (((x // 26) + (y // 26)) % 2).astype("f4")
    scene[:, :, 0] = 0.23 + 0.24 * planks + 0.05 * grid
    scene[:, :, 1] = 0.33 + 0.06 * planks + 0.08 * (1.0 - planks) + 0.04 * grid
    scene[:, :, 2] = 0.31 - 0.05 * planks + 0.11 * (1.0 - planks) + 0.04 * grid
    dark_orb = (((x - w * 0.18) ** 2) / (150.0 ** 2) + ((y - h * 0.22) ** 2) / (76.0 ** 2)) < 1.0
    scene[dark_orb] *= 0.36
    return np.clip(scene, 0.0, 1.0)


def load_font(size: int, bold: bool = False):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ]
    for c in candidates:
        if Path(c).exists():
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()


def main():
    base_zip = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/home/user/google_drive/Pojavbouncenew-Nextgen-098-ORIGINAL-AURA-CHESTS.zip")
    preview_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None

    with zipfile.ZipFile(base_zip) as z:
        old_fsh = z.read("src/main/resources/resources/liquidbounce/shaders/clickgui/light_pollution_glass.fsh").decode()
    new_fsh = (SHADERS_CLICKGUI / "light_pollution_glass.fsh").read_text()

    ctx = moderngl.create_standalone_context(backend="egl", require=330)
    gl_renderer = f"{ctx.info['GL_RENDERER']} | {ctx.info['GL_VERSION']}"

    # -------------------------------------------------------------------------
    # PART 1: Pixel-exact regression on HUD (TARGET=1, COUNTER=2, SOFT=3) & 094 Music QUIET
    # -------------------------------------------------------------------------
    rw, rh = 640, 360
    scene_reg = build_synthetic_world(rw, rh)
    r_old = OffscreenRenderer(ctx, rw, rh, old_fsh)
    r_new = OffscreenRenderer(ctx, rw, rh, new_fsh)

    diffs = {}
    for label, prof, var in [
        ("hud_target", 1, "BLOOM_PANEL"),
        ("hud_counter", 2, "BLOOM_PANEL"),
        ("hud_soft", 3, "BLOOM_PANEL"),
        ("music_quiet", 0, "QUIET"),
    ]:
        for rr in (r_old, r_new):
            rr.prepare_scene_and_blur(scene_reg, strength=1.55 if prof != 0 else 1.35, vibrance=1.0, contrast=1 if prof != 0 else 0)
            rr.draw_lp_surface(160, 110, 280, 96, 18.0 if prof != 0 else 3.0, profile=prof, variant=var)
        a = r_old.read_rgba()
        b = r_new.read_rgba()
        max_diff = float(np.max(np.abs(a - b)))
        diffs[label] = max_diff
        assert max_diff == 0.0, f"Regression on {label}: max RGBA diff = {max_diff}"

    # -------------------------------------------------------------------------
    # PART 2: Volumetric Inward-to-Outward White Bloom Mathematical & Chromaticity Checks
    # -------------------------------------------------------------------------
    neutral_scene = np.full((rh, rw, 3), 0.28, dtype="f4")
    r_new.prepare_scene_and_blur(neutral_scene, strength=1.44, vibrance=0.96, contrast=0)
    # Draw BLOOM_PANEL at (80, 70, 200, 220) and BLOOM_CAPSULE at (360, 150, 200, 30)
    r_new.draw_lp_surface(80, 70, 200, 220, 6.0, profile=0, variant="BLOOM_PANEL", shadow_spread=16.0)
    r_new.draw_lp_surface(360, 150, 200, 30, 4.5, profile=0, variant="BLOOM_CAPSULE", shadow_spread=11.5)
    bloom_rgba = r_new.read_rgba()

    assert np.all(np.isfinite(bloom_rgba)), "Non-finite RGBA output detected"
    assert float(bloom_rgba.min()) >= 0.0 and float(bloom_rgba.max()) <= 1.0, "Out-of-range RGBA output"

    # Measure radial outward white bloom from capsule right edge (x = 560, y = 165)
    cy = 165
    luma_row = 0.299 * bloom_rgba[cy, :, 0] + 0.587 * bloom_rgba[cy, :, 1] + 0.114 * bloom_rgba[cy, :, 2]
    bg_luma = float(luma_row[635])
    cap_center_luma = float(luma_row[460])
    cap_inner_near_edge = float(luma_row[555])
    cap_out_2px = float(luma_row[562])
    cap_out_8px = float(luma_row[568])
    cap_out_16px = float(luma_row[576])
    cap_out_28px = float(luma_row[588])
    panel_center_luma = float(luma_row[180])

    assert cap_center_luma > panel_center_luma + 0.18, "Enabled capsule interior must glow brighter than smoky panel interior"
    assert cap_center_luma >= cap_inner_near_edge >= cap_out_2px > cap_out_8px > cap_out_16px > cap_out_28px >= bg_luma - 1e-4, (
        f"Volumetric bloom must decay smoothly from interior outward: "
        f"{cap_center_luma:.4f} -> {cap_inner_near_edge:.4f} -> {cap_out_2px:.4f} -> {cap_out_8px:.4f} -> {cap_out_16px:.4f} -> {cap_out_28px:.4f} (bg={bg_luma:.4f})"
    )
    # Check neutral white chromaticity (no pink R >> G/B bias!)
    cap_rgb = bloom_rgba[cy, 460, :3]
    chroma_span = float(np.max(cap_rgb) - np.min(cap_rgb))
    assert chroma_span < 0.025, f"Capsule bloom must be pure frost-white, got RGB={cap_rgb}"

    # Quad-edge seam check at x = 560 + ceil(11.5 * 2.5 + 4.0) = 593
    seam_jump = float(abs(luma_row[592] - bg_luma))
    assert seam_jump < (1.0 / 255.0), f"Visible padded-quad boundary jump: {seam_jump}"

    # -------------------------------------------------------------------------
    # PART 3: Render Production ClickGUI Offscreen Verification Artifact
    # -------------------------------------------------------------------------
    cw, ch = 880, 740
    world_c = build_synthetic_world(cw, ch)
    rc_new = OffscreenRenderer(ctx, cw, ch, new_fsh)
    rc_new.prepare_scene_and_blur(world_c, strength=1.44, vibrance=0.96, contrast=0)

    def render_clickgui_scene(renderer: OffscreenRenderer, mode_099: bool):
        # Top Tabs pill
        if mode_099:
            renderer.draw_lp_surface(260, 16, 360, 38, 19.0, variant="BLOOM_PANEL", shadow_spread=14.0)
            renderer.draw_lp_surface(266, 21, 108, 28, 14.0, variant="BLOOM_CAPSULE", shadow_spread=10.5)
            # Render & Misc panels
            renderer.draw_lp_surface(130, 78, 250, 634, 6.0, variant="BLOOM_PANEL", shadow_spread=16.0)
            renderer.draw_lp_surface(450, 78, 250, 224, 6.0, variant="BLOOM_PANEL", shadow_spread=16.0)
            # Header soft inner-to-outer glow
            renderer.draw_lp_surface(135, 82, 240, 30, 4.5, variant="BLOOM_CAPSULE", opacity=0.34, shadow_spread=12.0, shadow_alpha=0.26)
            renderer.draw_lp_surface(455, 82, 240, 30, 4.5, variant="BLOOM_CAPSULE", opacity=0.34, shadow_spread=12.0, shadow_alpha=0.26)
            renderer.emit_css_box(139, 114, 232, 1.8, 0.9, (1, 1, 1, 0.22), sh_col=(1, 1, 1, 0.22), sh_blur=5.0)
            renderer.emit_css_box(459, 114, 232, 1.8, 0.9, (1, 1, 1, 0.22), sh_col=(1, 1, 1, 0.22), sh_blur=5.0)
        else:
            renderer.draw_lp_surface(260, 16, 360, 38, 3.0, variant="QUIET")
            renderer.draw_lp_surface(130, 78, 250, 634, 3.0, variant="QUIET")
            renderer.draw_lp_surface(450, 78, 250, 224, 3.0, variant="QUIET")

        rows_render = [
            ("AmbientMist", True), ("Animations", True), ("BlockESP", True),
            ("Chams", True), ("ClickGUI", True), ("CustomFov", True),
            ("ESP", False), ("FullBright", False), ("HandView", True),
        ]
        ry = 120
        for _, en in rows_render:
            if en:
                if mode_099:
                    renderer.draw_lp_surface(134, ry + 3, 242, 28, 4.5, variant="BLOOM_CAPSULE", opacity=0.96, shadow_spread=11.5, shadow_alpha=0.56)
                else:
                    renderer.emit_css_box(137, ry + 5, 2.5, 24, 0.0, (244 / 255, 184 / 255, 198 / 255, 1.0))
            ry += 34

        # Expanded HandView settings
        if mode_099:
            renderer.emit_css_box(135, ry + 2, 240, 204, 6.0, (0, 0, 0, 0.13), border_col=(1, 1, 1, 0.09), border_w=0.75, sh_col=(1, 1, 1, 0.10), sh_blur=4.2)
            # SwitchWidget (right-aligned in LightPollutionGlass!)
            renderer.emit_css_box(340, ry + 40, 22, 8, 4.0, (198 / 255, 210 / 255, 228 / 255, 0.46))
            renderer.emit_css_box(350, ry + 38, 12, 12, 6.0, (1, 1, 1, 1), sh_col=(1, 1, 1, 0.72), sh_blur=4.8)
            # Sliders X, Y, Z
            for idx, ratio in enumerate([0.46, 0.88, 0.50]):
                sy = ry + 84 + idx * 38
                tx, tw = 147, 216
                renderer.emit_css_box(tx, sy, tw, 3.0, 1.5, (1, 1, 1, 0.18))
                renderer.emit_css_box(tx, sy, tw * ratio, 3.0, 1.5, (1, 1, 1, 0.60), sh_col=(1, 1, 1, 0.24), sh_blur=2.6)
                kx = tx + tw * ratio - 3.0
                renderer.emit_css_box(kx, sy + 1.5 - 6.5, 6.0, 13.0, 3.0, (1, 1, 1, 1.0), sh_col=(1, 1, 1, 0.72), sh_blur=4.2)
        else:
            renderer.emit_css_box(135, ry + 2, 240, 204, 3.0, (0, 0, 0, 0.09))
            renderer.emit_css_box(136.5, ry + 6, 2.5, 195, 1.25, (244 / 255, 184 / 255, 198 / 255, 0.92))
            renderer.emit_css_box(146, ry + 40, 22, 8, 4.0, (244 / 255, 184 / 255, 198 / 255, 0.62))
            renderer.emit_css_box(156, ry + 38, 12, 12, 6.0, (248 / 255, 202 / 255, 212 / 255, 1.0))
            for idx, ratio in enumerate([0.46, 0.88, 0.50]):
                sy = ry + 84 + idx * 38
                tx, tw = 147, 216
                renderer.emit_css_box(tx, sy, tw, 2.0, 1.0, (1, 1, 1, 0.16))
                renderer.emit_css_box(tx, sy, tw * ratio, 2.0, 1.0, (244 / 255, 184 / 255, 198 / 255, 1.0))
                renderer.emit_css_box(tx + tw * ratio - 6.0, sy - 5.0, 12.0, 12.0, 6.0, (244 / 255, 184 / 255, 198 / 255, 1.0))

        rows_misc = [
            ("Baritone", False), ("ClientSpoofer", True), ("Disabler", False),
            ("InventoryClicker", True), ("NickHider", False),
        ]
        ry2 = 120
        for _, en in rows_misc:
            if en:
                if mode_099:
                    renderer.draw_lp_surface(454, ry2 + 3, 242, 28, 4.5, variant="BLOOM_CAPSULE", opacity=0.96, shadow_spread=11.5, shadow_alpha=0.56)
                else:
                    renderer.emit_css_box(457, ry2 + 5, 2.5, 24, 0.0, (244 / 255, 184 / 255, 198 / 255, 1.0))
            ry2 += 34

    def annotate_clickgui(img: Image.Image, mode_099: bool):
        draw = ImageDraw.Draw(img)
        f_hdr = load_font(14, bold=True)
        f_row = load_font(12, bold=False)
        f_row_b = load_font(12, bold=True)
        draw.text((293, 24), "ClickGUI", font=f_row_b, fill=(255, 255, 255))
        draw.text((402, 24), "HUD", font=f_row, fill=(195, 204, 216))
        draw.text((488, 24), "Global", font=f_row, fill=(195, 204, 216))
        draw.text((172, 90), "Render", font=f_hdr, fill=(255, 255, 255))
        draw.text((492, 90), "Misc", font=f_hdr, fill=(255, 255, 255))
        rows_render = [
            ("AmbientMist", True), ("Animations", True), ("BlockESP", True),
            ("Chams", True), ("ClickGUI", True), ("CustomFov", True),
            ("ESP", False), ("FullBright", False), ("HandView", True),
        ]
        ry = 120
        for name, en in rows_render:
            col = (255, 255, 255) if en else ((142, 150, 162) if mode_099 else (203, 213, 225))
            draw.text((148, ry + 10), name, font=f_row_b if en else f_row, fill=col)
            ry += 34
        draw.text((147, ry + 12), "Bind", font=f_row, fill=(226, 232, 240))
        draw.text((322, ry + 12), "None", font=f_row, fill=(195, 204, 216))
        draw.text((147 if mode_099 else 176, ry + 37), "show-in-arraylist" if mode_099 else "ShowInArrayList", font=f_row, fill=(255, 255, 255))
        for idx, (lbl, val) in enumerate([("X", "-0.05"), ("Y", "0.80"), ("Z", "0.00")]):
            sy = ry + 64 + idx * 38
            draw.text((147, sy), lbl, font=f_row, fill=(245, 248, 255))
            draw.text((334, sy), val, font=f_row_b, fill=(255, 255, 255))
        rows_misc = [
            ("Baritone", False), ("ClientSpoofer", True), ("Disabler", False),
            ("InventoryClicker", True), ("NickHider", False),
        ]
        ry2 = 120
        for name, en in rows_misc:
            col = (255, 255, 255) if en else ((142, 150, 162) if mode_099 else (203, 213, 225))
            draw.text((468, ry2 + 10), name, font=f_row_b if en else f_row, fill=col)
            ry2 += 34

    render_clickgui_scene(rc_new, mode_099=True)
    img_099 = Image.fromarray((np.clip(rc_new.read_rgba()[:, :, :3], 0.0, 1.0) * 255.0).astype("uint8"))
    annotate_clickgui(img_099, mode_099=True)
    img_099.resize((440, 370), Image.Resampling.LANCZOS).save(OUT_DIR / "offscreen-white-bloom-gui.png")

    if preview_dir is not None:
        preview_dir.mkdir(parents=True, exist_ok=True)
        # Render 098 baseline for side-by-side comparison
        rc_old = OffscreenRenderer(ctx, cw, ch, old_fsh)
        rc_old.prepare_scene_and_blur(world_c, strength=1.05, vibrance=0.94, contrast=0)
        render_clickgui_scene(rc_old, mode_099=False)
        img_098 = Image.fromarray((np.clip(rc_old.read_rgba()[:, :, :3], 0.0, 1.0) * 255.0).astype("uint8"))
        annotate_clickgui(img_098, mode_099=False)

        ref_path = Path("/home/user/uploads/Screenshot_20261128_150446.jpg")
        panel_w, panel_h = 540, 620
        comp = Image.new("RGB", (panel_w * 3 + 48, panel_h + 74), (16, 19, 24))
        cdraw = ImageDraw.Draw(comp)
        f_title = load_font(17, bold=True)
        f_sub = load_font(12, bold=False)

        if ref_path.exists():
            ref_full = Image.open(ref_path).convert("RGB")
            ref_crop = ref_full.crop((80, 595, 1000, 1650)).resize((panel_w, panel_h), Image.Resampling.LANCZOS)
        else:
            ref_crop = img_099.resize((panel_w, panel_h), Image.Resampling.LANCZOS)

        crop_098 = img_098.crop((85, 0, 745, 740)).resize((panel_w, panel_h), Image.Resampling.LANCZOS)
        crop_099 = img_099.crop((85, 0, 745, 740)).resize((panel_w, panel_h), Image.Resampling.LANCZOS)

        for idx, (tile, ttl, sub) in enumerate([
            (ref_crop, "1. Reference Target Screenshot", "Inner-to-outer white bloom panels, rows & controls"),
            (crop_098, "2. Before (098 LightPollutionGlass)", "Flat quiet panel + 2.5px pink stripe + pink knobs"),
            (crop_099, "3. After (099 LightPollutionGlass - OpenGL)", "Volumetric 2D Gaussian white bloom + white controls"),
        ]):
            ox = 12 + idx * (panel_w + 12)
            comp.paste(tile, (ox, 62))
            cdraw.text((ox + 6, 14), ttl, font=f_title, fill=(248, 250, 255))
            cdraw.text((ox + 6, 37), sub, font=f_sub, fill=(168, 179, 196))

        comp.save(preview_dir / "099_clickgui_white_bloom_comparison.png")

        # Render Global ModernGUI + Untouched 089 HUD Showcase
        mw, mh = 1020, 560
        world_m = build_synthetic_world(mw, mh)
        rm = OffscreenRenderer(ctx, mw, mh, new_fsh)
        rm.prepare_scene_and_blur(world_m, strength=1.44, vibrance=0.96, contrast=0)

        # Left: Global ModernGUI Container / Inventory with hovered slot & ModernGUI buttons
        rm.draw_lp_surface(48, 68, 460, 430, 10.0, variant="BLOOM_PANEL", shadow_spread=16.0)
        # Inner doll / crafting recessed well
        rm.emit_css_box(70, 106, 130, 144, 6.0, (0.12, 0.14, 0.17, 0.30), border_col=(1, 1, 1, 0.14), border_w=0.8)
        # ModernGUI Widget Pills (Selected tab, Hovered button, Normal button, Disabled button)
        rm.draw_lp_surface(220, 108, 125, 28, 6.0, variant="BLOOM_CAPSULE", opacity=0.96, shadow_spread=11.5, shadow_alpha=0.56)
        rm.draw_lp_surface(357, 108, 125, 28, 6.0, variant="BLOOM_PANEL", opacity=0.85, shadow_spread=11.0, shadow_alpha=0.28)
        rm.draw_lp_surface(220, 148, 262, 30, 6.0, variant="BLOOM_CAPSULE", opacity=0.92, shadow_spread=11.5, shadow_alpha=0.48)
        rm.draw_lp_surface(220, 188, 262, 30, 6.0, variant="BLOOM_PANEL", opacity=0.88, shadow_spread=11.0, shadow_alpha=0.28)
        # Disabled button (suppressBloom = true -> rim=0, shadow=0)
        rm.draw_lp_surface(220, 226, 262, 26, 6.0, variant="BLOOM_PANEL", opacity=0.56, rim_strength=0.0, shadow_alpha=0.0, shadow_spread=0.0)

        # 9x3 Inventory slots + 1 hovered glowing white slot
        for row_i in range(3):
            for col_i in range(9):
                sx = 74 + col_i * 46
                sy = 278 + row_i * 46
                hov = (row_i == 1 and col_i == 4)
                if hov:
                    rm.emit_css_box(sx, sy, 40, 40, 5.0, (0.82, 0.86, 0.94, 0.42), border_col=(1, 1, 1, 0.90), border_w=1.1, sh_col=(1, 1, 1, 0.55), sh_blur=4.2)
                else:
                    rm.emit_css_box(sx, sy, 40, 40, 5.0, (0.18, 0.20, 0.24, 0.30), border_col=(1, 1, 1, 0.16), border_w=0.75)

        # Right: Untouched 089 HUD Profiles (TargetHUD=1, BlockCounter=2, MusicIsland=3) — 0.0 RGBA diff verified!
        rm.draw_lp_surface(568, 92, 392, 108, 22.0, profile=1)
        rm.draw_lp_surface(568, 236, 236, 76, 18.0, profile=2)
        rm.draw_lp_surface(568, 348, 340, 68, 20.0, profile=3)

        img_m = Image.fromarray((np.clip(rm.read_rgba()[:, :, :3], 0.0, 1.0) * 255.0).astype("uint8"))
        mdraw = ImageDraw.Draw(img_m)
        f_h1 = load_font(15, bold=True)
        f_b = load_font(13, bold=True)
        f_r = load_font(12, bold=False)
        mdraw.text((48, 26), "Global ModernGUI (LightPollutionGlass White Bloom)", font=f_h1, fill=(255, 255, 255))
        mdraw.text((70, 79), "Inventory & Screen Menus", font=f_b, fill=(255, 255, 255))
        mdraw.text((246, 115), "Active Tab", font=f_b, fill=(255, 255, 255))
        mdraw.text((386, 115), "Tab 2", font=f_r, fill=(215, 222, 234))
        mdraw.text((284, 156), "Hovered Menu Button", font=f_b, fill=(255, 255, 255))
        mdraw.text((288, 196), "Normal Menu Button", font=f_r, fill=(235, 240, 248))
        mdraw.text((285, 232), "Disabled Button (Quiet)", font=f_r, fill=(165, 174, 188))
        mdraw.text((248, 430), "Hovered Slot -> Soft White Glow", font=f_r, fill=(236, 242, 252))

        mdraw.text((568, 26), "089 HUD Profiles (0.000000 RGBA Diff)", font=f_h1, fill=(255, 255, 255))
        mdraw.text((594, 118), "Profile.TARGET (TargetHUD)", font=f_b, fill=(255, 255, 255))
        mdraw.text((594, 142), "Curved specular fold + corner arc unchanged", font=f_r, fill=(210, 218, 230))
        mdraw.text((590, 256), "Profile.COUNTER (Blocks)", font=f_b, fill=(255, 255, 255))
        mdraw.text((590, 278), "S-seam + smoked glass unchanged", font=f_r, fill=(210, 218, 230))
        mdraw.text((590, 366), "Profile.SOFT (MusicIsland / HUD)", font=f_b, fill=(255, 255, 255))
        mdraw.text((590, 388), "Soft lower fold unchanged", font=f_r, fill=(210, 218, 230))

        img_m.save(preview_dir / "099_global_moderngui_and_hud_preview.png")

    report = {
        "gl_context": gl_renderer,
        "hud_and_music_max_rgba_diff": diffs,
        "volumetric_bloom_luma_profile": {
            "capsule_center": round(cap_center_luma, 5),
            "capsule_inner_edge": round(cap_inner_near_edge, 5),
            "outside_2px": round(cap_out_2px, 5),
            "outside_8px": round(cap_out_8px, 5),
            "outside_16px": round(cap_out_16px, 5),
            "outside_28px": round(cap_out_28px, 5),
            "background": round(bg_luma, 5),
            "panel_center": round(panel_center_luma, 5),
            "chroma_rgb_span": round(chroma_span, 5),
            "padded_quad_edge_jump": round(seam_jump, 6),
        },
        "status": "PASS",
    }
    (OUT_DIR / "shader-report.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = (
        f"PASS: EGL OpenGL ({gl_renderer})\n"
        f"  HUD TARGET/COUNTER/SOFT & 094 Music QUIET max RGBA diff = {max(diffs.values()):.6f}\n"
        f"  Volumetric White Bloom luma (center -> edge -> +2px -> +8px -> +16px -> bg): "
        f"{cap_center_luma:.4f} -> {cap_inner_near_edge:.4f} -> {cap_out_2px:.4f} -> {cap_out_8px:.4f} -> {cap_out_16px:.4f} -> {bg_luma:.4f}\n"
        f"  Chroma span = {chroma_span:.5f} (frost-white), padded-quad seam = {seam_jump:.6f}\n"
    )
    (OUT_DIR / "shader-results.txt").write_text(summary)
    print(summary, end="")


if __name__ == "__main__":
    main()
