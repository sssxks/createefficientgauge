package io.github.createefficientgauge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelConnection;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelSupportBehaviour;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import io.github.createefficientgauge.GaugeItemModels.PreparedItemModel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Retained GPU representation of one Create factory gauge block entity.
 *
 * <p>Create's renderer rebuilds a {@code SuperByteBuffer} for every connection
 * segment on every frame. Under Iris that also performs buffer lookup, tangent
 * generation, ordering bookkeeping, and a flush. This visual performs the
 * inverse split:</p>
 *
 * <ul>
 *   <li>Immutable vertices live in reload-aware {@link Model} caches.</li>
 *   <li>Connection topology and displayed items are checked once per tick.</li>
 *   <li>Only matrices, color and light are changed during a rendered frame.</li>
 * </ul>
 *
 * <p>The class uses only public Flywheel APIs. Colorwheel can therefore render
 * these instances through its backend without this mod importing Colorwheel or
 * Iris classes.</p>
 */
public final class FactoryGaugeVisual
    extends AbstractBlockEntityVisual<FactoryPanelBlockEntity>
    implements SimpleDynamicVisual, SimpleTickableVisual
{

    private static final Material ADDITIVE = SimpleMaterial.builder()
        .transparency(Transparency.ADDITIVE)
        .backfaceCulling(false)
        .build();
    private static final RendererReloadCache<
        PartialModel,
        Model
    > ADDITIVE_MODELS = new RendererReloadCache<>(partial ->
        new BakedModelBuilder(partial.get())
            .materialFunc((renderType, shaded, ambientOcclusion) -> ADDITIVE)
            .build()
    );

    // Entries own Flywheel instances and must be explicitly deleted. They are
    // intentionally kept per block entity: Models are shared, instance state is
    // not. Factory gauges contain at most four panel behaviours.
    private final List<PathEntry> paths = new ArrayList<>();
    // All segments of one connection share color, status-row offset and light.
    // Grouping them lets a stable connection perform one comparison per frame
    // instead of one comparison for every half-block segment in a long path.
    private final List<PathGroup> pathGroups = new ArrayList<>();
    private final List<BulbEntry> bulbs = new ArrayList<>();
    private final Map<PanelSlot, ItemEntry> items = new EnumMap<>(
        PanelSlot.class
    );
    private int topologyHash = Integer.MIN_VALUE;
    private boolean hasAnimatingBulbs = true;
    private boolean pathStylesInitialized;

    public FactoryGaugeVisual(
        VisualizationContext context,
        FactoryPanelBlockEntity blockEntity,
        float partialTick
    ) {
        super(context, blockEntity, partialTick);
        refreshTopology(true);
        refreshItems();
        animate(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        // Far-away visuals retain their last valid state. Flywheel continues to
        // render the instances, but there is no reason to recompute animated
        // matrices/colors on every distant frame.
        if (doDistanceLimitThisFrame(context)) {
            return;
        }
        animate(context.partialTick());
    }

    @Override
    public void tick(TickableVisual.Context context) {
        // Network packets and Create's block-entity tick can replace connection
        // maps or filters. Tick cadence is sufficient for those discrete state
        // changes and avoids model resolution on every rendered frame.
        refreshTopology(false);
        refreshItems();
        // Discrete behaviour/connection state changes are tick-driven. Refresh
        // every group here so settled paths do not need polling every rendered
        // frame (or again for every shader shadow pass).
        int light = computePackedLight();
        updateBulbs(0, light, true);
        updatePathStyles(0, light, true);
    }

    private void refreshTopology(boolean force) {
        // Compute a cheap connection-level fingerprint before flattening paths.
        // A test wall can contain tens of thousands of half-block segments;
        // allocating one PathSpec for every segment merely to discover that the
        // topology is unchanged costs more than updating the retained geometry.
        int hash = computeTopologyHash();
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (behaviour.isActive() && behaviour.getAmount() > 0) {
                hash = 31 * hash + behaviour.slot.ordinal() + 1;
                hash =
                    31 * hash +
                    (behaviour.redstonePowered || behaviour.isMissingAddress()
                        ? 1
                        : 0);
            }
        }
        if (!force && hash == topologyHash) {
            return;
        }
        topologyHash = hash;
        List<PathSpec> specs = collectPathSpecs();

        paths.forEach(entry -> entry.instance.delete());
        paths.clear();
        pathGroups.clear();
        PathGroup group = null;
        for (PathSpec spec : specs) {
            TransformedInstance instance = instancerProvider()
                .instancer(
                    InstanceTypes.TRANSFORMED,
                    Models.partial(spec.partial)
                )
                .createInstance();
            PathEntry entry = new PathEntry(spec, instance);
            paths.add(entry);
            // collectPathSpecs appends every segment of a connection
            // contiguously, so identity comparison is enough to form groups.
            if (
                group == null || group.styleSpec.connection != spec.connection
            ) {
                group = new PathGroup(spec);
                pathGroups.add(group);
            }
            group.entries.add(entry);
        }

        bulbs.forEach(BulbEntry::delete);
        bulbs.clear();
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (!behaviour.isActive() || behaviour.getAmount() <= 0) {
                continue;
            }
            PartialModel partial = bulbPartial(behaviour);
            TransformedInstance base = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(partial))
                .createInstance();
            TransformedInstance glow = instancerProvider()
                .instancer(
                    InstanceTypes.TRANSFORMED,
                    ADDITIVE_MODELS.get(partial)
                )
                .createInstance();
            bulbs.add(new BulbEntry(behaviour, partial, base, glow));
        }
        // New instance handles have no submitted state yet. Force one update
        // even if Create's lerp is already settled at zero or one.
        hasAnimatingBulbs = true;
        pathStylesInitialized = false;
    }

    private int computeTopologyHash() {
        int hash = 31 + blockEntity.getBlockState().hashCode();
        BlockState state = blockEntity.getBlockState();
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (!behaviour.isActive()) {
                continue;
            }
            hash = 31 * hash + behaviour.slot.ordinal();
            for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                hash =
                    31 * hash +
                    connectionTopologyHash(behaviour, connection, state);
            }
            for (FactoryPanelConnection connection : behaviour.targetedByLinks.values()) {
                hash =
                    31 * hash +
                    connectionTopologyHash(behaviour, connection, state);
            }
        }
        return hash;
    }

    private int connectionTopologyHash(
        FactoryPanelBehaviour behaviour,
        FactoryPanelConnection connection,
        BlockState state
    ) {
        // FactoryPanelConnection caches its path and mutates that list only when
        // arrowBendMode changes. The path is otherwise a deterministic function
        // of from/to, block orientation and that bend mode, so hashing those
        // inputs avoids walking every Direction element on every client tick.
        List<Direction> path = connection.getPath(
            level,
            state,
            behaviour.getPanelPosition()
        );
        FactoryPanelSupportBehaviour support = FactoryPanelBehaviour.linkAt(
            level,
            connection
        );
        int hash = System.identityHashCode(connection);
        hash = 31 * hash + connection.from.hashCode();
        hash = 31 * hash + connection.arrowBendMode;
        hash = 31 * hash + path.size();
        hash =
            31 * hash +
            (support != null &&
            support.blockEntity instanceof DisplayLinkBlockEntity
                ? 1
                : 0);
        hash =
            31 * hash +
            (support != null &&
            support.blockEntity instanceof RedstoneLinkBlockEntity
                ? 1
                : 0);
        hash = 31 * hash + (support != null && !support.isOutput() ? 1 : 0);
        return hash;
    }

    private List<PathSpec> collectPathSpecs() {
        // Store a flattened segment list. Rendering one segment is then a single
        // transformed instance; no BakedQuad/SuperByteBuffer work remains in the
        // per-frame path.
        List<PathSpec> result = new ArrayList<>();
        BlockState state = blockEntity.getBlockState();
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (!behaviour.isActive()) {
                continue;
            }
            for (FactoryPanelConnection connection : behaviour.targetedBy.values()) {
                appendPath(result, behaviour, connection, state);
            }
            for (FactoryPanelConnection connection : behaviour.targetedByLinks.values()) {
                appendPath(result, behaviour, connection, state);
            }
        }
        return result;
    }

    private void appendPath(
        List<PathSpec> out,
        FactoryPanelBehaviour behaviour,
        FactoryPanelConnection connection,
        BlockState state
    ) {
        List<Direction> path = connection.getPath(
            level,
            state,
            behaviour.getPanelPosition()
        );
        FactoryPanelSupportBehaviour support = FactoryPanelBehaviour.linkAt(
            level,
            connection
        );
        boolean display =
            support != null &&
            support.blockEntity instanceof DisplayLinkBlockEntity;
        boolean redstone =
            support != null &&
            support.blockEntity instanceof RedstoneLinkBlockEntity;
        boolean reversed = support != null && !support.isOutput();
        float currentX = 0;
        float currentZ = 0;
        for (int i = 0; i < path.size(); i++) {
            Direction direction = path.get(i);
            if (!reversed) {
                currentX += direction.getStepX() * .5f;
                currentZ += direction.getStepZ() * .5f;
            }
            // Create puts the arrow at the logical source. Reversed support-link
            // connections therefore select the last physical segment instead.
            boolean arrow = reversed ? i == path.size() - 1 : i == 0;
            PartialModel partial = (
                display
                    ? AllPartialModels.FACTORY_PANEL_DOTTED
                    : arrow
                      ? AllPartialModels.FACTORY_PANEL_ARROWS
                      : AllPartialModels.FACTORY_PANEL_LINES
            ).get(reversed ? direction : direction.getOpposite());
            out.add(
                new PathSpec(
                    behaviour,
                    connection,
                    partial,
                    direction,
                    currentX,
                    currentZ,
                    display,
                    redstone,
                    reversed
                )
            );
            if (reversed) {
                currentX += direction.getStepX() * .5f;
                currentZ += direction.getStepZ() * .5f;
            }
        }
    }

    private void refreshItems() {
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            ItemStack stack = behaviour.getFilter();
            // A missing ItemEntry is meaningful: the conditional renderer mixin
            // will draw unsupported stacks with Create's ValueBoxRenderer.
            // Resolve once here. The previous implementation called
            // ItemRenderer#getModel in isSupported(), again in get(), and a
            // third time while positioning; that repeated work for every slot
            // on every client tick even though all three answers came from the
            // same resolved BakedModel.
            PreparedItemModel prepared =
                behaviour.isActive() && !stack.isEmpty()
                    ? GaugeItemModels.getSupported(level, stack)
                    : null;
            boolean supported = prepared != null;
            ItemEntry old = items.get(behaviour.slot);
            if (!supported) {
                if (old != null) {
                    old.instance.delete();
                    items.remove(behaviour.slot);
                }
                continue;
            }
            Model model = prepared.model();
            if (old == null) {
                TransformedInstance instance = instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, model)
                    .createInstance();
                old = new ItemEntry(
                    behaviour,
                    stack.copy(),
                    model,
                    prepared.gui3d(),
                    instance
                );
                items.put(behaviour.slot, old);
            } else if (
                !ItemStack.matches(old.stack, stack) || old.model != model
            ) {
                // stealInstance keeps the instance handle/state while moving it
                // to the instancer for a newly resolved mesh/material.
                instancerProvider()
                    .instancer(InstanceTypes.TRANSFORMED, model)
                    .stealInstance(old.instance);
                old.stack = stack.copy();
                old.model = model;
                old.gui3d = prepared.gui3d();
            }
            positionItem(old);
        }
        items.entrySet().removeIf(entry -> {
            FactoryPanelBehaviour behaviour = blockEntity.panels.get(
                entry.getKey()
            );
            if (behaviour != null) {
                return false;
            }
            entry.getValue().instance.delete();
            return true;
        });
    }

    private void positionItem(ItemEntry entry) {
        FactoryPanelBehaviour behaviour = entry.behaviour;
        PoseStack pose = new PoseStack();
        pose.translate(
            getVisualPosition().getX(),
            getVisualPosition().getY(),
            getVisualPosition().getZ()
        );
        // Reproduce ValueBoxTransform followed by ValueBoxRenderer's inner item
        // scale and depth offset. Gauge mounting rotation is already encoded in
        // the slot positioning object supplied by Create.
        behaviour
            .getSlotPositioning()
            .transform(level, pos, blockEntity.getBlockState(), pose);
        boolean blockItem = entry.gui3d;
        float scale = (blockItem ? 1f : .5f) + 1f / 64f;
        pose.scale(scale, scale, scale);
        pose.translate(
            0,
            0,
            (blockItem ? 0 : -.15f) + customZOffset(entry.stack.getItem())
        );
        entry.instance.setTransform(pose);
        entry.instance.light(computePackedLight());
        entry.instance.overlay(
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
        );
        entry.instance.setChanged();
    }

    private void animate(float partialTick) {
        // This method never creates vertex data. A frame updates only compact
        // instance records consumed by whichever Flywheel backend is active.
        int light = computePackedLight();
        // Capture the pre-update value so the frame in which the final bulb
        // settles also submits the path's final interpolated color/offset.
        boolean animatePaths = hasAnimatingBulbs || !pathStylesInitialized;
        updateBulbs(partialTick, light, false);
        if (animatePaths) {
            updatePathStyles(partialTick, light, false);
        }
    }

    private void updateBulbs(
        float partialTick,
        int light,
        boolean forceSettled
    ) {
        if (!forceSettled && !hasAnimatingBulbs) {
            return;
        }

        boolean stillAnimating = false;
        for (BulbEntry bulb : bulbs) {
            boolean settled = bulb.behaviour.bulb.settled();
            stillAnimating |= !settled;
            if (!forceSettled && bulb.initialized && settled) {
                continue;
            }
            PartialModel current = bulbPartial(bulb.behaviour);
            if (current != bulb.partial) {
                instancerProvider()
                    .instancer(
                        InstanceTypes.TRANSFORMED,
                        Models.partial(current)
                    )
                    .stealInstance(bulb.base);
                instancerProvider()
                    .instancer(
                        InstanceTypes.TRANSFORMED,
                        ADDITIVE_MODELS.get(current)
                    )
                    .stealInstance(bulb.glow);
                bulb.partial = current;
            }
            float glowValue = bulb.behaviour.bulb.getValue(partialTick);
            transformBulb(bulb.base, bulb.behaviour);
            bulb.base.light(
                glowValue > .125f ? LightTexture.FULL_BRIGHT : light
            );
            bulb.base.setChanged();

            if (glowValue < .125f) {
                bulb.glow.setZeroTransform().setChanged();
            } else {
                transformBulb(bulb.glow, bulb.behaviour);
                float curve = (float) (1 - 2 * Math.pow(glowValue - .75f, 2));
                int color = (int) (200 * Mth.clamp(curve, -1, 1));
                bulb.glow.color(color, color, color, 255);
                bulb.glow.light(LightTexture.FULL_BRIGHT);
                bulb.glow.setChanged();
            }
            bulb.initialized = true;
        }
        hasAnimatingBulbs = stillAnimating;
    }

    private void updatePathStyles(
        float partialTick,
        int light,
        boolean forceSettled
    ) {
        for (PathGroup group : pathGroups) {
            // Only the bulb lerp can change a path style between client ticks.
            // Once it settles, the tick-side forced refresh above owns updates.
            if (
                !forceSettled &&
                group.initialized &&
                group.styleSpec.behaviour.bulb.settled()
            ) {
                continue;
            }
            PathStyle style = pathStyle(group.styleSpec, partialTick);
            if (group.hasState(style, light)) {
                continue;
            }
            for (PathEntry path : group.entries) {
                transformPath(path, style);
                path.instance.light(light);
                path.instance.setChanged();
            }
            group.rememberState(style, light);
        }
        pathStylesInitialized = true;
    }

    private void transformBulb(
        TransformedInstance instance,
        FactoryPanelBehaviour behaviour
    ) {
        BlockState state = blockEntity.getBlockState();
        // Transform order mirrors FactoryPanelRenderer.renderBulb exactly. The
        // visual position is relative to Flywheel's movable render origin.
        instance
            .setIdentityTransform()
            .translate(getVisualPosition())
            .rotateYCentered(FactoryPanelBlock.getYRot(state))
            .rotateXCentered(FactoryPanelBlock.getXRot(state) + Mth.PI / 2)
            .rotateYCentered(Mth.PI)
            .translate(
                behaviour.slot.xOffset * .5f,
                0,
                behaviour.slot.yOffset * .5f
            );
    }

    private void transformPath(PathEntry entry, PathStyle style) {
        PathSpec spec = entry.spec;
        FactoryPanelBehaviour behaviour = spec.behaviour;
        BlockState state = blockEntity.getBlockState();
        float parity = (spec.direction.get2DDataValue() % 2) * .125f;
        // Keep the tiny parity offset used by Create to prevent perpendicular
        // connection pieces from z-fighting at turns.
        entry.instance
            .setIdentityTransform()
            .translate(getVisualPosition())
            .rotateYCentered(FactoryPanelBlock.getYRot(state))
            .rotateXCentered(FactoryPanelBlock.getXRot(state) + Mth.PI / 2)
            .rotateYCentered(Mth.PI)
            .translate(
                behaviour.slot.xOffset * .5f + .25f,
                0,
                behaviour.slot.yOffset * .5f + .25f
            )
            .translate(spec.x, (style.yOffset + parity) / 512f, spec.z);
        entry.instance.colorRgb(style.color);
    }

    private PathStyle pathStyle(PathSpec spec, float partialTick) {
        // Color and vertical texture-row selection are copied from Create 6.0.10
        // FactoryPanelRenderer. They remain dynamic instance properties.
        FactoryPanelBehaviour behaviour = spec.behaviour;
        if (spec.display) {
            return new PathStyle(0x3C9852, 0);
        }
        if (spec.redstone) {
            int color = spec.reversed
                ? behaviour.count == 0
                    ? 0x888898
                    : behaviour.satisfied
                      ? 0xEF0000
                      : 0x580101
                : behaviour.redstonePowered
                  ? 0xEF0000
                  : 0x580101;
            return new PathStyle(color, .5f);
        }

        int color = behaviour.getIngredientStatusColor();
        float yOffset =
            1 + (behaviour.promisedSatisfied ? 1 : behaviour.satisfied ? 0 : 2);
        float glow = behaviour.bulb.getValue(partialTick);
        if (
            !behaviour.redstonePowered &&
            !behaviour.waitingForNetwork &&
            glow > 0 &&
            !behaviour.satisfied
        ) {
            float progress = 1 - (1 - glow) * (1 - glow);
            color = Color.mixColors(
                color,
                spec.connection.success ? 0xEAF2EC : 0xE5654B,
                progress
            );
            if (!behaviour.promisedSatisfied) {
                yOffset += (spec.connection.success ? 1 : 2) * progress;
            }
        }
        return new PathStyle(color, yOffset);
    }

    private static PartialModel bulbPartial(FactoryPanelBehaviour behaviour) {
        return behaviour.redstonePowered || behaviour.isMissingAddress()
            ? AllPartialModels.FACTORY_PANEL_RED_LIGHT
            : AllPartialModels.FACTORY_PANEL_LIGHT;
    }

    private static float customZOffset(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return 0;
        }
        Block block = blockItem.getBlock();
        return block instanceof AbstractSimpleShaftBlock ||
            block instanceof FenceBlock ||
            block.defaultBlockState().is(BlockTags.BUTTONS) ||
            block == Blocks.END_ROD
            ? -.1f
            : 0;
    }

    @Override
    public void updateLight(float partialTick) {
        // A section-light callback must update settled instances immediately;
        // it cannot wait for the next client tick's forced refresh.
        int light = computePackedLight();
        updateBulbs(partialTick, light, true);
        updatePathStyles(partialTick, light, true);
    }

    @Override
    public void collectCrumblingInstances(
        Consumer<@Nullable Instance> consumer
    ) {
        // Only geometry attached to the block should receive breaking overlay.
        // The additive glow is an emitted effect and is intentionally excluded.
        paths.forEach(path -> consumer.accept(path.instance));
        bulbs.forEach(bulb -> consumer.accept(bulb.base));
        items.values().forEach(item -> consumer.accept(item.instance));
    }

    @Override
    protected void _delete() {
        // Instance handles own backend allocations. Deleting all of them is
        // required when chunks unload, the backend changes, or visuals rebuild.
        paths.forEach(path -> path.instance.delete());
        bulbs.forEach(BulbEntry::delete);
        items.values().forEach(item -> item.instance.delete());
        paths.clear();
        pathGroups.clear();
        bulbs.clear();
        items.clear();
    }

    private record PathSpec(
        FactoryPanelBehaviour behaviour,
        FactoryPanelConnection connection,
        PartialModel partial,
        Direction direction,
        float x,
        float z,
        boolean display,
        boolean redstone,
        boolean reversed
    ) {}

    private record PathEntry(PathSpec spec, TransformedInstance instance) {}

    private static final class PathGroup {

        private final PathSpec styleSpec;
        private final List<PathEntry> entries = new ArrayList<>();
        private int lastColor = Integer.MIN_VALUE;
        private int lastYOffsetBits = Integer.MIN_VALUE;
        private int lastLight = Integer.MIN_VALUE;
        private boolean initialized;

        private PathGroup(PathSpec styleSpec) {
            this.styleSpec = styleSpec;
        }

        private boolean hasState(PathStyle style, int light) {
            return (
                lastColor == style.color &&
                lastYOffsetBits == Float.floatToIntBits(style.yOffset) &&
                lastLight == light
            );
        }

        private void rememberState(PathStyle style, int light) {
            lastColor = style.color;
            lastYOffsetBits = Float.floatToIntBits(style.yOffset);
            lastLight = light;
            initialized = true;
        }
    }

    private record PathStyle(int color, float yOffset) {}

    private static final class BulbEntry {

        private final FactoryPanelBehaviour behaviour;
        private PartialModel partial;
        private final TransformedInstance base;
        private final TransformedInstance glow;
        private boolean initialized;

        private BulbEntry(
            FactoryPanelBehaviour behaviour,
            PartialModel partial,
            TransformedInstance base,
            TransformedInstance glow
        ) {
            this.behaviour = behaviour;
            this.partial = partial;
            this.base = base;
            this.glow = glow;
        }

        private void delete() {
            base.delete();
            glow.delete();
        }
    }

    private static final class ItemEntry {

        private final FactoryPanelBehaviour behaviour;
        private ItemStack stack;
        private Model model;
        private boolean gui3d;
        private final TransformedInstance instance;

        private ItemEntry(
            FactoryPanelBehaviour behaviour,
            ItemStack stack,
            Model model,
            boolean gui3d,
            TransformedInstance instance
        ) {
            this.behaviour = behaviour;
            this.stack = stack;
            this.model = model;
            this.gui3d = gui3d;
            this.instance = instance;
        }
    }
}
