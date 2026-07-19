# Handheld Cannon Art Tools

Regenerates and previews the Create: Handheld Cannon item model textures and
GUI sheet. All scripts carry PEP 723 inline dependencies (Pillow) and are meant
to be run with [uv](https://docs.astral.sh/uv/):

```powershell
uv run <script>.py [options]
```

## `gen_item_texture.py`

Draws `textures/item/handheld_cannon.png` (128x64 atlas, 4px per model unit) in
Create's palette straight into the mod's assets. The atlas is the single texture
used by `models/item/handheld_cannon/{item,cog}.json` (`texture_size: [32, 16]`;
UV units are annotated at the top of the script).

```powershell
uv run gen_item_texture.py --preview   # also writes out/item_atlas_x6.png
```

## `gen_gui_texture.py`

Composes `textures/gui/handheld_cannon.png` (256x256) from materials extracted
out of the local Create mod jar (`schematics_2.png` in the Gradle cache; version
resolved from `gradle.properties`, override with `--create-jar`). Region layout
and the matching `CannonScreen`/`CannonMenu` coordinates are documented at the
top of the script — keep all three in sync when moving widgets.

```powershell
uv run gen_gui_texture.py --preview    # also writes out/gui_window_preview.png
```

## `render_model.py`

Renders item-model JSONs (plus optional cog partials, spun by `--angle` around
`--spin-origin`) to `out/model_preview.png` for visual checks. Textures are
resolved from the model's `textures` map: mod namespaces come from
`mods/*/src/main/resources/assets`, `create:` from the Create jar.

```powershell
$models = "mods/create-handheld-cannon/src/main/resources/assets/createhandheldcannon/models/item/handheld_cannon"
uv run render_model.py "$models/item.json" "$models/cog.json" --angle 15 --views side,gui,iso,back
```

Available views: `gui` (the inventory rotation), `side`, `iso`, `back`, `fp`.
Previews land in `out/` which is git-ignored.
