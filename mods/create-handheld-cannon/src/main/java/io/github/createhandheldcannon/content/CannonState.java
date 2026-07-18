package io.github.createhandheldcannon.content;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Persistent cannon settings. Item contents live in the vanilla CONTAINER
 * component so vanilla networking, cloning and commands all preserve them.
 */
public final class CannonState {
    public static final int FUEL_SLOT = 0;
    public static final int SCHEMATIC_START = 1;
    public static final int SCHEMATIC_COUNT = 6;
    public static final int SLOT_COUNT = 7;
    public static final int MAX_TODO = 999;

    private static final String ROOT = "HandheldCannon";
    private static final String SELECTED = "Selected";
    private static final String TODO = "Todo";
    private static final String SHOTS = "Shots";
    private static final String REPLACE_MODE = "ReplaceMode";
    private static final String REPLACE_BLOCK_ENTITIES = "ReplaceBlockEntities";
    private static final String ADDRESS = "Address";

    private CannonState() {
    }

    public static NonNullList<ItemStack> contents(ItemStack cannon) {
        NonNullList<ItemStack> result = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        cannon.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(result);
        return result;
    }

    public static void setContents(ItemStack cannon, List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            copy.add(i < stacks.size() ? stacks.get(i).copy() : ItemStack.EMPTY);
        }
        cannon.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(copy));
    }

    public static ItemStack selectedSchematic(ItemStack cannon) {
        return contents(cannon).get(SCHEMATIC_START + selected(cannon));
    }

    public static int selected(ItemStack cannon) {
        return Math.clamp(root(cannon).getInt(SELECTED), 0, SCHEMATIC_COUNT - 1);
    }

    public static void setSelected(ItemStack cannon, int selected) {
        update(cannon, tag -> tag.putInt(SELECTED, Math.clamp(selected, 0, SCHEMATIC_COUNT - 1)));
    }

    public static int todo(ItemStack cannon, int index) {
        int[] values = root(cannon).getIntArray(TODO);
        return index >= 0 && index < values.length ? Math.clamp(values[index], 0, MAX_TODO) : 0;
    }

    public static void setTodo(ItemStack cannon, int index, int value) {
        if (index < 0 || index >= SCHEMATIC_COUNT) {
            return;
        }
        update(cannon, tag -> {
            int[] values = normalTodo(tag.getIntArray(TODO));
            values[index] = Math.clamp(value, 0, MAX_TODO);
            tag.putIntArray(TODO, values);
        });
    }

    public static void decrementSelectedTodo(ItemStack cannon) {
        int selected = selected(cannon);
        setTodo(cannon, selected, Math.max(0, todo(cannon, selected) - 1));
    }

    public static int remainingShots(ItemStack cannon) {
        return Math.max(0, root(cannon).getInt(SHOTS));
    }

    public static void setRemainingShots(ItemStack cannon, int shots) {
        update(cannon, tag -> tag.putInt(SHOTS, Math.max(0, shots)));
    }

    public static ReplaceMode replaceMode(ItemStack cannon) {
        int ordinal = Math.clamp(root(cannon).getInt(REPLACE_MODE), 0, ReplaceMode.values().length - 1);
        return ReplaceMode.values()[ordinal];
    }

    public static void cycleReplaceMode(ItemStack cannon) {
        ReplaceMode[] values = ReplaceMode.values();
        update(cannon, tag -> tag.putInt(REPLACE_MODE, (replaceMode(cannon).ordinal() + 1) % values.length));
    }

    public static boolean replaceBlockEntities(ItemStack cannon) {
        return root(cannon).getBoolean(REPLACE_BLOCK_ENTITIES);
    }

    public static void toggleReplaceBlockEntities(ItemStack cannon) {
        update(cannon, tag -> tag.putBoolean(REPLACE_BLOCK_ENTITIES, !replaceBlockEntities(cannon)));
    }

    public static String address(ItemStack cannon) {
        return root(cannon).getString(ADDRESS);
    }

    public static void setAddress(ItemStack cannon, String address) {
        String safe = address == null ? "" : address.strip();
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        String finalSafe = safe;
        update(cannon, tag -> tag.putString(ADDRESS, finalSafe));
    }

    private static int[] normalTodo(int[] source) {
        int[] result = new int[SCHEMATIC_COUNT];
        System.arraycopy(source, 0, result, 0, Math.min(source.length, result.length));
        return result;
    }

    private static CompoundTag root(ItemStack cannon) {
        CompoundTag custom = cannon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return custom.contains(ROOT) ? custom.getCompound(ROOT) : new CompoundTag();
    }

    private static void update(ItemStack cannon, java.util.function.Consumer<CompoundTag> mutator) {
        CompoundTag custom = cannon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag state = custom.contains(ROOT) ? custom.getCompound(ROOT) : new CompoundTag();
        mutator.accept(state);
        custom.put(ROOT, state);
        cannon.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    }

    public enum ReplaceMode {
        DONT_REPLACE,
        REPLACE_SOLID,
        REPLACE_ANY,
        REPLACE_EMPTY
    }
}
