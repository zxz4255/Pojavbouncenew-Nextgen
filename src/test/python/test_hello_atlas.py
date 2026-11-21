#!/usr/bin/env python3
"""Checks the transparent, interpolated Hello assets. Requires Pillow and numpy."""
from pathlib import Path
import unittest
import numpy as np
from PIL import Image

PROJECT = Path(__file__).resolve().parents[3]
ROOT = PROJECT / "src/main/resources/assets/liquidbounce/textures/gui"
WEB = PROJECT / "src-theme/public/img/hello"
COLS, FRAMES = 5, 48
LAYERS = (("hello_main", 382, 202), ("hello_subtitle", 344, 109))
CROPS = {"hello_main": (0, 0, 764, 405), "hello_subtitle": (374, 56, 1062, 274)}


def get_frame(atlas, i, w, h):
    cell_w, cell_h = w + 2, h + 2
    x, y = (i % COLS) * cell_w + 1, (i // COLS) * cell_h + 1
    return atlas[y:y + h, x:x + w, 3].astype(np.float32)


class HelloAtlasTest(unittest.TestCase):
    def test_transparent_ink_and_monotonic_animation(self):
        for name, w, h in LAYERS:
            with self.subTest(layer=name):
                atlas = np.asarray(Image.open(ROOT / (name + "_atlas.png")).convert("RGBA"))
                frames = [get_frame(atlas, i, w, h) for i in range(FRAMES)]
                self.assertEqual(np.count_nonzero(frames[0]), 0)
                self.assertGreater(np.count_nonzero(frames[-1]), 100)
                self.assertGreater(float(np.mean(frames[-1] == 0)), 0.75, "background must remain transparent")
                for frame in frames:
                    self.assertEqual(frame[0, 0], 0)
                    self.assertEqual(frame[-1, -1], 0)
                for before, after in zip(frames, frames[1:]):
                    self.assertTrue(np.all(after >= before), "drawn ink must never disappear")

    def test_final_frame_retains_the_supplied_transparent_coloured_ink(self):
        for name, w, h in LAYERS:
            with self.subTest(layer=name):
                atlas = np.asarray(Image.open(ROOT / (name + "_atlas.png")).convert("RGBA"))
                i = FRAMES - 1
                x, y = (i % COLS) * (w + 2) + 1, (i // COLS) * (h + 2) + 1
                actual = atlas[y:y + h, x:x + w]
                expected = np.asarray(Image.open(ROOT / (name + "_smooth.png")).convert("RGBA")
                                      .crop(CROPS[name]).resize((w, h), Image.Resampling.LANCZOS))
                np.testing.assert_array_equal(actual, expected)

    def test_interpolation_matches_adjacent_frames_without_opacity_buildup(self):
        for name, w, h in LAYERS:
            with self.subTest(layer=name):
                atlas = np.asarray(Image.open(ROOT / (name + "_atlas.png")).convert("RGBA"))
                delta = np.asarray(Image.open(ROOT / (name + "_delta_atlas.png")).convert("RGBA"))
                for i in range(FRAMES - 1):
                    before = get_frame(atlas, i, w, h)
                    after = get_frame(atlas, i + 1, w, h)
                    addition = get_frame(delta, i, w, h)
                    for blend in (0.25, 0.5, 0.75, 1.0):
                        composed = before + (255 - before) * (addition / 255) * blend
                        ideal = before + (after - before) * blend
                        self.assertLessEqual(float(np.max(np.abs(composed - ideal))), 0.51)

    def test_smoothing_preserves_original_handwriting_silhouette(self):
        for name, _, _ in LAYERS:
            with self.subTest(layer=name):
                original = np.asarray(Image.open(ROOT / (name + ".png")).convert("RGBA"))[:, :, 3] > 128
                smoothed = np.asarray(Image.open(ROOT / (name + "_smooth.png")).convert("RGBA"))[:, :, 3] > 128
                self.assertGreater(np.count_nonzero(original & smoothed) / np.count_nonzero(original | smoothed), 0.98)
                self.assertEqual((ROOT / (name + "_smooth.png")).read_bytes(),
                                 (WEB / (name + "_smooth.png")).read_bytes())

    def test_web_reveal_masks_are_monotonic_and_cover_the_full_resolution_ink(self):
        for name, w, h in LAYERS:
            with self.subTest(layer=name):
                atlas = np.asarray(Image.open(WEB / (name + "_reveal_atlas.png")).convert("RGBA"))
                delta = np.asarray(Image.open(WEB / (name + "_reveal_delta_atlas.png")).convert("RGBA"))
                frames = [get_frame(atlas, i, w, h) for i in range(FRAMES)]
                self.assertEqual(np.count_nonzero(frames[0]), 0)
                expected = np.asarray(Image.open(ROOT / (name + "_smooth.png")).convert("RGBA")
                                      .crop(CROPS[name]).resize((w, h), Image.Resampling.LANCZOS))[:, :, 3]
                self.assertTrue(np.all(frames[-1][expected > 0] == 255), "reveal mask clips the original edge")
                self.assertGreater(np.mean(frames[-1] == 0), 0.60)
                for i, (before, after) in enumerate(zip(frames, frames[1:])):
                    self.assertTrue(np.all(after >= before))
                    addition = get_frame(delta, i, w, h)
                    composed = before + (255 - before) * (addition / 255)
                    self.assertLessEqual(float(np.max(np.abs(composed - after))), 0.51)

    def test_new_credit_is_an_alpha_cutout_not_a_white_rectangle(self):
        credit = np.asarray(Image.open(WEB / "credit.png").convert("RGBA"))
        alpha = credit[:, :, 3]
        self.assertGreater(np.mean(alpha == 0), 0.85)
        self.assertGreater(np.count_nonzero(alpha > 150), 1000)
        self.assertEqual(int(alpha[0, 0]), 0)
        self.assertEqual(int(alpha[-1, -1]), 0)
        self.assertTrue((PROJECT / "scripts/hello_sources/credit_photo.jpg").is_file())


if __name__ == "__main__":
    unittest.main()
