# Create Mod Suite

This Gradle multi-project build contains independently distributable NeoForge
client mods and their development tools.

## Projects

- [Create Efficient Gauge](mods/create-efficient-gauge/README.md) retains
  Create factory-gauge rendering through Flywheel.
- [Minecraft Frame Profiler](mods/minecraft-frame-profiler/README.md) records a
  bounded rolling JFR window for diagnosing slow client frames.
- `tools/jfr-analyzer` analyzes recordings produced by the profiler.
- `tools/sparkprofile` is a zero-dependency agent/debugging reader for raw
  `.sparkprofile` files.

The mods do not depend on each other. The integration client run loads both for
development convenience.

## Build

```powershell
.\gradlew.bat build
```

Individual artifacts can be built with:

```powershell
.\gradlew.bat :mods:create-efficient-gauge:build
.\gradlew.bat :mods:minecraft-frame-profiler:build
```

Each mod jar is written to its own `build/libs` directory.
