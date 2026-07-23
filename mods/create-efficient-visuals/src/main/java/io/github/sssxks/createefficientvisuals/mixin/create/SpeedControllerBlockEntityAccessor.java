package io.github.sssxks.createefficientvisuals.mixin.create;

import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpeedControllerBlockEntity.class)
public interface SpeedControllerBlockEntityAccessor {
    @Accessor("hasBracket")
    boolean createefficientvisuals$hasBracket();
}
