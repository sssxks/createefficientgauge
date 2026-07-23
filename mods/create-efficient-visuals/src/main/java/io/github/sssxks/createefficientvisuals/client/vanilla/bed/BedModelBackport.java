package io.github.sssxks.createefficientvisuals.client.vanilla.bed;

import io.github.sssxks.createefficientvisuals.client.vanilla.ModelBakeContext;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
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
        });
    }

    private BedModelBackport() {}
}
