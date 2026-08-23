package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.AuctionListingCandidate;
import com.example.donutflipscanner.automation.model.AuctionLocateResult;
import com.example.donutflipscanner.automation.model.AuctionSlotSnapshot;
import com.example.donutflipscanner.automation.model.AuctionVerificationResult;
import com.example.donutflipscanner.automation.model.InventoryVerificationResult;
import com.example.donutflipscanner.automation.model.ListingResult;
import com.example.donutflipscanner.automation.model.ListingVerificationResult;
import com.example.donutflipscanner.automation.model.PurchaseResult;
import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.service.AuctionInteractionAdapter;
import com.example.donutflipscanner.automation.service.AuctionCommandRenderer;
import com.example.donutflipscanner.automation.service.PurchasedStackPlacementPlanner;
import com.example.donutflipscanner.automation.service.StrictAuctionListingMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounded Minecraft adapter for an explicitly configured private-server screen contract.
 * Exact titles, slot ranges, listing ID, price, seller, item components, and inventory deltas
 * are all checked before the next mutation. There are no built-in public-server layouts.
 */
public final class MinecraftAuctionInteractionAdapter implements AuctionInteractionAdapter, AutoCloseable {
    private static final Duration SCREEN_TIMEOUT = Duration.ofSeconds(5);

    private final MinecraftClient client;
    private final Supplier<Optional<AuctionInteractionProfile>> profileSupplier;
    private final StrictAuctionListingMatcher matcher = new StrictAuctionListingMatcher();
    private final AuctionCommandRenderer commandRenderer = new AuctionCommandRenderer();
    private final PurchasedStackPlacementPlanner placementPlanner = new PurchasedStackPlacementPlanner();
    private final MinecraftStackFingerprintFactory fingerprintFactory = new MinecraftStackFingerprintFactory();
    private final ScheduledExecutorService pollingExecutor;
    private final Map<String, CapturedListing> captures = new ConcurrentHashMap<>();

    public MinecraftAuctionInteractionAdapter(
            MinecraftClient client,
            Supplier<Optional<AuctionInteractionProfile>> profileSupplier
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
        pollingExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "donut-authorized-auction-poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
        Optional<AuctionInteractionProfile> configured = profileSupplier.get();
        if (configured.isEmpty()) {
            return CompletableFuture.completedFuture(AuctionLocateResult.missing(
                    "No private-server auction interaction profile is configured."
            ));
        }
        AuctionInteractionProfile profile = configured.orElseThrow();
        String command;
        try {
            command = commandRenderer.search(profile, request);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(AuctionLocateResult.missing(failure.getMessage()));
        }
        return onClient(() -> {
            requireConnection();
            client.getNetworkHandler().sendChatCommand(command);
            return null;
        }).thenCompose(ignored -> awaitHandledScreen(profile.resultsScreenTitle()))
                .thenCompose(ignored -> scanAllPages(request, profile))
                .exceptionally(error -> AuctionLocateResult.missing(rootMessage(error)));
    }

    @Override
    public CompletableFuture<AuctionVerificationResult> verifyListing(
            TradeExecutionRequest request, AuctionListingCandidate candidate
    ) {
        return onClient(() -> {
            CapturedListing capture = requiredCapture(request.executionId());
            HandledScreen<?> screen = currentHandled(capture.profile().resultsScreenTitle());
            StrictAuctionListingMatcher.Match match = matcher.match(
                    request, capture.profile(), snapshots(screen, capture.profile())
            );
            if (match.slot().isEmpty() || match.slot().orElseThrow().slotId() != capture.slotId()) {
                return AuctionVerificationResult.rejected(match.message());
            }
            ItemStack current = screen.getScreenHandler().getSlot(capture.slotId()).getStack();
            if (!sameIntrinsicStack(capture.intrinsicStack(), current, capture.profile())) {
                return AuctionVerificationResult.rejected("Listing components changed after it was located.");
            }
            return AuctionVerificationResult.accepted();
        }).exceptionally(error -> AuctionVerificationResult.rejected(rootMessage(error)));
    }

    @Override
    public CompletableFuture<PurchaseResult> purchase(
            TradeExecutionRequest request, AuctionListingCandidate candidate
    ) {
        return onClient(() -> {
            CapturedListing capture = requiredCapture(request.executionId());
            HandledScreen<?> screen = currentHandled(capture.profile().resultsScreenTitle());
            requireSlot(screen.getScreenHandler(), capture.slotId());
            client.interactionManager.clickSlot(
                    screen.getScreenHandler().syncId, capture.slotId(), 0,
                    SlotActionType.PICKUP, Objects.requireNonNull(client.player, "player")
            );
            return capture;
        }).thenCompose(capture -> awaitHandledScreen(capture.profile().purchaseConfirmationTitle())
                .thenCompose(screen -> onClient(() -> {
                    requireSlot(screen.getScreenHandler(), capture.profile().purchaseConfirmationSlot());
                    ItemStack confirmation = screen.getScreenHandler()
                            .getSlot(capture.profile().purchaseConfirmationSlot()).getStack();
                    if (confirmation.isEmpty()
                            || !matcher.matchesSlot(request, capture.profile(), new AuctionSlotSnapshot(
                             capture.profile().purchaseConfirmationSlot(),
                             Registries.ITEM.getId(confirmation.getItem()).toString(),
                            confirmation.getCount(), fingerprintFactory.fingerprint(confirmation, capture.profile()),
                            fingerprintFactory.visibleLore(confirmation)
                    )) || !sameIntrinsicStack(capture.intrinsicStack(), confirmation, capture.profile())) {
                        throw new IllegalStateException(
                                "Confirmation slot does not repeat the exact immutable listing."
                        );
                    }
                    client.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId,
                            capture.profile().purchaseConfirmationSlot(), 0,
                            SlotActionType.PICKUP, Objects.requireNonNull(client.player, "player")
                    );
                    return new PurchaseResult(true, true,
                            "Purchase confirmation was clicked; inventory verification is required.");
                })))
                .exceptionally(error -> new PurchaseResult(false, false, rootMessage(error)));
    }

    @Override
    public CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request) {
        CapturedListing capture;
        try {
            capture = requiredCapture(request.executionId());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new InventoryVerificationResult(
                    false, true, failure.getMessage()
            ));
        }
        return awaitClientValue(() -> {
            int current = inventoryCount(capture.intrinsicStack(), capture.profile());
            return current == capture.baselineInventoryCount() + request.expectedItemCount()
                    ? Optional.of(current) : Optional.empty();
        }, SCREEN_TIMEOUT).thenApply(ignored -> new InventoryVerificationResult(
                true, false, "Inventory increased by the exact expected item count."
        )).exceptionally(error -> new InventoryVerificationResult(
                false, true, "Purchase inventory verification failed: " + rootMessage(error)
        ));
    }

    @Override
    public CompletableFuture<ListingResult> listForSale(
            TradeExecutionRequest request, RelistPlan relistPlan
    ) {
        CapturedListing capture;
        try {
            capture = requiredCapture(request.executionId());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new ListingResult(false, failure.getMessage()));
        }
        return preparePurchasedStackInHand(request, capture).thenCompose(ignored -> onClient(() -> {
            String command = commandRenderer.listing(capture.profile(), relistPlan.listingPrice());
            capture.relistPrice = relistPlan.listingPrice();
            requireConnection();
            client.getNetworkHandler().sendChatCommand(command);
            return new ListingResult(true,
                    "Relist command submitted; title and inventory verification are required.");
        })).exceptionally(error -> new ListingResult(false, rootMessage(error)));
    }

    @Override
    public CompletableFuture<ListingVerificationResult> verifyListingCreated(
            TradeExecutionRequest request, RelistPlan relistPlan
    ) {
        CapturedListing capture;
        try {
            capture = requiredCapture(request.executionId());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new ListingVerificationResult(false, failure.getMessage()));
        }
        return awaitHandledScreen(capture.profile().listingCreatedTitle())
                .thenCompose(ignored -> awaitClientValue(() -> inventoryCount(
                                capture.intrinsicStack(), capture.profile())
                                == capture.baselineInventoryCount() ? Optional.of(Boolean.TRUE) : Optional.empty(),
                        SCREEN_TIMEOUT))
                .thenApply(ignored -> {
                    captures.remove(request.executionId());
                    return new ListingVerificationResult(true,
                            "Private-server listing confirmation and exact inventory delta were verified.");
                })
                .exceptionally(error -> new ListingVerificationResult(false, rootMessage(error)));
    }

    @Override
    public CompletableFuture<Void> returnToSafeScreen() {
        captures.clear();
        return onClient(() -> {
            if (client.player != null) {
                client.player.closeHandledScreen();
            } else {
                client.setScreen(null);
            }
            return (Void) null;
        }).exceptionally(error -> null);
    }

    private CompletableFuture<AuctionLocateResult> scanAllPages(
            TradeExecutionRequest request, AuctionInteractionProfile profile
    ) {
        List<List<AuctionSlotSnapshot>> pages = new ArrayList<>();
        return scanPage(profile, pages, 0).thenCompose(lastPage -> {
            StrictAuctionListingMatcher.PageMatch found = matcher.matchPages(request, profile, pages);
            if (found.match().isEmpty()) {
                return CompletableFuture.completedFuture(AuctionLocateResult.missing(found.message()));
            }
            StrictAuctionListingMatcher.PageSlot pageSlot = found.match().orElseThrow();
            int pagesBack = lastPage - pageSlot.pageIndex();
            return navigateBackward(profile, pagesBack).thenCompose(ignored -> onClient(() ->
                    captureExactListing(request, profile, pageSlot.slot().slotId())
            ));
        });
    }

    private CompletableFuture<Integer> scanPage(
            AuctionInteractionProfile profile,
            List<List<AuctionSlotSnapshot>> pages,
            int pageIndex
    ) {
        return onClient(() -> {
            HandledScreen<?> screen = currentHandled(profile.resultsScreenTitle());
            pages.add(snapshots(screen, profile));
            if (pageIndex + 1 >= profile.maximumPages()) {
                return Optional.<String>empty();
            }
            ScreenHandler handler = screen.getScreenHandler();
            requireSlot(handler, profile.nextPageSlot());
            if (handler.getSlot(profile.nextPageSlot()).getStack().isEmpty()) {
                return Optional.<String>empty();
            }
            String before = pageSignature(screen, profile);
            client.interactionManager.clickSlot(
                    handler.syncId, profile.nextPageSlot(), 0, SlotActionType.PICKUP,
                    Objects.requireNonNull(client.player, "player")
            );
            return Optional.of(before);
        }).thenCompose(before -> {
            if (before.isEmpty()) {
                return CompletableFuture.completedFuture(pageIndex);
            }
            return awaitPageChanged(profile, before.orElseThrow())
                    .thenCompose(ignored -> scanPage(profile, pages, pageIndex + 1));
        });
    }

    private CompletableFuture<Void> navigateBackward(
            AuctionInteractionProfile profile, int remaining
    ) {
        if (remaining <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return onClient(() -> {
            HandledScreen<?> screen = currentHandled(profile.resultsScreenTitle());
            ScreenHandler handler = screen.getScreenHandler();
            requireSlot(handler, profile.previousPageSlot());
            if (handler.getSlot(profile.previousPageSlot()).getStack().isEmpty()) {
                throw new IllegalStateException("Configured previous-page control is unavailable.");
            }
            String before = pageSignature(screen, profile);
            client.interactionManager.clickSlot(
                    handler.syncId, profile.previousPageSlot(), 0, SlotActionType.PICKUP,
                    Objects.requireNonNull(client.player, "player")
            );
            return before;
        }).thenCompose(before -> awaitPageChanged(profile, before))
                .thenCompose(ignored -> navigateBackward(profile, remaining - 1));
    }

    private CompletableFuture<HandledScreen<?>> awaitPageChanged(
            AuctionInteractionProfile profile, String previousSignature
    ) {
        return awaitClientValue(() -> {
            if (client.currentScreen instanceof HandledScreen<?> screen
                    && profile.resultsScreenTitle().equals(screen.getTitle().getString())
                    && !previousSignature.equals(pageSignature(screen, profile))) {
                return Optional.of(screen);
            }
            return Optional.empty();
        }, SCREEN_TIMEOUT);
    }

    private AuctionLocateResult captureExactListing(
            TradeExecutionRequest request, AuctionInteractionProfile profile, int expectedSlot
    ) {
        HandledScreen<?> screen = currentHandled(profile.resultsScreenTitle());
        StrictAuctionListingMatcher.Match match = matcher.match(request, profile, snapshots(screen, profile));
        if (match.slot().isEmpty() || match.slot().orElseThrow().slotId() != expectedSlot) {
            return AuctionLocateResult.missing(match.message());
        }
        int slotId = match.slot().orElseThrow().slotId();
        ItemStack visible = screen.getScreenHandler().getSlot(slotId).getStack();
        ItemStack intrinsic = fingerprintFactory.intrinsicCopy(visible, profile);
        int baseline = inventoryCount(intrinsic, profile);
        if (baseline != 0) {
            return AuctionLocateResult.missing(
                    "An identical item already exists in inventory; purchase attribution would be ambiguous."
            );
        }
        captures.put(request.executionId(), new CapturedListing(profile, slotId, intrinsic, baseline));
        return AuctionLocateResult.found(request.expectedCandidate());
    }

    private List<AuctionSlotSnapshot> snapshots(
            HandledScreen<?> screen, AuctionInteractionProfile profile
    ) {
        ScreenHandler handler = screen.getScreenHandler();
        List<AuctionSlotSnapshot> result = new ArrayList<>();
        int last = Math.min(handler.slots.size() - 1, profile.lastResultSlot());
        int first = profile.firstResultSlot();
        for (int slotId = Math.max(0, first); slotId <= last; slotId++) {
            Slot slot = handler.getSlot(slotId);
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                result.add(new AuctionSlotSnapshot(
                        slotId, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                        fingerprintFactory.fingerprint(stack, profile), fingerprintFactory.visibleLore(stack)
                ));
            }
        }
        return List.copyOf(result);
    }

    private String pageSignature(HandledScreen<?> screen, AuctionInteractionProfile profile) {
        StringBuilder result = new StringBuilder();
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            result.append(slot.id).append(':');
            if (!stack.isEmpty()) {
                result.append(Registries.ITEM.getId(stack.getItem())).append(':')
                        .append(stack.getCount()).append(':')
                        .append(stack.getName().getString()).append(':')
                        .append(fingerprintFactory.visibleLore(stack));
            }
            result.append('|');
        }
        return result.toString();
    }

    private int inventoryCount(ItemStack intrinsic, AuctionInteractionProfile profile) {
        if (client.player == null) {
            return 0;
        }
        int total = 0;
        for (int index = 0; index < client.player.getInventory().size(); index++) {
            ItemStack candidate = client.player.getInventory().getStack(index);
            if (sameIntrinsicStack(intrinsic, candidate, profile)) {
                total += candidate.getCount();
            }
        }
        return total;
    }

    private List<PurchasedStackPlacementPlanner.InventorySlotState> inventoryState(
            ItemStack intrinsic, AuctionInteractionProfile profile
    ) {
        List<PurchasedStackPlacementPlanner.InventorySlotState> result = new ArrayList<>();
        for (int index = 0; index < client.player.getInventory().size(); index++) {
            ItemStack candidate = client.player.getInventory().getStack(index);
            result.add(new PurchasedStackPlacementPlanner.InventorySlotState(
                    index, candidate.isEmpty() ? 0 : candidate.getCount(),
                    sameIntrinsicStack(intrinsic, candidate, profile)
            ));
        }
        return List.copyOf(result);
    }

    private boolean sameIntrinsicStack(
            ItemStack expected, ItemStack candidate, AuctionInteractionProfile profile
    ) {
        return !candidate.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(
                expected, fingerprintFactory.intrinsicCopy(candidate, profile)
        );
    }

    private CompletableFuture<Void> preparePurchasedStackInHand(
            TradeExecutionRequest request, CapturedListing capture
    ) {
        return onClient(() -> {
            requireConnection();
            if (client.currentScreen instanceof HandledScreen<?>) {
                client.player.closeHandledScreen();
            }
            PurchasedStackPlacementPlanner.Plan plan = placementPlanner.plan(
                    inventoryState(capture.intrinsicStack(), capture.profile()),
                    request.expectedItemCount(), client.player.getInventory().getSelectedSlot()
            );
            if (!plan.accepted()) {
                throw new IllegalStateException(plan.message());
            }
            int source = plan.sourceInventoryIndex().orElseThrow();
            int hotbar = plan.targetHotbarSlot().orElseThrow();
            if (plan.swapRequired()) {
                client.interactionManager.clickSlot(
                        client.player.playerScreenHandler.syncId, source, hotbar,
                        SlotActionType.SWAP, client.player
                );
            }
            client.player.getInventory().setSelectedSlot(hotbar);
            return hotbar;
        }).thenCompose(hotbar -> awaitClientValue(() -> {
            ItemStack held = client.player == null ? ItemStack.EMPTY : client.player.getMainHandStack();
            return held.getCount() == request.expectedItemCount()
                    && sameIntrinsicStack(capture.intrinsicStack(), held, capture.profile())
                    ? Optional.of(Boolean.TRUE) : Optional.empty();
        }, SCREEN_TIMEOUT)).thenApply(ignored -> null);
    }

    private HandledScreen<?> currentHandled(String exactTitle) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)
                || !exactTitle.equals(screen.getTitle().getString())) {
            throw new IllegalStateException("Expected exact auction screen is not open: " + exactTitle);
        }
        return screen;
    }

    private CompletableFuture<HandledScreen<?>> awaitHandledScreen(String exactTitle) {
        return awaitClientValue(() -> {
            if (client.currentScreen instanceof HandledScreen<?> screen
                    && exactTitle.equals(screen.getTitle().getString())) {
                return Optional.of(screen);
            }
            return Optional.empty();
        }, SCREEN_TIMEOUT);
    }

    private <T> CompletableFuture<T> awaitClientValue(Supplier<Optional<T>> supplier, Duration timeout) {
        CompletableFuture<T> result = new CompletableFuture<>();
        poll(supplier, System.nanoTime() + timeout.toNanos(), result);
        return result;
    }

    private <T> void poll(Supplier<Optional<T>> supplier, long deadline, CompletableFuture<T> result) {
        if (result.isDone()) {
            return;
        }
        client.execute(() -> {
            try {
                Optional<T> value = supplier.get();
                if (value.isPresent()) {
                    result.complete(value.orElseThrow());
                } else if (System.nanoTime() >= deadline) {
                    result.completeExceptionally(new IllegalStateException("Timed out waiting for verified screen state."));
                } else {
                    pollingExecutor.schedule(() -> poll(supplier, deadline, result), 50, TimeUnit.MILLISECONDS);
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
    }

    private <T> CompletableFuture<T> onClient(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        client.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CapturedListing requiredCapture(String executionId) {
        CapturedListing value = captures.get(executionId);
        if (value == null) {
            throw new IllegalStateException("No immutable Minecraft listing capture exists for this execution.");
        }
        return value;
    }

    private void requireConnection() {
        if (client.getNetworkHandler() == null || client.player == null || client.interactionManager == null) {
            throw new IllegalStateException("Minecraft is not connected to an interactive server session.");
        }
    }

    private static void requireSlot(ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size() || !handler.getSlot(slotId).isEnabled()) {
            throw new IllegalStateException("Configured auction slot is absent or disabled.");
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return Objects.requireNonNullElse(current.getMessage(), current.getClass().getSimpleName());
    }

    @Override
    public void close() {
        captures.clear();
        pollingExecutor.shutdownNow();
    }

    private static final class CapturedListing {
        private final AuctionInteractionProfile profile;
        private final int slotId;
        private final ItemStack intrinsicStack;
        private final int baselineInventoryCount;
        private volatile BigDecimal relistPrice;

        private CapturedListing(
                AuctionInteractionProfile profile, int slotId, ItemStack intrinsicStack,
                int baselineInventoryCount
        ) {
            this.profile = profile;
            this.slotId = slotId;
            this.intrinsicStack = intrinsicStack;
            this.baselineInventoryCount = baselineInventoryCount;
        }

        private AuctionInteractionProfile profile() {
            return profile;
        }

        private int slotId() {
            return slotId;
        }

        private ItemStack intrinsicStack() {
            return intrinsicStack;
        }

        private int baselineInventoryCount() {
            return baselineInventoryCount;
        }
    }
}
