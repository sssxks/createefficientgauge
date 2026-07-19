package io.github.createhandheldcannon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import io.github.createhandheldcannon.CreateHandheldCannon;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class HandheldCannonItemRenderer extends CustomRenderedItemModelRenderer {
    private static final PartialModel COG =
        PartialModel.of(CreateHandheldCannon.id("item/handheld_cannon/cog"));
    private static final Vec3 COG_CENTER = new Vec3(8 / 16f, 8.5 / 16f, 7.5 / 16f);

    @Override
    protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
        ItemDisplayContext transformType, PoseStack pose, MultiBufferSource buffer, int light, int overlay) {
        renderer.renderSolid(model.getOriginalModel(), light);

        pose.pushPose();
        float angle = (AnimationTickHolder.getRenderTime() * -2.5f) % 360;
        TransformStack.of(pose)
            .translate(COG_CENTER)
            .rotateZDegrees(angle)
            .translateBack(COG_CENTER);
        renderer.renderSolid(COG.get(), light);
        pose.popPose();
    }
}
