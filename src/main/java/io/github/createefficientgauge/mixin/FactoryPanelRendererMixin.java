package io.github.createefficientgauge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.createefficientgauge.GaugeFallbackRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FactoryPanelRenderer.class)
abstract class FactoryPanelRendererMixin {
    /**
     * Replaces Create's immediate renderer only when Flywheel reports a usable
     * visualization backend for this level.
     *
     * <p>{@code supportsVisualization} is a public Flywheel API method, not a
     * predicate supplied by this mod. Flywheel's implementation checks that a
     * backend is on, that this is a client level, and that the level is the
     * active Minecraft level (or explicitly implements {@code VisualizationLevel}).
     * It does not decide whether an individual item model is supported.</p>
     *
     * <p>The condition is the compatibility boundary. If Flywheel is disabled,
     * Iris has no compatible backend, or a virtual level refuses visualization,
     * the method returns without cancelling. CreateBetterFPS and Flerovium then
     * see the complete original Create renderer.</p>
     */
    @Inject(method = "renderSafe", at = @At("HEAD"), cancellable = true)
    private void createefficientgauge$useRetainedVisual(FactoryPanelBlockEntity blockEntity,
                                                        float partialTick,
                                                        PoseStack poseStack,
                                                        MultiBufferSource buffers,
                                                        int light,
                                                        int overlay,
                                                        CallbackInfo ci) {
        if (!VisualizationManager.supportsVisualization(blockEntity.getLevel())) {
            return;
        }
        // FactoryGaugeVisual owns paths/bulbs/supported items. Render only the
        // deliberately unsupported subset and prevent Create from duplicating
        // every component through MultiBufferSource.
        GaugeFallbackRenderer.render(blockEntity, partialTick, poseStack, buffers, light, overlay);
        ci.cancel();
    }
}
