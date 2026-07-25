package io.github.createhandheldcannon.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllKeys;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.content.schematics.SchematicInstances;
import com.simibubi.create.content.schematics.client.SchematicRenderer;
import com.simibubi.create.content.schematics.client.SchematicTransformation;
import com.simibubi.create.foundation.utility.RaycastHelper;
import com.simibubi.create.foundation.utility.RaycastHelper.PredicateTraceResult;

import io.github.createhandheldcannon.CreateHandheldCannon;
import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.net.CannonNetworking.DeployEffect;
import io.github.createhandheldcannon.net.CannonNetworking.DeployRequest;
import io.github.createhandheldcannon.net.CannonNetworking.PlanRequest;
import io.github.createhandheldcannon.net.CannonNetworking.PlanStatus;
import io.github.createhandheldcannon.net.CannonNetworking.ResupplyRequest;
import io.github.createhandheldcannon.net.CannonNetworking.StockRequestPrefill;
import io.github.createhandheldcannon.service.CreateSchematicBridge;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult.Type;
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
    private static final CannonToolSelection TOOL_SELECTION = new CannonToolSelection();

    private static BlockPos selectedPos;
    private static BlockPos targetAnchor;
    private static boolean targetVisible;
    private static boolean locked;
    private static Direction selectedFace;
    private static boolean schematicSelected;
    private static PlanStatus status;
    private static SchematicTransformation transformation;
    private static AABB bounds;
    private static AABBOutline outline;
    private static SchematicRenderer renderer;
    private static ItemStack renderedSchematic = ItemStack.EMPTY;
    private static List<ItemStack> pendingStockRequest;

    private ClientCannonController() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        applyPendingStockRequest(minecraft);
        TOOL_SELECTION.update();

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
        if (schematic.isEmpty() || !schematic.has(AllDataComponents.SCHEMATIC_FILE)
            || schematic.get(AllDataComponents.SCHEMATIC_BOUNDS) == null) {
            clearPreview();
            return;
        }

        initializePreview(minecraft, schematic);
        transformation.tick();
        if (locked) {
            updateSchematicFace(minecraft);
        } else {
            updatePointedTarget(minecraft);
        }
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
        if (schematic.isEmpty() || transformation == null || (!locked && (!targetVisible || targetAnchor == null))) {
            return;
        }

        BlockPos anchor = transformation.getAnchor();
        StructurePlaceSettings settings = transformation.toSettings();
        if (!locked) {
            locked = true;
            status = null;
            PacketDistributor.sendToServer(
                new PlanRequest(InteractionHand.MAIN_HAND, anchor, settings.getRotation(), settings.getMirror()));
        } else if (status != null && status.valid()) {
            status = null;
            PacketDistributor.sendToServer(
                new DeployRequest(InteractionHand.MAIN_HAND, anchor, settings.getRotation(), settings.getMirror()));
        } else {
            PacketDistributor.sendToServer(
                new PlanRequest(InteractionHand.MAIN_HAND, anchor, settings.getRotation(), settings.getMirror()));
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!locked) {
            return;
        }
        double delta = event.getScrollDeltaY();
        if (delta == 0) {
            return;
        }
        if (TOOL_SELECTION.focused()) {
            TOOL_SELECTION.cycle((int) Math.signum(delta));
            event.setCanceled(true);
            return;
        }
        if (!AllKeys.ctrlDown() || transformation == null) {
            return;
        }
        applySelectedTool(delta);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!locked || !AllKeys.TOOL_MENU.doesModifierAndCodeMatch(event.getKey())) {
            return;
        }
        if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            TOOL_SELECTION.setFocused(true);
        } else if (event.getAction() == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
            TOOL_SELECTION.setFocused(false);
        }
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || renderer == null
            || transformation == null || bounds == null || outline == null || (!locked && !targetVisible)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        transformation.applyTransformations(pose, event.getCamera().getPosition());

        DefaultSuperRenderTypeBuffer buffers = DefaultSuperRenderTypeBuffer.getInstance();
        renderer.render(pose, buffers);

        int color = outlineColor();
        outline.getParams().colored(color).lineWidth(1 / 16f).clearTextures();
        if (locked && schematicSelected && selectedFace != null
            && (TOOL_SELECTION.selected() == CannonTool.MOVE || TOOL_SELECTION.selected() == CannonTool.FLIP)) {
            outline.getParams().highlightFace(selectedFace);
        }
        outline.render(pose, buffers, Vec3.ZERO, AnimationTickHolder.getPartialTicks());
        outline.getParams().clearTextures();
        buffers.draw();
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

        if (locked) {
            Component line;
            if (status == null) {
                line = Component.translatable("hud.createhandheldcannon.checking").withStyle(ChatFormatting.YELLOW);
            } else if (status.valid()) {
                line = Component.translatable("hud.createhandheldcannon.ready", status.targetCount())
                    .withStyle(ChatFormatting.GREEN);
            } else {
                line = Component.translatable("hud.createhandheldcannon.status." + status.message())
                    .withStyle(ChatFormatting.RED);
            }
            graphics.drawCenteredString(minecraft.font, line, center, graphics.guiHeight() - 64, 0xFFFFFFFF);
            TOOL_SELECTION.render(graphics, AnimationTickHolder.getPartialTicks());

            if (status != null && !status.missing().isEmpty()) {
                List<ItemStack> shown = status.missing().stream().limit(6).toList();
                int itemStart = center - shown.size() * 10;
                int itemY = graphics.guiHeight() - 111;
                graphics.fill(itemStart - 4, itemY - 4, itemStart + shown.size() * 20 + 4, itemY + 20,
                    0xC0101417);
                for (int i = 0; i < shown.size(); i++) {
                    ItemStack stack = shown.get(i);
                    int itemX = itemStart + i * 20;
                    graphics.renderItem(stack, itemX, itemY);
                    graphics.renderItemDecorations(minecraft.font, stack, itemX, itemY);
                }
            }
        }
    }

    public static void acceptStatus(PlanStatus incoming) {
        status = incoming;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && (incoming.message().contains("stock_keeper")
            || incoming.message().equals("not_stock_keeper") || incoming.message().equals("nothing_to_order"))) {
            minecraft.player.displayClientMessage(
                Component.translatable("hud.createhandheldcannon.status." + incoming.message()), true);
        }
        if (incoming.message().equals("deployed")) {
            locked = false;
            status = null;
        }
    }

    public static void acceptStockRequest(StockRequestPrefill request) {
        pendingStockRequest = request.stacks();
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
                minecraft.level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    point.x, point.y, point.z, delta.x * 0.01, delta.y * 0.01, delta.z * 0.01);
            }
        }
        CannonRecoil.trigger();
    }

    private static void initializePreview(Minecraft minecraft, ItemStack schematic) {
        if (renderer != null && ItemStack.isSameItemSameComponents(schematic, renderedSchematic)) {
            return;
        }
        var size = schematic.get(AllDataComponents.SCHEMATIC_BOUNDS);
        bounds = new AABB(0, 0, 0, size.getX(), size.getY(), size.getZ());
        outline = new AABBOutline(bounds);
        transformation = new SchematicTransformation();
        transformation.init(BlockPos.ZERO, new StructurePlaceSettings(), bounds);

        ItemStack preview = CreateSchematicBridge.deployedCopy(
            schematic, BlockPos.ZERO, Rotation.NONE, Mirror.NONE);
        var world = SchematicInstances.get(minecraft.level, preview);
        renderer = world == null ? null : new SchematicRenderer(world);
        renderedSchematic = schematic.copy();
        selectedPos = null;
        targetAnchor = null;
        targetVisible = false;
        locked = false;
        status = null;
    }

    private static void updatePointedTarget(Minecraft minecraft) {
        BlockHitResult trace = RaycastHelper.rayTraceRange(minecraft.level, minecraft.player, 75);
        if (trace == null || trace.getType() != Type.BLOCK) {
            selectedPos = null;
            targetVisible = false;
            return;
        }

        BlockPos hit = BlockPos.containing(trace.getLocation());
        boolean replaceable = minecraft.level.getBlockState(hit).canBeReplaced();
        if (trace.getDirection().getAxis().isVertical() && !replaceable) {
            hit = hit.relative(trace.getDirection());
        }
        selectedPos = hit;

        Vec3 center = bounds.getCenter();
        BlockPos nextAnchor = selectedPos.offset(-((int) center.x), 0, -((int) center.z));
        if (!targetVisible) {
            transformation.startAt(nextAnchor);
        }
        transformation.moveTo(nextAnchor);
        targetAnchor = nextAnchor;
        targetVisible = true;
    }

    private static void updateSchematicFace(Minecraft minecraft) {
        Vec3 traceOrigin = minecraft.player.getEyePosition();
        Vec3 start = transformation.toLocalSpace(traceOrigin);
        Vec3 end = transformation.toLocalSpace(RaycastHelper.getTraceTarget(minecraft.player, 70, traceOrigin));
        PredicateTraceResult result = RaycastHelper.rayTraceUntil(
            start, end, pos -> bounds.contains(VecHelper.getCenterOf(pos)));
        schematicSelected = !result.missed();
        selectedFace = schematicSelected ? result.getFacing() : null;
    }

    private static void applySelectedTool(double delta) {
        CannonTool tool = TOOL_SELECTION.selected();
        switch (tool) {
            case MOVE -> {
                if (!schematicSelected || selectedFace == null || !selectedFace.getAxis().isHorizontal()) {
                    return;
                }
                Vec3 movement = Vec3.atLowerCornerOf(selectedFace.getNormal()).scale(-Math.signum(delta));
                movement = movement.multiply(
                    transformation.getMirrorModifier(Axis.X), 1, transformation.getMirrorModifier(Axis.Z));
                movement = VecHelper.rotate(movement, transformation.getRotationTarget(), Axis.Y);
                transformation.move((int) movement.x, 0, (int) movement.z);
            }
            case MOVE_Y -> transformation.move(0, Mth.sign(delta), 0);
            case ROTATE -> transformation.rotate90(delta > 0);
            case FLIP -> {
                if (!schematicSelected || selectedFace == null || !selectedFace.getAxis().isHorizontal()) {
                    return;
                }
                transformation.flip(selectedFace.getAxis());
            }
        }
        status = null;
        StructurePlaceSettings settings = transformation.toSettings();
        PacketDistributor.sendToServer(new PlanRequest(InteractionHand.MAIN_HAND, transformation.getAnchor(),
            settings.getRotation(), settings.getMirror()));
    }

    private static void applyPendingStockRequest(Minecraft minecraft) {
        if (pendingStockRequest == null || !(minecraft.screen instanceof StockKeeperRequestScreen screen)) {
            return;
        }
        screen.itemsToOrder.clear();
        for (ItemStack stack : pendingStockRequest) {
            if (!stack.isEmpty()) {
                screen.itemsToOrder.add(new BigItemStack(stack.copyWithCount(1), stack.getCount()));
            }
        }
        pendingStockRequest = null;
    }

    private static int outlineColor() {
        if (!locked) {
            return 0x6886c5;
        }
        if (status == null) {
            return 0xe2b34f;
        }
        return status.valid() ? 0x58d68d : 0xe85b5b;
    }

    private static void clearPreview() {
        selectedPos = null;
        targetAnchor = null;
        targetVisible = false;
        locked = false;
        status = null;
        transformation = null;
        bounds = null;
        outline = null;
        renderer = null;
        renderedSchematic = ItemStack.EMPTY;
        TOOL_SELECTION.setFocused(false);
    }

    private static void reset() {
        clearPreview();
        pendingStockRequest = null;
    }
}
