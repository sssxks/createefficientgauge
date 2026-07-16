package io.github.createefficientgauge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.redstone.link.LinkRenderer;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxRenderer;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
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
    private GaugeFallbackRenderer() {
    }

    public static void render(FactoryPanelBlockEntity blockEntity, float partialTick,
                              PoseStack poseStack, MultiBufferSource buffers,
                              int light, int overlay) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return;
        }

        // SmartBlockEntityRenderer normally calls both FilteringRenderer and
        // LinkRenderer. The mixin cancels that superclass call, so preserve the
        // independent link overlay explicitly before filtering item slots.
        LinkRenderer.renderOnBlockEntity(blockEntity, partialTick, poseStack, buffers, light, overlay);

        Entity camera = Minecraft.getInstance().cameraEntity;
        for (FactoryPanelBehaviour behaviour : blockEntity.panels.values()) {
            if (!behaviour.isActive()) {
                continue;
            }
            ItemStack stack = behaviour.getFilter();
            // Supported stacks already have an ItemEntry in FactoryGaugeVisual;
            // drawing them here would cause duplicate/z-fighting item geometry.
            if (stack.isEmpty() || GaugeItemModels.isSupported(blockEntity.getLevel(), stack)) {
                continue;
            }
            if (!blockEntity.isVirtual() && camera != null && camera.level() == blockEntity.getLevel()) {
                float maxDistance = behaviour.getRenderDistance();
                if (camera.position().distanceToSqr(VecHelper.getCenterOf(blockEntity.getBlockPos()))
                        > maxDistance * maxDistance) {
                    continue;
                }
            }
            if (!behaviour.getSlotPositioning().shouldRender(blockEntity.getLevel(),
                    blockEntity.getBlockPos(), blockEntity.getBlockState())) {
                continue;
            }
            poseStack.pushPose();
            behaviour.getSlotPositioning().transform(blockEntity.getLevel(), blockEntity.getBlockPos(),
                    blockEntity.getBlockState(), poseStack);
            ValueBoxRenderer.renderItemIntoValueBox(stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        }
    }
}
