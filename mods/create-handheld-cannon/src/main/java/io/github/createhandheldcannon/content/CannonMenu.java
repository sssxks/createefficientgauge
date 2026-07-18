package io.github.createhandheldcannon.content;

import io.github.createhandheldcannon.CreateHandheldCannon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.items.SlotItemHandler;

public final class CannonMenu extends AbstractContainerMenu {
    public static final int BUTTON_SELECT_BASE = 0;
    public static final int BUTTON_TODO_MINUS_BASE = 10;
    public static final int BUTTON_TODO_PLUS_BASE = 20;
    public static final int BUTTON_REPLACE_MODE = 30;
    public static final int BUTTON_BLOCK_ENTITIES = 31;

    private final InteractionHand hand;
    private final CannonItemHandler cannonInventory;

    public static MenuType<CannonMenu> createType() {
        return IMenuTypeExtension.create(CannonMenu::new);
    }

    private CannonMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readEnum(InteractionHand.class));
    }

    public CannonMenu(int containerId, Inventory inventory, InteractionHand hand) {
        super(CreateHandheldCannon.CANNON_MENU.get(), containerId);
        this.hand = hand;
        this.cannonInventory = new CannonItemHandler(cannonStack(inventory.player));

        addSlot(new SlotItemHandler(cannonInventory, CannonState.FUEL_SLOT, 17, 25));
        for (int i = 0; i < CannonState.SCHEMATIC_COUNT; i++) {
            addSlot(new SlotItemHandler(cannonInventory, CannonState.SCHEMATIC_START + i, 47 + i * 22, 25));
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 17 + column * 18, 113 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            final int hotbarSlot = column;
            addSlot(new Slot(inventory, column, 17 + column * 18, 171) {
                @Override
                public boolean mayPickup(Player player) {
                    return hand != InteractionHand.MAIN_HAND || hotbarSlot != inventory.selected;
                }
            });
        }
    }

    public ItemStack cannonStack(Player player) {
        return player.getItemInHand(hand);
    }

    public InteractionHand hand() {
        return hand;
    }

    @Override
    public boolean stillValid(Player player) {
        return cannonStack(player).is(CreateHandheldCannon.HANDHELD_CANNON.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ItemStack cannon = cannonStack(player);
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return false;
        }
        if (id >= BUTTON_SELECT_BASE && id < BUTTON_SELECT_BASE + CannonState.SCHEMATIC_COUNT) {
            CannonState.setSelected(cannon, id - BUTTON_SELECT_BASE);
            return true;
        }
        if (id >= BUTTON_TODO_MINUS_BASE && id < BUTTON_TODO_MINUS_BASE + CannonState.SCHEMATIC_COUNT) {
            int index = id - BUTTON_TODO_MINUS_BASE;
            CannonState.setTodo(cannon, index, CannonState.todo(cannon, index) - 1);
            return true;
        }
        if (id >= BUTTON_TODO_PLUS_BASE && id < BUTTON_TODO_PLUS_BASE + CannonState.SCHEMATIC_COUNT) {
            int index = id - BUTTON_TODO_PLUS_BASE;
            CannonState.setTodo(cannon, index, CannonState.todo(cannon, index) + 1);
            return true;
        }
        if (id == BUTTON_REPLACE_MODE) {
            CannonState.cycleReplaceMode(cannon);
            return true;
        }
        if (id == BUTTON_BLOCK_ENTITIES) {
            CannonState.toggleReplaceBlockEntities(cannon);
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack empty = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return empty;
        }
        ItemStack source = slot.getItem();
        ItemStack copy = source.copy();
        int cannonSlots = CannonState.SLOT_COUNT;
        if (index < cannonSlots) {
            if (!moveItemStackTo(source, cannonSlots, slots.size(), true)) {
                return empty;
            }
        } else if (!moveItemStackTo(source, 0, cannonSlots, false)) {
            return empty;
        }
        if (source.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }
}
