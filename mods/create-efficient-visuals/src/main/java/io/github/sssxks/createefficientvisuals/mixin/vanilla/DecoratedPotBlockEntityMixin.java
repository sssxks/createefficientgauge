package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.DecoratedPotRenderState;
import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.PotRenderData;
import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.PotRenderTransitions;
import io.github.sssxks.createefficientvisuals.compat.Features;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DecoratedPotBlockEntity.class)
abstract class DecoratedPotBlockEntityMixin
    implements DecoratedPotRenderState
{

    @Unique
    private boolean createefficientvisuals$hideStaticModel;

    @Unique
    private boolean createefficientvisuals$dynamicRender;

    @Unique
    private PotDecorations createefficientvisuals$previousDecorations =
        PotDecorations.EMPTY;

    public ModelData getModelData() {
        DecoratedPotBlockEntity self =
            (DecoratedPotBlockEntity)(Object)this;
        return ModelData.of(
            PotRenderData.PROPERTY,
            new PotRenderData(
                self.getDecorations(),
                createefficientvisuals$hideStaticModel
            )
        );
    }

    @Override
    public boolean createefficientvisuals$dynamicRender() {
        return createefficientvisuals$dynamicRender;
    }

    @Override
    public void createefficientvisuals$setRenderState(
        boolean hideStaticModel,
        boolean dynamicRender
    ) {
        boolean staticChanged =
            createefficientvisuals$hideStaticModel != hideStaticModel;
        createefficientvisuals$hideStaticModel = hideStaticModel;
        createefficientvisuals$dynamicRender = dynamicRender;
        if (staticChanged) {
            ((DecoratedPotBlockEntity)(Object)this)
                .requestModelDataUpdate();
        }
    }

    @Inject(method = "triggerEvent", at = @At("RETURN"))
    private void createefficientvisuals$beginWobble(
        int id,
        int type,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (Features.decoratedPots() && cir.getReturnValue()) {
            PotRenderTransitions.wobble(
                (DecoratedPotBlockEntity)(Object)this
            );
        }
    }

    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void createefficientvisuals$captureDecorations(
        CompoundTag tag,
        HolderLookup.Provider registries,
        CallbackInfo ci
    ) {
        createefficientvisuals$previousDecorations =
            ((DecoratedPotBlockEntity)(Object)this).getDecorations();
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void createefficientvisuals$refreshDecorations(
        CompoundTag tag,
        HolderLookup.Provider registries,
        CallbackInfo ci
    ) {
        DecoratedPotBlockEntity self =
            (DecoratedPotBlockEntity)(Object)this;
        if (
            Features.decoratedPots()
                && self.getLevel() != null
                && self.getLevel().isClientSide
                && !createefficientvisuals$previousDecorations.equals(
                    self.getDecorations()
                )
        ) {
            PotRenderTransitions.refreshModel(self);
        }
    }
}
