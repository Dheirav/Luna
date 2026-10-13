#!/usr/bin/env python3
"""Draw the widget picker previews: res/drawable-nodpi/widget_preview_*.png.

Why images: Xiaomi's picker ignores previewLayout and showed the app icon for all three widgets, so
nothing in the picker looked like what you get. These are drawn from the widgets' own dark-mode
colours and shapes (res/values-night/colors.xml, widget_mascot.xml, widget_dot_*.xml), with sample
text only, never anyone's data. Re-run after changing a widget's look:

    python3 tools/widget_previews.py /path/to/Roboto-Regular.ttf

Roboto is the variable font from any Android phone's /system/fonts; it is not committed here.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONT = sys.argv[1]
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/res/drawable-nodpi"
OUT.mkdir(parents=True, exist_ok=True)

S = 3                      # pixels per dp, a typical phone density
BG = (0x2A, 0x21, 0x31)    # widget_background, night
TEXT = (0xF2, 0xE4, 0xEC)  # widget_text, night
BODY = (0xFD, 0xF2, 0xF7)  # widget_mascot_body, night
FACE = (0x4A, 0x35, 0x73)  # widget_mascot_face, night
BLUSH = (0xE8, 0x6A, 0x93, 115)

# Xiaomi's picker tiles are nearly square (about 1.24 wide to 1 tall) and it pins an image to the
# top of the tile, so a bare 3x1 strip sat at the top over empty grey. Each widget is placed centred
# on a transparent canvas of the tile's shape, with a small margin, so it sits in the middle.
TILE_ASPECT = 1.24
MARGIN_DP = 6

def framed(img):
    w = img.width + 2 * MARGIN_DP * S
    h = max(img.height + 2 * MARGIN_DP * S, int(w / TILE_ASPECT))
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.alpha_composite(img, ((w - img.width) // 2, (h - img.height) // 2))
    return canvas

def font(size_sp, bold=False):
    f = ImageFont.truetype(FONT, size_sp * S)
    try:
        f.set_variation_by_axes([700 if bold else 400])
    except Exception:
        pass
    return f

def card(w_dp, h_dp):
    img = Image.new("RGBA", (w_dp * S, h_dp * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=22 * S, fill=BG)
    return img, d

def cloud(img, x, y, w_dp, mood="rest"):
    """The mascot, mirroring widget_mascot.xml on its 78 by 56 canvas."""
    k = w_dp * S / 78
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    def c(cx, cy, r, fill):
        d.ellipse([x + (cx - r) * k, y + (cy - r) * k, x + (cx + r) * k, y + (cy + r) * k], fill=fill)
    c(18.7, 33.6, 19, BODY); c(60.8, 34.7, 16.8, BODY); c(39, 26.9, 24.6, BODY)
    d.rounded_rectangle([x + 9.4 * k, y + 29.1 * k, x + 68.6 * k, y + 51.5 * k], radius=11.2 * k, fill=BODY)
    img.alpha_composite(layer)
    # Blush on its own layer, blended over the body: drawn onto the same layer, a translucent pink
    # replaced the white beneath it and showed the dark card through.
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    c(21.8, 34.2, 4.8, BLUSH); c(56.2, 34.2, 4.8, BLUSH)
    c(29.6, 28, 3.1, FACE); c(48.4, 28, 3.1, FACE)
    lw = max(1, int(2.8 * k))
    if mood == "rest":
        d.line([x + 35.5 * k, y + 36 * k, x + 42.5 * k, y + 36 * k], fill=FACE, width=lw)
    elif mood == "smile":
        d.arc([x + 34 * k, y + 31 * k, x + 44 * k, y + 39 * k], 20, 160, fill=FACE, width=lw)
    else:
        d.arc([x + 34 * k, y + 35 * k, x + 44 * k, y + 43 * k], 200, 340, fill=FACE, width=lw)
    img.alpha_composite(layer)

def log_widget():
    img, d = card(250, 64)
    d.text((14 * S, 14 * S), "Today", font=font(16, bold=True), fill=TEXT)
    d.text((14 * S, 35 * S), "Tap to log", font=font(12), fill=TEXT)
    cx = img.width - 10 * S - 40 * S - 2 * S - 20 * S
    cy = img.height // 2
    d.ellipse([cx - 10 * S, cy - 10 * S, cx + 10 * S, cy + 10 * S], fill=TEXT)
    cx2 = img.width - 10 * S - 20 * S
    r = int(8.8 * S)
    d.ellipse([cx2 - r, cy - r, cx2 + r, cy + r], outline=TEXT, width=int(2.4 * S))
    framed(img).save(OUT / "widget_preview_log.png")

def mood_widget():
    img, d = card(250, 64)
    d.text((12 * S, 15 * S), "How are you?", font=font(13, bold=True), fill=TEXT)
    d.text((12 * S, 35 * S), "Tap a face", font=font(10), fill=TEXT)
    for i, m in enumerate(["smile", "rest", "frown"]):
        cloud(img, img.width - (10 + 36 * (3 - i)) * S + 2 * S, img.height // 2 - 12 * S, 32, m)
    framed(img).save(OUT / "widget_preview_mood.png")

def next_widget():
    img, d = card(170, 64)
    d.text((14 * S, 9 * S), "NEXT", font=font(11, bold=True), fill=TEXT)
    d.text((14 * S, 24 * S), "3–7 Nov", font=font(17, bold=True), fill=TEXT)
    d.text((14 * S, 45 * S), "in 5 to 9 days", font=font(12), fill=TEXT)
    framed(img).save(OUT / "widget_preview_next.png")

log_widget(); mood_widget(); next_widget()
print("\n".join(str(p) for p in sorted(OUT.glob("widget_preview_*.png"))))
