package io.github.createhandheldcannon.service;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.equipment.toolbox.ToolboxHandler;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/** Builds a simulation first and only mutates inventories after the whole plan succeeds. */
public final class MaterialTransaction {
    private final ServerPlayer player;
    private final List<VirtualSlot> slots;
    private final List<Allocation> allocations = new ArrayList<>();
    private final List<ItemStack> missing = new ArrayList<>();

    public MaterialTransaction(ServerPlayer player) {
        this.player = player;
        this.slots = discoverSlots(player);
    }

    public boolean reserve(List<ItemRequirement.StackRequirement> requirements) {
        if (player.isCreative()) {
            return true;
        }
        for (ItemRequirement.StackRequirement requirement : requirements) {
            reserve(requirement);
        }
        return missing.isEmpty();
    }

    public List<ItemStack> missing() {
        return missing.stream().map(ItemStack::copy).toList();
    }

    public boolean commit() {
        if (player.isCreative()) {
            return true;
        }
        for (Allocation allocation : allocations) {
            if (allocation.usage == ItemUseType.CONSUME) {
                ItemStack extracted = allocation.handler.extractItem(allocation.slot, allocation.amount, false);
                if (extracted.getCount() != allocation.amount) {
                    return false;
                }
                continue;
            }

            ItemStack tool = allocation.handler.extractItem(allocation.slot, 1, false);
            if (tool.isEmpty()) {
                return false;
            }
            tool.setDamageValue(tool.getDamageValue() + allocation.amount);
            if (tool.getDamageValue() >= tool.getMaxDamage()) {
                tool.shrink(1);
            }
            if (!tool.isEmpty()) {
                ItemStack remainder = allocation.handler.insertItem(allocation.slot, tool, false);
                if (!remainder.isEmpty()) {
                    player.drop(remainder, false);
                }
            }
        }
        return true;
    }

    private void reserve(ItemRequirement.StackRequirement requirement) {
        int wanted = Math.max(1, requirement.stack.getCount());
        int remaining = wanted;
        for (VirtualSlot slot : slots) {
            if (remaining == 0 || !requirement.matches(slot.stack)) {
                continue;
            }
            int available = requirement.usage == ItemUseType.DAMAGE
                ? Math.max(0, slot.stack.getMaxDamage() - slot.stack.getDamageValue())
                : slot.remaining;
            int taken = Math.min(remaining, available);
            if (taken == 0) {
                continue;
            }
            allocations.add(new Allocation(slot.handler, slot.slot, taken, requirement.usage));
            if (requirement.usage == ItemUseType.DAMAGE) {
                slot.stack.setDamageValue(slot.stack.getDamageValue() + taken);
            } else {
                slot.remaining -= taken;
            }
            remaining -= taken;
        }
        if (remaining > 0) {
            ItemStack absent = requirement.stack.copyWithCount(remaining);
            mergeMissing(absent);
        }
    }

    private void mergeMissing(ItemStack absent) {
        for (ItemStack existing : missing) {
            if (ItemStack.isSameItemSameComponents(existing, absent)) {
                existing.grow(absent.getCount());
                return;
            }
        }
        missing.add(absent);
    }

    private static List<VirtualSlot> discoverSlots(ServerPlayer player) {
        List<IItemHandler> handlers = new ArrayList<>();
        handlers.add(new InvWrapper(player.getInventory()));

        // Item capabilities cover vanilla shulker boxes and modded backpacks.
        for (ItemStack carried : player.getInventory().items) {
            IItemHandler nested = carried.getCapability(Capabilities.ItemHandler.ITEM);
            if (nested != null) {
                handlers.add(nested);
            }
        }

        // Nearby Create toolboxes participate without binding the player to them.
        ToolboxHandler.getNearest(player.level(), player, 8).forEach(toolbox -> {
            IItemHandler handler = player.level().getCapability(
                Capabilities.ItemHandler.BLOCK,
                toolbox.getBlockPos(),
                null
            );
            if (handler != null) {
                handlers.add(handler);
            }
        });

        List<VirtualSlot> result = new ArrayList<>();
        for (IItemHandler handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && !handler.extractItem(slot, 1, true).isEmpty()) {
                    result.add(new VirtualSlot(handler, slot, stack.copy(), stack.getCount()));
                }
            }
        }
        return result;
    }

    private static final class VirtualSlot {
        private final IItemHandler handler;
        private final int slot;
        private final ItemStack stack;
        private int remaining;

        private VirtualSlot(IItemHandler handler, int slot, ItemStack stack, int remaining) {
            this.handler = handler;
            this.slot = slot;
            this.stack = stack;
            this.remaining = remaining;
        }
    }

    private record Allocation(IItemHandler handler, int slot, int amount, ItemUseType usage) {
    }
}
