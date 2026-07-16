package io.github.createefficientgauge;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.SimpleModel;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

/**
 * Resource-reload-aware baked item mesh cache. The baking approach follows
 * Flywheel's Vanillin item visual, but deliberately rejects tinted and exotic
 * models so those items retain their original renderer.
 */
public final class GaugeItemModels {
    // Minecraft asks baked models for culled faces one Direction at a time and
    // for unculled faces with a null direction. Omitting the final null entry is
    // a common item-baking bug that makes crossed/flat geometry disappear.
    private static final Model EMPTY_MODEL = new SimpleModel(List.of());
    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null
    };
    private static final RendererReloadCache<MeshKey, Mesh> MESHES =
            new RendererReloadCache<>(key -> bakeMesh(key.model()));
    private static final RendererReloadCache<ModelKey, Model> MODELS =
            new RendererReloadCache<>(GaugeItemModels::bakeModel);
    private static final RendererReloadCache<BakedModel, Boolean> SUPPORT =
            new RendererReloadCache<>(GaugeItemModels::inspectSupport);

    private GaugeItemModels() {
    }

    public static BakedModel resolve(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return Minecraft.getInstance().getModelManager().getMissingModel();
        }
        // ItemRenderer#getModel resolves ItemOverrides (custom model data,
        // pulling bows, and similar predicates). Cache the resolved model, not
        // the registry's base model, or gauges would display the wrong variant.
        ClientLevel clientLevel = level instanceof ClientLevel client ? client : null;
        return Minecraft.getInstance().getItemRenderer().getModel(stack, clientLevel, null, 0);
    }

    public static boolean isSupported(Level level, ItemStack stack) {
        return stack.isEmpty() || SUPPORT.get(resolve(level, stack));
    }

    public static Model get(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return EMPTY_MODEL;
        }
        BakedModel baked = resolve(level, stack);
        if (!SUPPORT.get(baked)) {
            return EMPTY_MODEL;
        }

        boolean cull = !(stack.getItem() instanceof BlockItem blockItem)
                || (!(blockItem.getBlock() instanceof HalfTransparentBlock)
                && !(blockItem.getBlock() instanceof StainedGlassPaneBlock));
        Material material = ModelUtil.getItemMaterial(ItemBlockRenderTypes.getRenderType(stack, cull));
        if (material == null) {
            material = Materials.TRANSLUCENT_ENTITY;
        }
        if (stack.getItem() instanceof BlockItem && material.transparency() == Transparency.TRANSLUCENT) {
            material = SimpleMaterial.builderOf(material)
                    .transparency(Transparency.ORDER_INDEPENDENT)
                    .build();
        }
        return MODELS.get(new ModelKey(baked, material, stack.hasFoil()));
    }

    private static boolean inspectSupport(BakedModel model) {
        // Custom renderers can issue arbitrary draw calls, render block entities,
        // or depend on time/player state. A static Flywheel mesh cannot preserve
        // those semantics, so ValueBoxRenderer must handle them.
        if (model.isCustomRenderer()) {
            return false;
        }
        // Equality is deliberate. A subclass or forwarding wrapper can change
        // getQuads(), render types, model data, or lifecycle behavior. Rejecting
        // unknown implementations costs some optimization but never loses an
        // item's custom rendering behavior.
        Class<?> type = model.getClass();
        if (type != SimpleBakedModel.class && type != MultiPartBakedModel.class && type != WeightedBakedModel.class) {
            return false;
        }
        RandomSource random = RandomSource.create();
        for (Direction direction : DIRECTIONS) {
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, direction, random)) {
                if (quad.isTinted()) {
                    // Tint colors depend on ItemStack and sometimes the level.
                    // This first implementation caches geometry by BakedModel,
                    // so baking a tint here would incorrectly share it between
                    // different stacks. Use vanilla until tint is part of key.
                    return false;
                }
            }
        }
        return true;
    }

    private static Model bakeModel(ModelKey key) {
        // Foil is a second material pass over the same immutable mesh. It does
        // not require a second copy of the positions/UVs in native memory.
        Mesh mesh = MESHES.get(new MeshKey(key.model()));
        if (key.foil()) {
            return new SimpleModel(List.of(
                    new Model.ConfiguredMesh(key.material(), mesh),
                    new Model.ConfiguredMesh(Materials.GLINT, mesh)));
        }
        return new SingleMeshModel(mesh, key.material());
    }

    private static Mesh bakeMesh(BakedModel model) {
        // ValueBoxRenderer uses ItemDisplayContext.FIXED. Applying the same model
        // transform while baking lets every later instance use only the gauge's
        // world/slot transform. The half-block translation changes model-space
        // coordinates from [0,1] to Flywheel's centered item convention.
        PoseStack poseStack = new PoseStack();
        model.getTransforms().getTransform(ItemDisplayContext.FIXED).apply(false, poseStack);
        poseStack.translate(-0.5f, -0.5f, -0.5f);

        RandomSource random = RandomSource.create();
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction direction : DIRECTIONS) {
            // Vanilla resets this seed before every face query. Weighted/random
            // baked models must see exactly that sequence to match ItemRenderer.
            random.setSeed(42L);
            quads.addAll(model.getQuads(null, direction, random));
        }

        int vertexCount = quads.size() * 4;
        // Flywheel uploads FullVertexView to the backend and its tracked memory
        // owner is released when RendererReloadCache is invalidated. This avoids
        // retaining stale native meshes across F3+T resource reloads.
        MemoryBlock memory = MemoryBlock.mallocTracked(vertexCount * FullVertexView.STRIDE);
        FullVertexView vertices = new FullVertexView();
        vertices.nativeMemoryOwner(memory);
        vertices.ptr(memory.ptr());
        vertices.vertexCount(vertexCount);

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalPose = poseStack.last().normal();
        Vector4f position = new Vector4f();
        Vector3f normal = new Vector3f();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.malloc(DefaultVertexFormat.BLOCK.getVertexSize());
            IntBuffer ints = bytes.asIntBuffer();
            int vertex = 0;
            for (BakedQuad quad : quads) {
                int[] data = quad.getVertices();
                Direction direction = quad.getDirection();
                normal.set(direction.getStepX(), direction.getStepY(), direction.getStepZ()).mul(normalPose);
                int verticesInQuad = data.length / 8;
                for (int i = 0; i < verticesInQuad; i++) {
                    // BakedQuad stores the vanilla BLOCK vertex format in an
                    // int[]. Reusing one tiny stack buffer avoids one allocation
                    // for every vertex while decoding position and UV fields.
                    ints.clear();
                    ints.put(data, i * 8, 8);
                    position.set(bytes.getFloat(0), bytes.getFloat(4), bytes.getFloat(8), 1).mul(pose);
                    vertices.x(vertex, position.x());
                    vertices.y(vertex, position.y());
                    vertices.z(vertex, position.z());
                    vertices.r(vertex, 1);
                    vertices.g(vertex, 1);
                    vertices.b(vertex, 1);
                    vertices.a(vertex, 1);
                    vertices.u(vertex, bytes.getFloat(16));
                    vertices.v(vertex, bytes.getFloat(20));
                    vertices.overlay(vertex, OverlayTexture.NO_OVERLAY);
                    vertices.light(vertex, 0);
                    vertices.normalX(vertex, normal.x());
                    vertices.normalY(vertex, normal.y());
                    vertices.normalZ(vertex, normal.z());
                    vertex++;
                }
            }
        }
        return new SimpleQuadMesh(vertices);
    }

    private record MeshKey(BakedModel model) {
    }

    private record ModelKey(BakedModel model, Material material, boolean foil) {
    }
}
