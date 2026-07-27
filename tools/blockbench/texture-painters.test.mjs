import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const {
    metalPainter,
    noise,
    shade,
} = require("./texture-painters.cjs");

test("shade adjusts and clamps RGB channels", () => {
    assert.equal(shade("#102030", 16), "rgb(32,48,64)");
    assert.equal(shade("#f8fcff", 20), "rgb(255,255,255)");
    assert.equal(shade("#04080c", -20), "rgb(0,0,0)");
});

test("noise is deterministic and bounded", () => {
    const first = noise(12, 34, 56);
    assert.equal(noise(12, 34, 56), first);
    assert.ok(first >= -1);
    assert.ok(first <= 1);
});

test("metal painter fills every pixel before painting its bevel", () => {
    const fills = [];
    const context = {
        fillStyle: "",
        fillRect: (...rectangle) => {
            fills.push({ color: context.fillStyle, rectangle });
        },
    };

    metalPainter("#808080", 4, 3)(context, 1, 2, 3, 2);

    assert.equal(fills.length, 10);
    assert.deepEqual(
        fills.slice(0, 6).map(({ rectangle }) => rectangle),
        [
            [1, 2, 1, 1],
            [2, 2, 1, 1],
            [3, 2, 1, 1],
            [1, 3, 1, 1],
            [2, 3, 1, 1],
            [3, 3, 1, 1],
        ],
    );
});
