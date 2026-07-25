package io.github.createhandheldcannon.client;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.createhandheldcannon.CreateHandheldCannon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Custom item renderer for the handheld cannon. The item's model is a
 * builtin/entity wrapper so this renderer gets called; it renders the real
 * geometry model ({@code item/handheld_cannon_base}) and layers the firing
 * recoil animation on top.
 */
public final class CannonItemRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ModelResourceLocation BASE_MODEL =
        ModelResourceLocation.standalone(CreateHandheldCannon.id("item/handheld_cannon_base"));

    public CannonItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack pose,
        MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel baseModel = minecraft.getModelManager().getModel(BASE_MODEL);
        // The builtin/entity wrapper shifted the pose by (-0.5, -0.5, -0.5);
        // undo that so the base model renders exactly as it would on its own,
        // then apply the recoil kick in model space.
        pose.translate(0.5, 0.5, 0.5);
        CannonRecoil.apply(pose, displayContext);
        boolean leftHand = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        minecraft.getItemRenderer().render(
            stack, displayContext, leftHand, pose, buffer, packedLight, packedOverlay, baseModel);
    }
}
