package io.github.createefficientgauge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpeedControllerRenderer.class)
abstract class SpeedControllerRendererMixin {

    /**
     * Preserve SmartBlockEntityRenderer's value-box/interaction overlay, then
     * stop before Create submits the shaft and bracket. SpeedControllerVisual
     * owns both models while a Flywheel backend is active.
     */
    @Inject(
        method = "renderSafe(Lcom/simibubi/create/content/kinetics/speedController/SpeedControllerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/foundation/blockEntity/renderer/SmartBlockEntityRenderer;renderSafe(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void createefficientgauge$skipImmediateModels(
        SpeedControllerBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int overlay,
        CallbackInfo ci
    ) {
        if (
            VisualizationManager.supportsVisualization(blockEntity.getLevel())
        ) {
            ci.cancel();
        }
    }
}
