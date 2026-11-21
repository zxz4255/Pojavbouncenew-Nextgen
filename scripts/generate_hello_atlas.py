#!/usr/bin/env python3
"""Regenerate the writing frames and interpolation-delta atlases from the ink masks.

This optional asset-generation script requires numpy, scipy, scikit-image and Pillow;
these are not needed for a normal client build. The extracted coloured ink masks are
based on the image supplied by the user; no third-party font or artwork is used.
Each increment contains only new ink, correctly composited over its preceding frame.
"""
from pathlib import Path
import math
import shutil
import numpy as np
from PIL import Image
from scipy import ndimage
from skimage.morphology import skeletonize

PROJECT = Path(__file__).resolve().parent.parent
ROOT = PROJECT / "src/main/resources/assets/liquidbounce/textures/gui"
WEB = PROJECT / "src-theme/public/img/hello"
FRAME_COUNT, COLS, ROWS, PAD = 48, 5, 10, 1
# crop = x, y, right, bottom in the original 1062x405 image; size = atlas frame size.
LAYERS = (
    ("hello_main", (0, 0, 764, 405), (382, 202)),
    ("hello_subtitle", (374, 56, 1062, 274), (344, 109)),
)


def timeline(alpha):
    """Approximate a pen travelling along the image's connected ink skeleton.

    Trace each component continuously, visiting components from left to right. At
    branches, prefer a smooth continuation; lift the virtual pen where necessary.
    Antialiased edge pixels inherit the visit time of their nearest skeleton pixel.
    """
    skeleton = skeletonize(alpha >= 90)
    if not skeleton.any():
        raise ValueError("The ink mask contains no visible strokes")
    labels, count = ndimage.label(skeleton, structure=np.ones((3, 3), dtype=np.uint8))
    components = []
    for label in range(1, count + 1):
        ys, xs = np.nonzero(labels == label)
        if len(xs):
            components.append((int(xs.min()), int(ys.min()), set(zip(xs.tolist(), ys.tolist()))))
    components.sort(key=lambda c: (c[0], c[1]))
    visit = np.full(skeleton.shape, np.nan, dtype=np.float64)
    t, previous_component = 0.0, None
    offsets = [(dx, dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1) if dx or dy]
    for _, _, unvisited in components:
        endpoints = [p for p in unvisited
                     if sum((p[0] + dx, p[1] + dy) in unvisited for dx, dy in offsets) <= 1]
        start = min(endpoints or unvisited, key=lambda p: (p[0], p[1]))
        if previous_component is not None:
            t += min(math.dist(start, previous_component), 30.0) * 0.14
        cur, prev = start, None
        while unvisited:
            unvisited.remove(cur)
            visit[cur[1], cur[0]] = t
            neighbours = [(cur[0] + dx, cur[1] + dy) for dx, dy in offsets
                          if (cur[0] + dx, cur[1] + dy) in unvisited]
            if neighbours:
                def score(p):
                    dx, dy = p[0] - cur[0], p[1] - cur[1]
                    length = math.hypot(dx, dy)
                    continuity = 0.0
                    if prev is not None:
                        ox, oy = cur[0] - prev[0], cur[1] - prev[1]
                        continuity = (ox * dx + oy * dy) / (math.hypot(ox, oy) * length)
                    return continuity + 0.12 * dx / length - 0.02 * length
                chosen = max(neighbours, key=score)
                t += math.dist(cur, chosen)
                prev, cur = cur, chosen
            elif unvisited:
                last = cur
                cur = min(unvisited, key=lambda p: ((p[0] - last[0]) ** 2 + (p[1] - last[1]) ** 2, p[0], p[1]))
                t += min(math.dist(cur, last), 30.0) * 0.20
                prev = None
            previous_component = cur
    nearest = ndimage.distance_transform_edt(~skeleton, return_distances=False, return_indices=True)
    return np.nan_to_num(visit[tuple(nearest)], nan=0.0), max(t, 1.0)


def main():
    for name, crop, (frame_width, frame_height) in LAYERS:
        source = Image.open(ROOT / (name + "_smooth.png")).convert("RGBA")
        assert source.size == (1062, 405)
        times, end = timeline(np.asarray(source.getchannel("A")))
        cell_width, cell_height = frame_width + PAD * 2, frame_height + PAD * 2
        atlas_size = (cell_width * COLS, cell_height * ROWS)
        atlas = Image.new("RGBA", atlas_size, (0, 0, 0, 0))
        deltas = Image.new("RGBA", atlas_size, (0, 0, 0, 0))
        final_rgba = np.array(source.crop(crop).resize((frame_width, frame_height), Image.Resampling.LANCZOS))
        final_alpha = final_rgba[:, :, 3]
        previous_alpha = np.zeros_like(final_alpha)
        frames = []
        for n in range(FRAME_COUNT):
            pixels = np.array(source)
            if n == 0:
                pixels[:, :, 3] = 0
            elif n < FRAME_COUNT - 1:
                threshold = end * n / (FRAME_COUNT - 1)
                feather = np.clip((threshold - times) / (end / (FRAME_COUNT - 1) * 0.28) + 1, 0, 1)
                pixels[:, :, 3] = (pixels[:, :, 3].astype(np.float32) * feather).astype(np.uint8)
            image = Image.fromarray(pixels, "RGBA").crop(crop)
            image = image.resize((frame_width, frame_height), Image.Resampling.LANCZOS)
            # Resampling can produce small non-monotonic edge variations. Clip to the final
            # silhouette and ensure completed ink never disappears between successive frames.
            alpha = np.maximum(previous_alpha, np.minimum(np.array(image)[:, :, 3], final_alpha))
            frame = final_rgba.copy()
            frame[:, :, 3] = alpha
            frames.append(frame)
            previous_alpha = alpha
            atlas.paste(Image.fromarray(frame, "RGBA"),
                        ((n % COLS) * cell_width + PAD, (n // COLS) * cell_height + PAD))

        # For source-over compositing: a_next = a_prev + (1-a_prev)*a_delta.
        # Scaling the delta's opacity by the fractional frame position therefore interpolates
        # smoothly without making already-drawn semi-transparent edges blink or thicken.
        for n in range(FRAME_COUNT - 1):
            before = frames[n][:, :, 3].astype(np.float32)
            after = frames[n + 1][:, :, 3].astype(np.float32)
            denominator = 255.0 - before
            delta_alpha = np.zeros_like(before)
            np.divide((after - before) * 255.0, denominator, out=delta_alpha, where=denominator > 0)
            delta = final_rgba.copy()
            delta[:, :, 3] = np.rint(np.clip(delta_alpha, 0.0, 255.0)).astype(np.uint8)
            deltas.paste(Image.fromarray(delta, "RGBA"),
                         ((n % COLS) * cell_width + PAD, (n // COLS) * cell_height + PAD))

        for suffix, image in (("_atlas.png", atlas), ("_delta_atlas.png", deltas)):
            path = ROOT / (name + suffix)
            image.save(path, optimize=True)
            print(f"wrote {path} ({image.width}x{image.height})")

        # The Web title uses these atlases only as broad reveal masks and composites
        # the original 1062x405 coloured ink on top. This avoids upscaling a half-size
        # handwriting silhouette and keeps the final edges crisp on high-DPI screens.
        reveal_frames = []
        reveal = Image.new("RGBA", atlas_size, (0, 0, 0, 0))
        reveal_delta = Image.new("RGBA", atlas_size, (0, 0, 0, 0))
        for n, frame in enumerate(frames):
            coverage = np.zeros_like(final_alpha, dtype=np.float32)
            np.divide(frame[:, :, 3].astype(np.float32) * 255.0, final_alpha,
                      out=coverage, where=final_alpha > 0)
            # Expand the reveal beyond the pen silhouette. The full-resolution source
            # controls the actual visible edge, so this never fattens the artwork.
            coverage = ndimage.maximum_filter(coverage, size=7, mode="constant")
            mask = np.full_like(frame, 255)
            mask[:, :, 3] = np.rint(np.clip(coverage, 0, 255)).astype(np.uint8)
            reveal_frames.append(mask)
            reveal.paste(Image.fromarray(mask, "RGBA"),
                         ((n % COLS) * cell_width + PAD, (n // COLS) * cell_height + PAD))
        for n in range(FRAME_COUNT - 1):
            before = reveal_frames[n][:, :, 3].astype(np.float32)
            after = reveal_frames[n + 1][:, :, 3].astype(np.float32)
            alpha = np.zeros_like(before)
            np.divide((after - before) * 255.0, 255.0 - before,
                      out=alpha, where=before < 255)
            delta = np.full_like(reveal_frames[n], 255)
            delta[:, :, 3] = np.rint(np.clip(alpha, 0, 255)).astype(np.uint8)
            reveal_delta.paste(Image.fromarray(delta, "RGBA"),
                               ((n % COLS) * cell_width + PAD, (n // COLS) * cell_height + PAD))
        WEB.mkdir(parents=True, exist_ok=True)
        for suffix, image in (("_reveal_atlas.png", reveal), ("_reveal_delta_atlas.png", reveal_delta)):
            image.save(WEB / (name + suffix), optimize=True)
            print(f"wrote Web reveal mask {name + suffix}")
        shutil.copy2(ROOT / (name + "_smooth.png"), WEB / (name + "_smooth.png"))


if __name__ == "__main__":
    main()
