package io.github.frameprofiler.jfr;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Named set of exact methods whose slow invocations delimit frame phases. */
public record FrameProfile(
    String recordingName,
    List<MethodTarget> methodTargets
) {
    public FrameProfile {
        Objects.requireNonNull(recordingName, "recordingName");
        methodTargets = List.copyOf(methodTargets);
        if (recordingName.isBlank()) {
            throw new IllegalArgumentException("recordingName must not be blank");
        }
        if (methodTargets.isEmpty()) {
            throw new IllegalArgumentException("methodTargets must not be empty");
        }
    }

    public static FrameProfile minecraftClient() {
        return new FrameProfile(
            "minecraft-frame-profiler-slow-frames",
            List.of(
                new MethodTarget(
                    "net.minecraft.client.Minecraft",
                    "runTick"
                ),
                new MethodTarget(
                    "com.mojang.blaze3d.platform.Window",
                    "updateDisplay"
                ),
                new MethodTarget(
                    "net.minecraft.client.renderer.GameRenderer",
                    "render"
                ),
                new MethodTarget(
                    "net.minecraft.client.Minecraft",
                    "tick"
                )
            )
        );
    }

    String methodFilter() {
        StringJoiner filter = new StringJoiner(";");
        for (MethodTarget target : methodTargets) {
            filter.add(target.owner() + "::" + target.method());
        }
        return filter.toString();
    }

    /** Exact runtime owner and method name consumed by JFR MethodTrace. */
    public record MethodTarget(String owner, String method) {
        public MethodTarget {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(method, "method");
            if (owner.isBlank() || method.isBlank()) {
                throw new IllegalArgumentException(
                    "method target names must not be blank"
                );
            }
        }
    }
}
