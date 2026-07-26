import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const { geometry } = require("./handheld_cannon.bb.js");

test("removes both complete faces at a cuboid seam", () => {
    const cubes = [
        { name: "left", from: [0, 0, 0], to: [1, 1, 1] },
        { name: "right", from: [1, 0, 0], to: [2, 1, 1] },
    ];

    assert.deepEqual(
        geometry.findOccludedFaces(cubes).map(({ cubeName, faceName }) =>
            `${cubeName}.${faceName}`),
        ["left.east", "right.west"],
    );
});

test("does not remove a face that is only partly covered", () => {
    const cubes = [
        { name: "large", from: [0, 0, 0], to: [1, 2, 2] },
        { name: "small", from: [1, 0, 0], to: [2, 1, 1] },
    ];

    const removed = geometry.findOccludedFaces(cubes).map(({ cubeName, faceName }) =>
        `${cubeName}.${faceName}`);
    assert.equal(removed.includes("large.east"), false);
    assert.equal(removed.includes("small.west"), true);
});

test("detects the exact area of same-direction coplanar overlap", () => {
    const cubes = [
        { name: "wide", from: [0, 0, 0], to: [2, 2, 1] },
        { name: "offset", from: [1, 1, 0], to: [3, 3, 1] },
    ];
    const overlap = geometry.findCoplanarOverlaps(geometry.describeGeometry(cubes))
        .find(({ direction, left, right }) =>
            direction === "same" &&
            left.faceName === "south" &&
            right.faceName === "south");

    assert.ok(overlap);
    assert.equal(overlap.area, 1);
});

test("handles shared faces on equally rotated cuboids", () => {
    const rotation = [22.5, 0, 0];
    const origin = [0, 0, 0];
    const cubes = [
        { name: "front", from: [0, 0, 0], to: [2, 2, 1], rotation, origin },
        { name: "back", from: [0, 0, 1], to: [2, 2, 2], rotation, origin },
    ];

    assert.deepEqual(
        geometry.findOccludedFaces(cubes).map(({ cubeName, faceName }) =>
            `${cubeName}.${faceName}`),
        ["front.south", "back.north"],
    );
});
