package io.github.sssxks.createefficientvisuals.client.vanilla.sign;

import io.github.sssxks.createefficientvisuals.client.vanilla.ModelBakeContext;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class SignModelBackport {

    public static void replaceModels(ModelEvent.ModifyBakingResult event) {
        ModelBakeContext context = new ModelBakeContext(event);

        BuiltInRegistries.BLOCK.forEach(block -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (
                !(block instanceof SignBlock sign)
                    || !"minecraft".equals(id.getNamespace())
            ) {
                return;
            }

            boolean hanging =
                block instanceof CeilingHangingSignBlock
                    || block instanceof WallHangingSignBlock;
            String texture = "minecraft:entity/signs/"
                + (hanging ? "hanging/" : "")
                + sign.type().name();

            for (
                BlockState state : block
                    .getStateDefinition()
                    .getPossibleStates()
            ) {
                ModelSpec spec = modelFor(state);
                BakedModel model = context.bakeJson(
                    """
                    {
                      "parent": "createefficientvisuals:block/%s",
                      "textures": { "sign": "%s" }
                    }
                    """.formatted(spec.parent(), texture),
                    ModelBakeContext.yRotation(spec.rotation())
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

    private static ModelSpec modelFor(BlockState state) {
        if (state.getBlock() instanceof StandingSignBlock) {
            int segment = state.getValue(StandingSignBlock.ROTATION);
            return new ModelSpec(
                "template_sign",
                180.0F + segment * 22.5F
            );
        }
        if (state.getBlock() instanceof CeilingHangingSignBlock) {
            int segment = state.getValue(
                CeilingHangingSignBlock.ROTATION
            );
            boolean attached = state.getValue(
                CeilingHangingSignBlock.ATTACHED
            );
            return new ModelSpec(
                attached
                    ? "template_hanging_sign_attached"
                    : "template_hanging_sign",
                180.0F + segment * 22.5F
            );
        }
        if (state.getBlock() instanceof WallHangingSignBlock) {
            return new ModelSpec(
                "template_wall_hanging_sign",
                wallRotation(
                    state.getValue(WallHangingSignBlock.FACING)
                )
            );
        }
        return new ModelSpec(
            "template_wall_sign",
            wallRotation(state.getValue(WallSignBlock.FACING))
        );
    }

    private static float wallRotation(Direction facing) {
        return facing.toYRot() + 180.0F;
    }

    private record ModelSpec(String parent, float rotation) {}

    private SignModelBackport() {}
}
