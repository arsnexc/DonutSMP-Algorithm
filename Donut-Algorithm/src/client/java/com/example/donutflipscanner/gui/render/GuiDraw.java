package com.example.donutflipscanner.gui.render;

import net.minecraft.client.gui.DrawContext;

/** Small drawing helpers shared by shell and future components. */
public final class GuiDraw {
    private GuiDraw() {
    }

    public static void fillRoundedRect(
            DrawContext context,
            int x,
            int y,
            int width,
            int height,
            int radius,
            int color
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }

        int safeRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (safeRadius == 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }

        context.fill(x, y + safeRadius, x + width, y + height - safeRadius, color);
        context.fill(x + safeRadius, y, x + width - safeRadius, y + height, color);

        double radiusSquared = safeRadius * safeRadius;
        for (int row = 0; row < safeRadius; row++) {
            double distanceFromCenter = safeRadius - row - 0.5D;
            int inset = (int) Math.ceil(safeRadius - Math.sqrt(radiusSquared - distanceFromCenter * distanceFromCenter));
            context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            context.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
        }
    }

    public static void fillVerticalGradient(
            DrawContext context,
            int x,
            int y,
            int width,
            int height,
            int topColor,
            int bottomColor
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (height == 1) {
            context.fill(x, y, x + width, y + 1, topColor);
            return;
        }
        for (int row = 0; row < height; row++) {
            float progress = row / (float) (height - 1);
            context.fill(x, y + row, x + width, y + row + 1, blend(topColor, bottomColor, progress));
        }
    }

    public static void fillHorizontalGradient(
            DrawContext context,
            int x,
            int y,
            int width,
            int height,
            int leftColor,
            int rightColor
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == 1) {
            context.fill(x, y, x + 1, y + height, leftColor);
            return;
        }
        for (int column = 0; column < width; column++) {
            float progress = column / (float) (width - 1);
            context.fill(x + column, y, x + column + 1, y + height, blend(leftColor, rightColor, progress));
        }
    }

    public static void strokeRect(
            DrawContext context,
            int x,
            int y,
            int width,
            int height,
            int color
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y + 1, x + 1, y + height - 1, color);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static int blend(int from, int to, float progress) {
        int alpha = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * progress);
        int red = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * progress);
        int green = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * progress);
        int blue = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * progress);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int channel(int color, int shift) {
        return color >>> shift & 0xFF;
    }
}
