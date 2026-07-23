package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps one representation visible while a decorated pot moves between the
 * chunk mesh and the vanilla wobble renderer.
 */
public final class PotRenderTransitions {

    /**
     * Renderer-specific callbacks normally complete the hand-off as soon as
     * the rebuilt section reaches the GPU. This timeout is only a safety net,
     * and must not race a normal chunk rebuild.
     */
    private static final int REBUILD_FALLBACK_TICKS = 20;
    private static final Map<
        DecoratedPotBlockEntity,
        Transition
    > ACTIVE = new ConcurrentHashMap<>();
    private static long nextGeneration;

    public static void wobble(DecoratedPotBlockEntity pot) {
        if (
            pot.getLevel() == null
                || !pot.getLevel().isClientSide
                || pot.lastWobbleStyle == null
        ) {
            return;
        }

        long now = pot.getLevel().getGameTime();
        Transition transition = new Transition(
            ++nextGeneration,
            now + pot.lastWobbleStyle.duration,
            now + REBUILD_FALLBACK_TICKS
        );
        DecoratedPotRenderState renderState = state(pot);
        boolean dynamicVisible =
            renderState.createefficientvisuals$dynamicRender();
        Transition previous = ACTIVE.put(pot, transition);
        if (previous != null) {
            previous.cancelRestore.run();

            if (dynamicVisible) {
                previous.cancelReveal.run();
                transition.revealed = true;
                renderState.createefficientvisuals$setRenderState(
                    true,
                    true
                );
                if (previous.restoring) {
                    markSectionDirty(pot);
                }
                return;
            }

            /*
             * A second event can arrive while the first hidden-mesh rebuild
             * is still in flight. Keep its completion callback: that upload
             * is also valid for the new wobble and avoids restarting the
             * hand-off with neither representation visible.
             */
            transition.cancelReveal = previous.cancelReveal;
            return;
        }

        renderState.createefficientvisuals$setRenderState(true, false);
        transition.cancelReveal = rebuild(
            pot,
            () -> revealDynamic(pot)
        );
    }

    public static void clientTick(ClientTickEvent.Post event) {
        for (
            Map.Entry<
                DecoratedPotBlockEntity,
                Transition
            > entry : ACTIVE.entrySet()
        ) {
            DecoratedPotBlockEntity pot = entry.getKey();
            Transition transition = entry.getValue();
            if (
                pot.isRemoved()
                    || pot.getLevel() == null
                    || !pot.getLevel().isClientSide
            ) {
                transition.cancelReveal.run();
                transition.cancelRestore.run();
                ACTIVE.remove(pot, transition);
                continue;
            }

            long now = pot.getLevel().getGameTime();
            if (
                !transition.revealed
                    && now >= transition.revealDeadline
            ) {
                revealDynamic(pot);
            }
            if (!transition.restoring && now >= transition.endTick) {
                transition.restoring = true;
                transition.cancelReveal.run();
                transition.restoreDeadline =
                    now + REBUILD_FALLBACK_TICKS;
                state(pot).createefficientvisuals$setRenderState(
                    false,
                    true
                );
                transition.cancelRestore = rebuild(
                    pot,
                    () -> finishRestore(pot, transition.generation)
                );
            } else if (
                transition.restoring
                    && now >= transition.restoreDeadline
            ) {
                finishRestore(pot, transition.generation);
            }
        }
    }

    public static void refreshModel(DecoratedPotBlockEntity pot) {
        pot.requestModelDataUpdate();
        markSectionDirty(pot);
    }

    private static void revealDynamic(DecoratedPotBlockEntity pot) {
        Transition current = ACTIVE.get(pot);
        if (
            current == null
                || current.restoring
        ) {
            return;
        }
        current.cancelReveal.run();
        current.revealed = true;
        state(pot).createefficientvisuals$setRenderState(true, true);
    }

    private static void finishRestore(
        DecoratedPotBlockEntity pot,
        long generation
    ) {
        Transition current = ACTIVE.get(pot);
        if (
            current == null
                || current.generation != generation
                || !current.restoring
        ) {
            return;
        }
        current.cancelRestore.run();
        state(pot).createefficientvisuals$setRenderState(false, false);
        ACTIVE.remove(pot);
    }

    private static Runnable rebuild(
        DecoratedPotBlockEntity pot,
        Runnable completion
    ) {
        pot.requestModelDataUpdate();
        Runnable cancellation = SectionRebuildCallbacks.afterNextRebuild(
            SectionPos.asLong(pot.getBlockPos()),
            completion
        );
        markSectionDirty(pot);
        return cancellation;
    }

    private static void markSectionDirty(
        DecoratedPotBlockEntity pot
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return;
        }
        BlockPos pos = pot.getBlockPos();
        minecraft.levelRenderer.setSectionDirty(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ())
        );
    }

    private static DecoratedPotRenderState state(
        DecoratedPotBlockEntity pot
    ) {
        return (DecoratedPotRenderState)(Object)pot;
    }

    private static final class Transition {

        private final long generation;
        private final long endTick;
        private final long revealDeadline;
        private boolean revealed;
        private boolean restoring;
        private long restoreDeadline;
        private Runnable cancelReveal = () -> {};
        private Runnable cancelRestore = () -> {};

        private Transition(
            long generation,
            long endTick,
            long revealDeadline
        ) {
            this.generation = generation;
            this.endTick = endTick;
            this.revealDeadline = revealDeadline;
        }
    }

    private PotRenderTransitions() {}
}
