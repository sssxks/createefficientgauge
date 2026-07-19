package io.github.createhandheldcannon.client;

import java.util.List;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllKeys;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A cannon-owned copy of Create's passive schematic tool selector presentation. */
final class CannonToolSelection {
    private static final List<CannonTool> TOOLS = List.of(
        CannonTool.MOVE, CannonTool.MOVE_Y, CannonTool.ROTATE, CannonTool.FLIP);

    private int selection;
    private boolean focused;
    private float yOffset;

    CannonTool selected() {
        return TOOLS.get(selection);
    }

    boolean focused() {
        return focused;
    }

    void setFocused(boolean focused) {
        this.focused = focused;
    }

    void cycle(int direction) {
        selection += direction < 0 ? 1 : -1;
        selection = (selection + TOOLS.size()) % TOOLS.size();
    }

    void update() {
        if (focused) {
            yOffset += (10 - yOffset) * .1f;
        } else {
            yOffset *= .9f;
        }
    }

    void render(GuiGraphics graphics, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        int width = Math.max(TOOLS.size() * 50 + 30, 220);
        int height = 30;
        int x = (window.getGuiScaledWidth() - width) / 2 + 15;
        int y = window.getGuiScaledHeight() - height - 75;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, -yOffset, focused ? 100 : 0);

        AllGuiTextures gray = AllGuiTextures.HUD_BACKGROUND;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1, 1, 1, focused ? 7 / 8f : 1 / 2f);
        graphics.blit(gray.location, x - 15, y, gray.getStartX(), gray.getStartY(), width, height,
            gray.getWidth(), gray.getHeight());

        float tooltipAlpha = yOffset / 10;
        List<Component> tooltip = selected().description();
        int alpha = ((int) (tooltipAlpha * 0xff)) << 24;
        if (tooltipAlpha > .25f) {
            RenderSystem.setShaderColor(.7f, .7f, .8f, tooltipAlpha);
            graphics.blit(gray.location, x - 15, y + 33, gray.getStartX(), gray.getStartY(), width, height + 22,
                gray.getWidth(), gray.getHeight());
            RenderSystem.setShaderColor(1, 1, 1, 1);
            int[] colors = {0xEEEEEE, 0xCCDDFF, 0xCCDDFF, 0xCCCCDD};
            int[] ys = {38, 50, 60, 72};
            for (int i = 0; i < Math.min(4, tooltip.size()); i++) {
                graphics.drawString(minecraft.font, tooltip.get(i), x - 10, y + ys[i], colors[i] + alpha, false);
            }
        }

        RenderSystem.setShaderColor(1, 1, 1, 1);
        String keyName = AllKeys.TOOL_MENU.getBoundKey();
        if (!focused) {
            graphics.drawCenteredString(minecraft.font,
                Component.translatable("gui.toolmenu.focusKey", keyName), window.getGuiScaledWidth() / 2, y - 10,
                0xCCDDFF);
        } else {
            graphics.drawCenteredString(minecraft.font, CreateLang.translateDirect("gui.toolmenu.cycle"),
                window.getGuiScaledWidth() / 2, y - 10, 0xCCDDFF);
        }

        for (int i = 0; i < TOOLS.size(); i++) {
            pose.pushPose();
            float iconAlpha = focused ? 1 : .2f;
            if (i == selection) {
                pose.translate(0, -10, 0);
                graphics.drawCenteredString(minecraft.font, TOOLS.get(i).displayName().getString(),
                    x + i * 50 + 24, y + 28, 0xCCDDFF);
                iconAlpha = 1;
            }
            RenderSystem.setShaderColor(0, 0, 0, iconAlpha);
            TOOLS.get(i).icon().render(graphics, x + i * 50 + 16, y + 12);
            RenderSystem.setShaderColor(1, 1, 1, iconAlpha);
            TOOLS.get(i).icon().render(graphics, x + i * 50 + 16, y + 11);
            pose.popPose();
        }
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        pose.popPose();
    }
}
