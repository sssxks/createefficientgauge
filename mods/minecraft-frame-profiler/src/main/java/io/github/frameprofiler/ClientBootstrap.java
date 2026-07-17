package io.github.frameprofiler;

import com.mojang.logging.LogUtils;
import io.github.frameprofiler.config.ProfilerConfig;
import io.github.frameprofiler.jfr.FrameProfile;
import io.github.frameprofiler.jfr.RollingJfrRecorder;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/** Client-only NeoForge and Minecraft integration for the JFR recorder. */
final class ClientBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RollingJfrRecorder RECORDER =
        new RollingJfrRecorder(LOGGER);

    private ClientBootstrap() {}

    static void configure(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ProfilerConfig.SPEC);
        modBus.addListener(ClientBootstrap::clientSetup);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ProfilerConfig.legacySystemPropertyEnabled()) {
                LOGGER.warn(
                    "The JVM property -D{}=true is deprecated; use -D{}=true instead.",
                    ProfilerConfig.LEGACY_SYSTEM_PROPERTY,
                    ProfilerConfig.SYSTEM_PROPERTY
                );
            }

            Path outputDirectory = Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("debug")
                .resolve("profiling")
                .resolve(MinecraftFrameProfiler.MOD_ID);
            RECORDER.start(
                outputDirectory,
                ProfilerConfig.snapshot(),
                FrameProfile.minecraftClient()
            );
        });
    }
}
