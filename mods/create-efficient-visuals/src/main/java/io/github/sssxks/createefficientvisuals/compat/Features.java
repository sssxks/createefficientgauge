package io.github.sssxks.createefficientvisuals.compat;

import com.mojang.logging.LogUtils;
import io.github.sssxks.createefficientvisuals.config.CreateEfficientVisualsConfig;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * One compatibility boundary shared by model baking and renderer mixins.
 */
public final class Features {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean ENHANCED_BLOCK_ENTITIES = isLoaded(
        "enhancedblockentities"
    );
    private static final boolean BETTER_BEDS = isLoaded("betterbeds");

    public static boolean factoryGauges() {
        return CreateEfficientVisualsConfig.FACTORY_GAUGES.get();
    }

    public static boolean speedControllers() {
        return CreateEfficientVisualsConfig.SPEED_CONTROLLERS.get();
    }

    public static boolean beds() {
        return (
            CreateEfficientVisualsConfig.BEDS.get()
                && !ENHANCED_BLOCK_ENTITIES
                && !BETTER_BEDS
        );
    }

    public static boolean signs() {
        return (
            CreateEfficientVisualsConfig.SIGNS.get()
                && !ENHANCED_BLOCK_ENTITIES
        );
    }

    public static boolean decoratedPots() {
        return (
            CreateEfficientVisualsConfig.DECORATED_POTS.get()
                && !ENHANCED_BLOCK_ENTITIES
        );
    }

    public static void logCompatibilityDecisions() {
        if (ENHANCED_BLOCK_ENTITIES) {
            LOGGER.info(
                "Enhanced Block Entities detected; vanilla bed, sign, and decorated-pot backports are disabled"
            );
        } else if (BETTER_BEDS) {
            LOGGER.info(
                "Better Beds detected; the vanilla bed backport is disabled"
            );
        }
    }

    private static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    private Features() {}
}
