package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Backports the decorated-pot special item model without using a synthetic
 * block entity. Item overrides resolve the stack's four decorations to a
 * regular baked-quad model.
 */
final class DecoratedPotItemBakedModel
    extends BakedModelWrapper<DecoratedPotBakedModel>
{

    private static final int MAX_CACHED_COMBINATIONS = 256;

    private final Map<PotDecorations, BakedModel> resolved =
        new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(
                Map.Entry<PotDecorations, BakedModel> eldest
            ) {
                return size() > MAX_CACHED_COMBINATIONS;
            }
        };

    private final ItemOverrides overrides = new ItemOverrides() {
        @Override
        public BakedModel resolve(
            BakedModel model,
            ItemStack stack,
            @Nullable ClientLevel level,
            @Nullable LivingEntity entity,
            int seed
        ) {
            PotDecorations decorations = stack.getOrDefault(
                DataComponents.POT_DECORATIONS,
                PotDecorations.EMPTY
            );
            return resolved.computeIfAbsent(
                decorations,
                key -> new Resolved(originalModel, key)
            );
        }
    };

    DecoratedPotItemBakedModel(DecoratedPotBakedModel model) {
        super(model);
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    private static final class Resolved
        extends BakedModelWrapper<DecoratedPotBakedModel>
    {

        private final ModelData data;

        private Resolved(
            DecoratedPotBakedModel model,
            PotDecorations decorations
        ) {
            super(model);
            this.data = ModelData.of(
                PotRenderData.PROPERTY,
                new PotRenderData(decorations, false)
            );
        }

        @Override
        public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource random
        ) {
            return originalModel.getQuads(
                state,
                side,
                random,
                data,
                null
            );
        }

        @Override
        public BakedModel applyTransform(
            ItemDisplayContext context,
            PoseStack poseStack,
            boolean leftHand
        ) {
            originalModel.applyTransform(
                context,
                poseStack,
                leftHand
            );
            return this;
        }

        @Override
        public List<BakedModel> getRenderPasses(
            ItemStack stack,
            boolean fabulous
        ) {
            return List.of(this);
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }
    }
}
