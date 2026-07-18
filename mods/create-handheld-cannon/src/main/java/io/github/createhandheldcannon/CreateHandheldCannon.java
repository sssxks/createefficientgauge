package io.github.createhandheldcannon;

import io.github.createhandheldcannon.content.CannonMenu;
import io.github.createhandheldcannon.content.HandheldCannonItem;
import io.github.createhandheldcannon.net.CannonNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CreateHandheldCannon.MOD_ID)
public final class CreateHandheldCannon {
    public static final String MOD_ID = "createhandheldcannon";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, MOD_ID);

    public static final DeferredHolder<Item, HandheldCannonItem> HANDHELD_CANNON = ITEMS.register(
        "handheld_cannon",
        () -> new HandheldCannonItem(new Item.Properties()
            .stacksTo(1))
    );

    public static final DeferredHolder<MenuType<?>, MenuType<CannonMenu>> CANNON_MENU = MENUS.register(
        "handheld_cannon",
        () -> CannonMenu.createType()
    );

    public CreateHandheldCannon(IEventBus modBus) {
        ITEMS.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(CannonNetworking::register);
        modBus.addListener(this::addCreativeTabContents);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(HANDHELD_CANNON.get());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
