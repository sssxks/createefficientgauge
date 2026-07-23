package io.github.sssxks.createefficientvisuals.client.vanilla.decoratedpot;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;

public final class SectionRebuildCallbacks {

    private static final ConcurrentHashMap<
        Long,
        CopyOnWriteArrayList<Runnable>
    > CALLBACKS = new ConcurrentHashMap<>();

    public static Runnable afterNextRebuild(
        long section,
        Runnable callback
    ) {
        CopyOnWriteArrayList<Runnable> callbacks = CALLBACKS
            .computeIfAbsent(
                section,
                ignored -> new CopyOnWriteArrayList<>()
            );
        callbacks.add(callback);
        return () -> {
            callbacks.remove(callback);
            if (callbacks.isEmpty()) {
                CALLBACKS.remove(section, callbacks);
            }
        };
    }

    public static void rebuilt(long section) {
        List<Runnable> callbacks = CALLBACKS.remove(section);
        if (callbacks == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        callbacks.forEach(callback -> minecraft.execute(callback));
    }

    private SectionRebuildCallbacks() {}
}
