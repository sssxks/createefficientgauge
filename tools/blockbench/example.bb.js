module.exports = async function buildExample({ output, log }) {
    newProject("java_block");
    Project.name = "blockbench_batch_example";
    Project.texture_width = 16;
    Project.texture_height = 16;

    const canvas = document.createElement("canvas");
    canvas.width = 16;
    canvas.height = 16;
    const context = canvas.getContext("2d");
    context.imageSmoothingEnabled = false;
    context.fillStyle = "#263238";
    context.fillRect(0, 0, 16, 16);
    context.fillStyle = "#ffb300";
    context.fillRect(0, 0, 8, 8);
    context.fillStyle = "#29b6f6";
    context.fillRect(8, 0, 8, 8);
    context.fillStyle = "#ef5350";
    context.fillRect(0, 8, 8, 8);
    context.fillStyle = "#ab47bc";
    context.fillRect(8, 8, 8, 8);
    context.fillStyle = "#ffffff";
    context.fillRect(1, 1, 2, 2);

    const texture = new Texture({
        id: "main",
        name: "blockbench_batch_example.png",
    });
    const textureLoaded = new Promise((resolve, reject) => {
        texture.img.addEventListener("load", resolve, { once: true });
        texture.img.addEventListener("error", reject, { once: true });
    });
    texture.fromDataURL(canvas.toDataURL("image/png")).add(false);
    await textureLoaded;

    const textureFace = (uv, rotation = 0) => ({
        rotation,
        texture: texture.uuid,
        uv,
    });
    new Cube({
        name: "asymmetric_test_cube",
        from: [2, 3, 4],
        to: [14, 13, 11],
        faces: {
            north: textureFace([0, 0, 7, 10]),
            east: textureFace([7, 0, 14, 10]),
            south: textureFace([1, 1, 8, 11], 90),
            west: textureFace([8, 1, 15, 11], 270),
            up: textureFace([0, 10, 12, 16]),
            down: textureFace([4, 10, 16, 16], 180),
        },
    }).init();

    Canvas.updateAll();
    Preview.selected.loadAnglePreset(DefaultCameraPresets[1]);
    Preview.selected.controls.target.set(8, 8, 8);
    Preview.selected.controls.update();
    Preview.selected.render();

    output.model("models/item/blockbench_batch_example.json");
    output.texture("textures/item/blockbench_batch_example.png", texture);
    await output.preview("previews/blockbench_batch_example.png", {
        crop: true,
        height: 512,
        width: 512,
    });

    log(`Created ${Cube.all.length} cube and ${Texture.all.length} texture`);
    return {
        cubes: Cube.all.length,
        textures: Texture.all.length,
    };
};
