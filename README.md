# Create Efficient Gauge

Client-side NeoForge 1.21.1 optimization mod for the released Create 6.0.10
factory gauges. The build uses Maven artifact `6.0.10-281`, whose internal and
user-facing mod version is `6.0.10`.

Factory gauge paths, bulbs, and conservative ordinary baked item models are
represented as retained Flywheel instances. With Colorwheel or another active
Flywheel backend this avoids rebuilding and submitting the same quad lists for
every gauge on every frame.

Compatibility behavior is intentionally asymmetric:

- With an active visualization backend, supported content is instanced.
- Custom-rendered, tinted, and unusual wrapped item models use Create's original
  `ItemRenderer` path.
- Without an active visualization backend, Create's complete renderer is left
  untouched so CreateBetterFPS/Flerovium can continue to optimize it.
- The mod uses only the standard Flywheel API and has no direct Colorwheel or
  Iris dependency.

## Documentation

- [Rendering architecture](docs/ARCHITECTURE.md) explains the lifecycle split,
  selective renderer mixin, item support policy, and compatibility invariants.
- [Testing and profiling](docs/TESTING.md) contains the development client,
  functional test matrix, and expected profiler changes.

## Build

The mod targets Java 21 bytecode. The Gradle 9.1 wrapper can itself run on
Java 25 and will use the configured Java 21 toolchain for compilation:

```powershell
.\gradlew.bat clean build
```

The mod jar is written to `build/libs`.

The development runtime pins Flywheel 1.0.6 explicitly. Ponder 1.0.82 requests
Flywheel 1.0.4 in its published dependency metadata, which otherwise makes
`runClient` fail the Flywheel version check before mod initialization.
