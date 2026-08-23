package com.example.donutflipscanner.gui;

import com.example.donutflipscanner.ModConstants;
import com.example.donutflipscanner.data.ApiConnectionStatus;
import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.MarketStatistics;
import com.example.donutflipscanner.data.ScannerStatus;
import com.example.donutflipscanner.gui.navigation.SidebarNavigation;
import com.example.donutflipscanner.gui.panel.ShellTabPanel;
import com.example.donutflipscanner.gui.panel.TabContentPanel;
import com.example.donutflipscanner.gui.render.GuiDraw;
import com.example.donutflipscanner.gui.theme.GuiColors;
import com.example.donutflipscanner.gui.theme.GuiTypography;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ClickGuiScreen extends Screen {
    private static final long TAB_DISSOLVE_DURATION_NANOS = 180_000_000L;
    private static final int TAB_DISSOLVE_MAXIMUM_ALPHA = 216;
    private static final Identifier DONUT_LOGO = Identifier.of(
            ModConstants.MOD_ID, "textures/gui/donut_smp_logo.png"
    );
    private static final int BRAND_LOGO_SIZE = 22;
    private static final int SCANNER_TOGGLE_WIDTH = 58;
    private static final int SCANNER_TOGGLE_HEIGHT = 18;
    private static final int SCANNER_TOGGLE_X = GuiLayout.LOGICAL_WIDTH - SCANNER_TOGGLE_WIDTH - 12;
    private static final int SCANNER_TOGGLE_Y = 10;

    private final ClientUiDataSources dataSources;
    private final SidebarNavigation navigation = new SidebarNavigation();
    private final Map<GuiTab, TabContentPanel> panels = new EnumMap<>(GuiTab.class);

    private GuiTab selectedTab = GuiTab.DASHBOARD;
    private GuiTab outgoingTab;
    private long tabTransitionStartedAt;
    private GuiLayout layout;

    public ClickGuiScreen(ClientUiDataSources dataSources) {
        super(Text.literal(ModConstants.MOD_NAME));
        this.dataSources = Objects.requireNonNull(dataSources, "dataSources");
        for (GuiTab tab : GuiTab.values()) {
            panels.put(tab, new ShellTabPanel(tab, dataSources));
        }
    }

    @Override
    protected void init() {
        layout = GuiLayout.fit(width, height);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layout = GuiLayout.fit(width, height);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (layout == null) {
            layout = GuiLayout.fit(width, height);
        }

        int logicalMouseX = layout.toLogicalX(mouseX);
        int logicalMouseY = layout.toLogicalY(mouseY);
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(layout.screenX(), layout.screenY());
        matrices.scale(layout.scale(), layout.scale());

        renderFrame(context);
        renderHeader(context, logicalMouseX, logicalMouseY);
        navigation.render(context, textRenderer, selectedTab, logicalMouseX, logicalMouseY);
        renderTabContent(context, logicalMouseX, logicalMouseY, System.nanoTime());
        renderStatusBar(context);

        matrices.popMatrix();
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderFrame(DrawContext context) {
        GuiDraw.fillVerticalGradient(context, 0, 0,
                GuiLayout.LOGICAL_WIDTH, GuiLayout.LOGICAL_HEIGHT,
                GuiColors.FRAME_GRADIENT_TOP, GuiColors.FRAME_GRADIENT_BOTTOM);
        GuiDraw.strokeRect(context, 0, 0, GuiLayout.LOGICAL_WIDTH,
                GuiLayout.LOGICAL_HEIGHT, GuiColors.PRIMARY_BORDER);
        GuiDraw.fillVerticalGradient(context, 1, GuiLayout.HEADER_HEIGHT,
                GuiLayout.SIDEBAR_WIDTH - 2,
                GuiLayout.LOGICAL_HEIGHT - GuiLayout.HEADER_HEIGHT - GuiLayout.STATUS_BAR_HEIGHT,
                GuiColors.SIDEBAR_GRADIENT_TOP, GuiColors.SIDEBAR_GRADIENT_BOTTOM);
        GuiDraw.fillHorizontalGradient(context, 1, 1,
                GuiLayout.LOGICAL_WIDTH - 2, GuiLayout.HEADER_HEIGHT - 2,
                GuiColors.HEADER_GRADIENT_LEFT, GuiColors.HEADER_GRADIENT_RIGHT);
        context.fill(1, GuiLayout.HEADER_HEIGHT - 1, GuiLayout.LOGICAL_WIDTH - 1, GuiLayout.HEADER_HEIGHT, GuiColors.SECONDARY_BORDER);
        context.fill(GuiLayout.SIDEBAR_WIDTH - 1, GuiLayout.HEADER_HEIGHT, GuiLayout.SIDEBAR_WIDTH, GuiLayout.LOGICAL_HEIGHT - GuiLayout.STATUS_BAR_HEIGHT, GuiColors.SECONDARY_BORDER);
        context.fill(1, GuiLayout.LOGICAL_HEIGHT - GuiLayout.STATUS_BAR_HEIGHT - 1, GuiLayout.LOGICAL_WIDTH - 1, GuiLayout.LOGICAL_HEIGHT - GuiLayout.STATUS_BAR_HEIGHT, GuiColors.SECONDARY_BORDER);
    }

    private void renderHeader(DrawContext context, int mouseX, int mouseY) {
        ScannerStatus status = dataSources.scannerStatus().getScannerStatus();
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                DONUT_LOGO,
                12,
                8,
                0.0F,
                0.0F,
                BRAND_LOGO_SIZE,
                BRAND_LOGO_SIZE,
                500,
                500,
                500,
                500
        );
        renderScannerToggle(context, status.enabled(), isInsideScannerToggle(mouseX, mouseY));
    }

    private void renderScannerToggle(DrawContext context, boolean enabled, boolean hovered) {
        int trackColor = enabled
                ? hovered ? GuiColors.TOGGLE_ON_HOVER : GuiColors.TOGGLE_ON_TRACK
                : hovered ? GuiColors.TOGGLE_OFF_HOVER : GuiColors.TOGGLE_OFF_TRACK;
        GuiDraw.fillRoundedRect(
                context,
                SCANNER_TOGGLE_X,
                SCANNER_TOGGLE_Y,
                SCANNER_TOGGLE_WIDTH,
                SCANNER_TOGGLE_HEIGHT,
                SCANNER_TOGGLE_HEIGHT / 2,
                trackColor
        );
        String label = "Scanner";
        GuiTypography.drawCentered(context, textRenderer, label,
                SCANNER_TOGGLE_X + SCANNER_TOGGLE_WIDTH / 2,
                SCANNER_TOGGLE_Y + 5,
                enabled ? GuiColors.INVERSE_TEXT : GuiColors.PRIMARY_TEXT);
    }

    private void renderStatusBar(DrawContext context) {
        ApiConnectionStatus api = dataSources.apiConnectionStatus().getApiConnectionStatus();
        MarketStatistics statistics = dataSources.marketStatistics().getMarketStatistics();
        int y = GuiLayout.LOGICAL_HEIGHT - 14;
        int x = 10;

        x = drawStatusText(context, "API: ", GuiColors.PRIMARY_TEXT, x, y);
        x = drawStatusText(context, api.displayName(), GuiColors.SECONDARY_TEXT, x, y);
        x = drawStatusText(context, "  |  Last Update: ", GuiColors.MUTED_TEXT, x, y);
        x = drawStatusText(context, api.lastUpdateText(), GuiColors.SECONDARY_TEXT, x, y);
        x = drawStatusText(context, "  |  Opportunities: ", GuiColors.MUTED_TEXT, x, y);
        drawStatusText(context, Integer.toString(statistics.opportunitiesFound()), GuiColors.PRIMARY_TEXT, x, y);
    }

    private void renderTabContent(DrawContext context, int mouseX, int mouseY, long now) {
        if (outgoingTab == null) {
            panels.get(selectedTab).render(context, textRenderer, mouseX, mouseY);
            return;
        }

        long elapsed = Math.max(0L, now - tabTransitionStartedAt);
        if (elapsed >= TAB_DISSOLVE_DURATION_NANOS) {
            outgoingTab = null;
            panels.get(selectedTab).render(context, textRenderer, mouseX, mouseY);
            return;
        }

        float progress = elapsed / (float) TAB_DISSOLVE_DURATION_NANOS;
        GuiTab renderedTab = progress < 0.5F ? outgoingTab : selectedTab;
        panels.get(renderedTab).render(context, textRenderer, mouseX, mouseY);

        float dissolve = 1.0F - Math.abs(progress * 2.0F - 1.0F);
        float eased = dissolve * dissolve * (3.0F - 2.0F * dissolve);
        int alpha = Math.round(TAB_DISSOLVE_MAXIMUM_ALPHA * eased);
        int overlay = alpha << 24 | GuiColors.TAB_DISSOLVE_RGB;
        context.fill(
                GuiLayout.SIDEBAR_WIDTH,
                GuiLayout.HEADER_HEIGHT,
                GuiLayout.LOGICAL_WIDTH - 1,
                GuiLayout.LOGICAL_HEIGHT - GuiLayout.STATUS_BAR_HEIGHT,
                overlay
        );
    }

    private int drawStatusText(DrawContext context, String text, int color, int x, int y) {
        GuiTypography.draw(context, textRenderer, text, x, y, color, false);
        return x + GuiTypography.width(textRenderer, text);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && layout != null) {
            int logicalX = layout.toLogicalX(click.x());
            int logicalY = layout.toLogicalY(click.y());
            if (isInsideScannerToggle(logicalX, logicalY)) {
                dataSources.scannerStatus().toggleScanner();
                return true;
            }
            GuiTab clickedTab = navigation.findTabAt(
                    logicalX,
                    logicalY
            );
            if (clickedTab != null) {
                if (clickedTab != selectedTab) {
                    outgoingTab = selectedTab;
                    selectedTab = clickedTab;
                    tabTransitionStartedAt = System.nanoTime();
                }
                return true;
            }
            return panels.get(selectedTab).mouseClicked(logicalX, logicalY, click.button());
        }
        return true;
    }

    private boolean isInsideScannerToggle(int x, int y) {
        return x >= SCANNER_TOGGLE_X
                && x < SCANNER_TOGGLE_X + SCANNER_TOGGLE_WIDTH
                && y >= SCANNER_TOGGLE_Y
                && y < SCANNER_TOGGLE_Y + SCANNER_TOGGLE_HEIGHT;
    }

    @Override
    public boolean mouseReleased(Click click) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (layout == null) {
            return true;
        }
        return panels.get(selectedTab).mouseScrolled(
                layout.toLogicalX(mouseX),
                layout.toLogicalY(mouseY),
                verticalAmount
        );
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (panels.get(selectedTab).keyPressed(input)) {
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE || input.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return panels.get(selectedTab).charTyped(input) || super.charTyped(input);
    }

    @Override
    public void close() {
        client.setScreen(null);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
