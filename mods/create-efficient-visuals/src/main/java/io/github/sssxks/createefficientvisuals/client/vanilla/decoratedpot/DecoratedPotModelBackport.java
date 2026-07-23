package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import io.github.sssxks.createefficientvisuals.client.vanilla.ModelBakeContext;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.DecoratedPotPattern;
import net.minecraft.world.level.block.entity.DecoratedPotPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class DecoratedPotModelBackport {

    private static final String[] FACE_TEMPLATES = {
        "north",
        "south",
        "west",
        "east",
    };

    public static void replaceModels(ModelEvent.ModifyBakingResult event) {
        ModelBakeContext context = new ModelBakeContext(event);
        Map<Direction, BakedModel> models = new EnumMap<>(
            Direction.class
        );
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            models.put(direction, bakeForDirection(context, direction));
        }

        for (
            BlockState state : Blocks.DECORATED_POT
                .getStateDefinition()
                .getPossibleStates()
        ) {
            event
                .getModels()
                .put(
                    BlockModelShaper.stateToModelLocation(state),
                    models.get(
                        state.getValue(
                            BlockStateProperties.HORIZONTAL_FACING
                        )
                    )
                );
        }
    }

    private static BakedModel bakeForDirection(
        ModelBakeContext context,
        Direction direction
    ) {
        float rotation = direction.toYRot() + 180.0F;
        BakedModel base = context.bakeJson(
            """
            {
              "parent": "createefficientvisuals:block/decorated_pot_base"
            }
            """,
            ModelBakeContext.yRotation(rotation)
        );

        Map<Item, BakedModel[]> patterns = new HashMap<>();
        BuiltInRegistries.ITEM.forEach(item -> {
            ResourceKey<DecoratedPotPattern> pattern =
                DecoratedPotPatterns.getPatternFromItem(item);
            if (pattern == null) {
                return;
            }
            Material material = Sheets.getDecoratedPotMaterial(pattern);
            if (material != null) {
                patterns.put(
                    item,
                    bakePattern(
                        context,
                        material.texture().toString(),
                        rotation
                    )
                );
            }
        });
        patterns.computeIfAbsent(
            Items.BRICK,
            ignored -> bakePattern(
                context,
                Sheets.DECORATED_POT_SIDE.texture().toString(),
                rotation
            )
        );
        return new DecoratedPotBakedModel(base, patterns);
    }

    private static BakedModel[] bakePattern(
        ModelBakeContext context,
        String texture,
        float rotation
    ) {
        BakedModel[] models = new BakedModel[FACE_TEMPLATES.length];
        for (int i = 0; i < FACE_TEMPLATES.length; i++) {
            models[i] = context.bakeJson(
                """
                {
                  "parent": "createefficientvisuals:block/template_pottery_pattern_%s",
                  "textures": { "pattern": "%s" }
                }
                """.formatted(FACE_TEMPLATES[i], texture),
                ModelBakeContext.yRotation(rotation)
            );
        }
        return models;
    }

    private DecoratedPotModelBackport() {}
}
