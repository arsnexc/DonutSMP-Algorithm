package com.example.donutflipscanner.gui.navigation;

import com.example.donutflipscanner.gui.GuiLayout;
import com.example.donutflipscanner.gui.GuiTab;
import com.example.donutflipscanner.gui.render.GuiDraw;
import com.example.donutflipscanner.gui.theme.GuiColors;
import com.example.donutflipscanner.gui.theme.GuiTypography;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class SidebarNavigation {
    private static final int START_X = 6;
    private static final int START_Y = GuiLayout.HEADER_HEIGHT + 12;
    private static final int BUTTON_WIDTH = GuiLayout.SIDEBAR_WIDTH - 12;
    private static final int BUTTON_HEIGHT = 34;
    private static final int BUTTON_GAP = 6;

    public void render(
            DrawContext context,
            TextRenderer textRenderer,
            GuiTab selectedTab,
            int mouseX,
            int mouseY
    ) {
        for (int index = 0; index < GuiTab.values().length; index++) {
            GuiTab tab = GuiTab.values()[index];
            int y = buttonY(index);
            boolean selected = tab == selectedTab;
            boolean hovered = contains(mouseX, mouseY, y);

            if (selected || hovered) {
                if (selected) {
                    GuiDraw.fillVerticalGradient(context, START_X, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                            GuiColors.SELECTED_BACKGROUND, GuiColors.SELECTED_BACKGROUND_DARK);
                    context.fill(START_X, y, START_X + 2, y + BUTTON_HEIGHT, GuiColors.ACCENT_TEXT);
                } else {
                    context.fill(START_X, y, START_X + BUTTON_WIDTH, y + BUTTON_HEIGHT,
                            GuiColors.HOVER_BACKGROUND);
                }
            }
            GuiTypography.draw(
                    context,
                    textRenderer,
                    tab.displayName(),
                    START_X + 8,
                    y + 13,
                    selected ? GuiColors.PRIMARY_TEXT : hovered ? GuiColors.PRIMARY_TEXT : GuiColors.MUTED_TEXT,
                    false
            );
        }
    }

    public GuiTab findTabAt(int mouseX, int mouseY) {
        for (int index = 0; index < GuiTab.values().length; index++) {
            if (contains(mouseX, mouseY, buttonY(index))) {
                return GuiTab.values()[index];
            }
        }
        return null;
    }

    private int buttonY(int index) {
        return START_Y + index * (BUTTON_HEIGHT + BUTTON_GAP);
    }

    private boolean contains(int mouseX, int mouseY, int y) {
        return mouseX >= START_X
                && mouseX < START_X + BUTTON_WIDTH
                && mouseY >= y
                && mouseY < y + BUTTON_HEIGHT;
    }
}
