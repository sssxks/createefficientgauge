package io.github.createhandheldcannon.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;

import io.github.createhandheldcannon.content.CannonMenu;
import io.github.createhandheldcannon.content.CannonState;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class CannonScreen extends AbstractSimiContainerScreen<CannonMenu> {
    private static final AllGuiTextures BG_TOP = AllGuiTextures.SCHEMATICANNON_TOP;
    private static final AllGuiTextures BG_BOTTOM = AllGuiTextures.SCHEMATICANNON_BOTTOM;

    private final List<IconButton> schematicButtons = new ArrayList<>();
    private final List<IconButton> settingButtons = new ArrayList<>();
    private IconButton settingsButton;
    private IconButton confirmButton;
    private ScrollInput todoInput;
    private Label todoValue;
    private boolean settingsOpen;
    private int observedSelection = -1;

    public CannonScreen(CannonMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        setWindowSize(BG_TOP.getWidth(), BG_TOP.getHeight() + BG_BOTTOM.getHeight() + 2
            + AllGuiTextures.PLAYER_INVENTORY.getHeight());
        setWindowOffset(-11, 0);
        super.init();

        int x = leftPos;
        int y = topPos;
        schematicButtons.clear();
        settingButtons.clear();

        for (int i = 0; i < CannonState.SCHEMATIC_COUNT; i++) {
            int index = i;
            IconButton button = new IconButton(x + 46 + i * 22, y + 43, AllIcons.I_CONFIRM);
            button.withCallback(() -> click(CannonMenu.BUTTON_SELECT_BASE + index));
            button.setToolTip(Component.translatable("gui.createhandheldcannon.select", i + 1));
            schematicButtons.add(button);
            addRenderableWidget(button);
        }

        todoValue = new Label(x + 111, y + 73, CommonComponents.EMPTY).colored(0xDDEEFF);
        todoInput = new ScrollInput(x + 102, y + 67, 50, 18)
            .withRange(0, CannonState.MAX_TODO + 1)
            .withShiftStep(10)
            .titled(Component.translatable("gui.createhandheldcannon.todo"))
            .writingTo(todoValue)
            .calling(value -> click(CannonMenu.BUTTON_TODO_SET_BASE + value));
        addRenderableWidgets(todoInput, todoValue);

        settingsButton = new IconButton(x + 8, y + 111, AllIcons.I_PLACEMENT_SETTINGS);
        settingsButton.withCallback(this::toggleSettings);
        settingsButton.setToolTip(CreateLang.translateDirect("gui.schematicannon.showOptions"));
        confirmButton = new IconButton(x + 180, y + 111, AllIcons.I_CONFIRM);
        confirmButton.withCallback(() -> minecraft.player.closeContainer());
        addRenderableWidgets(settingsButton, confirmButton);

        addSettingButton(x + 33, y + 111, AllIcons.I_DONT_REPLACE, 0,
            "gui.schematicannon.option.dontReplaceSolid");
        addSettingButton(x + 51, y + 111, AllIcons.I_REPLACE_SOLID, 1,
            "gui.schematicannon.option.replaceWithSolid");
        addSettingButton(x + 69, y + 111, AllIcons.I_REPLACE_ANY, 2,
            "gui.schematicannon.option.replaceWithAny");
        addSettingButton(x + 87, y + 111, AllIcons.I_REPLACE_EMPTY, 3,
            "gui.schematicannon.option.replaceWithEmpty");

        IconButton blockEntities = new IconButton(x + 135, y + 111, AllIcons.I_SKIP_BLOCK_ENTITIES);
        blockEntities.withCallback(() -> click(CannonMenu.BUTTON_BLOCK_ENTITIES));
        blockEntities.setToolTip(CreateLang.translateDirect("gui.schematicannon.option.skipBlockEntities"));
        settingButtons.add(blockEntities);
        addRenderableWidget(blockEntities);

        setSettingsVisible(false);
        refreshWidgets();
    }

    private void addSettingButton(int x, int y, AllIcons icon, int mode, String tooltip) {
        IconButton button = new IconButton(x, y, icon);
        button.withCallback(() -> {
            if (CannonState.replaceMode(cannon()).ordinal() != mode) {
                click(CannonMenu.BUTTON_REPLACE_MODE_BASE + mode);
            }
        });
        button.setToolTip(CreateLang.translateDirect(tooltip));
        settingButtons.add(button);
        addRenderableWidget(button);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshWidgets();
    }

    private void refreshWidgets() {
        ItemStack cannon = cannon();
        int selected = CannonState.selected(cannon);
        for (int i = 0; i < schematicButtons.size(); i++) {
            schematicButtons.get(i).green = i == selected;
        }
        if (observedSelection != selected || todoInput.getState() != CannonState.todo(cannon, selected)) {
            observedSelection = selected;
            todoInput.setState(CannonState.todo(cannon, selected));
        }
        for (int i = 0; i < 4 && i < settingButtons.size(); i++) {
            settingButtons.get(i).green = CannonState.replaceMode(cannon).ordinal() == i;
        }
        if (settingButtons.size() > 4) {
            settingButtons.get(4).green = !CannonState.replaceBlockEntities(cannon);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int inventoryX = getLeftOfCentered(AllGuiTextures.PLAYER_INVENTORY.getWidth());
        int inventoryY = y + BG_TOP.getHeight() + BG_BOTTOM.getHeight() + 2;
        renderPlayerInventory(graphics, inventoryX, inventoryY);

        BG_TOP.render(graphics, x, y);
        BG_BOTTOM.render(graphics, x, y + BG_TOP.getHeight());
        AllGuiTextures.SCHEMATIC_TITLE.render(graphics, x, y - 2);
        graphics.drawString(font, title, x + (BG_TOP.getWidth() - 8 - font.width(title)) / 2, y + 2,
            0x505050, false);

        int selected = CannonState.selected(cannon());
        if (!CannonState.selectedSchematic(cannon()).isEmpty()) {
            AllGuiTextures.SCHEMATICANNON_HIGHLIGHT.render(graphics, x + 41 + selected * 22, y + 14);
        }

        int perPowder = Math.max(1,
            com.simibubi.create.infrastructure.config.AllConfigs.server().schematics
                .schematicannonShotsPerGunpowder.get());
        float fuel = Mth.clamp(CannonState.remainingShots(cannon()) / (float) perPowder, 0, 1);
        AllGuiTextures fuelTexture = AllGuiTextures.SCHEMATICANNON_FUEL;
        graphics.blit(fuelTexture.location, x + 36, y + 66, fuelTexture.getStartX(), fuelTexture.getStartY(),
            (int) (fuelTexture.getWidth() * fuel), fuelTexture.getHeight());

        graphics.drawString(font, Component.translatable("gui.createhandheldcannon.todo"), x + 72, y + 72,
            0xDDEEFF, false);
        ItemStack selectedSchematic = CannonState.selectedSchematic(cannon());
        Component selectedName = selectedSchematic.isEmpty()
            ? Component.translatable("gui.createhandheldcannon.empty") : selectedSchematic.getHoverName();
        String clippedName = font.plainSubstrByWidth(selectedName.getString(), 124);
        graphics.drawCenteredString(font, clippedName, x + 106, y + 92, 0xDDEEFF);

        GuiGameElement.of(cannon()).<GuiGameElement.GuiRenderBuilder>at(
            x + BG_TOP.getWidth(), y + BG_TOP.getHeight() + BG_BOTTOM.getHeight() - 48, -200)
            .scale(5).render(graphics);
    }

    @Override
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int fuelX = leftPos + 36;
        int fuelY = topPos + 66;
        if (mouseX >= fuelX && mouseY >= fuelY
            && mouseX <= fuelX + AllGuiTextures.SCHEMATICANNON_FUEL.getWidth()
            && mouseY <= fuelY + AllGuiTextures.SCHEMATICANNON_FUEL.getHeight()) {
            int perPowder = Math.max(1,
                com.simibubi.create.infrastructure.config.AllConfigs.server().schematics
                    .schematicannonShotsPerGunpowder.get());
            int stored = CannonState.remainingShots(cannon());
            int powder = CannonState.contents(cannon()).get(CannonState.FUEL_SLOT).getCount();
            graphics.renderComponentTooltip(font, List.of(
                Component.translatable("gui.schematicannon.shotsRemaining", stored),
                Component.translatable("gui.schematicannon.shotsRemainingWithBackup", stored + powder * perPowder)
            ), mouseX, mouseY);
        }
        if (hoveredSlot != null && !hoveredSlot.hasItem()) {
            Component tooltip = hoveredSlot.index == CannonState.FUEL_SLOT
                ? Component.translatable("gui.schematicannon.slot.gunpowder")
                : Component.translatable("gui.createhandheldcannon.schematic_slot");
            graphics.renderComponentTooltip(font, List.of(tooltip), mouseX, mouseY);
        }
        super.renderForeground(graphics, mouseX, mouseY, partialTicks);
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        setSettingsVisible(settingsOpen);
    }

    private void setSettingsVisible(boolean visible) {
        settingsButton.green = visible;
        for (AbstractWidget widget : settingButtons) {
            widget.visible = visible;
            widget.active = visible;
        }
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private ItemStack cannon() {
        return menu.cannonStack(minecraft.player);
    }
}
