package io.github.sssxks.createefficientvisuals.client.vanilla.bed;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * The 26.2 bed item is a composite of the head and foot block models.
 * Returning this wrapper as its own render pass is important: delegating to
 * the head model would silently discard the foot half.
 */
final class BedItemBakedModel extends BakedModelWrapper<BakedModel> {

    private final BakedModel foot;

    BedItemBakedModel(BakedModel head, BakedModel foot) {
        super(head);
        this.foot = foot;
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<BakedQuad> getQuads(
        @Nullable BlockState state,
        @Nullable Direction side,
        RandomSource random
    ) {
        return combine(
            originalModel.getQuads(state, side, random),
            foot.getQuads(state, side, random)
        );
    }

    @Override
    public List<BakedQuad> getQuads(
        @Nullable BlockState state,
        @Nullable Direction side,
        RandomSource random,
        ModelData data,
        @Nullable RenderType renderType
    ) {
        return combine(
            originalModel.getQuads(
                state,
                side,
                random,
                data,
                renderType
            ),
            foot.getQuads(state, side, random, data, renderType)
        );
    }

    @Override
    public BakedModel applyTransform(
        ItemDisplayContext context,
        PoseStack poseStack,
        boolean leftHand
    ) {
        originalModel.applyTransform(context, poseStack, leftHand);
        return this;
    }

    @Override
    public List<BakedModel> getRenderPasses(
        ItemStack stack,
        boolean fabulous
    ) {
        return List.of(this);
    }

    private static List<BakedQuad> combine(
        List<BakedQuad> head,
        List<BakedQuad> foot
    ) {
        if (head.isEmpty()) {
            return foot;
        }
        if (foot.isEmpty()) {
            return head;
        }
        List<BakedQuad> combined = new ArrayList<>(
            head.size() + foot.size()
        );
        combined.addAll(head);
        combined.addAll(foot);
        return combined;
    }
}
