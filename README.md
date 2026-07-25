# Create Mod Suite

This Gradle multi-project build contains independently distributable NeoForge
client mods and their development tools.

## Projects

- [Create Efficient Visuals](mods/create-efficient-visuals/README.md) retains
  selected Create visuals through Flywheel and backports chunk-baked rendering
  for vanilla beds, signs, and decorated pots.
- [Minecraft Frame Profiler](mods/minecraft-frame-profiler/README.md) records a
  bounded rolling JFR window for diagnosing slow client frames.
- `tools/jfr-analyzer` analyzes recordings produced by the profiler.
- `tools/sparkprofile` is a zero-dependency agent/debugging reader for raw
  `.sparkprofile` files.
- [`tools/blockbench`](tools/blockbench/README.md) runs procedural
  model-design jobs in an isolated Blockbench desktop process and exports
  models, textures, and previews in one non-interactive build.

The mods do not depend on each other. The integration client run loads both for
development convenience.

## Build

```powershell
.\gradlew.bat build
```

Individual artifacts can be built with:

```powershell
.\gradlew.bat :mods:create-efficient-visuals:build
.\gradlew.bat :mods:minecraft-frame-profiler:build
```

Each mod jar is written to its own `build/libs` directory.
