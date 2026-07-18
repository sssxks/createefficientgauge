package io.github.createhandheldcannon.service;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.StackRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.StrictNbtStackRequirement;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.infrastructure.config.AllConfigs;

import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.content.CannonState.ReplaceMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** The only class that knows Create's schematic internals. No mixins are used. */
public final class CreateSchematicBridge {
    public static final int MAX_TARGETS = 256;
    public static final double MAX_RANGE = 75.0;

    private CreateSchematicBridge() {
    }

    public static CannonPlan plan(
        ServerPlayer player,
        ItemStack cannon,
        BlockPos anchor,
        Rotation rotation,
        Mirror mirror
    ) {
        ItemStack source = CannonState.selectedSchematic(cannon);
        if (source.isEmpty() || !source.has(AllDataComponents.SCHEMATIC_FILE)) {
            return CannonPlan.invalid("no_schematic", anchor, rotation, mirror);
        }
        if (player.position().distanceToSqr(anchor.getCenter()) > MAX_RANGE * MAX_RANGE) {
            return CannonPlan.invalid("out_of_range", anchor, rotation, mirror);
        }

        ItemStack deployed = deployedCopy(source, anchor, rotation, mirror);
        SchematicPrinter printer = new SchematicPrinter();
        printer.loadSchematic(deployed, player.level(), true);
        if (!printer.isLoaded() || printer.isErrored() || printer.isWorldEmpty()) {
            return CannonPlan.invalid("invalid_schematic", anchor, rotation, mirror);
        }

        List<ItemRequirement.StackRequirement> requirements = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        while (printer.advanceCurrentPos()) {
            BlockPos target = printer.getCurrentTarget();
            if (target == null || player.position().distanceToSqr(target.getCenter()) > MAX_RANGE * MAX_RANGE) {
                return CannonPlan.invalid("out_of_range", anchor, rotation, mirror);
            }
            if (!printer.shouldPlaceCurrent(player.level(), (pos, state, blockEntity, replacing, replacingOther, normal) ->
                shouldPlace(player, cannon, pos, state, blockEntity, replacing, replacingOther, normal))) {
                continue;
            }
            ItemRequirement requirement = printer.getCurrentRequirement();
            if (requirement.isInvalid()) {
                return CannonPlan.invalid("unsupported_block", anchor, rotation, mirror);
            }
            requirements.addAll(requirement.getRequiredItems());
            targets.add(target.immutable());
            if (targets.size() > MAX_TARGETS) {
                return CannonPlan.invalid("too_large", anchor, rotation, mirror);
            }
        }

        if (targets.isEmpty()) {
            return CannonPlan.invalid("nothing_to_place", anchor, rotation, mirror);
        }

        MaterialTransaction materials = new MaterialTransaction(player);
        boolean materialReady = materials.reserve(requirements);
        List<ItemStack> missing = new ArrayList<>(materials.missing());
        int missingGunpowder = missingGunpowder(cannon, targets.size(), player.isCreative());
        if (missingGunpowder > 0) {
            missing.add(new ItemStack(net.minecraft.world.item.Items.GUNPOWDER, missingGunpowder));
        }
        boolean valid = materialReady && missingGunpowder == 0;
        return new CannonPlan(
            valid,
            valid ? "ready" : "missing_materials",
            targets.size(),
            List.copyOf(requirements),
            List.copyOf(missing),
            List.copyOf(targets),
            anchor,
            rotation,
            mirror
        );
    }

    public static DeploymentResult deploy(
        ServerPlayer player,
        ItemStack cannon,
        BlockPos anchor,
        Rotation rotation,
        Mirror mirror
    ) {
        CannonPlan plan = plan(player, cannon, anchor, rotation, mirror);
        if (!plan.valid()) {
            return new DeploymentResult(false, plan.message(), plan.targets());
        }

        MaterialTransaction materials = new MaterialTransaction(player);
        if (!materials.reserve(plan.requirements()) || !materials.commit()) {
            return new DeploymentResult(false, "inventory_changed", List.of());
        }
        if (!consumeFuel(cannon, plan.targetCount(), player.isCreative())) {
            return new DeploymentResult(false, "fuel_changed", List.of());
        }

        ItemStack deployed = deployedCopy(CannonState.selectedSchematic(cannon), anchor, rotation, mirror);
        SchematicPrinter printer = new SchematicPrinter();
        printer.loadSchematic(deployed, player.level(), true);
        while (printer.advanceCurrentPos()) {
            if (!printer.shouldPlaceCurrent(player.level(), (pos, state, blockEntity, replacing, replacingOther, normal) ->
                shouldPlace(player, cannon, pos, state, blockEntity, replacing, replacingOther, normal))) {
                continue;
            }
            printer.handleCurrentTarget(
                (target, state, blockEntity) -> placeBlock(player, target, state, blockEntity),
                (target, entity) -> placeEntity(player, entity)
            );
        }
        printer.sendBlockUpdates(player.level());
        CannonState.decrementSelectedTodo(cannon);
        player.getInventory().setChanged();
        return new DeploymentResult(true, "deployed", plan.targets());
    }

    /** Calculates shortages for every loaded schematic multiplied by its todo count. */
    public static List<ItemStack> todoShortages(ServerPlayer player, ItemStack cannon) {
        List<StackRequirement> combined = new ArrayList<>();
        var contents = CannonState.contents(cannon);
        long totalShots = 0;
        for (int index = 0; index < CannonState.SCHEMATIC_COUNT; index++) {
            int todo = CannonState.todo(cannon, index);
            ItemStack schematic = contents.get(CannonState.SCHEMATIC_START + index);
            if (todo <= 0 || schematic.isEmpty() || !schematic.has(AllDataComponents.SCHEMATIC_FILE)) {
                continue;
            }
            SchematicPrinter printer = new SchematicPrinter();
            printer.loadSchematic(
                deployedCopy(schematic, player.blockPosition(), Rotation.NONE, Mirror.NONE),
                player.level(),
                true
            );
            if (!printer.isLoaded() || printer.isErrored()) {
                continue;
            }
            while (printer.advanceCurrentPos()) {
                boolean[] ignored = {false};
                printer.handleCurrentTarget(
                    (target, state, blockEntity) -> ignored[0] = shouldIgnore(state, blockEntity),
                    (target, entity) -> { }
                );
                if (ignored[0]) {
                    continue;
                }
                ItemRequirement requirement = printer.getCurrentRequirement();
                if (requirement.isInvalid()) {
                    continue;
                }
                for (StackRequirement stackRequirement : requirement.getRequiredItems()) {
                    mergeRequirement(combined, stackRequirement, todo);
                }
                totalShots += todo;
            }
        }

        if (!player.isCreative() && totalShots > 0) {
            int perPowder = Math.max(1, AllConfigs.server().schematics.schematicannonShotsPerGunpowder.get());
            long afterStored = Math.max(0, totalShots - CannonState.remainingShots(cannon));
            int powder = (int) Math.min(Integer.MAX_VALUE, (afterStored + perPowder - 1) / perPowder);
            powder = Math.max(0, powder - contents.get(CannonState.FUEL_SLOT).getCount());
            if (powder > 0) {
                mergeRequirement(
                    combined,
                    new StackRequirement(new ItemStack(net.minecraft.world.item.Items.GUNPOWDER), ItemUseType.CONSUME),
                    powder
                );
            }
        }

        MaterialTransaction transaction = new MaterialTransaction(player);
        transaction.reserve(combined);
        return transaction.missing();
    }

    private static void mergeRequirement(List<StackRequirement> result, StackRequirement source, int multiplier) {
        long amount = (long) Math.max(1, source.stack.getCount()) * Math.max(1, multiplier);
        while (amount > 0) {
            int batch = (int) Math.min(Integer.MAX_VALUE, amount);
            ItemStack stack = source.stack.copyWithCount(batch);
            StackRequirement scaled = source instanceof StrictNbtStackRequirement
                ? new StrictNbtStackRequirement(stack, source.usage)
                : new StackRequirement(stack, source.usage);
            result.add(scaled);
            amount -= batch;
        }
    }

    public static ItemStack deployedCopy(ItemStack schematic, BlockPos anchor, Rotation rotation, Mirror mirror) {
        ItemStack copy = schematic.copyWithCount(1);
        copy.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        copy.set(AllDataComponents.SCHEMATIC_ANCHOR, anchor);
        copy.set(AllDataComponents.SCHEMATIC_ROTATION, rotation);
        copy.set(AllDataComponents.SCHEMATIC_MIRROR, mirror);
        copy.remove(AllDataComponents.SCHEMATIC_HASH);
        return copy;
    }

    private static boolean shouldPlace(
        ServerPlayer player,
        ItemStack cannon,
        BlockPos pos,
        BlockState state,
        BlockEntity blockEntity,
        BlockState replacing,
        BlockState replacingOther,
        boolean normalCube
    ) {
        if (!CannonState.replaceBlockEntities(cannon)
            && (replacing.hasBlockEntity() || replacingOther != null && replacingOther.hasBlockEntity())) {
            return false;
        }
        if (shouldIgnore(state, blockEntity)) {
            return false;
        }

        boolean placingAir = state.isAir();
        ReplaceMode mode = CannonState.replaceMode(cannon);
        if (mode == ReplaceMode.REPLACE_EMPTY) {
            return true;
        }
        if (mode == ReplaceMode.REPLACE_ANY && !placingAir) {
            return true;
        }
        boolean replacingSolid = replacing.isRedstoneConductor(player.level(), pos)
            || replacingOther != null && replacingOther.isRedstoneConductor(player.level(), pos);
        if (mode == ReplaceMode.REPLACE_SOLID && (normalCube || !replacingSolid) && !placingAir) {
            return true;
        }
        return mode == ReplaceMode.DONT_REPLACE && !replacingSolid && !placingAir;
    }

    private static boolean shouldIgnore(BlockState state, BlockEntity blockEntity) {
        if (state.is(Blocks.STRUCTURE_VOID)) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
            && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return true;
        }
        if (state.getBlock() instanceof PistonHeadBlock) {
            return true;
        }
        return AllBlocks.BELT.has(state) && state.getValue(BeltBlock.PART) == BeltPart.MIDDLE;
    }

    private static void placeBlock(ServerPlayer player, BlockPos target, BlockState state, BlockEntity blockEntity) {
        if (AllBlocks.BELT.has(state)) {
            BlockState stripped = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity
                .stripBeltIfNotLast(state);
            if (stripped.isAir()) {
                return;
            }
            if (AllBlocks.BELT.has(stripped) && blockEntity instanceof BeltBlockEntity belt) {
                placeBelt(player, target, stripped, belt);
                return;
            }
            BlockHelper.placeSchematicBlock(player.level(), stripped, target, ItemStack.EMPTY, null);
            return;
        }
        CompoundTag data = BlockHelper.prepareBlockEntityData(player.level(), state, blockEntity);
        BlockHelper.placeSchematicBlock(player.level(), state, target, ItemStack.EMPTY, data);
    }

    private static void placeBelt(ServerPlayer player, BlockPos target, BlockState state, BeltBlockEntity source) {
        boolean isStart = state.getValue(BeltBlock.PART) == BeltPart.START;
        BlockPos offset = BeltBlock.nextSegmentPosition(state, BlockPos.ZERO, isStart);
        int last = Math.max(0, source.beltLength - 1);
        Axis axis = state.getValue(BeltBlock.SLOPE) == BeltSlope.SIDEWAYS
            ? Axis.Y
            : state.getValue(BeltBlock.HORIZONTAL_FACING).getClockWise().getAxis();

        BeltBlockEntity.CasingType[] casings = new BeltBlockEntity.CasingType[source.beltLength];
        java.util.Arrays.fill(casings, BeltBlockEntity.CasingType.NONE);
        BlockPos sourcePos = target;
        for (int i = 0; i < source.beltLength; i++) {
            if (source.getLevel().getBlockEntity(sourcePos) instanceof BeltBlockEntity segment) {
                casings[i] = segment.casing;
            }
            BlockState sourceState = source.getLevel().getBlockState(sourcePos);
            sourcePos = BeltBlock.nextSegmentPosition(sourceState, sourcePos,
                state.getValue(BeltBlock.PART) != BeltPart.END);
        }

        player.level().setBlockAndUpdate(target,
            AllBlocks.SHAFT.getDefaultState().setValue(AbstractSimpleShaftBlock.AXIS, axis));
        BeltConnectorItem.createBelts(player.level(), target,
            target.offset(offset.getX() * last, offset.getY() * last, offset.getZ() * last));
        for (int segment = 0; segment < casings.length; segment++) {
            if (casings[segment] == BeltBlockEntity.CasingType.NONE) {
                continue;
            }
            BlockPos casingTarget = target.offset(
                offset.getX() * segment,
                offset.getY() * segment,
                offset.getZ() * segment
            );
            if (player.level().getBlockEntity(casingTarget) instanceof BeltBlockEntity placed) {
                placed.setCasingType(casings[segment]);
            }
        }
    }

    private static void placeEntity(ServerPlayer player, Entity entity) {
        entity.setUUID(java.util.UUID.randomUUID());
        player.level().addFreshEntity(entity);
    }

    private static int missingGunpowder(ItemStack cannon, int shots, boolean creative) {
        if (creative) {
            return 0;
        }
        int perPowder = Math.max(1, AllConfigs.server().schematics.schematicannonShotsPerGunpowder.get());
        int need = Math.max(0, shots - CannonState.remainingShots(cannon));
        int powder = (need + perPowder - 1) / perPowder;
        int available = CannonState.contents(cannon).get(CannonState.FUEL_SLOT).getCount();
        return Math.max(0, powder - available);
    }

    private static boolean consumeFuel(ItemStack cannon, int shots, boolean creative) {
        if (creative) {
            return true;
        }
        int perPowder = Math.max(1, AllConfigs.server().schematics.schematicannonShotsPerGunpowder.get());
        int stored = CannonState.remainingShots(cannon);
        int needed = Math.max(0, shots - stored);
        int powder = (needed + perPowder - 1) / perPowder;
        var contents = CannonState.contents(cannon);
        ItemStack fuel = contents.get(CannonState.FUEL_SLOT);
        if (fuel.getCount() < powder) {
            return false;
        }
        fuel.shrink(powder);
        CannonState.setContents(cannon, contents);
        CannonState.setRemainingShots(cannon, stored + powder * perPowder - shots);
        return true;
    }

    public record DeploymentResult(boolean success, String message, List<BlockPos> targets) {
    }
}
