package io.github.sssxks.createefficientvisuals.client.vanilla;

import io.github.sssxks.createefficientvisuals.client.vanilla.bed.BedModelBackport;
import io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot.DecoratedPotModelBackport;
import io.github.sssxks.createefficientvisuals.client.vanilla.sign.SignModelBackport;
import io.github.sssxks.createefficientvisuals.compat.Features;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class VanillaModelBackports {

    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (Features.beds()) {
            BedModelBackport.replaceModels(event);
        }
        if (Features.signs()) {
            SignModelBackport.replaceModels(event);
        }
        if (Features.decoratedPots()) {
            DecoratedPotModelBackport.replaceModels(event);
        }
    }

    private VanillaModelBackports() {}
}
