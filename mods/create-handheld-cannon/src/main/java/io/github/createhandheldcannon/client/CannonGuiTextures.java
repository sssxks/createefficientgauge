package io.github.createhandheldcannon.client;

import io.github.createhandheldcannon.CreateHandheldCannon;
import net.createmod.catnip.gui.TextureSheetSegment;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * GUI textures drawn for the handheld cannon, laid out after the schematicannon
 * sheet but matching this menu's own widget positions.
 */
public enum CannonGuiTextures implements ScreenElement, TextureSheetSegment {
    TITLE(0, 0, 205, 15),
    TOP(0, 16, 213, 42),
    BOTTOM(0, 58, 213, 99),
    HIGHLIGHT(0, 160, 26, 26),
    FUEL(32, 160, 47, 16);

    public final ResourceLocation location;
    private final int width;
    private final int height;
    private final int startX;
    private final int startY;

    CannonGuiTextures(int startX, int startY, int width, int height) {
        this.location = CreateHandheldCannon.id("textures/gui/handheld_cannon.png");
        this.startX = startX;
        this.startY = startY;
        this.width = width;
        this.height = height;
    }

    @Override
    public ResourceLocation getLocation() {
        return location;
    }

    @OnlyIn(Dist.CLIENT)
    public void render(GuiGraphics graphics, int x, int y) {
        graphics.blit(location, x, y, startX, startY, width, height);
    }

    @Override
    public int getStartX() {
        return startX;
    }

    @Override
    public int getStartY() {
        return startY;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }
}
