package io.github.createhandheldcannon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Client-side recoil state for the handheld cannon. The deploy effect packet
 * is only sent to the firing player, so this state always describes the local
 * player's own shot.
 */
public final class CannonRecoil {
    private static final float DURATION_TICKS = 14;
    private static final float KICK_BACK = 0.14f; // blocks, along the barrel axis
    private static final float KICK_UP = 0.03f;
    private static final float MUZZLE_RISE_DEGREES = 9;
    // Grip pivot in block units (model units / 16); the item pivots around
    // the hand during the kick.
    private static final float PIVOT_X = 8 / 16f;
    private static final float PIVOT_Y = 4.5f / 16f;
    private static final float PIVOT_Z = 11.5f / 16f;

    private static int fireTick = Integer.MIN_VALUE;

    private CannonRecoil() {
    }

    public static void trigger() {
        fireTick = AnimationTickHolder.getTicks();
    }

    public static void apply(PoseStack pose, ItemDisplayContext context) {
        if (!context.firstPerson() && !isThirdPerson(context)) {
            return;
        }
        float elapsed = AnimationTickHolder.getTicks() - fireTick + AnimationTickHolder.getPartialTicks();
        float t = elapsed / DURATION_TICKS;
        if (t < 0 || t >= 1) {
            return;
        }
        // Sharp kick, then ease back into the hand.
        float attack = Math.min(t * 10, 1);
        float decay = (float) Math.pow(1 - t, 1.2);
        float kick = attack * decay;

        pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        pose.mulPose(Axis.XP.rotationDegrees(MUZZLE_RISE_DEGREES * kick));
        pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        pose.translate(0, KICK_UP * kick, KICK_BACK * kick);
    }

    private static boolean isThirdPerson(ItemDisplayContext context) {
        return context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }
}
