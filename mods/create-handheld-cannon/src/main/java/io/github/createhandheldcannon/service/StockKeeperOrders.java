package io.github.createhandheldcannon.service;

import java.util.List;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class StockKeeperOrders {
    private StockKeeperOrders() {
    }

    public static RequestResult openRequest(ServerPlayer player, ItemStack cannon, int entityId) {
        Entity keeper = player.level().getEntity(entityId);
        if (keeper == null || player.distanceToSqr(keeper) > 64) {
            return RequestResult.error("stock_keeper_out_of_range");
        }
        BlockPos tickerPos = StockTickerInteractionHandler.getStockTickerPosition(keeper);
        if (tickerPos == null || !(player.level().getBlockEntity(tickerPos) instanceof StockTickerBlockEntity ticker)) {
            return RequestResult.error("not_stock_keeper");
        }
        if (!ticker.behaviour.mayInteract(player)) {
            return RequestResult.error("stock_keeper_protected");
        }
        List<ItemStack> shortages = CreateSchematicBridge.todoShortages(player, cannon);
        if (shortages.isEmpty()) {
            return RequestResult.error("nothing_to_order");
        }
        if (!StockTickerInteractionHandler.interactWithLogisticsManagerAt(player, player.level(), tickerPos)) {
            return RequestResult.error("not_stock_keeper");
        }
        return new RequestResult(true, "request_opened", List.copyOf(shortages));
    }

    public record RequestResult(boolean opened, String message, List<ItemStack> stacks) {
        static RequestResult error(String message) {
            return new RequestResult(false, message, List.of());
        }
    }
}
