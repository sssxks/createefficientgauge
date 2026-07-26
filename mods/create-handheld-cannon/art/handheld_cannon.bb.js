// Builds the Create: Handheld Cannon item model + texture from the new design
// (docs/new_design.png). Run with:
//   node tools/blockbench/run.mjs mods/create-handheld-cannon/art/handheld_cannon.bb.js \
//     --out mods/create-handheld-cannon/art/out --blockbench <Blockbench.exe>
//
// The job lets Blockbench's own template generator (the packer behind
// "Create Texture > Template") lay out every face on the texture, then
// paints the texture procedurally into those rects, so UVs can never drift
// out of sync with the texture.

const TEXTURE_SIZE = 128; // initial/maximum UV space; the packer may shrink it
const PX_PER_UNIT = 2; // texture pixels per model unit (16-unit cube -> 32 px)

// --- color helpers ---------------------------------------------------------

function hexToRgb(hex) {
    const value = parseInt(hex.slice(1), 16);
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function shade(hex, delta) {
    const [r, g, b] = hexToRgb(hex);
    const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));
    return `rgb(${clamp(r + delta)},${clamp(g + delta)},${clamp(b + delta)})`;
}

// Deterministic per-pixel noise in [-1, 1].
function noise(x, y, seed) {
    const n = Math.sin(x * 127.1 + y * 311.7 + seed * 74.7) * 43758.5453;
    return (n - Math.floor(n)) * 2 - 1;
}

// --- material painters -----------------------------------------------------
// Each painter fills one face rect (pixel coordinates) on the texture canvas.

function paintNoise(ctx, x, y, w, h, base, amp, seed) {
    for (let py = y; py < y + h; py += 1) {
        for (let px = x; px < x + w; px += 1) {
            ctx.fillStyle = shade(base, noise(px, py, seed) * amp);
            ctx.fillRect(px, py, 1, 1);
        }
    }
}

// 1px bevel: lighter top/left, darker bottom/right.
function paintBevel(ctx, x, y, w, h, base) {
    if (w < 2 || h < 2) {
        return;
    }
    ctx.fillStyle = shade(base, 18);
    ctx.fillRect(x, y, w, 1);
    ctx.fillRect(x, y, 1, h);
    ctx.fillStyle = shade(base, -22);
    ctx.fillRect(x, y + h - 1, w, 1);
    ctx.fillRect(x + w - 1, y, 1, h);
}

function metalPainter(base, amp, seed) {
    return (ctx, x, y, w, h) => {
        paintNoise(ctx, x, y, w, h, base, amp, seed);
        paintBevel(ctx, x, y, w, h, base);
    };
}

function woodPainter(base, seed) {
    return (ctx, x, y, w, h) => {
        paintNoise(ctx, x, y, w, h, base, 7, seed);
        // Vertical grain stripes.
        for (let px = x; px < x + w; px += 1) {
            const stripe = Math.sin((px - x) * 1.7 + seed) > 0.35;
            if (stripe) {
                ctx.fillStyle = shade(base, -16 + noise(px, 0, seed) * 5);
                ctx.fillRect(px, y, 1, h);
            }
        }
        paintBevel(ctx, x, y, w, h, base);
    };
}

// Orange side plate: raised bright frame, recessed darker inner panel.
function platePainter(base, dark) {
    return (ctx, x, y, w, h) => {
        paintNoise(ctx, x, y, w, h, base, 6, 3);
        ctx.fillStyle = shade(base, 26);
        ctx.fillRect(x, y, w, 1);
        ctx.fillRect(x, y, 1, h);
        ctx.fillRect(x, y + h - 1, w, 1);
        ctx.fillRect(x + w - 1, y, 1, h);
        ctx.fillStyle = shade(base, -12);
        ctx.fillRect(x + 1, y + 1, w - 2, 1);
        ctx.fillRect(x + 1, y + 1, 1, h - 2);
        ctx.fillRect(x + 1, y + h - 2, w - 2, 1);
        ctx.fillRect(x + w - 2, y + 1, 1, h - 2);
        paintNoise(ctx, x + 2, y + 2, w - 4, h - 4, dark, 5, 4);
    };
}

// Muzzle front: steel ring with a dark bore.
function borePainter(base) {
    return (ctx, x, y, w, h) => {
        paintNoise(ctx, x, y, w, h, base, 6, 5);
        paintBevel(ctx, x, y, w, h, base);
        const inset = Math.max(1, Math.floor(Math.min(w, h) / 4));
        ctx.fillStyle = shade(base, -30);
        ctx.fillRect(x + inset - 1, y + inset - 1, w - 2 * inset + 2, h - 2 * inset + 2);
        ctx.fillStyle = "#17120f";
        ctx.fillRect(x + inset, y + inset, w - 2 * inset, h - 2 * inset);
    };
}

const MATERIALS = {
    steel: metalPainter("#8d8d95", 7, 11),       // barrel tube
    steelDark: metalPainter("#6f6f78", 6, 12),   // muzzle ring sides
    steelLight: metalPainter("#a9a9b0", 6, 13),  // bolts, trigger, back plate
    darkMetal: metalPainter("#3b332c", 5, 14),   // frame, guard, cap, cog
    brass: metalPainter("#c17c2e", 9, 15),       // body casing
    brassBand: metalPainter("#b06f26", 8, 16),   // front band
    plate: platePainter("#c17c2e", "#9a5f1e"),    // ornate side plates
    bore: borePainter("#9a9aa2"),                // muzzle face
    wood: woodPainter("#b5701f", 17),            // grip
};

// --- geometry ---------------------------------------------------------------
// +y up, -z = muzzle direction. Materials per face default to the cube
// material; `faces` overrides individual faces.

const BARREL_PIVOT = [8, 8, 11];
const BARREL_ANGLE = 22.5;
const GRIP_PIVOT = [8, 7, 11.75];
const GRIP_ANGLE = -22.5;

const CUBES = [
    // barrel
    { name: "barrel_low", material: "steel", from: [6, 6, 6], to: [10.25, 10.75, 9],
      rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT },
    { name: "cradle_low_step", material: "steelDark", from: [6.5, 6.25, 5.5], to: [9.5, 7.75, 6],
    rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT },
    { name: "barrel_mid", material: "steel", from: [6.25, 6.75, 0], to: [10, 10.5, 6],
      rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT },
    { name: "muzzle_ring", material: "steelDark", from: [5.75, 6.25, -2], to: [10.5, 11, 0],
      rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT,
      faces: { north: "bore" }, faceTransforms: { west: {flipU: true} } },

    { name: "frame_bottom", material: "darkMetal", from: [4.75, 5.5, 6.75], to: [11.25, 7, 13] },
    { name: "front_band", material: "brassBand", from: [5.5, 5.75, 6], to: [10.5, 7.25, 7] },
    { name: "body", material: "brass", from: [5.5, 7, 6.5], to: [10.5, 11, 12.5] },
    { name: "frame_top", material: "brass", from: [5.75, 10.75, 10.5], to: [10.25, 11.5, 12.25],
      faceTransforms: { up: { rotation: 180 } } },
    { name: "plate_left", material: "brass", from: [5, 6.75, 7], to: [5.75, 11.5, 12],
      faces: { west: "plate" } },
    { name: "plate_right", material: "brass", from: [10.25, 6.75, 7], to: [11, 11.5, 12],
      faces: { east: "plate" },
      faceTransforms: { up: { flipU: true } } },

    // side bolts
    { name: "bolt_left", material: "steelLight", from: [4.25, 7.875, 8.25], to: [5, 10.375, 10.75],
      rotation: [67.5, 0, 0], origin: [4.625, 9.125, 9.5] },
    { name: "bolt_right", material: "steelLight", from: [11, 7.875, 8.25], to: [11.75, 10.375, 10.75],
      rotation: [67.5, 0, 0], origin: [11.375, 9.125, 9.5] },

    // cog
    { name: "cog", material: "darkMetal", from: [4.75, 7.25, 12], to: [5.5, 10.75, 13.5] },
    { name: "cog_tooth_top", material: "darkMetal", from: [4.75, 10.75, 12.5], to: [5.5, 11.25, 13] },
    { name: "cog_tooth_bottom", material: "darkMetal", from: [4.75, 6.75, 12.5], to: [5.5, 7.25, 13] },
    { name: "cog_tooth_rear", material: "darkMetal", from: [4.75, 8.75, 13.5], to: [5.5, 9.75, 14] },
    { name: "cog_r", material: "darkMetal", from: [10.5, 7.25, 12], to: [11.25, 10.75, 13.5] },
    { name: "cog_tooth_top_r", material: "darkMetal", from: [10.5, 10.75, 12.5], to: [11.25, 11.25, 13] },
    { name: "cog_tooth_bottom_r", material: "darkMetal", from: [10.5, 6.75, 12.5], to: [11.25, 7.25, 13] },
    { name: "cog_tooth_rear_r", material: "darkMetal", from: [10.5, 8.75, 13.5], to: [11.25, 9.75, 14] },

    { name: "breech", material: "darkMetal", from: [6, 7, 12.5], to: [10, 11, 14] },
    { name: "back_plate", material: "steelLight", from: [6.5, 7.5, 14], to: [9.5, 10.5, 14.75] },
    { name: "rear_sight", material: "darkMetal", from: [7, 11.5, 10.75], to: [9, 12.25, 12], rotation: [22.5, 0, 0], origin: [8, 11.5, 10] },

    { name: "grip", material: "wood", from: [6.5, 1.5, 10.5], to: [9.5, 7, 13],
      rotation: [GRIP_ANGLE, 0, 0], origin: GRIP_PIVOT },
    { name: "grip_cap", material: "darkMetal", from: [6, 0.75, 10.25], to: [10, 1.75, 13.25],
      rotation: [GRIP_ANGLE, 0, 0], origin: GRIP_PIVOT },

    { name: "trigger", material: "steelLight", from: [7.5, 5, 9.25], to: [8.5, 6.5, 10], rotation: [22.5, 0, 0], origin: [8, 4, 8.25] },
    { name: "guard_bottom", material: "darkMetal", from: [7.25, 3.75, 9.25], to: [8.75, 4.75, 11.75] },
    { name: "guard_front", material: "darkMetal", from: [7.25, 4.25, 8.75], to: [8.75, 5.5, 9.5] },
];

// --- geometry audit ---------------------------------------------------------
// Java item models do not support cutting a rectangular face into arbitrary
// visible pieces. The culler is therefore deliberately conservative: it only
// removes a complete face when all four of its corners, nudged outwards, are
// inside one other convex cuboid. Partial coverage is left alone.
//
// The z-fighting pass is separate. It finds same-facing coplanar rectangles
// and makes atlas texels covering the same model-space area share their
// averaged color. That leaves the cuboids untouched and makes either depth
// winner display the same color.

const FACE_NAMES = ["north", "east", "south", "west", "up", "down"];
const GEOMETRY_EPSILON = 1e-6;
const OCCLUSION_PROBE = 1e-4;

function add3(a, b) {
    return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
}

function subtract3(a, b) {
    return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

function scale3(vector, amount) {
    return [vector[0] * amount, vector[1] * amount, vector[2] * amount];
}

function dot3(a, b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

function cross3(a, b) {
    return [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ];
}

function length3(vector) {
    return Math.sqrt(dot3(vector, vector));
}

function rotateOnAxis(point, axis, angle) {
    const cosine = Math.cos(angle);
    const sine = Math.sin(angle);
    if (axis === 0) {
        return [
            point[0],
            point[1] * cosine - point[2] * sine,
            point[1] * sine + point[2] * cosine,
        ];
    }
    if (axis === 1) {
        return [
            point[0] * cosine + point[2] * sine,
            point[1],
            -point[0] * sine + point[2] * cosine,
        ];
    }
    return [
        point[0] * cosine - point[1] * sine,
        point[0] * sine + point[1] * cosine,
        point[2],
    ];
}

function rotatePoint(point, rotation = [0, 0, 0], origin = [0, 0, 0]) {
    let rotated = subtract3(point, origin);
    for (let axis = 0; axis < 3; axis += 1) {
        if (rotation[axis]) {
            rotated = rotateOnAxis(rotated, axis, rotation[axis] * Math.PI / 180);
        }
    }
    return add3(rotated, origin);
}

function unrotatePoint(point, rotation = [0, 0, 0], origin = [0, 0, 0]) {
    let unrotated = subtract3(point, origin);
    for (let axis = 2; axis >= 0; axis -= 1) {
        if (rotation[axis]) {
            unrotated = rotateOnAxis(unrotated, axis, -rotation[axis] * Math.PI / 180);
        }
    }
    return add3(unrotated, origin);
}

function localFace(spec, faceName) {
    const [x0, y0, z0] = spec.from;
    const [x1, y1, z1] = spec.to;
    switch (faceName) {
        case "north":
            return {
                normal: [0, 0, -1],
                origin: [x0, y0, z0],
                u: [x1 - x0, 0, 0],
                v: [0, y1 - y0, 0],
            };
        case "south":
            return {
                normal: [0, 0, 1],
                origin: [x0, y0, z1],
                u: [x1 - x0, 0, 0],
                v: [0, y1 - y0, 0],
            };
        case "west":
            return {
                normal: [-1, 0, 0],
                origin: [x0, y0, z0],
                u: [0, 0, z1 - z0],
                v: [0, y1 - y0, 0],
            };
        case "east":
            return {
                normal: [1, 0, 0],
                origin: [x1, y0, z0],
                u: [0, 0, z1 - z0],
                v: [0, y1 - y0, 0],
            };
        case "down":
            return {
                normal: [0, -1, 0],
                origin: [x0, y0, z0],
                u: [x1 - x0, 0, 0],
                v: [0, 0, z1 - z0],
            };
        case "up":
            return {
                normal: [0, 1, 0],
                origin: [x0, y1, z0],
                u: [x1 - x0, 0, 0],
                v: [0, 0, z1 - z0],
            };
        default:
            throw new Error(`Unknown cube face: ${faceName}`);
    }
}

function describeFace(spec, cubeIndex, faceName) {
    const local = localFace(spec, faceName);
    const rotation = spec.rotation || [0, 0, 0];
    const pivot = spec.origin || [0, 0, 0];
    const origin = rotatePoint(local.origin, rotation, pivot);
    const u = subtract3(rotatePoint(add3(local.origin, local.u), rotation, pivot), origin);
    const v = subtract3(rotatePoint(add3(local.origin, local.v), rotation, pivot), origin);
    const rotatedNormalPoint = rotatePoint(
        add3(local.origin, local.normal),
        rotation,
        pivot,
    );
    const normalVector = subtract3(rotatedNormalPoint, origin);
    const normal = scale3(normalVector, 1 / length3(normalVector));
    return {
        cubeIndex,
        cubeName: spec.name,
        faceName,
        normal,
        origin,
        u,
        v,
        corners: [
            origin,
            add3(origin, u),
            add3(add3(origin, u), v),
            add3(origin, v),
        ],
    };
}

function describeGeometry(specs) {
    return specs.flatMap((spec, cubeIndex) =>
        FACE_NAMES.map((faceName) => describeFace(spec, cubeIndex, faceName)));
}

function facePoint(face, u, v) {
    return add3(face.origin, add3(scale3(face.u, u), scale3(face.v, v)));
}

function faceCoordinates(face, point) {
    const relative = subtract3(point, face.origin);
    return [
        dot3(relative, face.u) / dot3(face.u, face.u),
        dot3(relative, face.v) / dot3(face.v, face.v),
    ];
}

function pointInsideCuboid(point, spec, epsilon = GEOMETRY_EPSILON) {
    const local = unrotatePoint(
        point,
        spec.rotation || [0, 0, 0],
        spec.origin || [0, 0, 0],
    );
    return local.every((coordinate, axis) =>
        coordinate >= spec.from[axis] - epsilon &&
        coordinate <= spec.to[axis] + epsilon);
}

function findOccludedFaces(specs, faces = describeGeometry(specs)) {
    const occluded = [];
    for (const face of faces) {
        const probes = face.corners.map((corner) =>
            add3(corner, scale3(face.normal, OCCLUSION_PROBE)));
        for (let cubeIndex = 0; cubeIndex < specs.length; cubeIndex += 1) {
            if (cubeIndex === face.cubeIndex) {
                continue;
            }
            if (probes.every((point) =>
                pointInsideCuboid(point, specs[cubeIndex], GEOMETRY_EPSILON))) {
                occluded.push({
                    cubeIndex: face.cubeIndex,
                    cubeName: face.cubeName,
                    faceName: face.faceName,
                    occluderIndex: cubeIndex,
                    occluderName: specs[cubeIndex].name,
                });
                break;
            }
        }
    }
    return occluded;
}

function clipPolygonToUnitSquare(polygon) {
    const boundaries = [
        { inside: ([x]) => x >= -GEOMETRY_EPSILON, intersect: (a, b) => {
            const amount = -a[0] / (b[0] - a[0]);
            return [0, a[1] + (b[1] - a[1]) * amount];
        } },
        { inside: ([x]) => x <= 1 + GEOMETRY_EPSILON, intersect: (a, b) => {
            const amount = (1 - a[0]) / (b[0] - a[0]);
            return [1, a[1] + (b[1] - a[1]) * amount];
        } },
        { inside: (([, y]) => y >= -GEOMETRY_EPSILON), intersect: (a, b) => {
            const amount = -a[1] / (b[1] - a[1]);
            return [a[0] + (b[0] - a[0]) * amount, 0];
        } },
        { inside: (([, y]) => y <= 1 + GEOMETRY_EPSILON), intersect: (a, b) => {
            const amount = (1 - a[1]) / (b[1] - a[1]);
            return [a[0] + (b[0] - a[0]) * amount, 1];
        } },
    ];

    let clipped = polygon;
    for (const boundary of boundaries) {
        const input = clipped;
        clipped = [];
        for (let index = 0; index < input.length; index += 1) {
            const current = input[index];
            const previous = input[(index + input.length - 1) % input.length];
            const currentInside = boundary.inside(current);
            const previousInside = boundary.inside(previous);
            if (currentInside !== previousInside) {
                clipped.push(boundary.intersect(previous, current));
            }
            if (currentInside) {
                clipped.push(current);
            }
        }
        if (clipped.length === 0) {
            break;
        }
    }
    return clipped;
}

function polygonArea(polygon) {
    let doubledArea = 0;
    for (let index = 0; index < polygon.length; index += 1) {
        const current = polygon[index];
        const next = polygon[(index + 1) % polygon.length];
        doubledArea += current[0] * next[1] - next[0] * current[1];
    }
    return Math.abs(doubledArea) / 2;
}

function findCoplanarOverlaps(faces) {
    const overlaps = [];
    for (let leftIndex = 0; leftIndex < faces.length; leftIndex += 1) {
        const left = faces[leftIndex];
        for (let rightIndex = leftIndex + 1; rightIndex < faces.length; rightIndex += 1) {
            const right = faces[rightIndex];
            if (left.cubeIndex === right.cubeIndex) {
                continue;
            }
            const normalDot = dot3(left.normal, right.normal);
            if (Math.abs(normalDot) < 1 - GEOMETRY_EPSILON) {
                continue;
            }
            const planeDistance = Math.abs(dot3(
                left.normal,
                subtract3(right.origin, left.origin),
            ));
            if (planeDistance > GEOMETRY_EPSILON) {
                continue;
            }
            const projected = right.corners.map((point) =>
                faceCoordinates(left, point));
            const clipped = clipPolygonToUnitSquare(projected);
            const area = polygonArea(clipped) * length3(cross3(left.u, left.v));
            if (area <= GEOMETRY_EPSILON) {
                continue;
            }
            overlaps.push({
                area,
                direction: normalDot > 0 ? "same" : "opposite",
                left,
                right,
            });
        }
    }
    return overlaps;
}

function geometryToTextureCoordinates(u, v, rotation = 0) {
    switch (((rotation % 360) + 360) % 360) {
        case 90:
            return [1 - v, u];
        case 180:
            return [1 - u, 1 - v];
        case 270:
            return [v, 1 - u];
        default:
            return [u, v];
    }
}

function textureToGeometryCoordinates(u, v, rotation = 0) {
    switch (((rotation % 360) + 360) % 360) {
        case 90:
            return [v, 1 - u];
        case 180:
            return [1 - u, 1 - v];
        case 270:
            return [1 - v, u];
        default:
            return [u, v];
    }
}

function blendCoplanarFacePixels(ctx, width, height, textureScale, overlaps, cubes) {
    const image = ctx.getImageData(0, 0, width, height);
    const parents = new Int32Array(width * height);
    parents.fill(-1);

    const find = (value) => {
        let root = value;
        while (parents[root] !== root) {
            root = parents[root];
        }
        while (value !== root) {
            const next = parents[value];
            parents[value] = root;
            value = next;
        }
        return root;
    };
    const unite = (left, right) => {
        if (left === right) {
            return;
        }
        if (parents[left] < 0) parents[left] = left;
        if (parents[right] < 0) parents[right] = right;
        const leftRoot = find(left);
        const rightRoot = find(right);
        if (leftRoot !== rightRoot) {
            parents[rightRoot] = leftRoot;
        }
    };

    const faceUv = (description) =>
        cubes[description.cubeIndex].faces[description.faceName];
    const bounds = (face) => ({
        x0: Math.round(Math.min(face.uv[0], face.uv[2]) * textureScale),
        x1: Math.round(Math.max(face.uv[0], face.uv[2]) * textureScale),
        y0: Math.round(Math.min(face.uv[1], face.uv[3]) * textureScale),
        y1: Math.round(Math.max(face.uv[1], face.uv[3]) * textureScale),
    });

    const linkPixels = (sourceDescription, targetDescription) => {
        const source = faceUv(sourceDescription);
        const target = faceUv(targetDescription);
        const sourceBounds = bounds(source);
        const targetBounds = bounds(target);
        const sourceU0 = source.uv[0] * textureScale;
        const sourceU1 = source.uv[2] * textureScale;
        const sourceV0 = source.uv[1] * textureScale;
        const sourceV1 = source.uv[3] * textureScale;
        const targetU0 = target.uv[0] * textureScale;
        const targetU1 = target.uv[2] * textureScale;
        const targetV0 = target.uv[1] * textureScale;
        const targetV1 = target.uv[3] * textureScale;

        for (let y = sourceBounds.y0; y < sourceBounds.y1; y += 1) {
            for (let x = sourceBounds.x0; x < sourceBounds.x1; x += 1) {
                const textureU = (x + 0.5 - sourceU0) / (sourceU1 - sourceU0);
                const textureV = (y + 0.5 - sourceV0) / (sourceV1 - sourceV0);
                const [geometryU, geometryV] = textureToGeometryCoordinates(
                    textureU,
                    textureV,
                    source.rotation || 0,
                );
                const point = facePoint(sourceDescription, geometryU, geometryV);
                const [targetGeometryU, targetGeometryV] =
                    faceCoordinates(targetDescription, point);
                if (
                    targetGeometryU < -GEOMETRY_EPSILON ||
                    targetGeometryU > 1 + GEOMETRY_EPSILON ||
                    targetGeometryV < -GEOMETRY_EPSILON ||
                    targetGeometryV > 1 + GEOMETRY_EPSILON
                ) {
                    continue;
                }
                const [targetTextureU, targetTextureV] =
                    geometryToTextureCoordinates(
                        targetGeometryU,
                        targetGeometryV,
                        target.rotation || 0,
                    );
                const targetX = Math.floor(targetU0 + targetTextureU * (targetU1 - targetU0));
                const targetY = Math.floor(targetV0 + targetTextureV * (targetV1 - targetV0));
                if (
                    targetX < targetBounds.x0 || targetX >= targetBounds.x1 ||
                    targetY < targetBounds.y0 || targetY >= targetBounds.y1
                ) {
                    continue;
                }
                unite(y * width + x, targetY * width + targetX);
            }
        }
    };

    for (const overlap of overlaps) {
        if (overlap.direction !== "same") {
            continue;
        }
        linkPixels(overlap.left, overlap.right);
        linkPixels(overlap.right, overlap.left);
    }

    const groups = new Map();
    for (let pixel = 0; pixel < parents.length; pixel += 1) {
        if (parents[pixel] < 0) {
            continue;
        }
        const root = find(pixel);
        if (!groups.has(root)) {
            groups.set(root, { pixels: [], totals: [0, 0, 0, 0] });
        }
        const group = groups.get(root);
        group.pixels.push(pixel);
        const offset = pixel * 4;
        for (let channel = 0; channel < 4; channel += 1) {
            group.totals[channel] += image.data[offset + channel];
        }
    }

    let blendedPixels = 0;
    for (const group of groups.values()) {
        const average = group.totals.map((total) =>
            Math.round(total / group.pixels.length));
        for (const pixel of group.pixels) {
            const offset = pixel * 4;
            for (let channel = 0; channel < 4; channel += 1) {
                image.data[offset + channel] = average[channel];
            }
        }
        blendedPixels += group.pixels.length;
    }
    if (blendedPixels > 0) {
        ctx.putImageData(image, 0, 0);
    }
    return { blendedGroups: groups.size, blendedPixels };
}

const DISPLAY = {
    thirdperson_righthand: { rotation: [0, -15, 0], translation: [1.75, 1.75, -4] },
    thirdperson_lefthand: { rotation: [0, -15, 0], translation: [1.75, 1.75, -4] },
    firstperson_righthand: { rotation: [5, 5, 5], translation: [0.75, 4.75, 0.75], scale: [0.5, 0.5, 0.5] },
    firstperson_lefthand: { rotation: [5, 5, 5], translation: [0.75, 4.75, 0.75], scale: [0.5, 0.5, 0.5] },
    ground: { rotation: [0, 0, 90], translation: [0, -1.3, 0], scale: [0.7, 0.7, 0.7] },
    gui: { rotation: [22.5, 45, 0], translation: [1.25, -1.35, 0], scale: [0.7, 0.7, 0.7] },
    head: { rotation: [-16, 3, 0], translation: [0, 12, -4], scale: [2, 2, 2] },
    fixed: { rotation: [0, 90, 0], translation: [2, -1, 0], scale: [0.9, 0.9, 0.9] },
		on_shelf: {rotation: [-19, -180, 0], translation: [0, -1, 5.5], scale: [2, 2, 2]}
};

// --- job --------------------------------------------------------------------

module.exports = async function buildHandheldCannon({ output, log }) {
    newProject("java_block");
    Project.name = "handheld_cannon";
    Project.texture_width = TEXTURE_SIZE;
    Project.texture_height = TEXTURE_SIZE;
    Project.box_uv = false;

    // Blank texture up front so cubes bind to it on creation. The template
    // generator below only packs faces that have a texture.
    const canvas = document.createElement("canvas");
    canvas.width = TEXTURE_SIZE;
    canvas.height = TEXTURE_SIZE;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#ff00ff";
    ctx.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE);

    const waitForLoad = (tex) => new Promise((resolve, reject) => {
        tex.img.addEventListener("load", resolve, { once: true });
        tex.img.addEventListener("error", reject, { once: true });
    });
    const texture = new Texture({ id: "main", name: "handheld_cannon.png" });
    let loaded = waitForLoad(texture);
    texture.fromDataURL(canvas.toDataURL("image/png")).add(false);
    await loaded;

    for (const spec of CUBES) {
        const cube = new Cube({
            name: spec.name,
            from: spec.from,
            to: spec.to,
        });
        if (spec.rotation) {
            cube.rotation = spec.rotation;
            cube.origin = spec.origin;
        }
        cube.init();
        cube.material = spec.material;
        cube.faceOverrides = spec.faces || {};
        cube.faceTransforms = spec.faceTransforms || {};
    }

    const describedFaces = describeGeometry(CUBES);
    const allCoplanarOverlaps = findCoplanarOverlaps(describedFaces);
    const occludedFaces = findOccludedFaces(CUBES, describedFaces);
    const occludedKeys = new Set(occludedFaces.map(({ cubeIndex, faceName }) =>
        `${cubeIndex}:${faceName}`));
    for (const { cubeIndex, faceName } of occludedFaces) {
        Cube.all[cubeIndex].faces[faceName].texture = null;
    }
    const visibleFaces = describedFaces.filter(({ cubeIndex, faceName }) =>
        !occludedKeys.has(`${cubeIndex}:${faceName}`));
    const visibleCoplanarOverlaps = findCoplanarOverlaps(visibleFaces);

    // Let Blockbench's own template generator (the packer behind
    // "Create Texture > Template") lay out every face: it sorts faces by
    // area, scan-packs them with padding, shrinks the texture to a power of
    // two, and assigns the resulting UVs. java_block is not a single-texture
    // format, so it packs the current selection; select everything first.
    Cube.all.forEach((cube) => cube.markAsSelected());
    let packedTexture;
    let packedLoaded;
    await TextureGenerator.generateTemplate({
        type: "template",
        rearrange_uv: true,
        resolution: PX_PER_UNIT * 16,
        power: true,
        double_use: false,
        padding: false,
    }, (dataUrl) => {
        packedTexture = new Texture({ id: "packed", name: "handheld_cannon.png" });
        packedLoaded = waitForLoad(packedTexture);
        packedTexture.fromDataURL(dataUrl).add(false);
        return packedTexture;
    });
    if (!packedTexture) {
        throw new Error("TextureGenerator did not produce a texture");
    }
    await packedLoaded;
    texture.remove(); // blank placeholder; all faces now reference packedTexture

    // Paint each face's packed rect. UVs are in UV units (0..uv_width); the
    // packer may flip up/down faces, so use the bounding rect.
    const width = packedTexture.width;
    const height = packedTexture.height;
    const scale = width / packedTexture.getUVWidth();
    canvas.width = width;
    canvas.height = height;
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = "#ff00ff";
    ctx.fillRect(0, 0, width, height);

    for (const cube of Cube.all) {
        for (const [faceName, face] of Object.entries(cube.faces)) {
            if (face.texture === null) {
                continue;
            }
            const x = Math.round(Math.min(face.uv[0], face.uv[2]) * scale);
            const y = Math.round(Math.min(face.uv[1], face.uv[3]) * scale);
            const w = Math.max(1, Math.round(Math.abs(face.uv[2] - face.uv[0]) * scale));
            const h = Math.max(1, Math.round(Math.abs(face.uv[3] - face.uv[1]) * scale));
            const material = cube.faceOverrides[faceName] || cube.material;
            MATERIALS[material](ctx, x, y, w, h);
        }
    }

    // Apply directional UV changes after painting so the same packed pixels
    // can be mirrored or rotated without moving their texture-atlas rects.
    for (const cube of Cube.all) {
        for (const [faceName, transform] of Object.entries(cube.faceTransforms || {})) {
            const face = cube.faces[faceName];
            if (!face) {
                throw new Error(`Unknown face transform: ${cube.name}.${faceName}`);
            }
            if (face.texture === null) {
                continue;
            }
            if (transform.flipU) {
                [face.uv[0], face.uv[2]] = [face.uv[2], face.uv[0]];
            }
            if (transform.rotation !== undefined) {
                face.rotation = transform.rotation;
            }
        }
    }

    const blendResult = blendCoplanarFacePixels(
        ctx,
        width,
        height,
        scale,
        visibleCoplanarOverlaps,
        Cube.all,
    );

    loaded = waitForLoad(packedTexture);
    packedTexture.fromDataURL(canvas.toDataURL("image/png"));
    await loaded;
    Canvas.updateAll();

    // Compile, then fix up texture references and display transforms.
    const model = JSON.parse(Codecs.java_block.compile());
    model.credit = "Create: Handheld Cannon";
    model.textures = {
        all: "createhandheldcannon:item/handheld_cannon",
        particle: "createhandheldcannon:item/handheld_cannon",
    };
    for (const element of model.elements || []) {
        delete element.color;
        for (const face of Object.values(element.faces || {})) {
            face.texture = "#all";
        }
    }
    model.display = DISPLAY;

    // The geometry lives in handheld_cannon_base.json. The item's own model
    // is a builtin/entity wrapper so the mod's custom item renderer
    // (CannonItemRenderer) can add the recoil animation on top.
    output.model("models/item/handheld_cannon_base.json", `${JSON.stringify(model, null)}\n`);
    output.model("models/item/handheld_cannon.json", `${JSON.stringify({
        parent: "minecraft:builtin/entity",
        textures: { particle: "createhandheldcannon:item/handheld_cannon" },
    }, null, 2)}\n`);
    output.texture("textures/item/handheld_cannon.png", packedTexture);
    const geometryReport = {
        cubeCount: CUBES.length,
        originalFaceCount: describedFaces.length,
        exportedFaceCount: describedFaces.length - occludedFaces.length,
        removedFaces: occludedFaces.map((face) => ({
            face: `${face.cubeName}.${face.faceName}`,
            occluder: face.occluderName,
        })),
        coplanarOverlapsBeforeCulling: allCoplanarOverlaps.map((overlap) => ({
            area: Number(overlap.area.toFixed(6)),
            direction: overlap.direction,
            faces: [
                `${overlap.left.cubeName}.${overlap.left.faceName}`,
                `${overlap.right.cubeName}.${overlap.right.faceName}`,
            ],
        })),
        sameDirectionOverlapsBlended: visibleCoplanarOverlaps
            .filter((overlap) => overlap.direction === "same")
            .map((overlap) => ({
                area: Number(overlap.area.toFixed(6)),
                faces: [
                    `${overlap.left.cubeName}.${overlap.left.faceName}`,
                    `${overlap.right.cubeName}.${overlap.right.faceName}`,
                ],
            })),
        ...blendResult,
    };
    output.text(
        "reports/handheld_cannon_geometry.json",
        `${JSON.stringify(geometryReport, null, 2)}\n`,
    );

    // sync to bbmodel export
    try {
        for (const [slot, transform] of Object.entries(DISPLAY)) {
            const displaySlot = Project.display_settings[slot] || new DisplaySlot();
            if (transform.rotation) displaySlot.rotation = transform.rotation.slice();
            if (transform.translation) displaySlot.translation = transform.translation.slice();
            if (transform.scale) displaySlot.scale = transform.scale.slice();
            Project.display_settings[slot] = displaySlot;
        }
    } catch (error) {
        log(`could not stage display settings for the bbmodel export: ${error.message}`);
    }
    try {
        output.text("handheld_cannon.bbmodel", Codecs.project.compile());
    } catch (error) {
        log(`bbmodel export failed: ${error.message}`);
    }

    Preview.selected.loadAnglePreset(DefaultCameraPresets[1]);
    Preview.selected.controls.target.set(8, 8, 3);
    Preview.selected.controls.update();
    Preview.selected.render();
    await output.preview("previews/handheld_cannon_iso.png", { crop: true, height: 512, width: 512 });

    Preview.selected.camera.position.set(60, 12, 3);
    Preview.selected.controls.target.set(8, 8, 3);
    Preview.selected.controls.update();
    Preview.selected.render();
    await output.preview("previews/handheld_cannon_side.png", { crop: true, height: 512, width: 512 });

    Preview.selected.camera.position.set(8, 11, -60);
    Preview.selected.controls.target.set(8, 8, 3);
    Preview.selected.controls.update();
    Preview.selected.render();
    await output.preview("previews/handheld_cannon_front.png", { crop: true, height: 512, width: 512 });

    // Render the item exactly as the GUI slot sees it.
    try {
        Modes.options.display.select();
        DisplayMode.loadGUI();
        // The default GUI display camera renders the icon at real GUI pixel
        // size; zoom in so the preview screenshot is actually inspectable.
        Preview.selected.camera.zoom = 5;
        Preview.selected.camera.updateProjectionMatrix();
        Canvas.updateAll();
        Preview.selected.render();
        await output.preview("previews/handheld_cannon_gui.png", { crop: false, height: 512, width: 512 });
        Modes.options.edit.select();
    } catch (error) {
        log(`gui display preview failed: ${error.message}`);
    }

    log(
        `Built ${Cube.all.length} cubes; removed ${occludedFaces.length} internal faces; ` +
        `blended ${blendResult.blendedPixels} coplanar texels`,
    );
    return {
        blendedPixels: blendResult.blendedPixels,
        cubes: Cube.all.length,
        removedFaces: occludedFaces.length,
    };
};

// Node-side tests can exercise the geometry logic without launching Blockbench.
module.exports.geometry = {
    describeGeometry,
    faceCoordinates,
    facePoint,
    findCoplanarOverlaps,
    findOccludedFaces,
    pointInsideCuboid,
};
