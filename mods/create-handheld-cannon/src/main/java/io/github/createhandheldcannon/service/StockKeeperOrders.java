package io.github.createhandheldcannon.service;

import java.util.List;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;

import io.github.createhandheldcannon.content.CannonState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class StockKeeperOrders {
    private StockKeeperOrders() {
    }

    public static String placeOrder(ServerPlayer player, ItemStack cannon, int entityId) {
        Entity keeper = player.level().getEntity(entityId);
        if (keeper == null || player.distanceToSqr(keeper) > 64) {
            return "stock_keeper_out_of_range";
        }
        BlockPos tickerPos = StockTickerInteractionHandler.getStockTickerPosition(keeper);
        if (tickerPos == null || !(player.level().getBlockEntity(tickerPos) instanceof StockTickerBlockEntity ticker)) {
            return "not_stock_keeper";
        }
        if (!ticker.behaviour.mayInteract(player)) {
            return "stock_keeper_protected";
        }
        String address = CannonState.address(cannon);
        if (address.isBlank()) {
            return "address_required";
        }
        List<ItemStack> shortages = CreateSchematicBridge.todoShortages(player, cannon);
        if (shortages.isEmpty()) {
            return "nothing_to_order";
        }
        List<BigItemStack> order = shortages.stream()
            .map(stack -> new BigItemStack(stack.copyWithCount(1), stack.getCount()))
            .toList();
        boolean sent = ticker.broadcastPackageRequest(
            RequestType.PLAYER,
            PackageOrderWithCrafts.simple(order),
            null,
            address
        );
        return sent ? "order_placed" : "order_failed";
    }
}
