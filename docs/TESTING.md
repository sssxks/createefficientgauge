# Test and profiling guide

## Supported development target

- Minecraft 1.21.1
- NeoForge 21.1.219 or newer
- Public Create 6.0.10 release
- Flywheel 1.0.6
- Java 21

The Create Maven artifact is `6.0.10-281`; the mod reports itself to NeoForge as
Create `6.0.10`. Build number `281` is not part of the user-facing version.

## Build

```powershell
$env:JAVA_HOME = 'path-to-jdk-21'
.\gradlew.bat clean build
```

The production jar is generated under `build/libs`.

## Development client

```powershell
$env:JAVA_HOME = 'path-to-jdk-21'
.\gradlew.bat runClient
```

The checked-in wrapper scripts honor `JAVA_HOME`. Do not launch Gradle 8.12.1
on Java 25 for this project; task creation can fail before Minecraft starts.

For a disposable game directory or an automated single-player smoke test, use
the run-model properties instead of Gradle's generic `--args` replacement:

```powershell
.\gradlew.bat runClient `
  -PclientGameDirectory=build/diagnostic-run `
  -PquickPlaySingleplayer=New_World
```

Without `clientGameDirectory`, the client still uses the repository's `run/`
directory, including its `mods`, shader configuration, and saves.

The build declares Flywheel 1.0.6 as an explicit `runtimeOnly` dependency.
Ponder 1.0.82 still requests Flywheel 1.0.4 in its Maven metadata; without the
explicit runtime constraint, Gradle can launch the development client with 1.0.4
even though released Create embeds and requires 1.0.6.

To verify the resolved runtime version without starting Minecraft:

```powershell
.\gradlew.bat dependencyInsight `
  --dependency flywheel-neoforge-1.21.1 `
  --configuration runtimeClasspath
```

The selected version must be 1.0.6.

## Functional matrix

Test all of these situations because they exercise different branches:

| Case | Expected behavior |
|---|---|
| Flywheel/Colorwheel backend active | Paths, bulbs, ordinary items are instances |
| Backend disabled/unavailable | Complete original Create renderer runs |
| Ordinary JSON/baked filter item | Item is retained and rendered once per slot |
| Generated/tinted JSON item | Tint is correct and the item is retained |
| Stack-component-dependent tint | Different stacks keep different colors |
| BEWLR/custom-rendered item | Item is drawn by Create's original value-box path |
| Filter item changes | New model appears within one game tick |
| Connection added/removed | Segment instance set updates within one game tick |
| F3+T | Models rebake; no stale textures or native-memory crash |
| Chunk unload/reload | No duplicate instances and no missing gauge content |
| Gauge on wall/floor/ceiling | Slot, bulb, and path transforms match Create |

Colorwheel and Iris Flywheel Compat should not be installed together. Test one
Flywheel/Iris integration backend at a time.

## Profiling

Profile after the world and shader pack have stabilized. First-frame resource
baking is expected and should not be compared with steady-state frames.

### Automatic slow-frame JFR workflow

On JDK 25 or newer, the mod can automatically keep a bounded rolling JFR
recording for client-frame diagnosis. It traces these exact frame phases:

- `Minecraft.runTick`, which is the complete client frame;
- `GameRenderer.render`, which covers CPU-side scene rendering;
- `Window.updateDisplay`, including the GLFW buffer swap/presentation call; and
- `Minecraft.tick`, which covers the client game tick.

Java and native stack samples can then be correlated by timestamp with an exact
slow-frame interval. Allocation sampling is disabled because it adds data volume
without helping a frame-pacing investigation.

Enable it once in `.minecraft/config/createefficientgauge-client.toml`:

```toml
[slowFrameJfr]
enabled = true
```

Alternatively, add this JVM argument to the Minecraft launcher profile:

```text
-Dcreateefficientgauge.jfr=true
```

The recorder starts automatically during client setup. By default it keeps a
rolling ten-minute/128 MiB window, saves a snapshot every ten minutes, keeps at
most six completed recordings, and writes the final window when Minecraft exits.
Recordings are written under:

```text
.minecraft/debug/profiling/createefficientgauge/
```

The first two seconds after method tracing starts are instrumentation warm-up and
should be ignored. The included analyzer does that automatically:

```powershell
& "$env:JAVA_HOME\bin\java.exe" `
  tools/jfr/JfrFramePhaseAnalyzer.java `
  'path\to\slowframes-session-YYYY-MM-DD_HH-mm-ss.jfr'
```

The analyzer prints the longest traced frames, separates render, presentation,
and client-tick time, and prints Java/native render-thread samples from the most
useful outliers. Because the default method threshold is 10 ms, its percentile
summary describes traced slow frames, not all frames rendered during the session.

All retention, sampling, and threshold values can be changed in the same client
configuration. Exact `jdk.MethodTrace` events were introduced in JDK 25; on an
older runtime the mod logs a warning and leaves profiling disabled.

With a backend active, a successful profile should show little or no steady
state time in:

- `FactoryPanelRenderer.renderPath`
- `CachedBuffers.partial`
- `SodiumByteBuffer.renderInto`
- `IrisSimpleBakedItemRenderer.renderQuadList` for supported gauge items

Some `ItemRenderer` time is expected for deliberately unsupported items.

Capture these when reporting a problem:

1. `run/logs/latest.log` or the modpack's `logs/latest.log`.
2. The crash report, if present.
3. Create, Flywheel, Colorwheel, Iris, CreateBetterFPS, and Flerovium versions.
4. A screenshot showing the affected gauge orientation and filter item.
5. A profiler tree from a stable frame.
