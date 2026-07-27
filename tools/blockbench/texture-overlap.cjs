"use strict";

const {
    GEOMETRY_EPSILON,
    faceCoordinates,
    facePoint,
} = require("./cuboid-geometry.cjs");

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

// Same-facing coplanar rectangles can z-fight. Make atlas texels that cover
// the same model-space point share their average color, so either depth winner
// displays the same result without changing the cuboids.
function blendCoplanarFacePixels(
    context,
    width,
    height,
    textureScale,
    overlaps,
    cubes,
) {
    const image = context.getImageData(0, 0, width, height);
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
        if (parents[left] < 0) {
            parents[left] = left;
        }
        if (parents[right] < 0) {
            parents[right] = right;
        }
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

        for (
            let y = sourceBounds.y0;
            y < sourceBounds.y1;
            y += 1
        ) {
            for (
                let x = sourceBounds.x0;
                x < sourceBounds.x1;
                x += 1
            ) {
                const textureU =
                    (x + 0.5 - sourceU0) / (sourceU1 - sourceU0);
                const textureV =
                    (y + 0.5 - sourceV0) / (sourceV1 - sourceV0);
                const [geometryU, geometryV] =
                    textureToGeometryCoordinates(
                        textureU,
                        textureV,
                        source.rotation || 0,
                    );
                const point = facePoint(
                    sourceDescription,
                    geometryU,
                    geometryV,
                );
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
                const targetX = Math.floor(
                    targetU0 +
                        targetTextureU * (targetU1 - targetU0),
                );
                const targetY = Math.floor(
                    targetV0 +
                        targetTextureV * (targetV1 - targetV0),
                );
                if (
                    targetX < targetBounds.x0 ||
                    targetX >= targetBounds.x1 ||
                    targetY < targetBounds.y0 ||
                    targetY >= targetBounds.y1
                ) {
                    continue;
                }
                unite(
                    y * width + x,
                    targetY * width + targetX,
                );
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
            Math.round(total / group.pixels.length),
        );
        for (const pixel of group.pixels) {
            const offset = pixel * 4;
            for (let channel = 0; channel < 4; channel += 1) {
                image.data[offset + channel] = average[channel];
            }
        }
        blendedPixels += group.pixels.length;
    }
    if (blendedPixels > 0) {
        context.putImageData(image, 0, 0);
    }
    return { blendedGroups: groups.size, blendedPixels };
}

module.exports = {
    blendCoplanarFacePixels,
    geometryToTextureCoordinates,
    textureToGeometryCoordinates,
};
