package com.example.donutflipscanner.gui.panel;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;

public interface TabContentPanel {
    void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY);

    default boolean mouseClicked(int mouseX, int mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(int mouseX, int mouseY, double verticalAmount) {
        return false;
    }

    default boolean keyPressed(KeyInput input) {
        return false;
    }

    default boolean charTyped(CharInput input) {
        return false;
    }
}
