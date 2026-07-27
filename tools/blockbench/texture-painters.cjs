"use strict";

function hexToRgb(hex) {
    const value = parseInt(hex.slice(1), 16);
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function shade(hex, delta) {
    const [red, green, blue] = hexToRgb(hex);
    const clamp = (value) =>
        Math.max(0, Math.min(255, Math.round(value)));
    return `rgb(${clamp(red + delta)},${clamp(green + delta)},${clamp(blue + delta)})`;
}

// Deterministic per-pixel noise in [-1, 1].
function noise(x, y, seed) {
    const value =
        Math.sin(x * 127.1 + y * 311.7 + seed * 74.7) * 43758.5453;
    return (value - Math.floor(value)) * 2 - 1;
}

function paintNoise(context, x, y, width, height, base, amplitude, seed) {
    for (let pixelY = y; pixelY < y + height; pixelY += 1) {
        for (let pixelX = x; pixelX < x + width; pixelX += 1) {
            context.fillStyle = shade(
                base,
                noise(pixelX, pixelY, seed) * amplitude,
            );
            context.fillRect(pixelX, pixelY, 1, 1);
        }
    }
}

// A one-pixel bevel with lighter top/left and darker bottom/right edges.
function paintBevel(context, x, y, width, height, base) {
    if (width < 2 || height < 2) {
        return;
    }
    context.fillStyle = shade(base, 18);
    context.fillRect(x, y, width, 1);
    context.fillRect(x, y, 1, height);
    context.fillStyle = shade(base, -22);
    context.fillRect(x, y + height - 1, width, 1);
    context.fillRect(x + width - 1, y, 1, height);
}

function metalPainter(base, amplitude, seed) {
    return (context, x, y, width, height) => {
        paintNoise(
            context,
            x,
            y,
            width,
            height,
            base,
            amplitude,
            seed,
        );
        paintBevel(context, x, y, width, height, base);
    };
}

function woodPainter(base, seed) {
    return (context, x, y, width, height) => {
        paintNoise(context, x, y, width, height, base, 7, seed);
        for (let pixelX = x; pixelX < x + width; pixelX += 1) {
            const stripe = Math.sin((pixelX - x) * 1.7 + seed) > 0.35;
            if (stripe) {
                context.fillStyle = shade(
                    base,
                    -16 + noise(pixelX, 0, seed) * 5,
                );
                context.fillRect(pixelX, y, 1, height);
            }
        }
        paintBevel(context, x, y, width, height, base);
    };
}

// Raised bright frame around a recessed, darker inner panel.
function platePainter(base, dark) {
    return (context, x, y, width, height) => {
        paintNoise(context, x, y, width, height, base, 6, 3);
        context.fillStyle = shade(base, 26);
        context.fillRect(x, y, width, 1);
        context.fillRect(x, y, 1, height);
        context.fillRect(x, y + height - 1, width, 1);
        context.fillRect(x + width - 1, y, 1, height);
        context.fillStyle = shade(base, -12);
        context.fillRect(x + 1, y + 1, width - 2, 1);
        context.fillRect(x + 1, y + 1, 1, height - 2);
        context.fillRect(x + 1, y + height - 2, width - 2, 1);
        context.fillRect(x + width - 2, y + 1, 1, height - 2);
        paintNoise(
            context,
            x + 2,
            y + 2,
            width - 4,
            height - 4,
            dark,
            5,
            4,
        );
    };
}

// Metal rim with a dark rectangular opening.
function borePainter(base) {
    return (context, x, y, width, height) => {
        paintNoise(context, x, y, width, height, base, 6, 5);
        paintBevel(context, x, y, width, height, base);
        const inset = Math.max(1, Math.floor(Math.min(width, height) / 4));
        context.fillStyle = shade(base, -30);
        context.fillRect(
            x + inset - 1,
            y + inset - 1,
            width - 2 * inset + 2,
            height - 2 * inset + 2,
        );
        context.fillStyle = "#17120f";
        context.fillRect(
            x + inset,
            y + inset,
            width - 2 * inset,
            height - 2 * inset,
        );
    };
}

module.exports = {
    borePainter,
    hexToRgb,
    metalPainter,
    noise,
    paintBevel,
    paintNoise,
    platePainter,
    shade,
    woodPainter,
};
