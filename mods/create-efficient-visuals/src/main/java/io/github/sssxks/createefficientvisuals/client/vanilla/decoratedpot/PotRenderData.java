package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import net.minecraft.world.level.block.entity.PotDecorations;
import net.neoforged.neoforge.client.model.data.ModelProperty;

public record PotRenderData(
    PotDecorations decorations,
    boolean hideStaticModel
) {
    public static final ModelProperty<PotRenderData> PROPERTY =
        new ModelProperty<>();
}
