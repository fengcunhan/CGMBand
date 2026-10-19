"""Generate PNG assets for the band app (app icon + trend arrows).

Usage (from anywhere):  python3 -I band/tools/gen_assets.py
Outputs into band/src/common/. Requires Pillow.

Arrows are rendered as images because the Vela system font is not guaranteed
to contain diagonal arrow glyphs and CSS transform is not part of the
documented general styles.
"""

import pathlib

from PIL import Image, ImageDraw

SRC_COMMON = pathlib.Path(__file__).resolve().parent.parent / "src" / "common"
TREND_DIR = SRC_COMMON / "trend"

SUPERSAMPLE = 4
ARROW_SIZE = 56          # displayed at 56x56 px (designWidth 336 == 1:1 on Band 9 Pro)
ICON_SIZE = 128          # same size as the official interconnect demo logo
ARROW_COLOR = (255, 255, 255, 255)
ICON_BG = (229, 57, 53, 255)
ICON_FG = (255, 255, 255, 255)

# Rotation applied to an upward arrow (PIL rotates counter-clockwise).
SINGLE_ARROWS = {
    "up": 0,
    "up45": -45,
    "flat": -90,
    "down45": -135,
    "down": 180,
}
DOUBLE_ARROWS = {
    "up2": 0,
    "down2": 180,
}


def draw_up_arrow(size, width_ratio=1.0):
    """Return an RGBA image (size x size) with a centred upward arrow."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    cx = size / 2
    head_half = size * 0.36 * width_ratio
    shaft_half = size * 0.11 * width_ratio
    top = size * 0.10
    head_bottom = size * 0.50
    bottom = size * 0.90
    draw.polygon(
        [(cx, top), (cx + head_half, head_bottom), (cx - head_half, head_bottom)],
        fill=ARROW_COLOR,
    )
    draw.rectangle(
        [cx - shaft_half, head_bottom - 1, cx + shaft_half, bottom],
        fill=ARROW_COLOR,
    )
    return img


def render_single(angle):
    big = ARROW_SIZE * SUPERSAMPLE
    arrow = draw_up_arrow(big).rotate(angle, resample=Image.BICUBIC)
    return arrow.resize((ARROW_SIZE, ARROW_SIZE), Image.LANCZOS)


def render_double(angle):
    big = ARROW_SIZE * SUPERSAMPLE
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    narrow = draw_up_arrow(big, width_ratio=0.5)
    offset = int(big * 0.22)
    canvas.alpha_composite(narrow, (-offset, 0))
    canvas.alpha_composite(narrow, (offset, 0))
    rotated = canvas.rotate(angle, resample=Image.BICUBIC)
    return rotated.resize((ARROW_SIZE, ARROW_SIZE), Image.LANCZOS)


def render_icon():
    big = ICON_SIZE * SUPERSAMPLE
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse([0, 0, big - 1, big - 1], fill=ICON_BG)
    # Blood drop: circle at the bottom + triangle tip at the top.
    cx = big / 2
    radius = big * 0.22
    cy = big * 0.60
    draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=ICON_FG)
    draw.polygon(
        [(cx, big * 0.16), (cx + radius * 0.93, cy - radius * 0.35), (cx - radius * 0.93, cy - radius * 0.35)],
        fill=ICON_FG,
    )
    icon = img.resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS)
    # Flatten on black (launcher background) to a plain RGB PNG like the demo logo.
    flat = Image.new("RGB", icon.size, (0, 0, 0))
    flat.paste(icon, mask=icon.split()[3])
    return flat


def main():
    TREND_DIR.mkdir(parents=True, exist_ok=True)
    for name, angle in SINGLE_ARROWS.items():
        render_single(angle).save(TREND_DIR / f"{name}.png", optimize=True)
    for name, angle in DOUBLE_ARROWS.items():
        render_double(angle).save(TREND_DIR / f"{name}.png", optimize=True)
    render_icon().save(SRC_COMMON / "logo.png", optimize=True)
    print(f"assets written to {SRC_COMMON}")


if __name__ == "__main__":
    main()
