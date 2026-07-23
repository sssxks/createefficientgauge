package io.github.sssxks.createefficientvisuals.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side switches. Model replacement settings are read during resource
 * baking and therefore require a resource reload (or restart) after changing.
 */
public final class CreateEfficientVisualsConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue FACTORY_GAUGES;
    public static final ModConfigSpec.BooleanValue SPEED_CONTROLLERS;
    public static final ModConfigSpec.BooleanValue BEDS;
    public static final ModConfigSpec.BooleanValue SIGNS;
    public static final ModConfigSpec.BooleanValue DECORATED_POTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("create");
        FACTORY_GAUGES = builder
            .comment("Retain Create factory-gauge geometry with Flywheel.")
            .define("factoryGauges", true);
        SPEED_CONTROLLERS = builder
            .comment("Retain the Rotation Speed Controller bracket with Flywheel.")
            .define("speedControllers", true);
        builder.pop();

        builder.push("vanillaBackports");
        BEDS = builder
            .comment(
                "Render vanilla beds as baked block models, backported from 26.2.",
                "Requires a resource reload or restart after changing."
            )
            .define("beds", true);
        SIGNS = builder
            .comment(
                "Bake vanilla sign wood geometry while retaining the vanilla text renderer.",
                "Requires a resource reload or restart after changing."
            )
            .define("signs", true);
        DECORATED_POTS = builder
            .comment(
                "Bake decorated pots into chunks and temporarily restore the vanilla renderer while wobbling.",
                "Requires a resource reload or restart after changing."
            )
            .define("decoratedPots", true);
        builder.pop();

        SPEC = builder.build();
    }

    private CreateEfficientVisualsConfig() {}
}
