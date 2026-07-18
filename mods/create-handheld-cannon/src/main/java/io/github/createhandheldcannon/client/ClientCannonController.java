package io.github.createhandheldcannon.client;

import java.util.List;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.content.schematics.SchematicInstances;
import com.simibubi.create.content.schematics.client.SchematicRenderer;

import io.github.createhandheldcannon.CreateHandheldCannon;
import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.net.CannonNetworking.DeployEffect;
import io.github.createhandheldcannon.net.CannonNetworking.DeployRequest;
import io.github.createhandheldcannon.net.CannonNetworking.PlanRequest;
import io.github.createhandheldcannon.net.CannonNetworking.PlanStatus;
import io.github.createhandheldcannon.net.CannonNetworking.ResupplyRequest;
import io.github.createhandheldcannon.service.CreateSchematicBridge;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = CreateHandheldCannon.MOD_ID, value = Dist.CLIENT)
public final class ClientCannonController {
    private static BlockPos anchor;
    private static boolean locked;
    private static Rotation rotation = Rotation.NONE;
    private static Mirror mirror = Mirror.NONE;
    private static PlanStatus status;
    private static SchematicRenderer renderer;
    private static int rendererHash;
    private static ItemStack renderedSchematic = ItemStack.EMPTY;

    private ClientCannonController() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        ItemStack cannon = minecraft.player.getMainHandItem();
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            reset();
            return;
        }
        ItemStack schematic = CannonState.selectedSchematic(cannon);
        if (schematic.isEmpty() || !schematic.has(AllDataComponents.SCHEMATIC_FILE)) {
            renderer = null;
            renderedSchematic = ItemStack.EMPTY;
            return;
        }
        if (!locked) {
            anchor = pointedAnchor(minecraft, schematic);
        }
        updateRenderer(minecraft, schematic);
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.isShiftKeyDown()) {
            return;
        }
        ItemStack cannon = minecraft.player.getMainHandItem();
        if (!cannon.is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return;
        }

        if (minecraft.hitResult instanceof EntityHitResult entityHit
            && StockTickerInteractionHandler.getStockTickerPosition(entityHit.getEntity()) != null) {
            PacketDistributor.sendToServer(new ResupplyRequest(entityHit.getEntity().getId()));
            event.setCanceled(true);
            return;
        }

        ItemStack schematic = CannonState.selectedSchematic(cannon);
        if (schematic.isEmpty() || anchor == null) {
            return;
        }
        if (!locked) {
            locked = true;
            status = null;
            PacketDistributor.sendToServer(new PlanRequest(InteractionHand.MAIN_HAND, anchor, rotation, mirror));
        } else if (status != null && status.valid()) {
            status = null;
            PacketDistributor.sendToServer(new DeployRequest(InteractionHand.MAIN_HAND, anchor, rotation, mirror));
        } else {
            PacketDistributor.sendToServer(new PlanRequest(InteractionHand.MAIN_HAND, anchor, rotation, mirror));
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!locked || Minecraft.getInstance().player == null) {
            return;
        }
        int direction = (int) Math.signum(event.getScrollDeltaY());
        if (direction == 0) {
            return;
        }
        if (Minecraft.getInstance().player.isShiftKeyDown()) {
            anchor = anchor.above(direction);
        } else if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            mirror = mirror == Mirror.NONE ? Mirror.FRONT_BACK : Mirror.NONE;
            renderer = null;
        } else {
            rotation = direction > 0 ? rotation.getRotated(Rotation.CLOCKWISE_90)
                : rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
            renderer = null;
        }
        status = null;
        PacketDistributor.sendToServer(new PlanRequest(InteractionHand.MAIN_HAND, anchor, rotation, mirror));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (locked && event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_R) {
            locked = false;
            status = null;
        }
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || renderer == null || anchor == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack cannon = minecraft.player.getMainHandItem();
        ItemStack schematic = CannonState.selectedSchematic(cannon);
        var bounds = schematic.get(AllDataComponents.SCHEMATIC_BOUNDS);
        if (bounds == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(anchor.getX() - camera.x, anchor.getY() - camera.y, anchor.getZ() - camera.z);

        DefaultSuperRenderTypeBuffer buffers = DefaultSuperRenderTypeBuffer.getInstance();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.72f, 0.9f, 1.0f, 0.72f);
        renderer.render(pose, buffers);
        buffers.draw();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        int x = bounds.getX();
        int z = bounds.getZ();
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            int swap = x;
            x = z;
            z = swap;
        }
        float[] color = outlineColor();
        LevelRenderer.renderLineBox(
            pose,
            minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines()),
            new AABB(0, 0, 0, x, bounds.getY(), z),
            color[0], color[1], color[2], 1.0f
        );
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        pose.popPose();
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
            || !minecraft.player.getMainHandItem().is(CreateHandheldCannon.HANDHELD_CANNON.get())) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int center = graphics.guiWidth() / 2;
        Component line;
        if (!locked) {
            line = Component.translatable("hud.createhandheldcannon.place").withStyle(ChatFormatting.AQUA);
        } else if (status == null) {
            line = Component.translatable("hud.createhandheldcannon.checking").withStyle(ChatFormatting.YELLOW);
        } else if (status.valid()) {
            line = Component.translatable("hud.createhandheldcannon.ready", status.targetCount())
                .withStyle(ChatFormatting.GREEN);
        } else {
            line = Component.translatable("hud.createhandheldcannon.status." + status.message())
                .withStyle(ChatFormatting.RED);
        }
        graphics.drawCenteredString(minecraft.font, line, center, graphics.guiHeight() - 64, 0xFFFFFFFF);
        if (locked) {
            Component tools = Component.translatable("hud.createhandheldcannon.toolbar");
            graphics.fill(center - 118, graphics.guiHeight() - 48, center + 118, graphics.guiHeight() - 32, 0xA020252B);
            graphics.drawCenteredString(minecraft.font, tools, center, graphics.guiHeight() - 44, 0xFFE0E6EA);
            if (status != null && !status.missing().isEmpty()) {
                List<ItemStack> shown = status.missing().stream().limit(4).toList();
                int itemStart = center - shown.size() * 10;
                int itemY = graphics.guiHeight() - 98;
                graphics.fill(itemStart - 3, itemY - 3, itemStart + shown.size() * 20 + 3, itemY + 19, 0xB0101417);
                for (int i = 0; i < shown.size(); i++) {
                    ItemStack stack = shown.get(i);
                    int itemX = itemStart + i * 20;
                    graphics.renderItem(stack, itemX, itemY);
                    graphics.renderItemDecorations(minecraft.font, stack, itemX, itemY);
                }
                String missing = status.missing().stream()
                    .limit(4)
                    .map(stack -> stack.getHoverName().getString() + " ×" + stack.getCount())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
                graphics.drawCenteredString(minecraft.font, missing, center, graphics.guiHeight() - 76, 0xFFFF7777);
            }
        }
    }

    public static void acceptStatus(PlanStatus incoming) {
        status = incoming;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && (incoming.message().startsWith("order_")
            || incoming.message().contains("stock_keeper")
            || incoming.message().equals("not_stock_keeper")
            || incoming.message().equals("nothing_to_order"))) {
            minecraft.player.displayClientMessage(
                Component.translatable("hud.createhandheldcannon.status." + incoming.message()),
                true
            );
        } else if (minecraft.player != null && incoming.message().contains("address")) {
            minecraft.player.displayClientMessage(
                Component.translatable("message.createhandheldcannon." + incoming.message()),
                true
            );
        }
        if (incoming.message().equals("deployed")) {
            locked = false;
        }
    }

    public static void acceptEffect(DeployEffect effect) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        List<BlockPos> targets = effect.targets();
        int stride = Math.max(1, targets.size() / 64);
        for (int i = 0; i < targets.size(); i += stride) {
            Vec3 target = targets.get(i).getCenter();
            Vec3 delta = target.subtract(effect.origin());
            int particles = Mth.clamp((int) (delta.length() / 2), 3, 12);
            for (int step = 0; step <= particles; step++) {
                Vec3 point = effect.origin().add(delta.scale(step / (double) particles));
                minecraft.level.addParticle(
                    net.minecraft.core.particles.ParticleTypes.END_ROD,
                    point.x, point.y, point.z,
                    delta.x * 0.01, delta.y * 0.01, delta.z * 0.01
                );
            }
        }
        if (minecraft.player != null) {
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static BlockPos pointedAnchor(Minecraft minecraft, ItemStack schematic) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
            return minecraft.player.blockPosition();
        }
        Direction face = hit.getDirection();
        BlockPos base = hit.getBlockPos().relative(face);
        var size = schematic.get(AllDataComponents.SCHEMATIC_BOUNDS);
        if (size == null) {
            return base;
        }
        int x = size.getX();
        int z = size.getZ();
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            int swap = x;
            x = z;
            z = swap;
        }
        return base.offset(-x / 2, face == Direction.DOWN ? -size.getY() : 0, -z / 2);
    }

    private static void updateRenderer(Minecraft minecraft, ItemStack schematic) {
        ItemStack preview = CreateSchematicBridge.deployedCopy(schematic, BlockPos.ZERO, rotation, mirror);
        int hash = SchematicInstances.getHash(preview);
        if (renderer != null && hash == rendererHash && ItemStack.isSameItemSameComponents(schematic, renderedSchematic)) {
            return;
        }
        var world = SchematicInstances.get(minecraft.level, preview);
        renderer = world == null ? null : new SchematicRenderer(world);
        rendererHash = hash;
        renderedSchematic = schematic.copy();
    }

    private static float[] outlineColor() {
        if (!locked) {
            return new float[] {0.25f, 0.65f, 1.0f};
        }
        if (status == null) {
            return new float[] {1.0f, 0.75f, 0.2f};
        }
        return status.valid() ? new float[] {0.2f, 1.0f, 0.35f} : new float[] {1.0f, 0.2f, 0.2f};
    }

    private static void reset() {
        anchor = null;
        locked = false;
        status = null;
        renderer = null;
        renderedSchematic = ItemStack.EMPTY;
    }
}
