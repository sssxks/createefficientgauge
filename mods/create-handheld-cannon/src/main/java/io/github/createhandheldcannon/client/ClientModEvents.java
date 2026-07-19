package io.github.createhandheldcannon.client;

import io.github.createhandheldcannon.CreateHandheldCannon;
import com.simibubi.create.foundation.item.render.SimpleCustomRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = CreateHandheldCannon.MOD_ID, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(CreateHandheldCannon.CANNON_MENU.get(), CannonScreen::new);
    }

    @SubscribeEvent
    public static void registerItemRenderer(RegisterClientExtensionsEvent event) {
        event.registerItem(
            SimpleCustomRenderer.create(CreateHandheldCannon.HANDHELD_CANNON.get(),
                new HandheldCannonItemRenderer()),
            CreateHandheldCannon.HANDHELD_CANNON.get()
        );
    }
}
