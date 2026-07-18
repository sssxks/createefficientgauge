package io.github.createhandheldcannon.client;

import io.github.createhandheldcannon.content.CannonMenu;
import io.github.createhandheldcannon.content.CannonState;
import io.github.createhandheldcannon.net.CannonNetworking.UpdateAddress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CannonScreen extends AbstractContainerScreen<CannonMenu> {
    private boolean settingsOpen;
    private Button settingsButton;
    private Button replaceModeButton;
    private Button blockEntitiesButton;
    private EditBox addressBox;

    public CannonScreen(CannonMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 196;
        imageHeight = 195;
        inventoryLabelY = 101;
    }

    @Override
    protected void init() {
        super.init();
        settingsButton = addRenderableWidget(Button.builder(
            Component.translatable("gui.createhandheldcannon.settings"),
            button -> toggleSettings()
        ).bounds(leftPos + 17, topPos + 61, 162, 20).build());

        replaceModeButton = addRenderableWidget(Button.builder(
            modeLabel(),
            button -> {
                click(CannonMenu.BUTTON_REPLACE_MODE);
                button.setMessage(modeLabelAfterCycle());
            }
        ).bounds(leftPos + 17, topPos + 61, 162, 18).build());

        blockEntitiesButton = addRenderableWidget(Button.builder(
            blockEntityLabel(),
            button -> {
                click(CannonMenu.BUTTON_BLOCK_ENTITIES);
                button.setMessage(blockEntityLabelAfterToggle());
            }
        ).bounds(leftPos + 17, topPos + 81, 162, 18).build());

        addressBox = new EditBox(font, leftPos + 75, topPos + 101, 104, 16,
            Component.translatable("gui.createhandheldcannon.address"));
        addressBox.setMaxLength(64);
        addressBox.setValue(CannonState.address(cannon()));
        addressBox.setResponder(value -> PacketDistributor.sendToServer(new UpdateAddress(value)));
        addRenderableWidget(addressBox);
        setSettingsVisible(false);
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF012171B);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 55, 0xFF263037);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFFB48A4A);

        for (int i = 0; i < CannonState.SLOT_COUNT; i++) {
            int x = i == 0 ? leftPos + 16 : leftPos + 46 + (i - 1) * 22;
            graphics.fill(x, topPos + 24, x + 18, topPos + 42, 0xFF0B0E10);
            graphics.renderOutline(x, topPos + 24, 18, 18, 0xFF65727A);
        }

        int selected = CannonState.selected(cannon());
        graphics.renderOutline(leftPos + 45 + selected * 22, topPos + 23, 20, 20, 0xFF55E27A);

        int perPowder = Math.max(1,
            com.simibubi.create.infrastructure.config.AllConfigs.server().schematics.schematicannonShotsPerGunpowder.get());
        int remaining = CannonState.remainingShots(cannon());
        int fill = Math.min(18, Math.round(18 * (remaining / (float) perPowder)));
        graphics.fill(leftPos + 16, topPos + 45, leftPos + 34, topPos + 49, 0xFF431D19);
        graphics.fill(leftPos + 16, topPos + 45, leftPos + 16 + fill, topPos + 49, 0xFFE2B34F);

        if (settingsOpen) {
            graphics.fill(leftPos + 10, topPos + 56, leftPos + 186, topPos + 121, 0xF01C2328);
            graphics.drawString(font, Component.translatable("gui.createhandheldcannon.address"),
                leftPos + 17, topPos + 105, 0xFFCFD6DA, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFE8D8B2, false);
        if (!settingsOpen) {
            graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFC4CDD2, false);
        }
        for (int i = 0; i < CannonState.SCHEMATIC_COUNT; i++) {
            int todo = CannonState.todo(cannon(), i);
            String controls = "− " + todo + " +";
            int x = 47 + i * 22 + 9 - font.width(controls) / 2;
            graphics.drawString(font, controls, x, 47, i == CannonState.selected(cannon()) ? 0xFF75F091 : 0xFFB9C1C5, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!settingsOpen && mouseY >= topPos + 45 && mouseY < topPos + 58) {
            for (int i = 0; i < CannonState.SCHEMATIC_COUNT; i++) {
                int start = leftPos + 46 + i * 22;
                if (mouseX >= start && mouseX < start + 7) {
                    click(CannonMenu.BUTTON_TODO_MINUS_BASE + i);
                    return true;
                }
                if (mouseX >= start + 14 && mouseX < start + 22) {
                    click(CannonMenu.BUTTON_TODO_PLUS_BASE + i);
                    return true;
                }
                if (mouseX >= start + 7 && mouseX < start + 14) {
                    click(CannonMenu.BUTTON_SELECT_BASE + i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        setSettingsVisible(settingsOpen);
    }

    private void setSettingsVisible(boolean visible) {
        settingsButton.visible = !visible;
        replaceModeButton.visible = visible;
        blockEntitiesButton.visible = visible;
        addressBox.visible = visible;
        addressBox.setEditable(visible);
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private ItemStack cannon() {
        return menu.cannonStack(minecraft.player);
    }

    private Component modeLabel() {
        return Component.translatable("gui.createhandheldcannon.replace_mode",
            Component.translatable("gui.createhandheldcannon.mode." + CannonState.replaceMode(cannon()).name().toLowerCase()));
    }

    private Component modeLabelAfterCycle() {
        int next = (CannonState.replaceMode(cannon()).ordinal() + 1) % CannonState.ReplaceMode.values().length;
        return Component.translatable("gui.createhandheldcannon.replace_mode",
            Component.translatable("gui.createhandheldcannon.mode." + CannonState.ReplaceMode.values()[next].name().toLowerCase()));
    }

    private Component blockEntityLabel() {
        return Component.translatable("gui.createhandheldcannon.replace_block_entities",
            Component.translatable(CannonState.replaceBlockEntities(cannon()) ? "options.on" : "options.off"));
    }

    private Component blockEntityLabelAfterToggle() {
        return Component.translatable("gui.createhandheldcannon.replace_block_entities",
            Component.translatable(!CannonState.replaceBlockEntities(cannon()) ? "options.on" : "options.off"));
    }
}
