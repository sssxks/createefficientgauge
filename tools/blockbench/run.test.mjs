import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

import {
    blockbenchCandidates,
    buildJobExpression,
    findBlockbenchExecutable,
    parseArgs,
    reserveLocalPort,
    writeArtifacts,
} from "./run.mjs";

test("parseArgs resolves the job, output, timeout, and forwarded arguments", () => {
    const options = parseArgs([
        "art/example.bb.js",
        "--out",
        "generated/assets",
        "--timeout",
        "2.5",
        "--",
        "--palette",
        "brass",
    ]);

    assert.equal(options.jobPath, path.resolve("art/example.bb.js"));
    assert.equal(options.outDir, path.resolve("generated/assets"));
    assert.equal(options.timeoutMs, 2_500);
    assert.deepEqual(options.jobArgs, ["--palette", "brass"]);
});

test("parseArgs rejects missing and unknown options", () => {
    assert.throws(() => parseArgs(["job.bb.js"]), /--out is required/);
    assert.throws(
        () => parseArgs(["job.bb.js", "--out", "out", "--wat"]),
        /unknown option/,
    );
    assert.throws(
        () => parseArgs(["job.bb.js", "--out", "out", "--timeout", "zero"]),
        /positive number/,
    );
});

test("findBlockbenchExecutable honors an explicit executable", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "blockbench-runner-test-"));
    const executable = path.join(directory, process.platform === "win32" ? "Blockbench.exe" : "blockbench");
    await writeFile(executable, "");

    try {
        assert.equal(findBlockbenchExecutable(executable, { PATH: "" }), executable);
        assert.equal(blockbenchCandidates(executable, { PATH: "" })[0], executable);
    } finally {
        await rm(directory, { force: true, recursive: true });
    }
});

test("buildJobExpression safely embeds source code, paths, and arguments", () => {
    const source = "module.exports = async () => `backtick ${value}`;\n// ütf8";
    const expression = buildJobExpression(
        source,
        "C:\\models\\quoted \"job\".bb.js",
        "C:\\generated assets",
        ["one", "two"],
    );

    assert.match(expression, /blockbenchJobBootstrap/);
    assert.ok(expression.includes(JSON.stringify(source)));
    assert.ok(expression.includes(JSON.stringify(["one", "two"])));
});

test("the embedded job runtime writes only declared artifacts under outDir", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "blockbench-runtime-test-"));
    const jobPath = path.join(directory, "job.bb.js");
    const outDir = path.join(directory, "generated");
    const source = `
        module.exports = async ({ output, args }) => {
            output.text("nested/result.txt", "hello");
            output.model("model.json");
            return { args };
        };
    `;
    const context = {
        Blockbench: { version: "test" },
        Buffer,
        Codecs: {
            java_block: {
                compile: () => '{"elements":[]}',
            },
        },
        Preview: {},
        Texture: {},
        console,
        setTimeout,
    };

    try {
        const result = await vm.runInNewContext(
            buildJobExpression(source, jobPath, outDir, ["argument"]),
            context,
        );
        const summaries = await writeArtifacts(outDir, result.artifacts);
        assert.deepEqual(
            summaries.map((artifact) => artifact.path),
            ["nested/result.txt", "model.json"],
        );
        assert.equal(
            await readFile(path.join(outDir, "nested", "result.txt"), "utf8"),
            "hello",
        );
        assert.equal(result.blockbenchVersion, "test");
        assert.deepEqual(Array.from(result.result.args), ["argument"]);
    } finally {
        await rm(directory, { force: true, recursive: true });
    }
});

test("the embedded job runtime rejects output path traversal", async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), "blockbench-runtime-test-"));
    const source = `
        module.exports = async ({ output }) => {
            output.text("../escaped.txt", "no");
        };
    `;
    const context = {
        Blockbench: { version: "test" },
        Buffer,
        Codecs: { java_block: { compile: () => "{}" } },
        Preview: {},
        Texture: {},
        console,
        setTimeout,
    };

    try {
        const result = await vm.runInNewContext(
            buildJobExpression(
                source,
                path.join(directory, "job.bb.js"),
                path.join(directory, "generated"),
            ),
            context,
        );
        await assert.rejects(
            writeArtifacts(path.join(directory, "generated"), result.artifacts),
            /escapes the output directory/,
        );
        assert.equal(existsSync(path.join(directory, "escaped.txt")), false);
    } finally {
        await rm(directory, { force: true, recursive: true });
    }
});

test("reserveLocalPort returns a usable TCP port number", async () => {
    const port = await reserveLocalPort();
    assert.ok(Number.isInteger(port));
    assert.ok(port > 0 && port <= 65_535);
});
