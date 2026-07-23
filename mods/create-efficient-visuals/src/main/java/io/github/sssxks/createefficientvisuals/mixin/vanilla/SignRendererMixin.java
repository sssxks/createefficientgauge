package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sssxks.createefficientvisuals.compat.Features;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignRenderer.class)
abstract class SignRendererMixin {

    @Shadow
    abstract void translateSign(
        PoseStack poseStack,
        float yRotation,
        BlockState state
    );

    @Shadow
    abstract void renderSignText(
        BlockPos pos,
        SignText text,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int lineHeight,
        int maxWidth,
        boolean front
    );

    @Inject(method = "renderSignWithText", at = @At("HEAD"), cancellable = true)
    private void createefficientvisuals$renderOnlyText(
        SignBlockEntity signEntity,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int light,
        int overlay,
        BlockState state,
        SignBlock signBlock,
        WoodType woodType,
        Model model,
        CallbackInfo ci
    ) {
        if (
            !Features.signs()
                || !"minecraft".equals(
                    BuiltInRegistries.BLOCK
                        .getKey(state.getBlock())
                        .getNamespace()
                )
        ) {
            return;
        }

        poseStack.pushPose();
        translateSign(
            poseStack,
            -signBlock.getYRotationDegrees(state),
            state
        );
        renderSignText(
            signEntity.getBlockPos(),
            signEntity.getFrontText(),
            poseStack,
            buffers,
            light,
            signEntity.getTextLineHeight(),
            signEntity.getMaxTextLineWidth(),
            true
        );
        renderSignText(
            signEntity.getBlockPos(),
            signEntity.getBackText(),
            poseStack,
            buffers,
            light,
            signEntity.getTextLineHeight(),
            signEntity.getMaxTextLineWidth(),
            false
        );
        poseStack.popPose();
        ci.cancel();
    }
}
