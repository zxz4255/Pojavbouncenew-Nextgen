#!/usr/bin/env python3
"""Checks the single-stroke SDF + 16-bit arc-time HelloScreen calligraphy assets."""
from pathlib import Path
import unittest
import numpy as np
from PIL import Image
from scipy import ndimage

PROJECT = Path(__file__).resolve().parents[3]
ROOT = PROJECT / "src/main/resources/assets/liquidbounce/textures/gui"

LAYERS = (
    ("hello_main", 2812, 2.60),
    ("hello_subtitle", 2889, 2.50),
)


def eval_field(tex, total_arc, progress, r_nom, aa=1.15):
    h = tex.shape[0] // 2
    w = tex.shape[1]
    cov_max = np.zeros((h, w), dtype=np.float32)
    for layer_idx in range(2):
        sub = tex[layer_idx * h:(layer_idx + 1) * h]
        sd = sub[:, :, 0].astype(np.float32) / 16.0 - 4.0
        s = (sub[:, :, 1].astype(np.float32) * 256.0 + sub[:, :, 2].astype(np.float32)) / 65535.0
        d_final = sd.copy()
        if progress < 0.9999:
            ds_px = (s - progress) * total_arc
            ahead = ds_px > 0.0
            radial = np.maximum(sd[ahead] + r_nom, 0.0)
            d_final[ahead] = np.maximum(sd[ahead], np.hypot(radial, ds_px[ahead]) - r_nom)
        t = np.clip((d_final + aa * 0.5) / aa, 0.0, 1.0)
        cov = 1.0 - t * t * (3.0 - 2.0 * t)
        cov_max = np.maximum(cov_max, cov)
    return cov_max


class HelloInkFieldTest(unittest.TestCase):
    def test_monotonic_single_stroke_reveal_and_uniform_width(self):
        for name, arc_px, r_nom in LAYERS:
            with self.subTest(layer=name):
                tex = np.asarray(Image.open(ROOT / f"{name}_ink.png").convert("RGBA"))
                self.assertEqual(tex.shape, (810, 1062, 4))
                prev = np.zeros((405, 1062), dtype=np.float32)
                for step in range(1, 25):
                    cur = eval_field(tex, arc_px, step / 24.0, r_nom)
                    self.assertLessEqual(float((prev - cur).max()), 1e-4, "drawn ink must never disappear")
                    prev = cur
                self.assertGreater(int(np.count_nonzero(prev > 0.5)), 5000)
                self.assertGreater(float(np.mean(prev == 0.0)), 0.85, "background must remain transparent")
                # Ensure 99th-percentile stroke half-width stays sleek and no 7.6px club bulges exist
                edt = ndimage.distance_transform_edt(prev > 0.5)
                nz_edt = edt[edt > 0]
                self.assertLessEqual(float(np.percentile(nz_edt, 99)), 4.35, "stroke must stay uniform without bulges")
                self.assertLessEqual(float(edt.max()), 6.60, "crossing diamonds must not form fat clubs")

    def test_smoothing_preserves_handwriting_silhouette(self):
        for name, _, _ in LAYERS:
            with self.subTest(layer=name):
                original = np.asarray(Image.open(ROOT / f"{name}.png").convert("RGBA"))[:, :, 3] > 128
                smoothed = np.asarray(Image.open(ROOT / f"{name}_smooth.png").convert("RGBA"))[:, :, 3] > 128
                iou = np.count_nonzero(original & smoothed) / np.count_nonzero(original | smoothed)
                self.assertGreater(iou, 0.96)

    def test_handwritten_build_glyph_sheet_covers_all_cells_cleanly(self):
        build_tex = np.asarray(Image.open(ROOT / "hello_build_ink.png").convert("RGBA"))
        self.assertEqual(build_tex.shape, (1080, 512, 4))
        cell_w, cell_h = 64, 108
        for row in range(5):
            for col in range(8):
                x0, y0 = col * cell_w, row * cell_h
                sub = np.vstack([
                    build_tex[y0:y0 + cell_h, x0:x0 + cell_w],
                    build_tex[540 + y0:540 + y0 + cell_h, x0:x0 + cell_w],
                ])
                cov = eval_field(sub, 120.0, 1.0, 1.46)
                self.assertGreater(int(np.count_nonzero(cov > 0.4)), 20, f"empty glyph at ({col}, {row})")
                # 2px cell border must remain completely empty so neighbors never bleed
                self.assertEqual(float(cov[:2, :].max()), 0.0)
                self.assertEqual(float(cov[-2:, :].max()), 0.0)
                self.assertEqual(float(cov[:, :2].max()), 0.0)
                self.assertEqual(float(cov[:, -2:].max()), 0.0)

    def test_credit_is_an_alpha_cutout_not_a_white_rectangle(self):
        credit = np.asarray(Image.open(ROOT / "hello_credit.png").convert("RGBA"))
        alpha = credit[:, :, 3]
        self.assertGreater(np.mean(alpha == 0), 0.85)
        self.assertGreater(np.count_nonzero(alpha > 150), 1000)
        self.assertEqual(int(alpha[0, 0]), 0)
        self.assertEqual(int(alpha[-1, -1]), 0)
        visible_rgb = credit[alpha > 150][:, :3]
        self.assertTrue(np.all(visible_rgb == 255), "native transparent-backdrop credit ink must be pure white")


if __name__ == "__main__":
    unittest.main()
