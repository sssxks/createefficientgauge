package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sssxks.createefficientvisuals.compat.Features;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BedRenderer.class)
abstract class BedRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void createefficientvisuals$useBakedModel(
        BedBlockEntity bed,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int overlay,
        CallbackInfo ci
    ) {
        if (
            Features.beds()
                && "minecraft".equals(
                    BuiltInRegistries.BLOCK
                        .getKey(bed.getBlockState().getBlock())
                        .getNamespace()
                )
        ) {
            ci.cancel();
        }
    }
}
