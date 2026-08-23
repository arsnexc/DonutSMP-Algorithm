package com.example.donutflipscanner.gui.theme;

import com.example.donutflipscanner.ModConstants;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Applies the bundled Geist Sans face without replacing Minecraft's global font. */
public final class GuiTypography {
    private static final Style INTERFACE_STYLE = Style.EMPTY.withFont(
            new StyleSpriteSource.Font(Identifier.of(ModConstants.MOD_ID, "interface"))
    );

    private GuiTypography() {
    }

    public static Text text(String value) {
        return Text.literal(value).setStyle(INTERFACE_STYLE);
    }

    public static int width(TextRenderer renderer, String value) {
        return renderer.getWidth(text(value));
    }

    public static void draw(
            DrawContext context,
            TextRenderer renderer,
            String value,
            int x,
            int y,
            int color,
            boolean shadow
    ) {
        context.drawText(renderer, text(value), x, y, color, shadow);
    }

    public static void drawCentered(
            DrawContext context,
            TextRenderer renderer,
            String value,
            int centerX,
            int y,
            int color
    ) {
        Text styled = text(value);
        context.drawText(renderer, styled, centerX - renderer.getWidth(styled) / 2,
                y, color, false);
    }

}
