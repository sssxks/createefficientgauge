# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow"]
# ///
"""Builds the handheld cannon GUI sheet (256x256) from Create's schematics_2.png materials.

The source materials (slot frames, dotted blue, dark checker, strip, title, fuel
bar, highlight) are extracted from the local Create mod jar found in the Gradle
cache (version read from gradle.properties), so the sheet always matches the
Create version the mod is built against.

Sheet layout, matching CannonScreen/CannonMenu widget coordinates (window is 213
wide; TOP 42px at screen y=0, BOTTOM 99px at screen y=42):

  TITLE     (0,0)   205x15  rendered at (x, y-2)
  TOP       (0,16)  213x42  6 schematic slots at (47+i*22, 19) in a navy bay
  BOTTOM    (0,58)  213x99  select buttons at (46+i*22, 43); fuel bay (7,63)-(92,89)
                            with slot (15,67) and bar inset (34,68); todo input box
                            (120,63)-(176,89) for ScrollInput (122,68,52,18);
                            button strip at y=111
  HIGHLIGHT (0,160) 26x26   selection highlight at (42+selected*22, 14)
  FUEL      (32,160) 47x16  fuel fill bar at (36,69)

Usage:  uv run gen_gui_texture.py [--out PATH] [--preview] [--create-jar PATH]
"""
import argparse
import re
import tempfile
import zipfile
from pathlib import Path

from PIL import Image

SLOT_COUNT = 6
K = (0, 0, 0, 255)
GRAY = (198, 198, 198, 255)
NAVY = (60, 65, 104, 255)
D_SOLID = (49, 49, 47, 255)

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO_ROOT / "mods/create-handheld-cannon/src/main/resources/assets/" \
    "createhandheldcannon/textures/gui/handheld_cannon.png"

def locate_create_jar():
    props = (REPO_ROOT / "gradle.properties").read_text()
    mc = re.search(r"minecraft_version=(.+)", props).group(1).strip()
    version = re.search(r"create_version=(.+)", props).group(1).strip()
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1/com.simibubi.create" \
        / f"create-{mc}" / version
    jars = list(cache.glob(f"*/create-{mc}-{version}.jar"))
    if not jars:
        raise SystemExit(f"Create jar not found under {cache}; pass --create-jar")
    return jars[0]

def extract_gui_textures(jar_path, names, dest_dir):
    found = {}
    with zipfile.ZipFile(jar_path) as zf:
        for name in names:
            entry = f"assets/create/textures/gui/{name}.png"
            out = dest_dir / f"{name}.png"
            out.write_bytes(zf.read(entry))
            found[name] = out
    return found

def build(src):
    out = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    white = src.getpixel((1, 183))  # strip white accent line

    def copy(dx, dy, sx, sy, w, h):
        out.paste(src.crop((sx, sy, sx + w, sy + h)), (dx, dy))

    def fill(x0, y0, x1, y1, color):
        for y in range(y0, y1):
            for x in range(x0, x1):
                out.putpixel((x, y), color)

    def tile(dx, dy, w, h, sx, sy, sw, sh):
        swatch = src.crop((sx, sy, sx + sw, sy + sh))
        for y in range(dy, dy + h, sh):
            for x in range(dx, dx + w, sw):
                cw = min(sw, dx + w - x)
                ch = min(sh, dy + h - y)
                out.paste(swatch.crop((0, 0, cw, ch)), (x, y))

    def hline(x0, x1, y, color):
        for x in range(x0, x1 + 1):
            out.putpixel((x, y), color)

    def vline(x, y0, y1, color):
        for y in range(y0, y1 + 1):
            out.putpixel((x, y), color)

    def recessed_bay(bx, by, sx0, sy0, sx1, sy1):
        """K border + D solid edge rows + dark checker interior. Screen coords."""
        x0, y0 = bx + sx0, by + sy0 - 42
        x1, y1 = bx + sx1, by + sy1 - 42
        hline(x0, x1, y0, K)
        hline(x0, x1, y1, K)
        vline(x0, y0, y1, K)
        vline(x1, y0, y1, K)
        hline(x0 + 1, x1 - 1, y0 + 1, D_SOLID)
        hline(x0 + 1, x1 - 1, y1 - 1, D_SOLID)
        tile(x0 + 1, y0 + 2, x1 - x0 - 1, y1 - y0 - 3, 84, 113, 12, 2)

    # ============ TITLE (0,0) ============
    copy(0, 0, 0, 0, 205, 15)

    # ============ TOP (0,16) ============
    TX, TY = 0, 16
    copy(TX, TY + 0, 0, 77, 206, 2)    # top band rows
    copy(TX, TY + 12, 0, 89, 206, 1)   # black separator line
    copy(TX, TY + 41, 0, 118, 206, 1)  # bottom black line
    # panel frame rows 13..40
    vline(TX + 1, TY + 13, TY + 40, K)
    vline(TX + 2, TY + 13, TY + 40, white)
    vline(TX + 204, TY + 13, TY + 40, white)
    vline(TX + 205, TY + 13, TY + 40, K)
    fill(TX + 3, TY + 13, TX + 204, TY + 40, GRAY)
    # navy schematic bay: slots at screen (47+i*22, 19); 26-box at (42+i*22, 14)
    bay_x0, bay_y0 = 41, 14
    bay_x1 = 42 + 26 + 22 * (SLOT_COUNT - 1)  # exclusive end = 42+26+110 = 178
    fill(TX + bay_x0, TY + bay_y0, TX + bay_x1, TY + bay_y0 + 26, NAVY)
    # paste 6 slot frames (18x18) at (46+i*22, 18)
    for i in range(SLOT_COUNT):
        copy(TX + 46 + i * 22, TY + 18, 14, 95, 18, 18)

    # ============ BOTTOM (0,58) ============
    BX, BY = 0, 58
    # blue area rows 0..61, frame columns
    vline(BX + 0, BY + 0, BY + 61, K)
    vline(BX + 1, BY + 0, BY + 61, white)
    vline(BX + 204, BY + 0, BY + 61, white)
    vline(BX + 205, BY + 0, BY + 61, K)
    tile(BX + 2, BY + 0, 202, 62, 40, 123, 120, 10)
    # last blue row (copy original row 181 for the accent join)
    copy(BX, BY + 62, 0, 181, 206, 1)

    # fuel bay: screen (7,63)..(92,89); slot at (15,67), bar inset at (34,68)
    recessed_bay(BX, BY, 7, 63, 92, 89)
    copy(BX + 14, BY + 66 - 42, 14, 95, 18, 18)     # fuel slot frame
    copy(BX + 34, BY + 68 - 42, 34, 95, 50, 18)     # fuel bar inset
    # todo box: screen (120,63)..(176,89)
    recessed_bay(BX, BY, 120, 63, 176, 89)

    # strip rows 63..92: copy original (0,182)-(213,212)
    copy(BX, BY + 63, 0, 182, 213, 30)
    # erase the two baked plates -> flat gray
    fill(BX + 6, BY + 63 + 5, BX + 27, BY + 63 + 25, GRAY)
    fill(BX + 179, BY + 63 + 5, BX + 200, BY + 63 + 25, GRAY)

    # ============ HIGHLIGHT (0,160) ============
    copy(0, 160, 1, 229, 26, 26)
    # ============ FUEL (32,160) ============
    copy(32, 160, 28, 222, 47, 16)

    return out

def write_preview(sheet, gui_textures, preview_path):
    """Assembles the full window roughly like CannonScreen does."""
    SCALE = 3
    win = Image.new("RGBA", (260 * SCALE, 260 * SCALE), (32, 32, 36, 255))

    def blit(img, x, y):
        big = img.resize((img.width * SCALE, img.height * SCALE), Image.NEAREST)
        win.paste(big, (round(x * SCALE), round(y * SCALE)), big)

    top = sheet.crop((0, 16, 213, 58))
    bottom = sheet.crop((0, 58, 213, 157))
    title = sheet.crop((0, 0, 205, 15))
    inv = Image.open(gui_textures["player_inventory"]).convert("RGBA").crop((0, 0, 176, 108))
    widgets = Image.open(gui_textures["widgets"]).convert("RGBA")
    btn = widgets.crop((0, 0, 18, 18))

    OX, OY = 20, 10
    blit(title, OX, OY - 2)
    blit(top, OX, OY)
    blit(bottom, OX, OY + 42)
    blit(inv, OX + 29, OY + 143)
    for i in range(SLOT_COUNT):
        blit(btn, OX + 46 + i * 22, OY + 43)
    for bx in (8, 33, 51, 69, 87, 135, 180):
        blit(btn, OX + bx, OY + 111)
    blit(sheet.crop((32, 160, 79, 176)), OX + 36, OY + 69)  # fuel bar full
    blit(sheet.crop((0, 160, 26, 186)), OX + 42, OY + 14)  # highlight slot 1
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    win.save(preview_path)

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output PNG path")
    ap.add_argument("--preview", action="store_true", help="also write an assembled window preview to out/")
    ap.add_argument("--create-jar", type=Path, default=None, help="path to the Create mod jar")
    args = ap.parse_args()

    jar = args.create_jar or locate_create_jar()
    print("using Create jar:", jar)
    tmp = Path(tempfile.mkdtemp(prefix="handheld-cannon-art-"))
    textures = extract_gui_textures(jar, ["schematics_2", "player_inventory", "widgets"], tmp)

    sheet = build(Image.open(textures["schematics_2"]).convert("RGBA"))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.out)
    print("saved", args.out)
    if args.preview:
        prev = Path(__file__).resolve().parent / "out" / "gui_window_preview.png"
        write_preview(sheet, textures, prev)
        print("saved", prev)

if __name__ == "__main__":
    main()
