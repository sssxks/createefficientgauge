# Create Efficient Visuals

Client-side NeoForge 1.21.1 optimization mod for the released Create 6.0.10
and selected vanilla block entities. The development build uses Create Maven
artifact `6.0.10-281`, whose user-facing version is `6.0.10`.

The mod currently contains five independent optimizations:

- Factory gauge paths, bulbs, and conservative ordinary item models are
  retained as Flywheel instances.
- The static Rotation Speed Controller bracket is retained as a Flywheel
  instance.
- Vanilla beds use ordinary chunk-baked block models, following the Minecraft
  26.2 renderer change.
- Vanilla sign wood and hanging chains use chunk-baked models. Text remains in
  the original block-entity renderer, including glow, color, filtering, and
  front/back behavior.
- Decorated-pot geometry and four patterns are chunk-baked. During a hit
  wobble, the chunk model is hidden and the original animated renderer is
  restored; visibility changes are synchronized with section rebuild
  completion.

Items and item frames are deliberately not implemented here. A fixed Vanillin
build already owns that problem. Chain drives are also deferred until profiling
shows that Create's existing rendering path is a material bottleneck.

## Compatibility

- With an active visualization backend, supported Create content is instanced.
- Custom-rendered and unusual wrapped gauge item models use Create's original
  `ItemRenderer` path.
- Without an active visualization backend, Create's complete renderer is left
  untouched so CreateBetterFPS/Flerovium can continue to optimize it.
- The mod uses only the standard Flywheel API and has no direct Colorwheel or
  Iris dependency.
- Enhanced Block Entities disables this mod's bed, sign, and decorated-pot
  replacements. Better Beds disables only the bed replacement.

Every feature can be disabled in
`config/createefficientvisuals-client.toml`. Model replacements require a
resource reload or restart after changing their switches.

## Documentation

- [Rendering architecture](docs/ARCHITECTURE.md) explains the retained Create
  visuals, vanilla model backports, and compatibility invariants.
- [Testing and profiling](docs/TESTING.md) contains the development client,
  functional test matrix, and expected profile changes.
- [Third-party notices](THIRD_PARTY_NOTICES.md) documents the EBE-derived model
  templates.

## Build

The mod targets Java 21 bytecode:

```powershell
.\gradlew.bat :mods:create-efficient-visuals:build
```

The jar is written to `mods/create-efficient-visuals/build/libs`. The development
runtime pins Flywheel 1.0.6 explicitly because Ponder 1.0.82 still requests
Flywheel 1.0.4 in its published metadata.

The mod id changed from `createefficientgauge` to
`createefficientvisuals` in version 0.2.0. The icon artwork is intentionally
unchanged.
