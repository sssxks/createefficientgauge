package io.github.sssxks.createefficientvisuals.mixin.vanilla;

import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.SectionRebuildCallbacks;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class SectionRenderDispatcherMixin {

    @Shadow
    public abstract BlockPos getOrigin();

    @Inject(method = "setCompiled", at = @At("TAIL"))
    private void createefficientvisuals$sectionRebuilt(
        SectionRenderDispatcher.CompiledSection compiled,
        CallbackInfo ci
    ) {
        SectionRebuildCallbacks.rebuilt(
            SectionPos.asLong(getOrigin())
        );
    }
}
