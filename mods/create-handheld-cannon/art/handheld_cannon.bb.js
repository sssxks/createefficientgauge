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

const {
    borePainter,
    metalPainter,
    platePainter,
    woodPainter,
} = require("../../../tools/blockbench/texture-painters.cjs");
const {
    describeGeometry,
    findCoplanarOverlaps,
    findOccludedFaces,
} = require("../../../tools/blockbench/cuboid-geometry.cjs");
const {
    blendCoplanarFacePixels,
} = require("../../../tools/blockbench/texture-overlap.cjs");

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
