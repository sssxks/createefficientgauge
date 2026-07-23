package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import io.github.sssxks.createefficientvisuals.compat.Features;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateRenderShapeMixin {

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void createefficientvisuals$useChunkModel(
        CallbackInfoReturnable<RenderShape> cir
    ) {
        Block block = getBlock();
        boolean vanilla = "minecraft".equals(
            BuiltInRegistries.BLOCK.getKey(block).getNamespace()
        );
        if (
            (Features.beds() && vanilla && block instanceof BedBlock)
                || (
                    Features.signs()
                        && vanilla
                        && block instanceof SignBlock
                )
                || (
                    Features.decoratedPots()
                        && block == Blocks.DECORATED_POT
                )
        ) {
            cir.setReturnValue(RenderShape.MODEL);
        }
    }
}
