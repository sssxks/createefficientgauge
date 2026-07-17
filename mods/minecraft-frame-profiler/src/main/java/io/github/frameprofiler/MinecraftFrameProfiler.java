package io.github.frameprofiler;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** NeoForge entry point for the independently packaged frame profiler. */
@Mod(MinecraftFrameProfiler.MOD_ID)
public final class MinecraftFrameProfiler {

    public static final String MOD_ID = "minecraftframeprofiler";

    public MinecraftFrameProfiler(
        IEventBus modBus,
        ModContainer container
    ) {
        // Keep all Minecraft client-class references beyond this physical-side
        // boundary so the jar is harmless if discovered on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientBootstrap.configure(modBus, container);
        }
    }
}
