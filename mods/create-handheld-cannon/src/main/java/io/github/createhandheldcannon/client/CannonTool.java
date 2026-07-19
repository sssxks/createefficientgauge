package io.github.createhandheldcannon.client;

import java.util.List;

import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

enum CannonTool {
    MOVE("move", AllIcons.I_TOOL_MOVE_XZ),
    MOVE_Y("move_y", AllIcons.I_TOOL_MOVE_Y),
    ROTATE("rotate", AllIcons.I_TOOL_ROTATE),
    FLIP("flip", AllIcons.I_TOOL_MIRROR);

    private final String translationId;
    private final AllIcons icon;

    CannonTool(String translationId, AllIcons icon) {
        this.translationId = translationId;
        this.icon = icon;
    }

    MutableComponent displayName() {
        return CreateLang.translateDirect("schematic.tool." + translationId);
    }

    List<Component> description() {
        return CreateLang.translatedOptions(
            "schematic.tool." + translationId + ".description", "0", "1", "2", "3");
    }

    AllIcons icon() {
        return icon;
    }
}
