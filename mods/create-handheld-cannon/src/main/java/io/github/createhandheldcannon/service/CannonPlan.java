package io.github.createhandheldcannon.service;

import java.util.List;

import com.simibubi.create.content.schematics.requirement.ItemRequirement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public record CannonPlan(
    boolean valid,
    String message,
    int targetCount,
    List<ItemRequirement.StackRequirement> requirements,
    List<ItemStack> missing,
    List<BlockPos> targets,
    BlockPos anchor,
    Rotation rotation,
    Mirror mirror
) {
    public static CannonPlan invalid(String message, BlockPos anchor, Rotation rotation, Mirror mirror) {
        return new CannonPlan(false, message, 0, List.of(), List.of(), List.of(), anchor, rotation, mirror);
    }
}
