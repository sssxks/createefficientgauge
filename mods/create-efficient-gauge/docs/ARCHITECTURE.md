# Rendering architecture

## Problem being addressed

Create 6.0.10 renders a factory panel as a block entity every frame. For every
visible connection segment, `FactoryPanelRenderer.renderPath` obtains a partial
model, transforms all of its vertices, asks the active `MultiBufferSource` for a
buffer, and writes those vertices again. The item in every occupied panel slot
also travels through `ValueBoxRenderer` and Minecraft's `ItemRenderer`.

That design is reasonable for a small number of gauges, but it scales with:

`frames × visible gauges × panels × path segments`

With Iris, each submission can add tangent generation, translucent-order
bookkeeping, buffer lookup, and flushing. CreateBetterFPS improves the cost of
building/submitting the bytes, and Flerovium improves some ordinary item models,
but both remain inside this per-frame immediate rendering model.

## Chosen boundary

This mod registers `FactoryGaugeVisual` for Create's factory-panel block entity.
It does not implement an Iris or Colorwheel backend. The visual uses public
Flywheel model, instancer, instance, and visualization APIs, so the selected
Flywheel backend decides how the retained instances reach the GPU.

The runtime flow is:

```text
FactoryPanelBlockEntity
        |
        +-- Flywheel backend active? -- no --> original Create renderer
        |
       yes
        |
        +-- FactoryGaugeVisual
        |     +-- path segment instances
        |     +-- normal/additive bulb instances
        |     +-- supported baked item instances
        |
        +-- FactoryPanelRendererMixin
              +-- LinkRenderer overlay
              +-- unsupported item-only fallback
              +-- cancel the rest of Create's immediate renderer
```

The visualizer is configured with `neverSkipVanillaRender()`. This may look
counterintuitive, but Flywheel's skip predicate can only skip the entire block
entity renderer. We need a more granular choice: a gauge can contain three
ordinary cached items and one procedural item. The renderer mixin therefore
performs the backend check and draws just that one unsupported item before
cancelling the remaining immediate renderer.

`VisualizationManager.supportsVisualization(level)` is defined by Flywheel,
not by this mod. In Flywheel 1.0.6 it means: a backend is enabled, the level is
client-side, and the level is either Minecraft's current level or a custom
`VisualizationLevel` that opts in. With `/flywheel backend` reporting
`colorwheel:instancing`, this predicate is true. Seeing `GaugeFallbackRenderer`
in a profile therefore does not mean Flywheel fell back; it means the retained
visual is active and this mod is checking its separate item-compatibility path.

## Lifecycle split

`FactoryGaugeVisual` implements both `SimpleTickableVisual` and
`SimpleDynamicVisual`:

- On a game tick, it resolves filter models and computes a flattened path
  topology. A stable topology hash allows existing instances to be retained.
- On a rendered frame, it computes dynamic bulb/path state without walking
  baked quads. A path instance is marked changed only when its color, vertical
  status-row offset, or packed light actually differs from the last submitted
  state. Bulb interpolation and Create's connection-success animation therefore
  remain smooth without rewriting stable instance matrices every frame.
- All half-block segments belonging to one connection are checked as a group.
  A 100-segment stable connection performs one frame-state comparison, not 100.
- Settled connection groups are refreshed on client ticks. Render-frame checks
  are limited to groups whose Create bulb lerp is still animating, preserving
  interpolation without polling static connections again for shader passes.
- A visual-level animation flag bypasses both bulb and path collections when
  every bulb is settled. The final interpolated frame is still submitted before
  the flag clears, and section-light callbacks force an immediate refresh.
- On a game tick, a connection-level topology fingerprint is computed before
  paths are flattened. `PathSpec` objects and instances are rebuilt only after
  the block orientation, connection identity/path inputs, support-link type, or
  bulb model state changes.
- On a light update, it refreshes instance light and full-bright bulb state.
- On deletion, it explicitly deletes every owned instance handle.

Flywheel `Model` objects are shared through `RendererReloadCache`. They are
invalidated on renderer/resource reload, preventing meshes baked against old
atlases from surviving F3+T.

## Item support policy

`GaugeItemModels` resolves the same `ItemDisplayContext.FIXED` model used by
Create's value box. It accepts only exact known baked-model implementations and
rejects custom renderers and unknown wrappers/subclasses.

The strict class test is a safety rule, not an assumption that modded items are
bad. A `BakedModel` subclass can make `getQuads` depend on model data, the level,
the player, time, or external rendering state. Treating it as immutable can
produce missing or incorrect items. Falling back is slower for that one item but
preserves correctness.

Tinted quads are supported. This matters even for apparently uncolored items:
Minecraft's generated flat-item model marks layer quads as tintable, then
`ItemColors` returns opaque white when an item has no registered color. Rejecting
the tint flag alone therefore sends ordinary items such as kelp through the full
`ItemRenderer` path every frame.

The model cache key contains the resolved per-stack tint palette. White entries
are normalized away, so common generated items share the ordinary mesh, while
component-colored items cannot accidentally reuse another stack's colors. Foil
is implemented as a second Flywheel material pass over that same tinted mesh.

The immediate fallback renderer caches each slot's support decision by copied
`ItemStack`. Filters normally remain unchanged for many frames, so steady-state
rendering performs only an `ItemStack.matches` comparison. The cache has weak
block-entity keys and is cleared when ModelManager replaces its missing-model
sentinel during F3+T.

## Compatibility invariants

1. Never cancel `FactoryPanelRenderer` when
   `VisualizationManager.supportsVisualization(level)` is false.
2. Never create an item instance unless the same support test causes the
   fallback renderer to skip that item.
3. Never keep instances across visual deletion.
4. Never cache a mesh independently of Flywheel's renderer-reload lifecycle.
5. Never call Colorwheel or Iris implementation classes directly.
6. Never cache a tinted mesh without its resolved `ItemColors` values in the
   cache key.

These invariants are more important than accepting every possible baked item.

## Known visual gap

Create conditionally applies `AllSpriteShifts.FACTORY_PANEL_CONNECTIONS` to
ordinary active paths. The initial retained model uses the partial's baked UVs
and does not yet cache a shifted UV mesh variant. Geometry, color, direction,
and status-row offset are preserved, but the moving/shifted path texture can be
static or differ from Create's immediate renderer.

The correct follow-up is a second reload-aware model cache keyed by partial plus
sprite-shift variant. Calling `SuperByteBuffer.shiftUV` each frame would restore
the appearance by restoring the performance problem, so it is intentionally not
used as a shortcut.
