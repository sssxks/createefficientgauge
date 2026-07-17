package io.github.createefficientgauge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.redstone.link.LinkRenderer;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxRenderer;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Draws only content intentionally excluded from the retained visual.
 *
 * <p>This is not a second general factory-panel renderer. It exists so one
 * procedural item cannot force paths, bulbs, and three ordinary items on the
 * same gauge back through the expensive immediate renderer.</p>
 */
public final class GaugeFallbackRenderer {

    /**
     * Support is a property of the resolved model and stack components, not of
     * the current frame. Keeping this tiny cache avoids resolving the same four
     * filter models once per visible gauge per frame.
     *
     * <p>Weak keys follow the block entity lifecycle without requiring a mixin
     * into chunk unload. Access happens only from the block-entity render path,
     * which is on the render thread, so this deliberately is not a concurrent
     * map.</p>
     */
    private static final Map<
        FactoryPanelBlockEntity,
        EnumMap<PanelSlot, CachedSupport>
    > SUPPORT = new WeakHashMap<>();
    private static BakedModel reloadMarker;

    private GaugeFallbackRenderer() {}

    public static void render(
        FactoryPanelBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int overlay
    ) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return;
        }

        // SmartBlockEntityRenderer normally calls both FilteringRenderer and
        // LinkRenderer. The mixin cancels that superclass call, so preserve the
        // independent link overlay explicitly before filtering item slots.
        LinkRenderer.renderOnBlockEntity(
            blockEntity,
            partialTick,
            poseStack,
            buffers,
            light,
            overlay
        );

        EnumMap<PanelSlot, CachedSupport> supportBySlot = supportBySlot(
            blockEntity
        );
        Entity camera = Minecraft.getInstance().cameraEntity;
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (!behaviour.isActive()) {
                continue;
            }
            ItemStack stack = behaviour.getFilter();
            // Supported stacks already have an ItemEntry in FactoryGaugeVisual;
            // drawing them here would cause duplicate/z-fighting item geometry.
            if (
                stack.isEmpty() ||
                !requiresFallback(supportBySlot, blockEntity, behaviour, stack)
            ) {
                continue;
            }
            if (
                !blockEntity.isVirtual() &&
                camera != null &&
                camera.level() == blockEntity.getLevel()
            ) {
                float maxDistance = behaviour.getRenderDistance();
                if (
                    camera
                        .position()
                        .distanceToSqr(
                            VecHelper.getCenterOf(blockEntity.getBlockPos())
                        ) >
                    maxDistance * maxDistance
                ) {
                    continue;
                }
            }
            if (
                !behaviour
                    .getSlotPositioning()
                    .shouldRender(
                        blockEntity.getLevel(),
                        blockEntity.getBlockPos(),
                        blockEntity.getBlockState()
                    )
            ) {
                continue;
            }
            poseStack.pushPose();
            behaviour
                .getSlotPositioning()
                .transform(
                    blockEntity.getLevel(),
                    blockEntity.getBlockPos(),
                    blockEntity.getBlockState(),
                    poseStack
                );
            ValueBoxRenderer.renderItemIntoValueBox(
                stack,
                poseStack,
                buffers,
                light,
                overlay
            );
            poseStack.popPose();
        }
    }

    private static EnumMap<PanelSlot, CachedSupport> supportBySlot(
        FactoryPanelBlockEntity blockEntity
    ) {
        // Perform the weak-map lookup once per gauge render, not once per slot.
        // A populated factory gauge has four slots, so placing this outside the
        // loop removes three quarters of the map work in the common case.
        BakedModel currentMarker = Minecraft.getInstance()
            .getModelManager()
            .getMissingModel();
        if (currentMarker != reloadMarker) {
            reloadMarker = currentMarker;
            SUPPORT.clear();
        }
        return SUPPORT.computeIfAbsent(blockEntity, ignored ->
            new EnumMap<>(PanelSlot.class)
        );
    }

    private static boolean requiresFallback(
        EnumMap<PanelSlot, CachedSupport> slots,
        FactoryPanelBlockEntity blockEntity,
        FactoryPanelBehaviour behaviour,
        ItemStack stack
    ) {
        CachedSupport cached = slots.get(behaviour.slot);
        if (cached != null && ItemStack.matches(cached.stack(), stack)) {
            return !cached.supported();
        }

        boolean supported = GaugeItemModels.isSupported(
            blockEntity.getLevel(),
            stack
        );
        slots.put(behaviour.slot, new CachedSupport(stack.copy(), supported));
        return !supported;
    }

    private record CachedSupport(ItemStack stack, boolean supported) {}
}
