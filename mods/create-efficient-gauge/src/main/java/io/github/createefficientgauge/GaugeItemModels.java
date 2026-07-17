package io.github.createefficientgauge;

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
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
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
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Resource-reload-aware baked item mesh cache. The baking approach follows
 * Flywheel's Vanillin item visual. Static JSON models, including Minecraft's
 * generated/tinted item layers, become shared Flywheel meshes; procedural
 * models retain their original renderer.
 */
public final class GaugeItemModels {

    // Minecraft asks baked models for culled faces one Direction at a time and
    // for unculled faces with a null direction. Omitting the final null entry is
    // a common item-baking bug that makes crossed/flat geometry disappear.
    private static final Direction[] DIRECTIONS = {
        Direction.DOWN,
        Direction.UP,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        null,
    };
    private static final RendererReloadCache<MeshKey, Mesh> MESHES =
        new RendererReloadCache<>(GaugeItemModels::bakeMesh);
    private static final RendererReloadCache<
        ModelKey,
        PreparedItemModel
    > MODELS = new RendererReloadCache<>(GaugeItemModels::bakeModel);
    private static final RendererReloadCache<
        BakedModel,
        ModelAnalysis
    > ANALYSES = new RendererReloadCache<>(GaugeItemModels::analyze);

    private GaugeItemModels() {}

    public static BakedModel resolve(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return Minecraft.getInstance().getModelManager().getMissingModel();
        }
        // ItemRenderer#getModel resolves ItemOverrides (custom model data,
        // pulling bows, and similar predicates). Cache the resolved model, not
        // the registry's base model, or gauges would display the wrong variant.
        ClientLevel clientLevel =
            level instanceof ClientLevel client ? client : null;
        return Minecraft.getInstance()
            .getItemRenderer()
            .getModel(stack, clientLevel, null, 0);
    }

    public static boolean isSupported(Level level, ItemStack stack) {
        return (
            stack.isEmpty() || ANALYSES.get(resolve(level, stack)).supported()
        );
    }

    /**
     * Resolves and prepares one filter item for retained rendering.
     *
     * <p>The nullable return is intentional. Both the tick-side visual and the
     * frame-side fallback use the same {@link #isSupported(Level, ItemStack)}
     * policy, so a slot can never be drawn by both paths or by neither path.</p>
     */
    @Nullable
    public static PreparedItemModel getSupported(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        BakedModel baked = resolve(level, stack);
        ModelAnalysis analysis = ANALYSES.get(baked);
        if (!analysis.supported()) {
            return null;
        }

        boolean cull =
            !(stack.getItem() instanceof BlockItem blockItem) ||
            (!(blockItem.getBlock() instanceof HalfTransparentBlock) &&
                !(blockItem.getBlock() instanceof StainedGlassPaneBlock));
        boolean blockItem = stack.getItem() instanceof BlockItem;
        List<Material> materials = new ArrayList<>();
        for (BakedModel renderPass : baked.getRenderPasses(stack, cull)) {
            for (RenderType renderType : renderPass.getRenderTypes(
                stack,
                cull
            )) {
                Material material = ModelUtil.getItemMaterial(renderType);
                if (material == null) {
                    material = Materials.TRANSLUCENT_ENTITY;
                }
                if (
                    blockItem &&
                    material.transparency() == Transparency.TRANSLUCENT
                ) {
                    material = SimpleMaterial.builderOf(material)
                        .transparency(Transparency.ORDER_INDEPENDENT)
                        .build();
                }
                materials.add(material);
            }
        }
        if (materials.isEmpty()) {
            materials.add(Materials.TRANSLUCENT_ENTITY);
        }
        // A BakedModel is shared by many ItemStacks, but ItemColors is allowed
        // to inspect stack components. Put the actual colors in the model key:
        // two leather items (or two potion-like mod items) must not accidentally
        // share a mesh baked with the first stack's tint.
        TintPalette tints = resolveTints(stack, analysis.tintIndices());
        return MODELS.get(
            new ModelKey(baked, List.copyOf(materials), stack.hasFoil(), tints)
        );
    }

    private static ModelAnalysis analyze(BakedModel model) {
        // Custom renderers can issue arbitrary draw calls, render block entities,
        // or depend on time/player state. A static Flywheel mesh cannot preserve
        // those semantics, so ValueBoxRenderer must handle them.
        if (model.isCustomRenderer()) {
            return ModelAnalysis.unsupported(model);
        }
        // Equality is deliberate. A subclass or forwarding wrapper can change
        // getQuads(), render types, model data, or lifecycle behavior. Rejecting
        // unknown implementations costs some optimization but never loses an
        // item's custom rendering behavior.
        Class<?> type = model.getClass();
        if (
            type != SimpleBakedModel.class &&
            type != MultiPartBakedModel.class &&
            type != WeightedBakedModel.class
        ) {
            return ModelAnalysis.unsupported(model);
        }

        List<BakedQuad> quads = new ArrayList<>();
        Set<Integer> tintIndices = new HashSet<>();
        RandomSource random = RandomSource.create();
        for (Direction direction : DIRECTIONS) {
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(
                null,
                direction,
                random,
                ModelData.EMPTY,
                null
            )) {
                if (quad.isTinted()) {
                    tintIndices.add(quad.getTintIndex());
                }
                quads.add(quad);
            }
        }
        return new ModelAnalysis(
            model,
            List.copyOf(quads),
            Set.copyOf(tintIndices),
            true
        );
    }

    private static TintPalette resolveTints(
        ItemStack stack,
        Set<Integer> tintIndices
    ) {
        if (tintIndices.isEmpty()) {
            return TintPalette.NONE;
        }

        Map<Integer, Integer> colors = new HashMap<>();
        for (int tintIndex : tintIndices) {
            int argb = Minecraft.getInstance()
                .getItemColors()
                .getColor(stack, tintIndex);
            // ItemColors uses -1 for opaque white. Omitting white entries keeps
            // ordinary generated items on the smallest, commonly shared key.
            if (argb != -1) {
                colors.put(tintIndex, argb);
            }
        }
        return colors.isEmpty()
            ? TintPalette.NONE
            : new TintPalette(Map.copyOf(colors));
    }

    private static PreparedItemModel bakeModel(ModelKey key) {
        // Every model-defined render type is a material pass over the same
        // immutable item mesh. Foil adds one glint pass for each render pass,
        // matching ItemRenderer without duplicating positions/UVs in memory.
        Mesh mesh = MESHES.get(new MeshKey(key.model(), key.tints()));
        if (key.materials().size() == 1 && !key.foil()) {
            return new PreparedItemModel(
                new SingleMeshModel(mesh, key.materials().getFirst()),
                key.model().isGui3d()
            );
        }

        List<Model.ConfiguredMesh> configured = new ArrayList<>();
        for (Material material : key.materials()) {
            configured.add(new Model.ConfiguredMesh(material, mesh));
            if (key.foil()) {
                configured.add(new Model.ConfiguredMesh(Materials.GLINT, mesh));
            }
        }
        return new PreparedItemModel(
            new SimpleModel(List.copyOf(configured)),
            key.model().isGui3d()
        );
    }

    private static Mesh bakeMesh(MeshKey key) {
        BakedModel model = key.model();
        // ValueBoxRenderer uses ItemDisplayContext.FIXED. Applying the same model
        // transform while baking lets every later instance use only the gauge's
        // world/slot transform. The half-block translation changes model-space
        // coordinates from [0,1] to Flywheel's centered item convention.
        PoseStack poseStack = new PoseStack();
        model.applyTransform(ItemDisplayContext.FIXED, poseStack, false);
        poseStack.translate(-0.5f, -0.5f, -0.5f);

        // ModelAnalysis captured the exact quad selection with vanilla's fixed
        // random seed. Reuse it here instead of asking a weighted model to make
        // a second (potentially different) selection while the mesh is baked.
        List<BakedQuad> quads = ANALYSES.get(model).quads();

        int vertexCount = quads.size() * 4;
        // Flywheel uploads FullVertexView to the backend and its tracked memory
        // owner is released when RendererReloadCache is invalidated. This avoids
        // retaining stale native meshes across F3+T resource reloads.
        MemoryBlock memory = MemoryBlock.mallocTracked(
            vertexCount * FullVertexView.STRIDE
        );
        FullVertexView vertices = new FullVertexView();
        vertices.nativeMemoryOwner(memory);
        vertices.ptr(memory.ptr());
        vertices.vertexCount(vertexCount);

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalPose = poseStack.last().normal();
        Vector4f position = new Vector4f();
        Vector3f normal = new Vector3f();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.malloc(
                DefaultVertexFormat.BLOCK.getVertexSize()
            );
            IntBuffer ints = bytes.asIntBuffer();
            int vertex = 0;
            for (BakedQuad quad : quads) {
                int color = quad.isTinted()
                    ? key.tints().colors().getOrDefault(quad.getTintIndex(), -1)
                    : -1;
                float red = ((color >>> 16) & 0xff) / 255f;
                float green = ((color >>> 8) & 0xff) / 255f;
                float blue = (color & 0xff) / 255f;
                float alpha = ((color >>> 24) & 0xff) / 255f;
                int[] data = quad.getVertices();
                Direction direction = quad.getDirection();
                normal
                    .set(
                        direction.getStepX(),
                        direction.getStepY(),
                        direction.getStepZ()
                    )
                    .mul(normalPose);
                int verticesInQuad = data.length / 8;
                for (int i = 0; i < verticesInQuad; i++) {
                    // BakedQuad stores the vanilla BLOCK vertex format in an
                    // int[]. Reusing one tiny stack buffer avoids one allocation
                    // for every vertex while decoding position and UV fields.
                    ints.clear();
                    ints.put(data, i * 8, 8);
                    position
                        .set(
                            bytes.getFloat(0),
                            bytes.getFloat(4),
                            bytes.getFloat(8),
                            1
                        )
                        .mul(pose);
                    vertices.x(vertex, position.x());
                    vertices.y(vertex, position.y());
                    vertices.z(vertex, position.z());
                    // ItemRenderer passes this same ARGB ItemColors result to
                    // every vertex of the tinted quad. FullVertexView exposes
                    // normalized RGBA channels, so unpack instead of using the
                    // ABGR packed form expected by Sodium's direct writer.
                    vertices.r(vertex, red);
                    vertices.g(vertex, green);
                    vertices.b(vertex, blue);
                    vertices.a(vertex, alpha);
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

    public record PreparedItemModel(Model model, boolean gui3d) {}

    private record ModelAnalysis(
        BakedModel model,
        List<BakedQuad> quads,
        Set<Integer> tintIndices,
        boolean supported
    ) {
        private static ModelAnalysis unsupported(BakedModel model) {
            return new ModelAnalysis(model, List.of(), Set.of(), false);
        }
    }

    private record TintPalette(Map<Integer, Integer> colors) {
        private static final TintPalette NONE = new TintPalette(Map.of());
    }

    private record MeshKey(BakedModel model, TintPalette tints) {}

    private record ModelKey(
        BakedModel model,
        List<Material> materials,
        boolean foil,
        TintPalette tints
    ) {}
}
