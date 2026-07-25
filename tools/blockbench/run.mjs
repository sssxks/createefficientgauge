#!/usr/bin/env node

import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_TIMEOUT_SECONDS = 120;
const CDP_HOST = "127.0.0.1";

export const USAGE = `Usage:
  node tools/blockbench/run.mjs <job.bb.js> --out <directory> [options] [-- <job arguments>]

Options:
  --blockbench <path>  Path to the Blockbench executable.
                       Defaults to BLOCKBENCH_EXE, PATH, then common install paths.
  --timeout <seconds>  Whole-job timeout. Default: ${DEFAULT_TIMEOUT_SECONDS}.
  --keep-profile       Keep the temporary Blockbench user-data profile after success.
  -h, --help           Show this help.

The job must export an async function with CommonJS:

  module.exports = async ({ output, log, args, outDir, jobPath }) => {
      // Build with Blockbench globals such as Cube, Texture, and Codecs.
  };
`;

export function parseArgs(argv) {
  const options = {
    blockbench: undefined,
    help: false,
    jobArgs: [],
    jobPath: undefined,
    keepProfile: false,
    outDir: undefined,
    timeoutMs: DEFAULT_TIMEOUT_SECONDS * 1_000,
  };
  const positionals = [];

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--") {
      options.jobArgs = argv.slice(index + 1);
      break;
    }
    if (argument === "-h" || argument === "--help") {
      options.help = true;
      continue;
    }
    if (argument === "--keep-profile") {
      options.keepProfile = true;
      continue;
    }
    if (
      argument === "--out" ||
      argument === "--blockbench" ||
      argument === "--timeout"
    ) {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith("--")) {
        throw new Error(`${argument} requires a value`);
      }
      index += 1;
      if (argument === "--out") {
        options.outDir = value;
      } else if (argument === "--blockbench") {
        options.blockbench = value;
      } else {
        const seconds = Number(value);
        if (!Number.isFinite(seconds) || seconds <= 0) {
          throw new Error("--timeout must be a positive number of seconds");
        }
        options.timeoutMs = Math.round(seconds * 1_000);
      }
      continue;
    }
    if (argument.startsWith("-")) {
      throw new Error(`unknown option: ${argument}`);
    }
    positionals.push(argument);
  }

  if (options.help) {
    return options;
  }
  if (positionals.length !== 1) {
    throw new Error("exactly one Blockbench job file is required");
  }
  if (!options.outDir) {
    throw new Error("--out is required");
  }
  options.jobPath = path.resolve(positionals[0]);
  options.outDir = path.resolve(options.outDir);
  if (options.blockbench) {
    options.blockbench = path.resolve(options.blockbench);
  }
  return options;
}

function executableNames() {
  if (process.platform === "win32") {
    return ["Blockbench.exe", "blockbench.exe"];
  }
  return ["blockbench", "Blockbench"];
}

export function blockbenchCandidates(explicitPath, environment = process.env) {
  const candidates = [];
  const add = (candidate) => {
    if (candidate && !candidates.includes(candidate)) {
      candidates.push(candidate);
    }
  };

  add(explicitPath);
  add(environment.BLOCKBENCH_EXE);

  for (const directory of (environment.PATH ?? "")
    .split(path.delimiter)
    .filter(Boolean)) {
    for (const name of executableNames()) {
      add(path.join(directory.replace(/^"(.*)"$/, "$1"), name));
    }
  }

  if (process.platform === "win32") {
    const localAppData = environment.LOCALAPPDATA;
    const programFiles = environment.ProgramFiles;
    if (localAppData) {
      add(path.join(localAppData, "Programs", "Blockbench", "Blockbench.exe"));
      add(path.join(localAppData, "Blockbench", "Blockbench.exe"));
    }
    if (programFiles) {
      add(path.join(programFiles, "Blockbench", "Blockbench.exe"));
    }
  } else if (process.platform === "darwin") {
    add("/Applications/Blockbench.app/Contents/MacOS/Blockbench");
    add(
      path.join(
        os.homedir(),
        "Applications",
        "Blockbench.app",
        "Contents",
        "MacOS",
        "Blockbench",
      ),
    );
  } else {
    add("/usr/bin/blockbench");
    add("/usr/local/bin/blockbench");
    add("/opt/Blockbench/blockbench");
  }

  return candidates.map((candidate) => path.resolve(candidate));
}

export function findBlockbenchExecutable(
  explicitPath,
  environment = process.env,
) {
  const candidates = blockbenchCandidates(explicitPath, environment);
  const executable = candidates.find((candidate) => existsSync(candidate));
  if (executable) {
    return executable;
  }

  const requested = explicitPath ?? environment.BLOCKBENCH_EXE;
  if (requested) {
    throw new Error(
      `Blockbench executable does not exist: ${path.resolve(requested)}`,
    );
  }
  throw new Error(
    "Blockbench was not found. Install it, set BLOCKBENCH_EXE, or pass --blockbench <path>.",
  );
}

export async function reserveLocalPort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, CDP_HOST, resolve);
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    server.close();
    throw new Error("could not reserve a local debugging port");
  }
  await new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
  return address.port;
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function remainingTime(deadline) {
  return Math.max(1, deadline - Date.now());
}

async function waitForRenderer(
  child,
  port,
  deadline,
  getLaunchError = () => undefined,
) {
  const endpoint = `http://${CDP_HOST}:${port}/json/list`;
  let lastError;

  while (Date.now() < deadline) {
    const launchError = getLaunchError();
    if (launchError) {
      throw launchError;
    }
    if (child.exitCode !== null) {
      throw new Error(
        `Blockbench exited before its renderer was ready (exit code ${child.exitCode})`,
      );
    }
    try {
      const response = await fetch(endpoint, {
        signal: AbortSignal.timeout(Math.min(1_000, remainingTime(deadline))),
      });
      if (response.ok) {
        const targets = await response.json();
        const target =
          targets.find(
            (candidate) =>
              candidate.type === "page" &&
              typeof candidate.webSocketDebuggerUrl === "string" &&
              (candidate.url?.includes("index.html") ||
                candidate.title?.toLowerCase().includes("blockbench")),
          ) ??
          targets.find(
            (candidate) =>
              candidate.type === "page" &&
              typeof candidate.webSocketDebuggerUrl === "string",
          );
        if (target) {
          return target;
        }
      }
    } catch (error) {
      lastError = error;
    }
    await delay(100);
  }

  const detail = lastError?.message
    ? ` Last connection error: ${lastError.message}`
    : "";
  throw new Error(`timed out waiting for the Blockbench renderer.${detail}`);
}

export class CdpClient {
  constructor(url, timeoutMs) {
    if (typeof WebSocket === "undefined") {
      throw new Error(
        "this runner requires a Node.js release with the WebSocket global",
      );
    }
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Set();
    this.timeoutMs = timeoutMs;
    this.webSocket = new WebSocket(url);
  }

  async connect() {
    if (this.webSocket.readyState === WebSocket.OPEN) {
      return;
    }
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup();
        reject(
          new Error("timed out connecting to Blockbench's debugging endpoint"),
        );
      }, this.timeoutMs);
      const onOpen = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(
          new Error("failed to connect to Blockbench's debugging endpoint"),
        );
      };
      const onClose = () => {
        cleanup();
        reject(
          new Error(
            "Blockbench closed its debugging endpoint before connection",
          ),
        );
      };
      const cleanup = () => {
        clearTimeout(timer);
        this.webSocket.removeEventListener("open", onOpen);
        this.webSocket.removeEventListener("error", onError);
        this.webSocket.removeEventListener("close", onClose);
      };
      this.webSocket.addEventListener("open", onOpen, { once: true });
      this.webSocket.addEventListener("error", onError, { once: true });
      this.webSocket.addEventListener("close", onClose, { once: true });
    });

    this.webSocket.addEventListener("message", (event) => {
      const message = JSON.parse(
        typeof event.data === "string"
          ? event.data
          : Buffer.from(event.data).toString("utf8"),
      );
      if (message.id !== undefined) {
        const pending = this.pending.get(message.id);
        if (!pending) {
          return;
        }
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        if (message.error) {
          pending.reject(
            new Error(`${message.error.message} (${message.error.code})`),
          );
        } else {
          pending.resolve(message.result);
        }
        return;
      }
      for (const listener of this.listeners) {
        listener(message);
      }
    });

    const rejectPending = () => {
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error("Blockbench closed the debugging connection"));
      }
      this.pending.clear();
    };
    this.webSocket.addEventListener("close", rejectPending, { once: true });
    this.webSocket.addEventListener("error", rejectPending);
  }

  onEvent(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  send(method, params = {}, timeoutMs = this.timeoutMs) {
    if (this.webSocket.readyState !== WebSocket.OPEN) {
      return Promise.reject(
        new Error("the Blockbench debugging connection is not open"),
      );
    }
    const id = this.nextId;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`timed out waiting for CDP method ${method}`));
      }, timeoutMs);
      this.pending.set(id, { reject, resolve, timer });
      this.webSocket.send(JSON.stringify({ id, method, params }));
    });
  }

  close() {
    if (
      this.webSocket.readyState === WebSocket.OPEN ||
      this.webSocket.readyState === WebSocket.CONNECTING
    ) {
      this.webSocket.close();
    }
  }
}

function blockbenchJobBootstrap(source, jobPath, outDir, jobArgs) {
  return (async () => {
    const startedAt = Date.now();
    const artifacts = [];

    function recordArtifact(relativePath, data, encoding, kind) {
      if (typeof relativePath !== "string" || relativePath.length === 0) {
        throw new TypeError("artifact path must be a non-empty string");
      }
      if (typeof data !== "string") {
        throw new TypeError("artifact data must be a string");
      }
      const artifact = { data, encoding, kind, path: relativePath };
      artifacts.push(artifact);
      return artifact;
    }

    const output = {
      dataUrl(relativePath, dataUrl) {
        return recordArtifact(relativePath, dataUrl, "data-url", "binary");
      },

      model(relativePath, contents = Codecs.java_block.compile()) {
        const serialized =
          typeof contents === "string"
            ? contents
            : `${JSON.stringify(contents, null, 2)}\n`;
        return recordArtifact(relativePath, serialized, "utf8", "model");
      },

      async preview(
        relativePath,
        options = { crop: true, height: 512, width: 512 },
      ) {
        if (!Preview.selected) {
          throw new Error("Blockbench has no selected preview");
        }
        const dataUrl = await new Promise((resolve, reject) => {
          const timer = setTimeout(
            () =>
              reject(new Error("timed out capturing the Blockbench preview")),
            30_000,
          );
          Preview.selected.screenshot(options, (result) => {
            clearTimeout(timer);
            resolve(result);
          });
        });
        return recordArtifact(relativePath, dataUrl, "data-url", "preview");
      },

      text(relativePath, contents) {
        return recordArtifact(relativePath, String(contents), "utf8", "text");
      },

      texture(relativePath, texture = Texture.getDefault()) {
        if (!texture || typeof texture.getDataURL !== "function") {
          throw new TypeError("output.texture expects a Blockbench Texture");
        }
        return recordArtifact(
          relativePath,
          texture.getDataURL(),
          "data-url",
          "texture",
        );
      },
    };

    const module = { exports: {} };
    const sourceUrl = jobPath.replaceAll("\\", "/");
    const factory = new Function(
      "module",
      "exports",
      "__filename",
      "__dirname",
      `${source}\n//# sourceURL=${sourceUrl}`,
    );
    factory.call(
      globalThis,
      module,
      module.exports,
      jobPath,
      jobPath.replace(/[\\/][^\\/]*$/, ""),
    );

    const build = module.exports?.default ?? module.exports;
    if (typeof build !== "function") {
      throw new TypeError(
        "the Blockbench job must export a function with module.exports",
      );
    }

    const log = (...values) => console.log("[blockbench-batch]", ...values);
    const result = await build({
      args: jobArgs,
      jobPath,
      log,
      outDir,
      output,
    });

    let serializableResult = null;
    if (result !== undefined) {
      try {
        serializableResult = JSON.parse(JSON.stringify(result));
      } catch {
        serializableResult = String(result);
      }
    }

    return {
      artifacts,
      blockbenchVersion: Blockbench.version ?? "unknown",
      durationMs: Date.now() - startedAt,
      result: serializableResult,
    };
  })();
}

export function buildJobExpression(source, jobPath, outDir, jobArgs = []) {
  return `(${blockbenchJobBootstrap.toString()})(${[
    source,
    jobPath,
    outDir,
    jobArgs,
  ]
    .map((value) => JSON.stringify(value))
    .join(", ")})`;
}

function decodeDataUrl(dataUrl) {
  if (typeof dataUrl !== "string") {
    throw new TypeError("expected an image data URL");
  }
  const match = /^data:([^;,]+)?(;base64)?,([\s\S]*)$/.exec(dataUrl);
  if (!match) {
    throw new Error("invalid data URL returned by Blockbench");
  }
  return match[2]
    ? Buffer.from(match[3], "base64")
    : Buffer.from(decodeURIComponent(match[3]), "utf8");
}

function resolveArtifact(outDir, relativePath) {
  if (typeof relativePath !== "string" || relativePath.length === 0) {
    throw new TypeError("artifact path must be a non-empty string");
  }
  if (path.isAbsolute(relativePath)) {
    throw new Error(`artifact path must be relative: ${relativePath}`);
  }
  const target = path.resolve(outDir, relativePath);
  const relation = path.relative(outDir, target);
  if (
    relation === ".." ||
    relation.startsWith(`..${path.sep}`) ||
    path.isAbsolute(relation)
  ) {
    throw new Error(
      `artifact path escapes the output directory: ${relativePath}`,
    );
  }
  return {
    relativePath: relation.split(path.sep).join("/"),
    target,
  };
}

export async function writeArtifacts(outDir, artifacts) {
  const pending = artifacts.map((artifact) => {
    const { relativePath, target } = resolveArtifact(outDir, artifact.path);
    let contents;
    if (artifact.encoding === "utf8") {
      contents = Buffer.from(artifact.data, "utf8");
    } else if (artifact.encoding === "data-url") {
      contents = decodeDataUrl(artifact.data);
    } else {
      throw new Error(`unsupported artifact encoding: ${artifact.encoding}`);
    }
    return { artifact, contents, relativePath, target };
  });

  const summaries = [];
  for (const item of pending) {
    await mkdir(path.dirname(item.target), { recursive: true });
    await writeFile(item.target, item.contents);
    summaries.push({
      bytes: item.contents.byteLength,
      kind: item.artifact.kind,
      path: item.relativePath,
    });
  }
  return summaries;
}

async function evaluate(client, expression, timeoutMs) {
  const response = await client.send(
    "Runtime.evaluate",
    {
      awaitPromise: true,
      expression,
      returnByValue: true,
      timeout: timeoutMs,
      userGesture: true,
    },
    timeoutMs + 1_000,
  );

  if (response.exceptionDetails) {
    const description =
      response.exceptionDetails.exception?.description ??
      response.exceptionDetails.text ??
      "unknown Blockbench evaluation error";
    throw new Error(description);
  }
  return response.result?.value;
}

async function waitForBlockbenchApi(client, deadline) {
  const readinessExpression = `Boolean(
        typeof Blockbench !== "undefined"
        && typeof Cube !== "undefined"
        && typeof Texture !== "undefined"
        && typeof Codecs?.java_block?.compile === "function"
        && typeof Preview !== "undefined"
        && typeof newProject === "function"
    )`;

  while (Date.now() < deadline) {
    try {
      if (
        await evaluate(
          client,
          readinessExpression,
          Math.min(2_000, remainingTime(deadline)),
        )
      ) {
        return;
      }
    } catch {
      // The renderer may exist before Blockbench finishes initializing.
    }
    await delay(100);
  }
  throw new Error("timed out waiting for the Blockbench Java model API");
}

function formatConsoleArgument(argument) {
  if (Object.hasOwn(argument, "value")) {
    return typeof argument.value === "string"
      ? argument.value
      : JSON.stringify(argument.value);
  }
  return argument.description ?? argument.type ?? "";
}

function observeBlockbenchConsole(client) {
  return client.onEvent((message) => {
    if (message.method === "Runtime.consoleAPICalled") {
      const type = message.params.type;
      const text = message.params.args.map(formatConsoleArgument).join(" ");
      const writer =
        type === "error" || type === "warning" ? console.error : console.log;
      writer(`blockbench: ${text}`);
    }
  });
}

function waitForChildExit(child, timeoutMs) {
  if (child.exitCode !== null) {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      cleanup();
      resolve(false);
    }, timeoutMs);
    const onExit = () => {
      cleanup();
      resolve(true);
    };
    const cleanup = () => {
      clearTimeout(timer);
      child.removeListener("exit", onExit);
    };
    child.once("exit", onExit);
  });
}

async function stopBlockbench(child, client) {
  if (!child || child.exitCode !== null) {
    return;
  }

  if (client) {
    try {
      await evaluate(
        client,
        `(() => {
                    // we have already captured the generated artifacts.
                    // Bypass Blockbench's interactive unsaved-project guard.
                    Blockbench.addFlag("allow_closing");

                    setTimeout(() => {
                        window.close();
                    }, 50);

                    return true;
                })()`,
        2_000,
      );
    } catch {
      // Closing the window may terminate CDP before Runtime.evaluate replies.
    }
  }

  if (!(await waitForChildExit(child, 5_000))) {
    child.kill();
    await waitForChildExit(child, 5_000);
  }
}
async function runJob(options) {
  if (!existsSync(options.jobPath)) {
    throw new Error(`Blockbench job does not exist: ${options.jobPath}`);
  }

  const executable = findBlockbenchExecutable(options.blockbench);
  const source = await readFile(options.jobPath, "utf8");
  const profileDirectory = await mkdtemp(
    path.join(os.tmpdir(), "blockbench-batch-"),
  );
  const logPath = path.join(profileDirectory, "blockbench.log");
  const port = await reserveLocalPort();
  const deadline = Date.now() + options.timeoutMs;
  let child;
  let client;
  let removeConsoleObserver;
  let succeeded = false;

  try {
    console.log(`Starting Blockbench: ${executable}`);
    child = spawn(
      executable,
      [
        "--userData",
        profileDirectory,
        `--remote-debugging-address=${CDP_HOST}`,
        `--remote-debugging-port=${port}`,
        "--enable-logging=file",
        `--log-file=${logPath}`,
      ],
      {
        stdio: ["ignore", "pipe", "pipe"],
        windowsHide: false,
      },
    );

    let launchError;
    child.once("error", (error) => {
      launchError = error;
    });
    child.stdout?.on("data", (chunk) =>
      process.stdout.write(`blockbench: ${chunk}`),
    );
    child.stderr?.on("data", (chunk) =>
      process.stderr.write(`blockbench: ${chunk}`),
    );

    const target = await waitForRenderer(
      child,
      port,
      deadline,
      () => launchError,
    );
    client = new CdpClient(
      target.webSocketDebuggerUrl,
      remainingTime(deadline),
    );
    await client.connect();
    await client.send(
      "Runtime.enable",
      {},
      Math.min(5_000, remainingTime(deadline)),
    );
    removeConsoleObserver = observeBlockbenchConsole(client);
    await waitForBlockbenchApi(client, deadline);

    console.log(`Running job: ${options.jobPath}`);
    const result = await evaluate(
      client,
      buildJobExpression(
        source,
        options.jobPath,
        options.outDir,
        options.jobArgs,
      ),
      remainingTime(deadline),
    );
    if (!result || !Array.isArray(result.artifacts)) {
      throw new Error("the Blockbench job returned an invalid result");
    }
    result.artifacts = await writeArtifacts(options.outDir, result.artifacts);

    console.log(
      `Blockbench ${result.blockbenchVersion}; ${result.artifacts.length} artifact(s); ${result.durationMs} ms`,
    );
    for (const artifact of result.artifacts) {
      console.log(
        `  ${artifact.path} (${artifact.kind}, ${artifact.bytes} bytes)`,
      );
    }
    if (result.result !== null) {
      console.log(`Result: ${JSON.stringify(result.result)}`);
    }
    succeeded = true;
    return result;
  } finally {
    removeConsoleObserver?.();
    await stopBlockbench(child, client);
    client?.close();
    if (succeeded && !options.keepProfile) {
      await rm(profileDirectory, {
        force: true,
        maxRetries: 3,
        recursive: true,
        retryDelay: 100,
      }).catch(() => {});
    } else {
      console.error(`Blockbench profile preserved at: ${profileDirectory}`);
    }
  }
}

export async function main(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    console.error(`Error: ${error.message}\n`);
    console.error(USAGE);
    return 2;
  }

  if (options.help) {
    console.log(USAGE);
    return 0;
  }

  try {
    await runJob(options);
    return 0;
  } catch (error) {
    console.error(error?.stack ?? String(error));
    return 1;
  }
}

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  process.exitCode = await main();
}
