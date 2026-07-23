package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the same baked quads used by the terrain renderer while a pot
 * wobbles. Using ModelBlockRenderer preserves per-vertex ambient occlusion
 * instead of switching to the flat lighting of a block-entity model.
 */
public final class DecoratedPotImmediateRenderer {

    private static final RandomSource RANDOM = RandomSource.create();

    public static void render(
        DecoratedPotBlockEntity pot,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int overlay
    ) {
        if (pot.getLevel() == null) {
            return;
        }

        poseStack.pushPose();
        applyWobble(pot, partialTick, poseStack);

        BlockState state = pot.getBlockState();
        ModelData visibleData = ModelData.of(
            PotRenderData.PROPERTY,
            new PotRenderData(pot.getDecorations(), false)
        );
        BlockRenderDispatcher dispatcher = Minecraft
            .getInstance()
            .getBlockRenderer();
        dispatcher.renderBatched(
            state,
            pot.getBlockPos(),
            pot.getLevel(),
            poseStack,
            buffers.getBuffer(RenderType.solid()),
            false,
            RANDOM,
            visibleData,
            RenderType.solid()
        );
        poseStack.popPose();
    }

    private static void applyWobble(
        DecoratedPotBlockEntity pot,
        float partialTick,
        PoseStack poseStack
    ) {
        DecoratedPotBlockEntity.WobbleStyle style =
            pot.lastWobbleStyle;
        if (style == null || pot.getLevel() == null) {
            return;
        }

        float progress =
            (
                (float)(
                    pot.getLevel().getGameTime()
                        - pot.wobbleStartedAtTick
                )
                    + partialTick
            )
                / (float)style.duration;
        if (progress < 0.0F || progress > 1.0F) {
            return;
        }

        if (style == DecoratedPotBlockEntity.WobbleStyle.POSITIVE) {
            float period = progress * Mth.TWO_PI;
            float x = -1.5F
                * (Mth.cos(period) + 0.5F)
                * Mth.sin(period / 2.0F);
            poseStack.rotateAround(
                Axis.XP.rotation(x * 0.015625F),
                0.5F,
                0.0F,
                0.5F
            );
            poseStack.rotateAround(
                Axis.ZP.rotation(Mth.sin(period) * 0.015625F),
                0.5F,
                0.0F,
                0.5F
            );
        } else {
            float yaw =
                Mth.sin(-progress * 3.0F * Mth.PI)
                    * 0.125F
                    * (1.0F - progress);
            poseStack.rotateAround(
                Axis.YP.rotation(yaw),
                0.5F,
                0.0F,
                0.5F
            );
        }
    }

    private DecoratedPotImmediateRenderer() {}
}
