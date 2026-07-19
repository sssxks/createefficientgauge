package io.github.createhandheldcannon.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.utility.CreatePaths;

import io.github.createhandheldcannon.CreateHandheldCannon;
import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.service.CreateSchematicBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateHandheldCannon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CannonGameTests {
    private CannonGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void stateRoundTrip(GameTestHelper helper) {
        ItemStack cannon = new ItemStack(CreateHandheldCannon.HANDHELD_CANNON.get());
        CannonState.setContents(cannon, List.of(
            new ItemStack(Items.GUNPOWDER, 12),
            AllItems.SCHEMATIC.asStack(),
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        ));
        CannonState.setSelected(cannon, 0);
        CannonState.setTodo(cannon, 0, 17);
        CannonState.setRemainingShots(cannon, 23);
        CannonState.cycleReplaceMode(cannon);
        CannonState.toggleReplaceBlockEntities(cannon);

        ItemStack copy = cannon.copy();
        helper.assertTrue(CannonState.contents(copy).get(0).getCount() == 12, "gunpowder slot was not persisted");
        helper.assertTrue(AllItems.SCHEMATIC.isIn(CannonState.selectedSchematic(copy)), "schematic slot was not persisted");
        helper.assertTrue(CannonState.todo(copy, 0) == 17, "todo count was not persisted");
        helper.assertTrue(CannonState.remainingShots(copy) == 23, "remaining fuel was not persisted");
        helper.assertTrue(CannonState.replaceMode(copy) == CannonState.ReplaceMode.REPLACE_SOLID,
            "replace mode was not persisted");
        helper.assertTrue(CannonState.replaceBlockEntities(copy), "block entity setting was not persisted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void deploysSingleBlockSchematic(GameTestHelper helper) throws IOException {
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 1));
        helper.getLevel().setBlockAndUpdate(source, Blocks.STONE.defaultBlockState());

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(helper.getLevel(), source, new Vec3i(1, 1, 1), false, null);
        String owner = "createhandheldcannon-gametest";
        String fileName = "single_stone.nbt";
        Path ownerDirectory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(owner);
        Path schematicFile = ownerDirectory.resolve(fileName);
        Files.createDirectories(ownerDirectory);
        CompoundTag templateTag = template.save(new CompoundTag());
        NbtIo.writeCompressed(templateTag, schematicFile);

        try {
            helper.getLevel().setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());

            ItemStack schematic = AllItems.SCHEMATIC.asStack();
            schematic.set(AllDataComponents.SCHEMATIC_OWNER, owner);
            schematic.set(AllDataComponents.SCHEMATIC_FILE, fileName);
            schematic.set(AllDataComponents.SCHEMATIC_BOUNDS, new Vec3i(1, 1, 1));

            ItemStack cannon = new ItemStack(CreateHandheldCannon.HANDHELD_CANNON.get());
            CannonState.setContents(cannon, List.of(
                ItemStack.EMPTY,
                schematic,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY
            ));
            CannonState.setTodo(cannon, 0, 1);
            CannonState.setRemainingShots(cannon, 1);

            CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "handheld-cannon-test"),
                false
            );
            ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                cookie.gameProfile(),
                cookie.clientInformation()
            );
            player.setPos(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
            player.getInventory().add(new ItemStack(Items.STONE));

            var result = CreateSchematicBridge.deploy(
                player,
                cannon,
                target,
                Rotation.NONE,
                Mirror.NONE
            );
            helper.assertTrue(result.success(), "deployment failed: " + result.message());
            helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.STONE), "target block was not placed");
            helper.assertTrue(!player.getInventory().contains(new ItemStack(Items.STONE)), "material was not consumed");
            helper.assertTrue(CannonState.todo(cannon, 0) == 0, "todo count was not decremented");
            helper.succeed();
        } finally {
            Files.deleteIfExists(schematicFile);
        }
    }
}
