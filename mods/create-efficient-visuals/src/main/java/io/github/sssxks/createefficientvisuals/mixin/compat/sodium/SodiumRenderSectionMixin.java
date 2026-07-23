package io.github.sssxks.createefficientvisuals.mixin.compat.sodium;

import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.SectionRebuildCallbacks;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium installs newly built section data in {@code RenderSection#setInfo}
 * after its region buffers have been uploaded. This is the equivalent of the
 * vanilla section rebuild completion hook.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
abstract class SodiumRenderSectionMixin {
    @Shadow(remap = false)
    public abstract SectionPos getPosition();

    @Inject(method = "setInfo", at = @At("TAIL"), require = 0, remap = false)
    private void createEfficientVisuals$onSectionUploaded(
        @Coerce Object info,
        CallbackInfoReturnable<Boolean> cir
    ) {
        SectionRebuildCallbacks.rebuilt(this.getPosition().asLong());
    }
}
