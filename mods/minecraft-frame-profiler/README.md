# Minecraft Frame Profiler

Independent client-side NeoForge 1.21.1 mod for diagnosing Minecraft frame
pacing with a bounded rolling JDK Flight Recorder session. It has no Create,
Flywheel, or Create Efficient Gauge dependency.

The profiler records exact slow invocations of:

- `Minecraft.runTick`, the complete client frame;
- `GameRenderer.render`, CPU-side scene rendering;
- `Window.updateDisplay`, including buffer swap/presentation; and
- `Minecraft.tick`, the client game tick.

Java and native stack samples can then be correlated by timestamp with those
frame intervals. Allocation sampling is disabled to limit recording volume.

## Runtime requirement

The mod targets Java 21 bytecode and is safe to install on Java 21, but targeted
`jdk.MethodTrace` recording requires Java 25 or newer. If profiling is enabled
on an older runtime, the mod logs a warning and does not start a recording.

## Enable recording

Enable it in `.minecraft/config/minecraftframeprofiler-client.toml`:

```toml
[recording]
enabled = true
```

Alternatively, add this JVM argument to the launcher profile:

```text
-Dminecraftframeprofiler.enabled=true
```

The legacy `-Dcreateefficientgauge.jfr=true` property is accepted for one
compatibility release and logs a deprecation warning.

By default, the profiler retains a rolling ten-minute/128 MiB window, writes a
snapshot every ten minutes, keeps at most six completed files, and writes the
final window when Minecraft exits. Recordings are stored in:

```text
.minecraft/debug/profiling/minecraftframeprofiler/
```

Sampling, thresholds, rolling duration, snapshot interval, size, and file
retention are configurable under the same `[recording]` section.

## Build and run

From the repository root:

```powershell
.\gradlew.bat :mods:minecraft-frame-profiler:build
.\gradlew.bat :mods:minecraft-frame-profiler:runClient
```

The jar is written to `mods/minecraft-frame-profiler/build/libs`.

To develop it alongside Create Efficient Gauge:

```powershell
.\gradlew.bat :mods:create-efficient-gauge:runIntegrationClient
```

The integration run is a development convenience; neither production mod
declares a dependency on the other.

## Analyze a recording

The analyzer ignores the first two seconds of MethodTrace instrumentation
warm-up, lists the longest traced frames, separates render/presentation/tick
time, and prints Java/native render-thread samples for useful outliers.

Run it from the repository root:

```powershell
.\gradlew.bat :tools:jfr-analyzer:run `
  --args='path\to\slowframes-session-YYYY-MM-DD_HH-mm-ss.jfr'
```

Because the default method threshold is 10 ms, the percentile summary describes
traced slow frames, not every frame rendered during the session.
