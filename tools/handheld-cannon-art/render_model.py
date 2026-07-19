# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow"]
# ///
"""Renders Minecraft item-model JSONs to PNG for preview (orthographic, painter's
algorithm). Supports element rotations, per-face UV + UV rotation, and spinning
extra models (e.g. a cog partial) around an origin.

Textures are resolved from the model's "textures" map ("all"/"0"/first entry):
mod namespaces are looked up under mods/\\*\\src\\main\\resources\\assets, and the
"create" namespace is read from the local Create jar (see gen_gui_texture.py).

Usage:
  uv run render_model.py item.json cog.json [--angle 15] [--spin-origin 8,8.5,7.5]
      [--views side,gui,iso] [--scale 17] [--atlas atlas.png] [--out preview.png]
"""
import argparse
import json
import math
import re
import tempfile
import zipfile
from pathlib import Path

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]

# Face corner tables: TL, TR, BR, BL as seen from outside; uv (u1,v1) at TL.
FACE_DEF = {
    "north": ((-1, 0, 0), [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)]),
    "south": ((1, 0, 0), [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)]),
    "east": ((0, 0, 1), [(1, 1, 0), (1, 1, 1), (1, 0, 1), (1, 0, 0)]),
    "west": ((0, 0, -1), [(0, 1, 1), (0, 1, 0), (0, 0, 0), (0, 0, 1)]),
    "up": ((0, 1, 0), [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)]),
    "down": ((0, -1, 0), [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)]),
}
SHADE = {"up": 1.0, "down": 0.55, "north": 0.8, "south": 0.8, "east": 0.65, "west": 0.65}
VIEWS = {
    "gui": (64, 47, -47),
    "side": (0, 90, 0),
    "iso": (30, 45, 0),
    "back": (25, -135, 0),
    "fp": (5, 5, 5),
}

def rot_matrix(ax, ay, az):
    cx, sx = math.cos(math.radians(ax)), math.sin(math.radians(ax))
    cy, sy = math.cos(math.radians(ay)), math.sin(math.radians(ay))
    cz, sz = math.cos(math.radians(az)), math.sin(math.radians(az))
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    def mul(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(rz, mul(ry, rx))

def apply(m, v):
    return [m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] for i in range(3)]

def rotate_uv(u, v, u1, v1, u2, v2, rot):
    cx, cy = (u1 + u2) / 2, (v1 + v2) / 2
    du, dv = u - cx, v - cy
    for _ in range((rot // 90) % 4):
        du, dv = -dv, du
    return cx + du, cy + dv

def build_quads(model, tex_img):
    tex_w, tex_h = model.get("texture_size", [16, 16])
    tw, th = tex_img.size
    quads = []
    for el in model.get("elements", []):
        f, t = el["from"], el["to"]
        erot = None
        if "rotation" in el:
            r = el["rotation"]
            erot = (r["origin"], r["axis"], r["angle"])
        for fname, face in el.get("faces", {}).items():
            normal, corners = FACE_DEF[fname]
            pts = [[f[0] + (t[0] - f[0]) * cx,
                    f[1] + (t[1] - f[1]) * cy,
                    f[2] + (t[2] - f[2]) * cz] for cx, cy, cz in corners]
            n = list(normal)
            if erot:
                origin, axis, angle = erot
                am = rot_matrix(angle if axis == "x" else 0,
                                angle if axis == "y" else 0,
                                angle if axis == "z" else 0)
                pts = [[o + q for o, q in zip(origin, apply(am, [p[i] - origin[i] for i in range(3)]))]
                       for p in pts]
                n = apply(am, n)
            u1, v1, u2, v2 = face["uv"]
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            rot = face.get("rotation", 0)
            if rot:
                uvs = [rotate_uv(u, v, u1, v1, u2, v2, rot) for u, v in uvs]
            uvs = [(u / tex_w * tw, v / tex_h * th) for u, v in uvs]
            quads.append((pts, uvs, n, SHADE[fname]))
    return quads

def spin(quads, angle, origin):
    m = rot_matrix(0, 0, angle)
    return [([[o + q for o, q in zip(origin, apply(m, [p[i] - origin[i] for i in range(3)]))] for p in pts],
             uvs, apply(m, n), shade) for pts, uvs, n, shade in quads]

def transform_all(quads, m, center=(8, 8, 8)):
    return [([apply(m, [p[i] - center[i] for i in range(3)]) for p in pts],
             uvs, apply(m, n), shade) for pts, uvs, n, shade in quads]

def render(quads, tex_img, size=384, scale=17, bg=(40, 40, 46, 255)):
    img = Image.new("RGBA", (size, size), bg)
    px = img.load()
    order = sorted(range(len(quads)), key=lambda i: sum(p[2] for p in quads[i][0]) / 4)
    for idx in order:
        pts, uvs, n, shade = quads[idx]
        sp = [(size / 2 + p[0] * scale, size / 2 - p[1] * scale) for p in pts]
        for tri in ((0, 1, 2), (0, 2, 3)):
            draw_tri(px, tex_img, [sp[i] for i in tri], [uvs[i] for i in tri], shade)
    return img

def draw_tri(px, tex, sp, uv, shade):
    xs = [p[0] for p in sp]
    ys = [p[1] for p in sp]
    minx, maxx = max(0, int(min(xs))), min(383, int(max(xs)) + 1)
    miny, maxy = max(0, int(min(ys))), min(383, int(max(ys)) + 1)
    (x0, y0), (x1, y1), (x2, y2) = sp
    d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
    if abs(d) < 1e-9:
        return
    tp = tex.load()
    for y in range(miny, maxy + 1):
        for x in range(minx, maxx + 1):
            w0 = ((y1 - y2) * (x + 0.5 - x2) + (x2 - x1) * (y + 0.5 - y2)) / d
            w1 = ((y2 - y0) * (x + 0.5 - x2) + (x0 - x2) * (y + 0.5 - y2)) / d
            w2 = 1 - w0 - w1
            if w0 < -0.001 or w1 < -0.001 or w2 < -0.001:
                continue
            tu = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0]
            tv = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1]
            c = tp[max(0, min(tex.width - 1, int(tu))), max(0, min(tex.height - 1, int(tv)))]
            if c[3] < 128:
                continue
            px[x, y] = (int(c[0] * shade), int(c[1] * shade), int(c[2] * shade), 255)

def locate_create_jar():
    props = (REPO_ROOT / "gradle.properties").read_text()
    mc = re.search(r"minecraft_version=(.+)", props).group(1).strip()
    version = re.search(r"create_version=(.+)", props).group(1).strip()
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1/com.simibubi.create" \
        / f"create-{mc}" / version
    jars = list(cache.glob(f"*/create-{mc}-{version}.jar"))
    if not jars:
        raise SystemExit("Create jar not found in Gradle cache; pass --atlas")
    return jars[0]

def resolve_texture(ref, create_jar=None):
    ns, _, path = ref.partition(":")
    for assets in REPO_ROOT.glob("mods/*/src/main/resources/assets"):
        candidate = assets / ns / "textures" / f"{path}.png"
        if candidate.exists():
            return Image.open(candidate).convert("RGBA")
    if ns == "create":
        jar = create_jar or locate_create_jar()
        with zipfile.ZipFile(jar) as zf:
            data = zf.read(f"assets/create/textures/{path}.png")
        tmp = Path(tempfile.mkdtemp(prefix="render-model-")) / "tex.png"
        tmp.write_bytes(data)
        return Image.open(tmp).convert("RGBA")
    raise SystemExit(f"texture not found: {ref}")

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("models", nargs="+", type=Path,
                    help="model JSONs; models after the first are spun (cog partials)")
    ap.add_argument("--angle", type=float, default=0, help="spin angle in degrees for cog models")
    ap.add_argument("--spin-origin", default="8,8.5,7.5", help="x,y,z spin pivot in model units")
    ap.add_argument("--views", default="side,gui,iso", help="comma list of: " + ",".join(VIEWS))
    ap.add_argument("--scale", type=float, default=17)
    ap.add_argument("--atlas", type=Path, default=None, help="override texture atlas PNG")
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "out" / "model_preview.png")
    args = ap.parse_args()

    def repo_path(p):
        return p if p.exists() else REPO_ROOT / p
    models = [json.loads(repo_path(p).read_text()) for p in args.models]
    if args.atlas:
        atlas = Image.open(args.atlas).convert("RGBA")
    else:
        textures = models[0].get("textures", {})
        key = "all" if "all" in textures else "0" if "0" in textures else next(iter(textures))
        atlas = resolve_texture(textures[key])
    origin = tuple(float(v) for v in args.spin_origin.split(","))

    quads = [build_quads(models[0], atlas)]
    for m in models[1:]:
        quads.append(spin(build_quads(m, atlas), args.angle, origin))

    names = [v.strip() for v in args.views.split(",")]
    tiles = []
    for name in names:
        m = rot_matrix(*VIEWS[name])
        merged = []
        for q in quads:
            merged.extend(transform_all(q, m))
        tiles.append(render(merged, atlas, scale=args.scale))
    sheet = Image.new("RGBA", (384 * len(tiles), 384), (24, 24, 28, 255))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, (i * 384, 0))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.out)
    print("saved", args.out, "views:", ",".join(names))

if __name__ == "__main__":
    main()
