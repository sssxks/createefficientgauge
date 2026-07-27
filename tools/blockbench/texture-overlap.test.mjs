import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const {
    blendCoplanarFacePixels,
    geometryToTextureCoordinates,
    textureToGeometryCoordinates,
} = require("./texture-overlap.cjs");

test("texture and geometry rotation transforms are inverses", () => {
    for (const rotation of [0, 90, 180, 270, -90, 450]) {
        const texture = geometryToTextureCoordinates(0.2, 0.7, rotation);
        const geometry = textureToGeometryCoordinates(
            texture[0],
            texture[1],
            rotation,
        );
        assert.ok(Math.abs(geometry[0] - 0.2) < 1e-12);
        assert.ok(Math.abs(geometry[1] - 0.7) < 1e-12);
    }
});

test("averages atlas pixels that represent the same coplanar point", () => {
    const image = {
        data: new Uint8ClampedArray([
            100, 0, 0, 255,
            0, 100, 0, 255,
        ]),
    };
    let writtenImage;
    const context = {
        getImageData: () => image,
        putImageData: (value) => {
            writtenImage = value;
        },
    };
    const face = {
        faceName: "north",
        normal: [0, 0, -1],
        origin: [0, 0, 0],
        u: [1, 0, 0],
        v: [0, 1, 0],
    };
    const left = { ...face, cubeIndex: 0 };
    const right = { ...face, cubeIndex: 1 };
    const cubes = [
        { faces: { north: { uv: [0, 0, 1, 1] } } },
        { faces: { north: { uv: [1, 0, 2, 1] } } },
    ];

    const result = blendCoplanarFacePixels(
        context,
        2,
        1,
        1,
        [{ direction: "same", left, right }],
        cubes,
    );

    assert.deepEqual(result, { blendedGroups: 1, blendedPixels: 2 });
    assert.equal(writtenImage, image);
    assert.deepEqual(
        Array.from(image.data),
        [50, 50, 0, 255, 50, 50, 0, 255],
    );
});
