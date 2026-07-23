package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.DecoratedPotRenderState;
import io.github.sssxks.createefficientvisuals.compat.Features;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DecoratedPotRenderer.class)
abstract class DecoratedPotRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void createefficientvisuals$useBakedModel(
        DecoratedPotBlockEntity pot,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int overlay,
        CallbackInfo ci
    ) {
        if (
            Features.decoratedPots()
                && !((DecoratedPotRenderState)(Object)pot)
                    .createefficientvisuals$dynamicRender()
        ) {
            ci.cancel();
        }
    }
}
