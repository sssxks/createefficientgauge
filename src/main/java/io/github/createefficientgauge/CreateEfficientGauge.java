package io.github.createefficientgauge;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CreateEfficientGauge.MOD_ID)
public final class CreateEfficientGauge {

    public static final String MOD_ID = "createefficientgauge";

    public CreateEfficientGauge(IEventBus modBus, ModContainer container) {
        // This jar is client-side functionality, but NeoForge still constructs
        // the @Mod entry point while discovering mods. Keep every reference to
        // Minecraft renderer classes behind the physical-side check so merely
        // placing the jar on a dedicated server does not class-load them.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            container.registerConfig(ModConfig.Type.CLIENT, SlowFrameJfrConfig.SPEC);
            modBus.addListener(CreateEfficientGauge::clientSetup);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        // Registry mutation is enqueued because client setup itself may execute
        // in parallel with other mods. Flywheel permits replacing a visualizer
        // for a BlockEntityType; Create 6.0.10 does not register one for factory
        // panels, which is the gap this mod fills.
        event.enqueueWork(() -> {
            SimpleBlockEntityVisualizer.builder(
                (net.minecraft.world.level.block.entity.BlockEntityType<FactoryPanelBlockEntity>) AllBlockEntityTypes.FACTORY_PANEL.get()
            )
                .factory(FactoryGaugeVisual::new)
                // Do not ask Flywheel to skip the whole block-entity renderer.
                // FactoryPanelRendererMixin makes that decision only while an
                // actual backend is active and preserves unsupported items.
                .neverSkipVanillaRender()
                .apply();

            // Optional and disabled by default. Starting during client setup
            // places MethodTrace's one-time instrumentation work before normal
            // world play instead of injecting it on the first profiled frame.
            SlowFrameJfrProfiler.start();
        });
    }
}
