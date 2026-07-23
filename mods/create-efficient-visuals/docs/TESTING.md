# Create Efficient Visuals test and profiling guide

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
.\gradlew.bat :mods:create-efficient-visuals:build
```

The production jar is generated under
`mods/create-efficient-visuals/build/libs`.

## Development client

```powershell
$env:JAVA_HOME = 'path-to-jdk-21'
.\gradlew.bat :mods:create-efficient-visuals:runClient
```

The checked-in wrapper scripts honor `JAVA_HOME`. Do not launch Gradle 8.12.1
on Java 25 for this project; task creation can fail before Minecraft starts.

For a disposable game directory or an automated single-player smoke test, use
the run-model properties instead of Gradle's generic `--args` replacement:

```powershell
.\gradlew.bat :mods:create-efficient-visuals:runClient `
  -PclientGameDirectory=build/diagnostic-run `
  -PquickPlaySingleplayer=New_World
```

Without `clientGameDirectory`, the client uses the repository's
`runs/create-efficient-visuals/` directory.

The build declares Flywheel 1.0.6 as an explicit `runtimeOnly` dependency.
Ponder 1.0.82 still requests Flywheel 1.0.4 in its Maven metadata; without the
explicit runtime constraint, Gradle can launch the development client with 1.0.4
even though released Create embeds and requires 1.0.6.

To verify the resolved runtime version without starting Minecraft:

```powershell
.\gradlew.bat :mods:create-efficient-visuals:dependencyInsight `
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
| All 16 bed colors and four facings | Both halves align; no entity-rendered duplicate |
| Standing/wall/hanging signs | Wood geometry aligns and front/back text remains correct |
| Dyed/glowing sign text | Color, outline, and glow match vanilla |
| F3+T with signs present | Text and wood survive font/model atlas reload |
| Decorated pot, all four facings | Back/left/right/front patterns are on the correct side |
| Pot positive/negative wobble | Exactly one representation is visible throughout |
| Pot at a section boundary | Static/dynamic transition does not leave stale geometry |
| Enhanced Block Entities present | This mod logs that all three vanilla replacements are disabled |
| Better Beds present | Only this mod's bed replacement is disabled |

Colorwheel and Iris Flywheel Compat should not be installed together. Test one
Flywheel/Iris integration backend at a time.

## Profiling

Profile after the world and shader pack have stabilized. First-frame resource
baking is expected and should not be compared with steady-state frames.

For exact slow-frame boundaries and correlated Java/native samples, install the
independent [Minecraft Frame Profiler](../../minecraft-frame-profiler/README.md).
The combined development run loads both mod projects:

```powershell
.\gradlew.bat :mods:create-efficient-visuals:runIntegrationClient
```

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
