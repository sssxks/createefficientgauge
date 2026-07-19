# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow"]
# ///
"""Generates the handheld cannon item texture atlas (128x64, 4px per model unit).

Palette sampled from Create's potato_cannon.png (copper) and wrench.png (brass).
Atlas layout (units, 1 unit = 4px):

  y0    barrel_side 8x3 | barrel_top 8x3 | muzzle_side | band_side | tube_side | brass/iron/copper flats
  y3    muzzle_front 4x4 | band_front 4x4 | stock_back 4x4          | tube_top  | grip_side | pommel
  y7    receiver_side 5.5x5 | receiver_end 4x5                      | spoke_face | spoke_edge | hub
  y12   receiver_top 5.5x4 | stock_side

Usage:  uv run gen_item_texture.py [--out PATH] [--preview]
"""
import argparse
from pathlib import Path

from PIL import Image

S = 4  # px per model unit
W, H = 128, 64

COPPER = ["#e3826c", "#d67b5b", "#c87456", "#c26b4c", "#b26247", "#a75a40", "#9a5038", "#904931"]
BRASS = ["#f0c060", "#e1aa56", "#dca446", "#da983b", "#bf863c", "#a06a2f", "#795b37", "#523823"]
IRON = ["#b0b0b0", "#9c9c9c", "#828282", "#686868", "#4f4f4f", "#3a3a3a"]
CYAN = ["#e8fbff", "#9fd9e8", "#5fc0d8", "#3a9ab8", "#2a7a94", "#1d5c70"]
DARK = ["#5b4a42", "#4a3c36", "#3a2f2b", "#2c2320"]

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO_ROOT / "mods/create-handheld-cannon/src/main/resources/assets/" \
    "createhandheldcannon/textures/item/handheld_cannon.png"

def hx(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))

def noise_step(x, y, seed=0):
    v = (x * 7349 + y * 15683 + seed * 2654435761) & 0xFFFFFFFF
    v ^= v >> 13
    v = (v * 1103515245 + 12345) & 0xFFFFFFFF
    return v % 5  # 0..4

class Atlas:
    def __init__(self):
        self.img = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    def fill(self, ux, uy, uw, uh, fn):
        x0, y0 = round(ux * S), round(uy * S)
        w, h = round(uw * S), round(uh * S)
        for dy in range(h):
            for dx in range(w):
                c = fn(dx, dy, w, h)
                if c is not None:
                    self.img.putpixel((x0 + dx, y0 + dy), hx(c) + (255,))

    def panel(self, ux, uy, uw, uh, pal, base, light_rows=1, dark_rows=1, noise=True, seed=0):
        def fn(dx, dy, w, h):
            i = base
            if noise:
                n = noise_step(dx // 2, dy // 2, seed)
                if n == 0:
                    i -= 1
                elif n == 4:
                    i += 1
            if dy < light_rows * S:
                i -= 1
            if dy >= h - dark_rows * S:
                i += 1
            return pal[max(0, min(len(pal) - 1, i))]
        self.fill(ux, uy, uw, uh, fn)

def build():
    A = Atlas()

    # ---- barrel side (0,0) 8x3 : copper, top light / bottom dark, rear darker, front rim light
    def barrel_side(dx, dy, w, h):
        i = 2
        n = noise_step(dx // 2, dy // 2, 1)
        if n == 0:
            i -= 1
        elif n == 4:
            i += 1
        if dy < S:
            i -= 1
        if dy >= h - S:
            i += 1
        if dx < S:
            i += 1
        if dx >= w - S:
            i -= 1
        return COPPER[max(0, min(7, i))]
    A.fill(0, 0, 8, 3, barrel_side)

    # ---- barrel top (8,0) 8x3 : brighter copper
    def barrel_top(dx, dy, w, h):
        i = 1
        n = noise_step(dx // 2, dy // 2, 2)
        if n == 0:
            i -= 1
        elif n == 4:
            i += 1
        if dy >= h - S:
            i += 1
        if dx < S:
            i += 1
        return COPPER[max(0, min(7, i))]
    A.fill(8, 0, 8, 3, barrel_top)

    # ---- muzzle side (16,0) 1.5x4 : dark copper band
    A.fill(16, 0, 1.5, 4, lambda dx, dy, w, h: COPPER[4] if dy < S else COPPER[5] if noise_step(dx, dy, 4) < 3 else COPPER[6])

    # ---- band side (17.5,0) 1.5x4 : dark iron ring
    A.fill(17.5, 0, 1.5, 4, lambda dx, dy, w, h: IRON[3] if dy < S else IRON[4] if noise_step(dx, dy, 5) < 3 else IRON[5])

    # ---- tube side (19,0) 3.5x1.5 : glowing cyan with scanlines
    def tube_side(dx, dy, w, h):
        if dy < 1 or dy >= h - 1:
            return CYAN[4]
        i = 1 if dx % 5 in (0, 1) else 2
        if noise_step(dx, dy, 10) == 0:
            i += 1
        return CYAN[max(0, min(5, i))]
    A.fill(19, 0, 3.5, 1.5, tube_side)

    # ---- tube end (22.5,0) 2x1.5 : bright cap
    A.fill(22.5, 0, 2, 1.5, lambda dx, dy, w, h: CYAN[0] if (dx in (0, w - 1) or dy in (0, h - 1)) else CYAN[1])

    # ---- flats (24.5,0): brass, iron, copper 2x2 each
    A.panel(24.5, 0, 2, 2, BRASS, 2, seed=11)
    A.panel(26.5, 0, 2, 2, IRON, 3, seed=12)
    A.panel(28.5, 0, 2, 2, COPPER, 3, seed=13)

    # ---- muzzle front (0,3) 4x4 : copper ring, dark hole
    def ring(cx, cy, dx, dy, w, h):
        return max(abs(dx - cx), abs(dy - cy)) / (w / 2)
    def muzzle_front(dx, dy, w, h):
        r = ring(w / 2 - 0.5, h / 2 - 0.5, dx, dy, w, h)
        if r > 0.86:
            return COPPER[6]
        if r > 0.62:
            return COPPER[2] if noise_step(dx, dy, 3) < 2 else COPPER[3]
        if r > 0.42:
            return COPPER[7]
        return "#3d161e"
    A.fill(0, 3, 4, 4, muzzle_front)

    # ---- band front (4,3) 4x4 : iron ring
    def band_front(dx, dy, w, h):
        r = ring(w / 2 - 0.5, h / 2 - 0.5, dx, dy, w, h)
        if r > 0.86:
            return IRON[4]
        if r > 0.62:
            return IRON[2] if noise_step(dx, dy, 6) < 3 else IRON[3]
        return COPPER[5]
    A.fill(4, 3, 4, 4, band_front)

    # ---- stock back (8,3) 4x4 : brass rim, copper core
    def stock_back(dx, dy, w, h):
        r = ring(w / 2 - 0.5, h / 2 - 0.5, dx, dy, w, h)
        if r > 0.86:
            return BRASS[5]
        if r > 0.62:
            return BRASS[2] if noise_step(dx, dy, 17) < 3 else BRASS[3]
        return COPPER[3]
    A.fill(8, 3, 4, 4, stock_back)

    # ---- tube top (19,1.5) 3.5x2 : bright cyan
    def tube_top(dx, dy, w, h):
        if dy < 1 or dx < 1 or dx >= w - 1 or dy >= h - 1:
            return CYAN[3]
        return CYAN[0] if dx % 5 in (0, 1) else CYAN[1]
    A.fill(19, 1.5, 3.5, 2, tube_top)

    # ---- grip side (19,3.5) 5.5x3 : dark, diagonal shading
    def grip_side(dx, dy, w, h):
        i = 1 + (dx + dy) // 6
        if noise_step(dx // 2, dy // 2, 14) == 0:
            i += 1
        return DARK[max(0, min(3, i))]
    A.fill(19, 3.5, 5.5, 3, grip_side)

    # ---- pommel (24.5,3.5) 2x1.5 : brass
    A.panel(24.5, 3.5, 2, 1.5, BRASS, 3, seed=15)

    # ---- spoke face (19,6.5) 5x1.5 : iron, bright top / dark tips
    def spoke_face(dx, dy, w, h):
        if dy < 1:
            return IRON[1]
        if dy >= h - 1 or dx < 1 or dx >= w - 1:
            return IRON[4]
        return IRON[2] if noise_step(dx, dy, 18) < 4 else IRON[3]
    A.fill(19, 6.5, 5, 1.5, spoke_face)

    # ---- spoke edge (24,6.5) 1.5x1 : dark iron
    A.panel(24, 6.5, 1.5, 1, IRON, 4, noise=False)

    # ---- hub face (25.5,6.5) 2x2 : brass with dark center
    def hub_face(dx, dy, w, h):
        if dx in (0, w - 1) or dy in (0, h - 1):
            return BRASS[5]
        cx, cy = w / 2 - 0.5, h / 2 - 0.5
        if abs(dx - cx) <= 1 and abs(dy - cy) <= 1:
            return BRASS[6]
        return BRASS[3]
    A.fill(25.5, 6.5, 2, 2, hub_face)

    # ---- receiver side (0,7) 5.5x5 : brass panel, seam + rivets
    def receiver_side(dx, dy, w, h):
        i = 2
        n = noise_step(dx // 2, dy // 2, 7)
        if n == 0:
            i -= 1
        elif n == 4:
            i += 1
        if dy < S:
            i -= 1
        if dy >= h - S:
            i += 1
        if dx < 1 or dx >= w - 1:
            i += 1
        if abs(dy - h // 2) < 1:
            i += 1
        if (dx in (2, w - 3)) and (dy in (2, h - 3)):
            return BRASS[0]
        return BRASS[max(0, min(7, i))]
    A.fill(0, 7, 5.5, 5, receiver_side)

    # ---- receiver end (5.5,7) 4x5 : plain brass
    A.panel(5.5, 7, 4, 5, BRASS, 2, seed=9)

    # ---- receiver top (0,12) 5.5x4 : brighter brass
    def receiver_top(dx, dy, w, h):
        i = 1
        n = noise_step(dx // 2, dy // 2, 8)
        if n == 0:
            i -= 1
        elif n == 4:
            i += 1
        if dy >= h - S:
            i += 1
        return BRASS[max(0, min(7, i))]
    A.fill(0, 12, 5.5, 4, receiver_top)

    # ---- stock side (5.5,12) 1.5x4 : dark brass
    A.panel(5.5, 12, 1.5, 4, BRASS, 4, noise=True, seed=16)

    return A.img

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output PNG path")
    ap.add_argument("--preview", action="store_true", help="also write a 6x preview to out/")
    args = ap.parse_args()

    img = build()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    img.save(args.out)
    print("saved", args.out)
    if args.preview:
        prev_dir = Path(__file__).resolve().parent / "out"
        prev_dir.mkdir(parents=True, exist_ok=True)
        prev = prev_dir / "item_atlas_x6.png"
        img.resize((W * 6, H * 6), Image.NEAREST).save(prev)
        print("saved", prev)

if __name__ == "__main__":
    main()
