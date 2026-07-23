package io.github.sssxks.createefficientvisuals.client.vanilla.bed;

import io.github.sssxks.createefficientvisuals.client.vanilla.ModelBakeContext;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class BedModelBackport {

    public static void replaceModels(ModelEvent.ModifyBakingResult event) {
        ModelBakeContext context = new ModelBakeContext(event);

        BuiltInRegistries.BLOCK.forEach(block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (
                !(block instanceof BedBlock bed)
                    || !"minecraft".equals(id.getNamespace())
            ) {
                return;
            }

            String texture =
                "minecraft:entity/bed/" + bed.getColor().getName();
            for (
                BlockState state : bed
                    .getStateDefinition()
                    .getPossibleStates()
            ) {
                BedPart part = state.getValue(BedBlock.PART);
                String parent = part == BedPart.HEAD
                    ? "template_bed_head"
                    : "template_bed_foot";
                float rotation =
                    state.getValue(BedBlock.FACING).toYRot() + 180.0F;
                BakedModel model = context.bakeJson(
                    """
                    {
                      "parent": "createefficientvisuals:block/%s",
                      "textures": { "bed": "%s" }
                    }
                    """.formatted(parent, texture),
                    ModelBakeContext.yRotation(rotation)
                );
                event
                    .getModels()
                    .put(
                        BlockModelShaper.stateToModelLocation(state),
                        model
                    );
            }

            BakedModel head = context.bakeJson(
                """
                {
                  "parent": "createefficientvisuals:block/template_bed_head",
                  "textures": { "bed": "%s" },
                  "display": {
                    "thirdperson_righthand": {
                      "rotation": [30, 340, 0],
                      "translation": [0, 3, -2],
                      "scale": [0.23, 0.23, 0.23]
                    },
                    "firstperson_righthand": {
                      "rotation": [30, 340, 0],
                      "translation": [0, 3, 0],
                      "scale": [0.375, 0.375, 0.375]
                    },
                    "gui": {
                      "rotation": [30, 340, 0],
                      "translation": [2, 3, 0],
                      "scale": [0.5325, 0.5325, 0.5325]
                    },
                    "ground": {
                      "rotation": [0, 180, 0],
                      "translation": [0, 1, 2],
                      "scale": [0.25, 0.25, 0.25]
                    },
                    "head": {
                      "rotation": [0, 0, 0],
                      "translation": [0, 10, -8],
                      "scale": [1, 1, 1]
                    },
                    "fixed": {
                      "rotation": [270, 180, 0],
                      "translation": [0, 4, -2],
                      "scale": [0.5, 0.5, 0.5]
                    }
                  }
                }
                """.formatted(texture),
                BlockModelRotation.X0_Y0
            );
            BakedModel foot = context.bakeJson(
                """
                {
                  "parent": "createefficientvisuals:block/template_bed_foot",
                  "textures": { "bed": "%s" }
                }
                """.formatted(texture),
                ModelBakeContext.translation(0.0F, 0.0F, 1.0F)
            );
            event
                .getModels()
                .put(
                    ModelResourceLocation.inventory(id),
                    new BedItemBakedModel(head, foot)
                );
        });
    }

    private BedModelBackport() {}
}
