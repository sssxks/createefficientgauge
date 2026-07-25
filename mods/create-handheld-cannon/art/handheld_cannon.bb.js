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

const BARREL_PIVOT = [8, 9.5, 7];
const BARREL_ANGLE = 22.5;
const GRIP_PIVOT = [8, 7, 11.75];
const GRIP_ANGLE = -22.5;

const CUBES = [
    // The barrel's rear end reaches well into the body casing so the tube
    // reads as seated in the frame, not floating in front of it.
    { name: "barrel", material: "steel", from: [6.5, 8.5, -4], to: [9.5, 11.5, 9.5],
      rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT },
    { name: "muzzle_ring", material: "steelDark", from: [6, 8, -6], to: [10, 12, -4],
      rotation: [BARREL_ANGLE, 0, 0], origin: BARREL_PIVOT,
      faces: { north: "bore" } },
    { name: "cradle", material: "steel", from: [6, 7, 5], to: [10, 9.5, 8] },
    { name: "cradle_step", material: "steelDark", from: [6.5, 7, 3.5], to: [9.5, 8.5, 5] },
    { name: "frame_bottom", material: "darkMetal", from: [4.75, 5.5, 5.5], to: [11.25, 7, 13] },
    { name: "front_band", material: "brassBand", from: [5.5, 5.5, 4.5], to: [10.5, 7, 5.5] },
    { name: "body", material: "brass", from: [5.5, 7, 6.5], to: [10.5, 11, 12.5] },
    { name: "plate_left", material: "brass", from: [5, 6.75, 7], to: [5.75, 11.5, 12],
      faces: { west: "plate" } },
    { name: "plate_right", material: "brass", from: [10.25, 6.75, 7], to: [11, 11.5, 12],
      faces: { east: "plate" } },
    // Side bolts: square plates centered on the side plates, rotated 45
    // degrees so they read as diamonds like on the design.
    { name: "bolt_left", material: "steelLight", from: [4.25, 7.875, 8.25], to: [5, 10.375, 10.75],
      rotation: [45, 0, 0], origin: [4.625, 9.125, 9.5] },
    { name: "bolt_right", material: "steelLight", from: [11, 7.875, 8.25], to: [11.75, 10.375, 10.75],
      rotation: [45, 0, 0], origin: [11.375, 9.125, 9.5] },
    { name: "breech", material: "darkMetal", from: [6, 7, 12.5], to: [10, 11, 14] },
    { name: "cog", material: "darkMetal", from: [4.75, 7.25, 12], to: [5.5, 10.75, 13.5] },
    { name: "cog_tooth_top", material: "darkMetal", from: [4.75, 10.75, 12.5], to: [5.5, 11.25, 13] },
    { name: "cog_tooth_bottom", material: "darkMetal", from: [4.75, 6.75, 12.5], to: [5.5, 7.25, 13] },
    { name: "cog_tooth_rear", material: "darkMetal", from: [4.75, 8.75, 13.5], to: [5.5, 9.75, 14] },
    { name: "back_plate", material: "steelLight", from: [6.5, 7.5, 14], to: [9.5, 10.5, 14.75] },
    { name: "rear_sight", material: "darkMetal", from: [7, 11, 10.5], to: [9, 11.75, 12] },
    { name: "grip", material: "wood", from: [6.5, 1.5, 10.5], to: [9.5, 7, 13],
      rotation: [GRIP_ANGLE, 0, 0], origin: GRIP_PIVOT },
    { name: "grip_cap", material: "darkMetal", from: [6, 0.75, 10.25], to: [10, 1.75, 13.25],
      rotation: [GRIP_ANGLE, 0, 0], origin: GRIP_PIVOT },
    { name: "trigger", material: "steelLight", from: [7.25, 5, 9], to: [8.75, 6.5, 9.75] },
    { name: "guard_bottom", material: "darkMetal", from: [6.75, 4.25, 8.75], to: [9.25, 5.25, 11.25] },
    { name: "guard_front", material: "darkMetal", from: [7.5, 5.25, 8.75], to: [8.5, 6, 9.5] },
];

const DISPLAY = {
    thirdperson_righthand: { rotation: [0, -15, 0], translation: [1.75, 1.75, -4] },
    thirdperson_lefthand: { rotation: [0, -15, 0], translation: [1.75, 1.75, -4] },
    firstperson_righthand: { rotation: [5, 5, 5], translation: [0.25, 5, 0.75] },
    firstperson_lefthand: { rotation: [5, 5, 5], translation: [0.25, 5, 0.75] },
    ground: { rotation: [0, 0, 90], translation: [0, -1.3, 0], scale: [0.55, 0.55, 0.55] },
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
    }

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
            const x = Math.round(Math.min(face.uv[0], face.uv[2]) * scale);
            const y = Math.round(Math.min(face.uv[1], face.uv[3]) * scale);
            const w = Math.max(1, Math.round(Math.abs(face.uv[2] - face.uv[0]) * scale));
            const h = Math.max(1, Math.round(Math.abs(face.uv[3] - face.uv[1]) * scale));
            const material = cube.faceOverrides[faceName] || cube.material;
            MATERIALS[material](ctx, x, y, w, h);
        }
    }

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

    log(`Built ${Cube.all.length} cubes`);
    return { cubes: Cube.all.length };
};
