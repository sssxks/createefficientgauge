# Blockbench batch runner

This tool launches an isolated Blockbench desktop process, evaluates one
procedural JavaScript job in Blockbench's renderer, collects the files emitted
by that job, and shuts Blockbench down. It uses Electron's local Chrome DevTools
Protocol endpoint. It does not require an MCP server or a Blockbench plugin.

The runner is non-interactive but not headless: the Blockbench window may be
visible briefly while a job runs.

## Requirements

- Node.js 22 or newer, including the built-in `fetch` and `WebSocket` APIs.
- The Blockbench desktop application.

Set `BLOCKBENCH_EXE` if Blockbench is not installed in a standard location:

```powershell
$env:BLOCKBENCH_EXE = 'C:\Program Files\Blockbench\Blockbench.exe'
```

## Run the smoke test

The example intentionally uses an asymmetric cuboid, distinct texture
quadrants, and rotated faces so that UV direction errors are visually obvious.

```powershell
node tools/blockbench/run.mjs `
  tools/blockbench/example.bb.js `
  --out build/blockbench-batch-example
```

The command writes:

```text
build/blockbench-batch-example/
  models/item/blockbench_batch_example.json
  textures/item/blockbench_batch_example.png
  previews/blockbench_batch_example.png
```

Pass `--blockbench C:\path\to\Blockbench.exe` to override executable discovery.
The entire launch and job must finish within 120 seconds by default; use
`--timeout <seconds>` to change that limit.

Each invocation uses a new temporary Blockbench user-data profile, so it does
not attach to a normal interactive Blockbench session. Successful runs remove
the profile. Failed runs preserve it and print its path so that
`blockbench.log` can be inspected. `--keep-profile` preserves it after success
as well.

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

Arguments following `--` are passed to the job:

```powershell
node tools/blockbench/run.mjs art/item.bb.js `
  --out generated `
  -- `
  --palette brass
```

The job context contains:

- `outDir`: absolute output directory.
- `jobPath`: absolute path to the running job.
- `args`: strings following `--`.
- `log(...values)`: writes a tagged message to the CLI.
- `output.text(path, contents)`.
- `output.dataUrl(path, dataUrl)`.
- `output.model(path, contents?)`: defaults to
  `Codecs.java_block.compile()`.
- `output.texture(path, texture?)`: defaults to
  `Texture.getDefault()`.
- `output.preview(path, options?)`: captures `Preview.selected`.

Artifact paths must be relative and cannot escape `outDir`. Blockbench returns
the completed artifacts to the CLI, and the CLI writes them only after the job
finishes successfully.

Jobs are trusted JavaScript evaluated in Blockbench. The initial runner does not
bundle modules or provide Node's `require`; keep each job self-contained.

## Tests

The unit tests do not require Blockbench:

```powershell
node --test tools/blockbench/run.test.mjs
```

The example command above is the live integration test.
