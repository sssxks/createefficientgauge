package io.github.createhandheldcannon.net;

import java.util.ArrayList;
import java.util.List;

import io.github.createhandheldcannon.CreateHandheldCannon;
import io.github.createhandheldcannon.client.ClientCannonController;
import io.github.createhandheldcannon.content.CannonMenu;
import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.service.CannonPlan;
import io.github.createhandheldcannon.service.CreateSchematicBridge;
import io.github.createhandheldcannon.service.StockKeeperOrders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CannonNetworking {
    private CannonNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(PlanRequest.TYPE, PlanRequest.CODEC, CannonNetworking::handlePlan);
        registrar.playToServer(DeployRequest.TYPE, DeployRequest.CODEC, CannonNetworking::handleDeploy);
        registrar.playToServer(UpdateAddress.TYPE, UpdateAddress.CODEC, CannonNetworking::handleAddress);
        registrar.playToServer(ResupplyRequest.TYPE, ResupplyRequest.CODEC, CannonNetworking::handleResupply);
        registrar.playToClient(PlanStatus.TYPE, PlanStatus.CODEC, CannonNetworking::handleStatus);
        registrar.playToClient(DeployEffect.TYPE, DeployEffect.CODEC, CannonNetworking::handleEffect);
    }

    private static void handlePlan(PlanRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack cannon = player.getItemInHand(payload.hand());
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return;
        }
        CannonPlan plan = CreateSchematicBridge.plan(
            player, cannon, payload.anchor(), payload.rotation(), payload.mirror()
        );
        context.reply(PlanStatus.from(plan));
    }

    private static void handleDeploy(DeployRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack cannon = player.getItemInHand(payload.hand());
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return;
        }
        CreateSchematicBridge.DeploymentResult result = CreateSchematicBridge.deploy(
            player, cannon, payload.anchor(), payload.rotation(), payload.mirror()
        );
        context.reply(new PlanStatus(result.success(), result.message(), result.targets().size(), List.of()));
        if (result.success()) {
            context.reply(new DeployEffect(player.position().add(0, 1.25, 0), result.targets()));
        }
    }

    private static void handleAddress(UpdateAddress payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof CannonMenu menu)) {
            return;
        }
        ItemStack cannon = menu.cannonStack(player);
        if (cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            CannonState.setAddress(cannon, payload.address());
        }
    }

    private static void handleResupply(ResupplyRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack cannon = player.getMainHandItem();
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return;
        }
        String message = StockKeeperOrders.placeOrder(player, cannon, payload.entityId());
        context.reply(new PlanStatus(false, message, 0, List.of()));
    }

    private static void handleStatus(PlanStatus payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientCannonController.acceptStatus(payload);
        }
    }

    private static void handleEffect(DeployEffect payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientCannonController.acceptEffect(payload);
        }
    }

    public record PlanRequest(
        InteractionHand hand,
        BlockPos anchor,
        Rotation rotation,
        Mirror mirror
    ) implements CustomPacketPayload {
        public static final Type<PlanRequest> TYPE = new Type<>(CreateHandheldCannon.id("plan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanRequest> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeEnum(value.hand);
                buffer.writeBlockPos(value.anchor);
                buffer.writeEnum(value.rotation);
                buffer.writeEnum(value.mirror);
            },
            buffer -> new PlanRequest(
                buffer.readEnum(InteractionHand.class),
                buffer.readBlockPos(),
                buffer.readEnum(Rotation.class),
                buffer.readEnum(Mirror.class)
            )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeployRequest(
        InteractionHand hand,
        BlockPos anchor,
        Rotation rotation,
        Mirror mirror
    ) implements CustomPacketPayload {
        public static final Type<DeployRequest> TYPE = new Type<>(CreateHandheldCannon.id("deploy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeployRequest> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeEnum(value.hand);
                buffer.writeBlockPos(value.anchor);
                buffer.writeEnum(value.rotation);
                buffer.writeEnum(value.mirror);
            },
            buffer -> new DeployRequest(
                buffer.readEnum(InteractionHand.class),
                buffer.readBlockPos(),
                buffer.readEnum(Rotation.class),
                buffer.readEnum(Mirror.class)
            )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlanStatus(boolean valid, String message, int targetCount, List<ItemStack> missing)
        implements CustomPacketPayload {
        public static final Type<PlanStatus> TYPE = new Type<>(CreateHandheldCannon.id("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanStatus> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeBoolean(value.valid);
                buffer.writeUtf(value.message, 64);
                buffer.writeVarInt(value.targetCount);
                buffer.writeVarInt(value.missing.size());
                value.missing.forEach(stack -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack));
            },
            buffer -> {
                boolean valid = buffer.readBoolean();
                String message = buffer.readUtf(64);
                int targetCount = buffer.readVarInt();
                int size = Math.min(64, buffer.readVarInt());
                List<ItemStack> missing = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    missing.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                }
                return new PlanStatus(valid, message, targetCount, List.copyOf(missing));
            }
        );

        public static PlanStatus from(CannonPlan plan) {
            return new PlanStatus(plan.valid(), plan.message(), plan.targetCount(), plan.missing());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UpdateAddress(String address) implements CustomPacketPayload {
        public static final Type<UpdateAddress> TYPE = new Type<>(CreateHandheldCannon.id("address"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateAddress> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeUtf(value.address, 64),
            buffer -> new UpdateAddress(buffer.readUtf(64))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResupplyRequest(int entityId) implements CustomPacketPayload {
        public static final Type<ResupplyRequest> TYPE = new Type<>(CreateHandheldCannon.id("resupply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResupplyRequest> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeVarInt(value.entityId),
            buffer -> new ResupplyRequest(buffer.readVarInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeployEffect(net.minecraft.world.phys.Vec3 origin, List<BlockPos> targets)
        implements CustomPacketPayload {
        private static final int MAX_EFFECT_TARGETS = CreateSchematicBridge.MAX_TARGETS;
        public static final Type<DeployEffect> TYPE = new Type<>(CreateHandheldCannon.id("effect"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeployEffect> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVec3(value.origin);
                buffer.writeVarInt(value.targets.size());
                value.targets.forEach(buffer::writeBlockPos);
            },
            buffer -> {
                var origin = buffer.readVec3();
                int size = Math.min(MAX_EFFECT_TARGETS, buffer.readVarInt());
                List<BlockPos> targets = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    targets.add(buffer.readBlockPos());
                }
                return new DeployEffect(origin, List.copyOf(targets));
            }
        );
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
