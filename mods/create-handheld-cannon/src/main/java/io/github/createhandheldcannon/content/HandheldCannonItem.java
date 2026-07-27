package io.github.createhandheldcannon.content;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public final class HandheldCannonItem extends Item {
    public HandheldCannonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(
                    new SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new CannonMenu(containerId, inventory, hand),
                        Component.translatable("container.createhandheldcannon.handheld_cannon")
                    ),
                    buffer -> buffer.writeEnum(hand)
                );
            }
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
        }
        return InteractionResultHolder.pass(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ItemStack selected = CannonState.selectedSchematic(stack);
        if (!selected.isEmpty()) {
            tooltip.add(Component.translatable(
                "tooltip.createhandheldcannon.selected",
                selected.getHoverName(),
                CannonState.todo(stack, CannonState.selected(stack))
            ).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.createhandheldcannon.open").withStyle(ChatFormatting.DARK_GRAY));
    }
}
