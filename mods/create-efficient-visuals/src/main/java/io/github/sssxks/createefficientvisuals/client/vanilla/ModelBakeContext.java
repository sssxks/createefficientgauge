package io.github.sssxks.createefficientvisuals.client.vanilla;

import com.mojang.math.Transformation;
import io.github.sssxks.createefficientvisuals.mixin.vanilla.ModelBakeryAccessor;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.SimpleModelState;
import org.joml.Quaternionf;

/**
 * Small adapter for baking generated child models during ModifyBakingResult.
 */
public final class ModelBakeContext implements ModelBaker {

    private final ModelEvent.ModifyBakingResult event;

    public ModelBakeContext(ModelEvent.ModifyBakingResult event) {
        this.event = event;
    }

    public BakedModel bakeJson(String json, ModelState state) {
        BlockModel model = BlockModel.fromString(json);
        model.resolveParents(this::getModel);
        return model.bake(this, event.getTextureGetter(), state);
    }

    public static ModelState yRotation(float degrees) {
        Quaternionf rotation = new Quaternionf().rotateY(
            (float)Math.toRadians(-degrees)
        );
        return new SimpleModelState(
            new Transformation(null, rotation, null, null)
        );
    }

    @Override
    public UnbakedModel getModel(ResourceLocation location) {
        return ((ModelBakeryAccessor)(Object)event.getModelBakery())
            .createefficientvisuals$getModel(location);
    }

    @Override
    public UnbakedModel getTopLevelModel(ModelResourceLocation location) {
        return null;
    }

    @Override
    public Function<Material, TextureAtlasSprite> getModelTextureGetter() {
        return event.getTextureGetter();
    }

    @Override
    public BakedModel bake(ResourceLocation location, ModelState state) {
        UnbakedModel model = getModel(location);
        return model.bake(this, event.getTextureGetter(), state);
    }

    @Override
    public BakedModel bake(
        ResourceLocation location,
        ModelState state,
        Function<Material, TextureAtlasSprite> sprites
    ) {
        return bakeUncached(getModel(location), state, sprites);
    }

    @Override
    public BakedModel bakeUncached(
        UnbakedModel model,
        ModelState state,
        Function<Material, TextureAtlasSprite> sprites
    ) {
        return model.bake(this, sprites, state);
    }
}
