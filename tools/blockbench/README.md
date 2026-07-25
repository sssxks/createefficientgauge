# Blockbench batch runner

This tool launches an isolated Blockbench desktop process, evaluates one
procedural JavaScript job in Blockbench's renderer, collects the files emitted
by that job, and shuts Blockbench down. It uses Electron's local Chrome DevTools
Protocol endpoint. It does not require an MCP server or a Blockbench plugin.

The runner is non-interactive but not headless: the Blockbench window may be
visible briefly while a job runs.

## Write a job

A job is a CommonJS file exporting a function. It executes in Blockbench's
renderer and can directly use Blockbench globals:

```js
module.exports = async function build({ output, log, args }) {
    newProject("java_block");

    const cube = new Cube({
        name: "example",
        from: [0, 0, 0],
        to: [16, 16, 16],
    }).init();

    // Build textures and assign face UVs with the Blockbench API.
    Canvas.updateAll();

    output.model("models/item/example.json");
    await output.preview("previews/example.png");
    log(`Generated ${Cube.all.length} cube`);

    return { cube: cube.name, arguments: args };
};
```