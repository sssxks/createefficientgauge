package io.github.createefficientgauge;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlock;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import io.github.createefficientgauge.mixin.SpeedControllerBlockEntityAccessor;
import java.util.function.Consumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import org.jetbrains.annotations.Nullable;

/**
 * Retains the static speed-controller bracket alongside Create's rotating
 * shaft instance.
 *
 * <p>The stock renderer already suppresses the shaft when Flywheel is active,
 * but it still transforms and submits the bracket through
 * {@code SuperByteBuffer} every frame. With thousands of controllers and Iris
 * enabled, that static model dominates render-thread CPU time.</p>
 */
public final class SpeedControllerVisual
    extends SingleAxisRotatingVisual<SpeedControllerBlockEntity>
{

    private @Nullable TransformedInstance bracket;

    public SpeedControllerVisual(
        VisualizationContext context,
        SpeedControllerBlockEntity blockEntity,
        float partialTick
    ) {
        super(
            context,
            blockEntity,
            partialTick,
            Models.partial(AllPartialModels.SHAFT)
        );
        refreshBracket(true);
    }

    @Override
    public void tick(TickableVisual.Context context) {
        super.tick(context);
        refreshBracket(false);
    }

    @Override
    public void update(float partialTick) {
        super.update(partialTick);
        refreshBracket(true);
    }

    private void refreshBracket(boolean updateTransform) {
        boolean hasBracket =
            ((SpeedControllerBlockEntityAccessor) blockEntity)
                .createefficientgauge$hasBracket();

        if (!hasBracket) {
            if (bracket != null) {
                bracket.delete();
                bracket = null;
            }
            return;
        }

        if (bracket == null) {
            bracket = instancerProvider()
                .instancer(
                    InstanceTypes.TRANSFORMED,
                    Models.partial(AllPartialModels.SPEED_CONTROLLER_BRACKET)
                )
                .createInstance();
            updateTransform = true;
        } else if (!updateTransform) {
            return;
        }

        if (updateTransform) {
            boolean alongX =
                blockState.getValue(SpeedControllerBlock.HORIZONTAL_AXIS) ==
                Axis.X;
            float angle = (float) (alongX ? Math.PI : Math.PI / 2);
            bracket
                .setIdentityTransform()
                .translate(getVisualPosition())
                .translate(0, 1, 0)
                .center()
                .rotate(angle, Direction.UP)
                .uncenter();
        }

        updateBracketLight();
        bracket.setChanged();
    }

    private void updateBracketLight() {
        if (bracket == null) {
            return;
        }
        bracket.light(
            LevelRenderer.getLightColor(
                blockEntity.getLevel(),
                blockEntity.getBlockPos().above()
            )
        );
    }

    @Override
    public void updateLight(float partialTick) {
        super.updateLight(partialTick);
        if (bracket != null) {
            updateBracketLight();
            bracket.setChanged();
        }
    }

    @Override
    protected void _delete() {
        super._delete();
        if (bracket != null) {
            bracket.delete();
            bracket = null;
        }
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        super.collectCrumblingInstances(consumer);
        if (bracket != null) {
            consumer.accept(bracket);
        }
    }
}
