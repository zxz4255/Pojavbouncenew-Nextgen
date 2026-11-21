#!/usr/bin/env python3
"""Smooth supplied signature masks and remove the paper from the supplied credit photo.

Optional regeneration only: requires numpy, scipy, Pillow and OpenCV. The two
hello_*.png files are the previous edition's coloured transparent masks derived
from the first user image. The output preserves those colours and shapes. The
second user image is cropped rather than replaced by a font or transcribed text.
"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
GUI = ROOT / "src/main/resources/assets/liquidbounce/textures/gui"
WEB = ROOT / "src-theme/public/img/hello"
PHOTO = ROOT / "scripts/hello_sources/credit_photo.jpg"
WEB.mkdir(parents=True, exist_ok=True)


def smooth_mask(name):
    pixels = np.array(Image.open(GUI / f"{name}.png").convert("RGBA"))
    # A 3x3 elliptical opening removes one-pixel nubs but keeps the long, deliberate
    # flourishes. A small Gaussian supplies subpixel antialiasing, not a redraw.
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    alpha = cv2.morphologyEx(pixels[:, :, 3], cv2.MORPH_OPEN, kernel)
    alpha = ndimage.gaussian_filter(alpha.astype(np.float32), sigma=0.6)
    pixels[:, :, 3] = np.rint(alpha).clip(0, 255).astype(np.uint8)
    Image.fromarray(pixels, "RGBA").save(GUI / f"{name}_smooth.png", optimize=True)


def extract_credit():
    photo = Image.open(PHOTO).convert("RGB")
    # This is the handwritten region of the supplied 3060x4080 photograph.
    # Exclude the surrounding paper before looking for connected pen marks.
    crop = np.asarray(photo.crop((50, 1550, 2600, 3100))).astype(np.float32)
    luminance = crop[:, :, 0] * 0.299 + crop[:, :, 1] * 0.587 + crop[:, :, 2] * 0.114
    alpha = np.clip((233.0 - luminance) / 145.0, 0.0, 1.0)
    mask = alpha > 0.10
    labels, count = ndimage.label(mask, np.ones((3, 3), dtype=np.uint8))
    sizes = np.bincount(labels.ravel())
    keep = sizes >= 9
    keep[0] = False
    alpha *= keep[labels]
    ys, xs = np.nonzero(alpha > 0.1)
    if not len(xs):
        raise ValueError("No handwritten credit found in supplied photograph")
    pad = 20
    top, bottom = max(0, ys.min()-pad), min(alpha.shape[0], ys.max()+pad+1)
    left, right = max(0, xs.min()-pad), min(alpha.shape[1], xs.max()+pad+1)
    alpha = alpha[top:bottom, left:right]
    # Original ink is near black, as photographed; CSS makes it legible over dark
    # title backgrounds without adding an opaque rectangle.
    result = np.zeros((*alpha.shape, 4), dtype=np.uint8)
    result[:, :, :3] = (24, 20, 23)
    result[:, :, 3] = np.rint(alpha * 255).clip(0, 255).astype(np.uint8)
    image = Image.fromarray(result, "RGBA")
    image.thumbnail((1000, 1000), Image.Resampling.LANCZOS)
    image.save(WEB / "credit.png", optimize=True)
    print("credit", image.size, "saved as", WEB / "credit.png")


if __name__ == "__main__":
    for layer in ("hello_main", "hello_subtitle"):
        smooth_mask(layer)
    extract_credit()
