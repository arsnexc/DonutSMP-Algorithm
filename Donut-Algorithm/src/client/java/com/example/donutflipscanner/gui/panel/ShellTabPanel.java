package com.example.donutflipscanner.gui.panel;

import com.example.donutflipscanner.command.AuctionSearchCommand;
import com.example.donutflipscanner.data.ApiConnectionStatus;
import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.ItemFilterSnapshot;
import com.example.donutflipscanner.data.MarketStatistics;
import com.example.donutflipscanner.data.MarketChartPoint;
import com.example.donutflipscanner.data.MarketChartSnapshot;
import com.example.donutflipscanner.data.OpportunityHistoryEntry;
import com.example.donutflipscanner.data.ScannerStatus;
import com.example.donutflipscanner.data.AutomationSettingsSnapshot;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.gui.GuiLayout;
import com.example.donutflipscanner.gui.GuiTab;
import com.example.donutflipscanner.gui.render.GuiDraw;
import com.example.donutflipscanner.gui.theme.GuiColors;
import com.example.donutflipscanner.gui.theme.GuiTypography;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Provider-driven panels for the spacious blue trading workspace. */
public final class ShellTabPanel implements TabContentPanel {
    private static final int CONTENT_X = GuiLayout.SIDEBAR_WIDTH + 12;
    private static final int CONTENT_RIGHT = GuiLayout.LOGICAL_WIDTH - 12;
    private static final int CONTENT_WIDTH = CONTENT_RIGHT - CONTENT_X;
    private static final int CONTENT_Y = GuiLayout.HEADER_HEIGHT + 10;
    private static final int CARD_TOP = CONTENT_Y + 30;

    private static final int OPPORTUNITY_GRID_TOP = CARD_TOP + 25;
    private static final int OPPORTUNITY_GAP = 6;
    private static final int OPPORTUNITY_CARD_WIDTH = (CONTENT_WIDTH - OPPORTUNITY_GAP) / 2;
    private static final int OPPORTUNITY_CARD_HEIGHT = 49;
    private static final int OPPORTUNITY_ROW_GAP = 5;
    private static final int MAX_VISIBLE_OPPORTUNITIES = 8;
    private static final int SETTINGS_PANEL_WIDTH = (CONTENT_WIDTH - 7) / 2;
    private static final int NOTIFICATION_TOGGLE_WIDTH = 42;
    private static final int NOTIFICATION_TOGGLE_HEIGHT = 15;
    private static final int PURCHASE_MODAL_WIDTH = 300;
    private static final int PURCHASE_MODAL_HEIGHT = 82;

    private final GuiTab tab;
    private final ClientUiDataSources dataSources;
    private volatile String notice = "";
    private int opportunityOffset;
    private TextFieldWidget authorizationField;
    private boolean authorizationModalVisible;
    private String pendingPurchaseId;
    private String pendingPurchaseLabel;

    private static final int AUTOMATION_LEFT_WIDTH = 225;
    private static final int AUTOMATION_RIGHT_X = CONTENT_X + AUTOMATION_LEFT_WIDTH + 7;
    private static final int AUTH_MODAL_X = CONTENT_X + 72;
    private static final int AUTH_MODAL_Y = CARD_TOP + 46;
    private static final int AUTH_MODAL_WIDTH = 310;
    private static final int AUTH_MODAL_HEIGHT = 116;

    public ShellTabPanel(GuiTab tab, ClientUiDataSources dataSources) {
        this.tab = Objects.requireNonNull(tab, "tab");
        this.dataSources = Objects.requireNonNull(dataSources, "dataSources");
    }

    @Override
    public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
        GuiTypography.draw(context, textRenderer, tab.displayName(), CONTENT_X, CONTENT_Y,
                GuiColors.PRIMARY_TEXT, false);
        GuiTypography.draw(context, textRenderer, fit(textRenderer, tab.description(), CONTENT_WIDTH),
                CONTENT_X, CONTENT_Y + 13, GuiColors.SECONDARY_TEXT, false);
        switch (tab) {
            case DASHBOARD -> renderDashboard(context, textRenderer);
            case OPPORTUNITIES -> renderOpportunities(context, textRenderer, mouseX, mouseY);
            case ITEM_FILTERS -> renderFilters(context, textRenderer, mouseX, mouseY);
            case HISTORY -> renderHistory(context, textRenderer);
            case AUTOMATION -> renderAutomation(context, textRenderer, mouseX, mouseY);
            case SETTINGS -> renderSettings(context, textRenderer, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (tab == GuiTab.OPPORTUNITIES) {
            return clickOpportunity(mouseX, mouseY);
        }
        if (tab == GuiTab.AUTOMATION) {
            return clickAutomation(mouseX, mouseY);
        }
        if (tab == GuiTab.ITEM_FILTERS) {
            ItemFilterMode[] modes = ItemFilterMode.values();
            for (int index = 0; index < modes.length; index++) {
                int y = CARD_TOP + 39 + index * 37;
                if (contains(mouseX, mouseY, CONTENT_X + 9, y, 202, 29)) {
                    dataSources.itemFilters().setMode(modes[index])
                            .whenComplete((ignored, error) -> notice = error == null
                                    ? "Filter mode updated; reevaluation queued."
                                    : "Unable to update filter mode.");
                    return true;
                }
            }
        }
        if (tab == GuiTab.SETTINGS && contains(
                mouseX, mouseY, notificationToggleX(), notificationToggleY(),
                NOTIFICATION_TOGGLE_WIDTH, NOTIFICATION_TOGGLE_HEIGHT
        )) {
            dataSources.notificationSettings().toggleNotifications();
            boolean enabled = dataSources.notificationSettings().getNotificationSettings().enabled();
            notice = enabled ? "HUD notifications enabled." : "HUD notifications disabled.";
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(int mouseX, int mouseY, double verticalAmount) {
        if (tab != GuiTab.OPPORTUNITIES
                || !contains(mouseX, mouseY, CONTENT_X, OPPORTUNITY_GRID_TOP,
                CONTENT_WIDTH, OPPORTUNITY_CARD_HEIGHT * 4 + OPPORTUNITY_ROW_GAP * 3)) {
            return false;
        }
        List<FlipOpportunity> opportunities = dataSources.opportunities().getOpportunities();
        int maximumOffset = Math.max(0, opportunities.size() - MAX_VISIBLE_OPPORTUNITIES);
        int direction = verticalAmount < 0.0D ? 2 : verticalAmount > 0.0D ? -2 : 0;
        opportunityOffset = Math.max(0, Math.min(maximumOffset, opportunityOffset + direction));
        return true;
    }

    private boolean clickOpportunity(int mouseX, int mouseY) {
        if (pendingPurchaseId != null) {
            int modalX = CONTENT_X + (CONTENT_WIDTH - PURCHASE_MODAL_WIDTH) / 2;
            int modalY = CARD_TOP + 65;
            if (contains(mouseX, mouseY, modalX + 154, modalY + 58, 62, 15)) {
                pendingPurchaseId = null;
                pendingPurchaseLabel = null;
                notice = "Purchase tracking cancelled.";
                return true;
            }
            if (contains(mouseX, mouseY, modalX + 222, modalY + 58, 69, 15)) {
                String opportunityId = pendingPurchaseId;
                pendingPurchaseId = null;
                pendingPurchaseLabel = null;
                notice = "Recording confirmed purchase...";
                dataSources.opportunities().markPurchasedManually(opportunityId)
                        .whenComplete((updated, error) -> notice = error == null && updated
                                ? "Purchase tracked; waiting for your completed sale."
                                : "Listing was no longer verified; purchase not tracked.");
                return true;
            }
            return true;
        }
        List<FlipOpportunity> opportunities = dataSources.opportunities().getOpportunities();
        clampOpportunityOffset(opportunities.size());
        int visible = Math.min(MAX_VISIBLE_OPPORTUNITIES, opportunities.size() - opportunityOffset);
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            int x = opportunityCardX(visibleIndex);
            int y = opportunityCardY(visibleIndex);
            FlipOpportunity opportunity = opportunities.get(opportunityOffset + visibleIndex);
            if (contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 153, y + 31, 44, 13)) {
                pendingPurchaseId = opportunity.opportunityId();
                pendingPurchaseLabel = opportunity.itemName() + " x" + opportunity.count()
                        + " for $" + compact(opportunity.listingPrice());
                return true;
            }
            if (contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 105, y + 31, 24, 13)) {
                notice = "Submitting guarded execution...";
                dataSources.automationSettings().executeConfiguredMode(opportunity)
                        .whenComplete((result, error) -> notice = error == null
                                ? result.message() : "Unable to update opportunity state.");
                return true;
            }
            if (contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 77, y + 31, 20, 13)) {
                String command = AuctionSearchCommand.forOpportunity(opportunity);
                MinecraftClient.getInstance().keyboard.setClipboard(command);
                notice = "Copied " + command + " - paste it in chat.";
                return true;
            }
            if (contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 53, y + 31, 30, 13)) {
                String command = AuctionSearchCommand.sellAtTargetPrice(opportunity);
                MinecraftClient.getInstance().keyboard.setClipboard(command);
                notice = "Copied target-price " + command + " - verify the held stack before pasting.";
                return true;
            }
            if (contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 19, y + 31, 13, 13)) {
                notice = "Dismissing...";
                dataSources.opportunities().dismiss(opportunity.opportunityId())
                        .whenComplete((updated, error) -> notice = error == null && updated
                                ? "Opportunity dismissed." : "Unable to dismiss opportunity.");
                return true;
            }
        }
        return false;
    }

    private void renderDashboard(DrawContext context, TextRenderer textRenderer) {
        ScannerStatus scanner = dataSources.scannerStatus().getScannerStatus();
        ApiConnectionStatus api = dataSources.apiConnectionStatus().getApiConnectionStatus();
        MarketStatistics market = dataSources.marketStatistics().getMarketStatistics();
        int gap = 7;
        int cardWidth = (CONTENT_WIDTH - gap * 2) / 3;
        metric(context, textRenderer, CONTENT_X, CARD_TOP, cardWidth, "SCANNER", scanner.lifecycleState());
        metric(context, textRenderer, CONTENT_X + cardWidth + gap, CARD_TOP, cardWidth,
                "MARKET API", api.displayName());
        metric(context, textRenderer, CONTENT_X + (cardWidth + gap) * 2, CARD_TOP, cardWidth,
                "ACTIVE LISTINGS", compact(market.activeListings()));
        metric(context, textRenderer, CONTENT_X, CARD_TOP + 57, cardWidth,
                "STORED SALES", compact(market.storedTransactions()));
        metric(context, textRenderer, CONTENT_X + cardWidth + gap, CARD_TOP + 57, cardWidth,
                "OPPORTUNITIES", Integer.toString(market.opportunitiesFound()));
        metric(context, textRenderer, CONTENT_X + (cardWidth + gap) * 2, CARD_TOP + 57, cardWidth,
                "POTENTIAL PROFIT", "$" + compact(market.combinedPotentialProfit()));

        renderMarketChart(context, textRenderer, dataSources.marketChart().getMarketChart(), CARD_TOP + 121);
    }

    private void renderMarketChart(
            DrawContext context,
            TextRenderer renderer,
            MarketChartSnapshot chart,
            int y
    ) {
        panel(context, CONTENT_X, y, CONTENT_WIDTH, 90);
        GuiTypography.draw(context, renderer, chart.title(), CONTENT_X + 10, y + 8,
                GuiColors.ACCENT_TEXT, false);
        String currentValue = "$" + compact(chart.currentValue());
        GuiTypography.draw(context, renderer, currentValue, CONTENT_X + 10, y + 21,
                GuiColors.PRIMARY_TEXT, false);
        String change = String.format(Locale.ROOT, "%+.1f%%", chart.changePercent());
        GuiTypography.draw(context, renderer, change,
                CONTENT_X + 16 + GuiTypography.width(renderer, currentValue), y + 21,
                chart.changePercent() >= 0.0D ? GuiColors.POSITIVE_TEXT : GuiColors.NEGATIVE_TEXT, false);
        GuiTypography.draw(context, renderer, chart.rangeLabel(),
                CONTENT_RIGHT - 10 - GuiTypography.width(renderer, chart.rangeLabel()), y + 8,
                GuiColors.MUTED_TEXT, false);

        int graphX = CONTENT_X + 10;
        int graphY = y + 37;
        int graphWidth = CONTENT_WIDTH - 20;
        int graphHeight = 36;
        for (int index = 0; index < 3; index++) {
            int gridY = graphY + index * (graphHeight - 1) / 2;
            context.fill(graphX, gridY, graphX + graphWidth, gridY + 1, GuiColors.CHART_GRID);
        }

        List<MarketChartPoint> points = chart.points();
        if (points.size() < 2) {
            String emptyMessage = chart.title().startsWith("TRACKED REALIZED PROFIT")
                    ? "Confirm BOUGHT on a trade; profit appears after its completed sale."
                    : "No chart data available";
            GuiTypography.drawCentered(context, renderer, emptyMessage,
                    CONTENT_X + CONTENT_WIDTH / 2, graphY + 13, GuiColors.MUTED_TEXT);
            return;
        }
        long minimum = points.stream().mapToLong(MarketChartPoint::value).min().orElse(0L);
        long maximum = points.stream().mapToLong(MarketChartPoint::value).max().orElse(minimum + 1L);
        long spread = Math.max(1L, maximum - minimum);
        int previousX = graphX;
        int previousY = chartY(points.getFirst().value(), minimum, spread, graphY, graphHeight);
        for (int index = 1; index < points.size(); index++) {
            int pointX = graphX + Math.round(index * (graphWidth - 1) / (float) (points.size() - 1));
            int pointY = chartY(points.get(index).value(), minimum, spread, graphY, graphHeight);
            drawChartSegment(context, previousX, previousY, pointX, pointY,
                    graphY + graphHeight - 1);
            previousX = pointX;
            previousY = pointY;
        }
        context.fill(previousX - 1, previousY - 1, previousX + 2, previousY + 2,
                chart.changePercent() >= 0.0D ? GuiColors.POSITIVE_TEXT : GuiColors.NEGATIVE_TEXT);
        GuiTypography.draw(context, renderer, points.getFirst().label(), graphX, y + 77,
                GuiColors.MUTED_TEXT, false);
        String lastLabel = points.getLast().label();
        GuiTypography.draw(context, renderer, lastLabel,
                graphX + graphWidth - GuiTypography.width(renderer, lastLabel), y + 77,
                GuiColors.MUTED_TEXT, false);
    }

    private static int chartY(long value, long minimum, long spread, int graphY, int graphHeight) {
        double normalized = (value - minimum) / (double) spread;
        return graphY + graphHeight - 2 - (int) Math.round(normalized * (graphHeight - 4));
    }

    private static void drawChartSegment(
            DrawContext context,
            int startX,
            int startY,
            int endX,
            int endY,
            int baselineY
    ) {
        int width = Math.max(1, endX - startX);
        for (int step = 0; step <= width; step++) {
            float progress = step / (float) width;
            int x = startX + step;
            int y = Math.round(startY + (endY - startY) * progress);
            context.fill(x, y + 1, x + 1, baselineY, GuiColors.CHART_AREA);
            context.fill(x, y, x + 1, y + 2, GuiColors.CHART_LINE);
        }
    }

    private void renderOpportunities(
            DrawContext context,
            TextRenderer textRenderer,
            int mouseX,
            int mouseY
    ) {
        List<FlipOpportunity> opportunities = dataSources.opportunities().getOpportunities();
        clampOpportunityOffset(opportunities.size());
        context.fill(CONTENT_X, CARD_TOP, CONTENT_RIGHT, CARD_TOP + 20, GuiColors.PANEL_BACKGROUND);
        context.fill(CONTENT_X, CARD_TOP + 19, CONTENT_RIGHT, CARD_TOP + 20, GuiColors.SECONDARY_BORDER);

        String range = opportunityRange(opportunities.size());
        GuiTypography.draw(context, textRenderer, range, CONTENT_X + 7, CARD_TOP + 6,
                GuiColors.ACCENT_TEXT, false);
        String helper = notice.isBlank() ? "Scroll to browse more trades" : notice;
        String fittedHelper = fit(textRenderer, helper, CONTENT_WIDTH - 120);
        GuiTypography.draw(context, textRenderer, fittedHelper,
                CONTENT_RIGHT - 7 - GuiTypography.width(textRenderer, fittedHelper), CARD_TOP + 6,
                notice.isBlank() ? GuiColors.MUTED_TEXT : GuiColors.SECONDARY_TEXT, false);

        if (opportunities.isEmpty()) {
            panel(context, CONTENT_X, OPPORTUNITY_GRID_TOP, CONTENT_WIDTH, 100);
            GuiTypography.drawCentered(context, textRenderer,
                    "No live opportunities match current filters.",
                    CONTENT_X + CONTENT_WIDTH / 2, OPPORTUNITY_GRID_TOP + 44,
                    GuiColors.MUTED_TEXT);
            return;
        }

        int visible = Math.min(MAX_VISIBLE_OPPORTUNITIES, opportunities.size() - opportunityOffset);
        for (int visibleIndex = 0; visibleIndex < visible; visibleIndex++) {
            FlipOpportunity opportunity = opportunities.get(opportunityOffset + visibleIndex);
            int x = opportunityCardX(visibleIndex);
            int y = opportunityCardY(visibleIndex);
            boolean hovered = contains(mouseX, mouseY, x, y,
                    OPPORTUNITY_CARD_WIDTH, OPPORTUNITY_CARD_HEIGHT);
            opportunityCard(context, textRenderer, opportunity, x, y, hovered, mouseX, mouseY);
        }
        if (pendingPurchaseId != null) {
            renderPurchaseConfirmation(context, textRenderer, mouseX, mouseY);
        }
    }

    private void opportunityCard(
            DrawContext context,
            TextRenderer renderer,
            FlipOpportunity value,
            int x,
            int y,
            boolean hovered,
            int mouseX,
            int mouseY
    ) {
        GuiDraw.fillVerticalGradient(context, x + 1, y + 1,
                OPPORTUNITY_CARD_WIDTH - 2, OPPORTUNITY_CARD_HEIGHT - 2,
                hovered ? GuiColors.HOVER_BACKGROUND : GuiColors.RAISED_PANEL,
                GuiColors.RAISED_PANEL_BOTTOM);
        GuiDraw.strokeRect(context, x, y, OPPORTUNITY_CARD_WIDTH, OPPORTUNITY_CARD_HEIGHT,
                hovered ? GuiColors.PRIMARY_BORDER : GuiColors.SUBTLE_BORDER);

        String name = fit(renderer, value.itemName() + " x" + value.count(), 121);
        GuiTypography.draw(context, renderer, name, x + 7, y + 6,
                GuiColors.PRIMARY_TEXT, false);
        String profit = "+$" + compact(value.estimatedProfit());
        GuiTypography.draw(context, renderer, profit,
                x + OPPORTUNITY_CARD_WIDTH - 7 - GuiTypography.width(renderer, profit),
                y + 6, GuiColors.POSITIVE_TEXT, false);

        String prices = "BUY $" + compact(value.listingPrice())
                + "  VALUE $" + compact(value.fairValue());
        GuiTypography.draw(context, renderer, fit(renderer, prices, OPPORTUNITY_CARD_WIDTH - 14),
                x + 7, y + 19, GuiColors.SECONDARY_TEXT, false);

        String evidence = formatPercent(value.roiPercent()) + " ROI  "
                + formatPercent(value.confidencePercent()) + " CONF  " + value.listingAge();
        GuiTypography.draw(context, renderer, fit(renderer, evidence, OPPORTUNITY_CARD_WIDTH - 160),
                x + 7, y + 34, GuiColors.MUTED_TEXT, false);
        button(context, renderer, x + OPPORTUNITY_CARD_WIDTH - 153, y + 31, 44, 13, "BOUGHT",
                contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 153, y + 31, 44, 13));
        button(context, renderer, x + OPPORTUNITY_CARD_WIDTH - 105, y + 31, 24, 13, "RUN",
                contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 105, y + 31, 24, 13));
        button(context, renderer, x + OPPORTUNITY_CARD_WIDTH - 77, y + 31, 20, 13, "AH",
                contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 77, y + 31, 20, 13));
        button(context, renderer, x + OPPORTUNITY_CARD_WIDTH - 53, y + 31, 30, 13, "SELL",
                contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 53, y + 31, 30, 13));
        button(context, renderer, x + OPPORTUNITY_CARD_WIDTH - 19, y + 31, 13, 13, "X",
                contains(mouseX, mouseY, x + OPPORTUNITY_CARD_WIDTH - 19, y + 31, 13, 13));
    }

    private void renderPurchaseConfirmation(
            DrawContext context,
            TextRenderer renderer,
            int mouseX,
            int mouseY
    ) {
        int x = CONTENT_X + (CONTENT_WIDTH - PURCHASE_MODAL_WIDTH) / 2;
        int y = CARD_TOP + 65;
        panel(context, x, y, PURCHASE_MODAL_WIDTH, PURCHASE_MODAL_HEIGHT);
        GuiDraw.strokeRect(context, x, y, PURCHASE_MODAL_WIDTH, PURCHASE_MODAL_HEIGHT,
                GuiColors.PRIMARY_BORDER);
        GuiTypography.draw(context, renderer, "CONFIRM PURCHASE", x + 9, y + 9,
                GuiColors.ACCENT_TEXT, false);
        GuiTypography.draw(context, renderer,
                fit(renderer, Objects.requireNonNullElse(pendingPurchaseLabel, "Selected opportunity"),
                        PURCHASE_MODAL_WIDTH - 18),
                x + 9, y + 24, GuiColors.PRIMARY_TEXT, false);
        GuiTypography.draw(context, renderer,
                "Only confirm after you bought this exact listing.",
                x + 9, y + 39, GuiColors.MUTED_TEXT, false);
        button(context, renderer, x + 154, y + 58, 62, 15, "CANCEL",
                contains(mouseX, mouseY, x + 154, y + 58, 62, 15));
        button(context, renderer, x + 222, y + 58, 69, 15, "CONFIRM",
                contains(mouseX, mouseY, x + 222, y + 58, 69, 15));
    }

    private void renderFilters(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
        ItemFilterSnapshot filters = dataSources.itemFilters().getItemFilters();
        panel(context, CONTENT_X, CARD_TOP, 220, 211);
        panel(context, CONTENT_X + 227, CARD_TOP, CONTENT_WIDTH - 227, 211);

        GuiTypography.draw(context, textRenderer, "FILTER MODE", CONTENT_X + 9, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        String counts = filters.whitelistedItems().size() + " whitelisted  |  "
                + filters.blacklistedItems().size() + " blacklisted";
        GuiTypography.draw(context, textRenderer, counts, CONTENT_X + 9, CARD_TOP + 24,
                GuiColors.MUTED_TEXT, false);

        ItemFilterMode[] modes = ItemFilterMode.values();
        for (int index = 0; index < modes.length; index++) {
            int y = CARD_TOP + 39 + index * 37;
            boolean selected = modes[index] == filters.mode();
            boolean hovered = contains(mouseX, mouseY, CONTENT_X + 9, y, 202, 29);
            if (selected) {
                GuiDraw.fillHorizontalGradient(context, CONTENT_X + 9, y, 202, 29,
                        GuiColors.SELECTED_BACKGROUND, GuiColors.SELECTED_BACKGROUND_DARK);
            } else {
                context.fill(CONTENT_X + 9, y, CONTENT_X + 211, y + 29,
                        hovered ? GuiColors.HOVER_BACKGROUND : GuiColors.PANEL_BACKGROUND);
            }
            GuiTypography.draw(context, textRenderer, modeName(modes[index]), CONTENT_X + 18, y + 11,
                    selected ? GuiColors.PRIMARY_TEXT : GuiColors.SECONDARY_TEXT, false);
        }

        int rightX = CONTENT_X + 237;
        GuiTypography.draw(context, textRenderer, "LIST SUMMARY", rightX, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        lineAt(context, textRenderer, rightX, CONTENT_RIGHT - 10,
                "Whitelist", Integer.toString(filters.whitelistedItems().size()), CARD_TOP + 34);
        lineAt(context, textRenderer, rightX, CONTENT_RIGHT - 10,
                "Blacklist", Integer.toString(filters.blacklistedItems().size()), CARD_TOP + 55);
        lineAt(context, textRenderer, rightX, CONTENT_RIGHT - 10,
                "Reevaluation", filters.reevaluationPending() ? "Pending" : "Ready", CARD_TOP + 76);
        context.fill(rightX, CARD_TOP + 95, CONTENT_RIGHT - 10, CARD_TOP + 96,
                GuiColors.SECONDARY_BORDER);
        String message = notice.isBlank()
                ? "Choose how the scanner applies your saved item lists."
                : notice;
        drawWrapped(context, textRenderer, message, rightX, CARD_TOP + 108,
                CONTENT_RIGHT - rightX - 10, GuiColors.MUTED_TEXT, 4);
    }

    private void renderHistory(DrawContext context, TextRenderer textRenderer) {
        List<OpportunityHistoryEntry> history = dataSources.history().getHistory();
        panel(context, CONTENT_X, CARD_TOP, CONTENT_WIDTH, 211);
        context.fill(CONTENT_X + 1, CARD_TOP + 1, CONTENT_RIGHT - 1, CARD_TOP + 23,
                GuiColors.PANEL_BACKGROUND);
        tableHeader(context, textRenderer, "ITEM", CONTENT_X + 9, CARD_TOP + 8);
        tableHeader(context, textRenderer, "PROFIT", CONTENT_X + 204, CARD_TOP + 8);
        tableHeader(context, textRenderer, "ROI", CONTENT_X + 277, CARD_TOP + 8);
        tableHeader(context, textRenderer, "CONFIDENCE", CONTENT_X + 323, CARD_TOP + 8);
        tableHeader(context, textRenderer, "STATE", CONTENT_X + 385, CARD_TOP + 8);
        if (history.isEmpty()) {
            GuiTypography.drawCentered(context, textRenderer, "No stored opportunity history.",
                    CONTENT_X + CONTENT_WIDTH / 2, CARD_TOP + 102, GuiColors.MUTED_TEXT);
            return;
        }
        int visible = Math.min(9, history.size());
        for (int index = 0; index < visible; index++) {
            OpportunityHistoryEntry value = history.get(index);
            int y = CARD_TOP + 24 + index * 20;
            if ((index & 1) == 0) {
                context.fill(CONTENT_X + 1, y, CONTENT_RIGHT - 1, y + 19,
                        GuiColors.RAISED_PANEL_BOTTOM);
            }
            GuiTypography.draw(context, textRenderer, fit(textRenderer, value.itemName(), 183),
                    CONTENT_X + 9, y + 6, GuiColors.PRIMARY_TEXT, false);
            GuiTypography.draw(context, textRenderer, "$" + compact(value.estimatedProfit()),
                    CONTENT_X + 204, y + 6, GuiColors.POSITIVE_TEXT, false);
            GuiTypography.draw(context, textRenderer, formatPercent(value.roiPercent()),
                    CONTENT_X + 277, y + 6, GuiColors.SECONDARY_TEXT, false);
            GuiTypography.draw(context, textRenderer, formatPercent(value.confidencePercent()),
                    CONTENT_X + 323, y + 6, GuiColors.SECONDARY_TEXT, false);
            GuiTypography.draw(context, textRenderer, fit(textRenderer, value.state(), 52),
                    CONTENT_X + 385, y + 6, GuiColors.MUTED_TEXT, false);
        }
    }

    private void renderAutomation(
            DrawContext context,
            TextRenderer renderer,
            int mouseX,
            int mouseY
    ) {
        AutomationSettingsSnapshot value = dataSources.automationSettings().snapshot();
        int left = CONTENT_X;
        int right = AUTOMATION_RIGHT_X;
        int rightWidth = CONTENT_RIGHT - right;
        panel(context, left, CARD_TOP, AUTOMATION_LEFT_WIDTH, 211);
        panel(context, right, CARD_TOP, rightWidth, 211);

        GuiTypography.draw(context, renderer, "EXECUTION SAFETY", left + 10, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        GuiTypography.draw(context, renderer, "Enabled", left + 10, CARD_TOP + 34,
                GuiColors.MUTED_TEXT, false);
        settingsToggle(context, renderer, left + AUTOMATION_LEFT_WIDTH - 52, CARD_TOP + 28,
                value.configuration().enabled(), contains(mouseX, mouseY,
                        left + AUTOMATION_LEFT_WIDTH - 52, CARD_TOP + 28,
                        NOTIFICATION_TOGGLE_WIDTH, NOTIFICATION_TOGGLE_HEIGHT));
        GuiTypography.draw(context, renderer, "Mode", left + 10, CARD_TOP + 57,
                GuiColors.MUTED_TEXT, false);
        button(context, renderer, left + 68, CARD_TOP + 51, 145, 18,
                shortMode(value.configuration().mode()),
                contains(mouseX, mouseY, left + 68, CARD_TOP + 51, 145, 18));
        GuiTypography.draw(context, renderer, "Current server", left + 10, CARD_TOP + 81,
                GuiColors.MUTED_TEXT, false);
        GuiTypography.draw(context, renderer, fit(renderer, value.currentServer(), 126),
                left + AUTOMATION_LEFT_WIDTH - 10
                        - GuiTypography.width(renderer, fit(renderer, value.currentServer(), 126)),
                CARD_TOP + 81, GuiColors.PRIMARY_TEXT, false);
        lineAt(context, renderer, left + 10, left + AUTOMATION_LEFT_WIDTH - 10,
                "Allowlist", value.configuration().allowedServerAddresses().size() + " exact",
                CARD_TOP + 103);
        button(context, renderer, left + 10, CARD_TOP + 119, AUTOMATION_LEFT_WIDTH - 20, 18,
                "ALLOW CURRENT SERVER",
                contains(mouseX, mouseY, left + 10, CARD_TOP + 119,
                        AUTOMATION_LEFT_WIDTH - 20, 18));
        lineAt(context, renderer, left + 10, left + AUTOMATION_LEFT_WIDTH - 10,
                "Min confidence", value.configuration().minimumConfidence() + "%", CARD_TOP + 150);
        lineAt(context, renderer, left + 10, left + AUTOMATION_LEFT_WIDTH - 10,
                "API balance", balanceText(value.balance()), CARD_TOP + 170);
        GuiTypography.draw(context, renderer, fit(renderer, value.balance().message(), AUTOMATION_LEFT_WIDTH - 20),
                left + 10, CARD_TOP + 194,
                value.balance().status() == com.example.donutflipscanner.balance.BalanceStatus.ERROR
                        ? GuiColors.NEGATIVE_TEXT : GuiColors.MUTED_TEXT, false);

        GuiTypography.draw(context, renderer, "SESSION", right + 10, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        lineAt(context, renderer, right + 10, CONTENT_RIGHT - 10,
                "Armed", value.execution().sessionArmed() ? "YES" : "NO", CARD_TOP + 34);
        lineAt(context, renderer, right + 10, CONTENT_RIGHT - 10,
                "State", value.execution().activeState().map(Enum::name).orElse("IDLE"), CARD_TOP + 55);
        lineAt(context, renderer, right + 10, CONTENT_RIGHT - 10,
                "Session purchases", Integer.toString(value.execution().purchasesThisSession()), CARD_TOP + 76);
        context.fill(right + 10, CARD_TOP + 91, CONTENT_RIGHT - 10, CARD_TOP + 92,
                GuiColors.SECONDARY_BORDER);
        drawWrapped(context, renderer,
                notice.isBlank() ? value.execution().statusMessage() : notice,
                right + 10, CARD_TOP + 103, rightWidth - 20,
                value.execution().emergencyStopped() ? GuiColors.NEGATIVE_TEXT : GuiColors.MUTED_TEXT, 3);

        int half = (rightWidth - 27) / 2;
        button(context, renderer, right + 10, CARD_TOP + 143, half, 18, "ARM SESSION",
                contains(mouseX, mouseY, right + 10, CARD_TOP + 143, half, 18));
        button(context, renderer, right + 17 + half, CARD_TOP + 143, half, 18, "CONFIRM TRADE",
                contains(mouseX, mouseY, right + 17 + half, CARD_TOP + 143, half, 18));
        button(context, renderer, right + 10, CARD_TOP + 168, half, 18, "DISARM",
                contains(mouseX, mouseY, right + 10, CARD_TOP + 168, half, 18));
        button(context, renderer, right + 17 + half, CARD_TOP + 168, half, 18,
                value.execution().emergencyStopped() ? "CLEAR STOP" : "EMERGENCY STOP",
                contains(mouseX, mouseY, right + 17 + half, CARD_TOP + 168, half, 18));
        GuiTypography.draw(context, renderer, fit(renderer, value.warning(), rightWidth - 20),
                right + 10, CARD_TOP + 195, GuiColors.MUTED_TEXT, false);

        if (authorizationModalVisible) {
            renderAuthorizationModal(context, renderer, mouseX, mouseY);
        }
    }

    private void renderAuthorizationModal(
            DrawContext context, TextRenderer renderer, int mouseX, int mouseY
    ) {
        context.fill(CONTENT_X, CARD_TOP, CONTENT_RIGHT, CARD_TOP + 211, 0xB8121417);
        panel(context, AUTH_MODAL_X, AUTH_MODAL_Y, AUTH_MODAL_WIDTH, AUTH_MODAL_HEIGHT);
        GuiTypography.draw(context, renderer, "SESSION AUTHORIZATION", AUTH_MODAL_X + 12,
                AUTH_MODAL_Y + 11, GuiColors.PRIMARY_TEXT, false);
        GuiTypography.draw(context, renderer,
                "Type exactly: " + TradeAutomationCoordinator.ARM_CONFIRMATION,
                AUTH_MODAL_X + 12, AUTH_MODAL_Y + 29, GuiColors.MUTED_TEXT, false);
        if (authorizationField == null) {
            authorizationField = new TextFieldWidget(
                    renderer, AUTH_MODAL_X + 12, AUTH_MODAL_Y + 47,
                    AUTH_MODAL_WIDTH - 24, 18, Text.literal("Authorization confirmation")
            );
            authorizationField.setMaxLength(64);
            authorizationField.setTextShadow(false);
            authorizationField.setPlaceholder(Text.literal("Authorization phrase"));
            authorizationField.setFocused(true);
        }
        authorizationField.renderWidget(context, mouseX, mouseY, 0.0F);
        int buttonY = AUTH_MODAL_Y + 82;
        button(context, renderer, AUTH_MODAL_X + 12, buttonY, 134, 20, "AUTHORIZE SESSION",
                contains(mouseX, mouseY, AUTH_MODAL_X + 12, buttonY, 134, 20));
        button(context, renderer, AUTH_MODAL_X + 164, buttonY, 134, 20, "CANCEL",
                contains(mouseX, mouseY, AUTH_MODAL_X + 164, buttonY, 134, 20));
    }

    private boolean clickAutomation(int mouseX, int mouseY) {
        if (authorizationModalVisible) {
            int buttonY = AUTH_MODAL_Y + 82;
            if (contains(mouseX, mouseY, AUTH_MODAL_X + 12, buttonY, 134, 20)) {
                boolean armed = dataSources.automationSettings().armCurrentServer(
                        authorizationField == null ? "" : authorizationField.getText()
                );
                notice = armed ? "Session armed for the exact current server."
                        : "Authorization rejected; verify mode, allowlist, server, and exact phrase.";
                closeAuthorizationModal();
                return true;
            }
            if (contains(mouseX, mouseY, AUTH_MODAL_X + 164, buttonY, 134, 20)) {
                closeAuthorizationModal();
                notice = "Session authorization cancelled.";
                return true;
            }
            if (authorizationField != null && contains(mouseX, mouseY,
                    AUTH_MODAL_X + 12, AUTH_MODAL_Y + 47, AUTH_MODAL_WIDTH - 24, 18)) {
                authorizationField.onClick(new Click(mouseX, mouseY,
                        new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
            }
            return true;
        }

        AutomationSettingsSnapshot snapshot = dataSources.automationSettings().snapshot();
        if (contains(mouseX, mouseY, CONTENT_X + AUTOMATION_LEFT_WIDTH - 52, CARD_TOP + 28,
                NOTIFICATION_TOGGLE_WIDTH, NOTIFICATION_TOGGLE_HEIGHT)) {
            dataSources.automationSettings().setEnabled(!snapshot.configuration().enabled())
                    .whenComplete((ignored, error) -> notice = error == null
                            ? "Automation setting saved; session remains disarmed."
                            : "Unable to save automation setting.");
            return true;
        }
        if (contains(mouseX, mouseY, CONTENT_X + 68, CARD_TOP + 51, 145, 18)) {
            AutomationMode next = nextMode(snapshot.configuration().mode());
            dataSources.automationSettings().setMode(next)
                    .whenComplete((ignored, error) -> notice = error == null
                            ? "Mode changed to " + shortMode(next) + "; session disarmed."
                            : "Unable to save automation mode.");
            return true;
        }
        if (contains(mouseX, mouseY, CONTENT_X + 10, CARD_TOP + 119,
                AUTOMATION_LEFT_WIDTH - 20, 18)) {
            dataSources.automationSettings().allowCurrentServer()
                    .whenComplete((ignored, error) -> notice = error == null
                            ? "Exact current server added to allowlist; session remains disarmed."
                            : "Unable to allow current server.");
            return true;
        }
        int rightWidth = CONTENT_RIGHT - AUTOMATION_RIGHT_X;
        int half = (rightWidth - 27) / 2;
        if (contains(mouseX, mouseY, AUTOMATION_RIGHT_X + 10, CARD_TOP + 143, half, 18)) {
            authorizationModalVisible = true;
            authorizationField = null;
            return true;
        }
        if (contains(mouseX, mouseY, AUTOMATION_RIGHT_X + 17 + half, CARD_TOP + 143, half, 18)) {
            dataSources.automationSettings().confirmPending()
                    .whenComplete((result, error) -> notice = error == null
                            ? result.message() : "Unable to confirm pending execution.");
            return true;
        }
        if (contains(mouseX, mouseY, AUTOMATION_RIGHT_X + 10, CARD_TOP + 168, half, 18)) {
            dataSources.automationSettings().disarm();
            notice = "Session disarmed.";
            return true;
        }
        if (contains(mouseX, mouseY, AUTOMATION_RIGHT_X + 17 + half, CARD_TOP + 168, half, 18)) {
            if (snapshot.execution().emergencyStopped()) {
                dataSources.automationSettings().clearEmergencyStop();
                notice = "Emergency stop cleared; session remains disarmed.";
            } else {
                dataSources.automationSettings().emergencyStop();
                notice = "Emergency stop active; all execution is blocked.";
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (pendingPurchaseId != null && input.key() == GLFW.GLFW_KEY_ESCAPE) {
            pendingPurchaseId = null;
            pendingPurchaseLabel = null;
            notice = "Purchase tracking cancelled.";
            return true;
        }
        if (!authorizationModalVisible) {
            return false;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeAuthorizationModal();
            notice = "Session authorization cancelled.";
            return true;
        }
        return authorizationField != null && authorizationField.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return authorizationModalVisible && authorizationField != null
                && authorizationField.charTyped(input);
    }

    private void closeAuthorizationModal() {
        authorizationModalVisible = false;
        if (authorizationField != null) {
            authorizationField.setText("");
            authorizationField.setFocused(false);
        }
    }

    private static AutomationMode nextMode(AutomationMode current) {
        return switch (current) {
            case DISABLED -> AutomationMode.DRY_RUN;
            case DRY_RUN -> AutomationMode.CONFIRM_EACH;
            case CONFIRM_EACH -> AutomationMode.AUTOMATIC_AUTHORIZED_SERVER;
            case AUTOMATIC_AUTHORIZED_SERVER -> AutomationMode.DISABLED;
        };
    }

    private static String shortMode(AutomationMode mode) {
        return switch (mode) {
            case DISABLED -> "DISABLED";
            case DRY_RUN -> "DRY RUN";
            case CONFIRM_EACH -> "CONFIRM EACH";
            case AUTOMATIC_AUTHORIZED_SERVER -> "AUTHORIZED AUTO";
        };
    }

    private void renderSettings(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
        ScannerStatus scanner = dataSources.scannerStatus().getScannerStatus();
        ApiConnectionStatus api = dataSources.apiConnectionStatus().getApiConnectionStatus();
        boolean notifications = dataSources.notificationSettings().getNotificationSettings().enabled();
        int panelWidth = SETTINGS_PANEL_WIDTH;
        panel(context, CONTENT_X, CARD_TOP, panelWidth, 211);
        panel(context, CONTENT_X + panelWidth + 7, CARD_TOP, panelWidth, 211);

        GuiTypography.draw(context, textRenderer, "SCANNER", CONTENT_X + 10, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        lineAt(context, textRenderer, CONTENT_X + 10, CONTENT_X + panelWidth - 10,
                "State", scanner.lifecycleState(), CARD_TOP + 34);
        lineAt(context, textRenderer, CONTENT_X + 10, CONTENT_X + panelWidth - 10,
                "Data mode", api.displayName().equals("Mock Data") ? "Mock" : "Live", CARD_TOP + 55);
        lineAt(context, textRenderer, CONTENT_X + 10, CONTENT_X + panelWidth - 10,
                "Cooldown", api.cooldownSeconds() + " seconds", CARD_TOP + 76);
        lineAt(context, textRenderer, CONTENT_X + 10, CONTENT_X + panelWidth - 10,
                "GUI size", "574 x 350", CARD_TOP + 97);
        context.fill(CONTENT_X + 10, CARD_TOP + 115, CONTENT_X + panelWidth - 10,
                CARD_TOP + 116, GuiColors.SECONDARY_BORDER);
        GuiTypography.draw(context, textRenderer, "HUD notifications", CONTENT_X + 10, CARD_TOP + 128,
                GuiColors.MUTED_TEXT, false);
        settingsToggle(context, textRenderer, notificationToggleX(), notificationToggleY(),
                notifications, contains(mouseX, mouseY, notificationToggleX(), notificationToggleY(),
                        NOTIFICATION_TOGGLE_WIDTH, NOTIFICATION_TOGGLE_HEIGHT));
        context.fill(CONTENT_X + 10, CARD_TOP + 151, CONTENT_X + panelWidth - 10,
                CARD_TOP + 152, GuiColors.SECONDARY_BORDER);
        drawWrapped(context, textRenderer,
                notice.isBlank()
                        ? "Disable HUD notifications without pausing market scanning."
                        : notice,
                CONTENT_X + 10, CARD_TOP + 164, panelWidth - 20, GuiColors.MUTED_TEXT, 3);

        int rightX = CONTENT_X + panelWidth + 17;
        int rightEdge = CONTENT_RIGHT - 10;
        GuiTypography.draw(context, textRenderer, "CONNECTION", rightX, CARD_TOP + 11,
                GuiColors.ACCENT_TEXT, false);
        lineAt(context, textRenderer, rightX, rightEdge,
                "Requests / window", Integer.toString(api.requestsInCurrentWindow()), CARD_TOP + 34);
        lineAt(context, textRenderer, rightX, rightEdge,
                "Average latency", api.averageLatencyMillis() + " ms", CARD_TOP + 55);
        lineAt(context, textRenderer, rightX, rightEdge,
                "Last update", api.lastUpdateText(), CARD_TOP + 76);
        context.fill(rightX, CARD_TOP + 94, rightEdge, CARD_TOP + 95,
                GuiColors.SECONDARY_BORDER);
        String warning = api.warning().or(() -> scanner.warning()).orElse(
                api.displayName().equals("Mock Data")
                        ? "Market API integration is inactive while mock mode is selected."
                        : "Live data is read asynchronously; purchases remain manual."
        );
        drawWrapped(context, textRenderer, warning, rightX, CARD_TOP + 108,
                rightEdge - rightX, GuiColors.MUTED_TEXT, 6);
    }

    private static int notificationToggleX() {
        return CONTENT_X + SETTINGS_PANEL_WIDTH - 10 - NOTIFICATION_TOGGLE_WIDTH;
    }

    private static int notificationToggleY() {
        return CARD_TOP + 124;
    }

    private static void settingsToggle(
            DrawContext context,
            TextRenderer renderer,
            int x,
            int y,
            boolean enabled,
            boolean hovered
    ) {
        int fill = enabled
                ? hovered ? GuiColors.TOGGLE_ON_HOVER : GuiColors.TOGGLE_ON_TRACK
                : hovered ? GuiColors.TOGGLE_OFF_HOVER : GuiColors.TOGGLE_OFF_TRACK;
        context.fill(x, y, x + NOTIFICATION_TOGGLE_WIDTH, y + NOTIFICATION_TOGGLE_HEIGHT, fill);
        GuiDraw.strokeRect(context, x, y, NOTIFICATION_TOGGLE_WIDTH, NOTIFICATION_TOGGLE_HEIGHT,
                enabled ? GuiColors.POSITIVE_TEXT : GuiColors.PRIMARY_BORDER);
        GuiTypography.drawCentered(context, renderer, enabled ? "ON" : "OFF",
                x + NOTIFICATION_TOGGLE_WIDTH / 2, y + 3,
                enabled ? GuiColors.INVERSE_TEXT : GuiColors.PRIMARY_TEXT);
    }

    private String opportunityRange(int total) {
        if (total == 0) {
            return "0 LIVE TRADES";
        }
        int first = opportunityOffset + 1;
        int last = Math.min(total, opportunityOffset + MAX_VISIBLE_OPPORTUNITIES);
        return total <= MAX_VISIBLE_OPPORTUNITIES
                ? total + " LIVE TRADES"
                : "LIVE TRADES " + first + "-" + last + " / " + total;
    }

    private void clampOpportunityOffset(int size) {
        opportunityOffset = Math.max(0,
                Math.min(Math.max(0, size - MAX_VISIBLE_OPPORTUNITIES), opportunityOffset));
    }

    private static int opportunityCardX(int visibleIndex) {
        return CONTENT_X + (visibleIndex % 2) * (OPPORTUNITY_CARD_WIDTH + OPPORTUNITY_GAP);
    }

    private static int opportunityCardY(int visibleIndex) {
        return OPPORTUNITY_GRID_TOP
                + (visibleIndex / 2) * (OPPORTUNITY_CARD_HEIGHT + OPPORTUNITY_ROW_GAP);
    }

    private static void metric(
            DrawContext context,
            TextRenderer renderer,
            int x,
            int y,
            int width,
            String label,
            String value
    ) {
        panel(context, x, y, width, 50);
        GuiTypography.draw(context, renderer, label, x + 9, y + 9,
                GuiColors.MUTED_TEXT, false);
        GuiTypography.draw(context, renderer, fit(renderer, value, width - 18), x + 9, y + 27,
                GuiColors.PRIMARY_TEXT, false);
    }

    private static void lineAt(
            DrawContext context,
            TextRenderer renderer,
            int left,
            int right,
            String label,
            String value,
            int y
    ) {
        GuiTypography.draw(context, renderer, label, left, y, GuiColors.MUTED_TEXT, false);
        String fitted = fit(renderer, value, Math.max(40, (right - left) / 2));
        GuiTypography.draw(context, renderer, fitted,
                right - GuiTypography.width(renderer, fitted), y,
                GuiColors.PRIMARY_TEXT, false);
    }

    private static void panel(DrawContext context, int x, int y, int width, int height) {
        GuiDraw.fillVerticalGradient(context, x + 1, y + 1, width - 2, height - 2,
                GuiColors.RAISED_PANEL, GuiColors.RAISED_PANEL_BOTTOM);
        GuiDraw.strokeRect(context, x, y, width, height, GuiColors.SECONDARY_BORDER);
    }

    private static void button(
            DrawContext context,
            TextRenderer renderer,
            int x,
            int y,
            int width,
            int height,
            String label,
            boolean hovered
    ) {
        context.fill(x, y, x + width, y + height,
                hovered ? GuiColors.SELECTED_BACKGROUND : GuiColors.HOVER_BACKGROUND);
        GuiTypography.drawCentered(context, renderer, label, x + width / 2, y + 3,
                GuiColors.PRIMARY_TEXT);
    }

    private static void tableHeader(DrawContext context, TextRenderer renderer, String text, int x, int y) {
        GuiTypography.draw(context, renderer, text, x, y, GuiColors.MUTED_TEXT, false);
    }

    private static void drawWrapped(
            DrawContext context,
            TextRenderer renderer,
            String text,
            int x,
            int y,
            int maximumWidth,
            int color,
            int maximumLines
    ) {
        String remaining = text;
        int line = 0;
        while (!remaining.isBlank() && line < maximumLines) {
            int end = remaining.length();
            while (end > 1 && GuiTypography.width(renderer, remaining.substring(0, end)) > maximumWidth) {
                end--;
            }
            if (end < remaining.length()) {
                int space = remaining.lastIndexOf(' ', end - 1);
                if (space > 0) {
                    end = space;
                }
            }
            String rendered = remaining.substring(0, end).strip();
            GuiTypography.draw(context, renderer, rendered, x, y + line * 13, color, false);
            remaining = remaining.substring(end).strip();
            line++;
        }
    }

    private static boolean contains(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String fit(TextRenderer renderer, String text, int maximumWidth) {
        if (GuiTypography.width(renderer, text) <= maximumWidth) {
            return text;
        }
        String suffix = "...";
        int end = text.length();
        while (end > 0 && GuiTypography.width(renderer, text.substring(0, end) + suffix) > maximumWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private static String compact(long value) {
        long absolute = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        String prefix = value < 0 ? "-" : "";
        if (absolute >= 1_000_000_000L) {
            return prefix + decimal(absolute / 1_000_000_000.0D) + "B";
        }
        if (absolute >= 1_000_000L) {
            return prefix + decimal(absolute / 1_000_000.0D) + "M";
        }
        if (absolute >= 1_000L) {
            return prefix + decimal(absolute / 1_000.0D) + "K";
        }
        return Long.toString(value);
    }

    private static String balanceText(com.example.donutflipscanner.balance.BalanceSnapshot snapshot) {
        return snapshot.amount().map(value -> "$" + compact(value)).orElseGet(() -> switch (snapshot.status()) {
            case REFRESHING -> "REFRESHING";
            case ERROR -> "ERROR";
            case AVAILABLE -> "AVAILABLE";
            case UNAVAILABLE -> "UNAVAILABLE";
        });
    }

    private static String compact(BigDecimal value) {
        BigDecimal absolute = value.abs();
        String prefix = value.signum() < 0 ? "-" : "";
        BigDecimal billion = BigDecimal.valueOf(1_000_000_000L);
        BigDecimal million = BigDecimal.valueOf(1_000_000L);
        BigDecimal thousand = BigDecimal.valueOf(1_000L);
        if (absolute.compareTo(billion) >= 0) {
            return prefix + decimal(absolute.divide(billion).doubleValue()) + "B";
        }
        if (absolute.compareTo(million) >= 0) {
            return prefix + decimal(absolute.divide(million).doubleValue()) + "M";
        }
        if (absolute.compareTo(thousand) >= 0) {
            return prefix + decimal(absolute.divide(thousand).doubleValue()) + "K";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String decimal(double value) {
        return value >= 100.0D
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value).replace(".0", "");
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String modeName(ItemFilterMode mode) {
        return switch (mode) {
            case ALL_ITEMS -> "All items";
            case WHITELIST_ONLY -> "Whitelist only";
            case ALL_EXCEPT_BLACKLIST -> "All except blacklist";
        };
    }
}
