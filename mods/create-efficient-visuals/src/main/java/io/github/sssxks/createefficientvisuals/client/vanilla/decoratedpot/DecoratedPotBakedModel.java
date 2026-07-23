package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

final class DecoratedPotBakedModel
    extends BakedModelWrapper<BakedModel>
{

    private final Map<Item, BakedModel[]> patterns;

    DecoratedPotBakedModel(
        BakedModel base,
        Map<Item, BakedModel[]> patterns
    ) {
        super(base);
        this.patterns = patterns;
    }

    @Override
    public List<BakedQuad> getQuads(
        @Nullable BlockState state,
        @Nullable Direction side,
        RandomSource random,
        ModelData modelData,
        @Nullable RenderType renderType
    ) {
        PotRenderData data = modelData.get(PotRenderData.PROPERTY);
        if (data != null && data.hideStaticModel()) {
            return List.of();
        }

        PotDecorations decorations = data == null
            ? PotDecorations.EMPTY
            : data.decorations();
        List<BakedQuad> quads = new ArrayList<>(
            originalModel.getQuads(
                state,
                side,
                random,
                modelData,
                renderType
            )
        );
        addPattern(
            quads,
            decorations.back(),
            0,
            state,
            side,
            random,
            modelData,
            renderType
        );
        addPattern(
            quads,
            decorations.left(),
            1,
            state,
            side,
            random,
            modelData,
            renderType
        );
        addPattern(
            quads,
            decorations.right(),
            2,
            state,
            side,
            random,
            modelData,
            renderType
        );
        addPattern(
            quads,
            decorations.front(),
            3,
            state,
            side,
            random,
            modelData,
            renderType
        );
        return quads;
    }

    private void addPattern(
        List<BakedQuad> target,
        Optional<Item> item,
        int face,
        @Nullable BlockState state,
        @Nullable Direction side,
        RandomSource random,
        ModelData data,
        @Nullable RenderType renderType
    ) {
        BakedModel[] models = patterns.getOrDefault(
            item.orElse(Items.BRICK),
            patterns.get(Items.BRICK)
        );
        target.addAll(
            models[face].getQuads(
                state,
                side,
                random,
                data,
                renderType
            )
        );
    }
}
