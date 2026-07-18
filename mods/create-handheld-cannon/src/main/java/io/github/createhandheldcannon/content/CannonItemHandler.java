package io.github.createhandheldcannon.content;

import com.simibubi.create.AllItems;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class CannonItemHandler extends ItemStackHandler {
    private final ItemStack cannon;

    public CannonItemHandler(ItemStack cannon) {
        super(CannonState.SLOT_COUNT);
        this.cannon = cannon;
        var initial = CannonState.contents(cannon);
        for (int i = 0; i < CannonState.SLOT_COUNT; i++) {
            stacks.set(i, initial.get(i).copy());
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot == CannonState.FUEL_SLOT) {
            return stack.is(Items.GUNPOWDER);
        }
        return AllItems.SCHEMATIC.isIn(stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return slot == CannonState.FUEL_SLOT ? 64 : 1;
    }

    @Override
    protected void onContentsChanged(int slot) {
        CannonState.setContents(cannon, stacks);
        int schematicIndex = slot - CannonState.SCHEMATIC_START;
        if (schematicIndex >= 0 && schematicIndex < CannonState.SCHEMATIC_COUNT
            && !stacks.get(slot).isEmpty() && CannonState.todo(cannon, schematicIndex) == 0) {
            CannonState.setTodo(cannon, schematicIndex, 1);
        }
    }
}
