package com.example.donutflipscanner;

import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.ClientUiDataSourceRouter;
import com.example.donutflipscanner.gui.ClickGuiScreen;
import com.example.donutflipscanner.gui.hud.OpportunityHudOverlay;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DonutFlipScannerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);
    private static KeyBinding openGuiKey;
    private static ClientUiDataSources dataSources;
    private static ClientUiDataSourceRouter dataSourceRouter;
    private static volatile MarketScanner marketScanner;
    private static volatile ClientProductionRuntime productionRuntime;
    private static volatile CompletableFuture<Optional<ClientProductionRuntime>> productionStartup;
    private static final AtomicBoolean clientStopping = new AtomicBoolean();
    private static final OpportunityHudOverlay HUD_OVERLAY = new OpportunityHudOverlay();
    private static volatile String currentServerAddress = "";

    @Override
    public void onInitializeClient() {
        dataSourceRouter = new ClientUiDataSourceRouter(ClientUiDataSources.createMock());
        dataSources = dataSourceRouter.dataSources();
        KeyBinding.Category category = KeyBinding.Category.create(
                Identifier.of(ModConstants.MOD_ID, ModConstants.KEYBIND_CATEGORY_PATH)
        );
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                ModConstants.OPEN_GUI_KEY,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category
        ));
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of(ModConstants.MOD_ID, "opportunity_alerts"),
                HUD_OVERLAY::render
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            net.minecraft.client.network.ServerInfo server = client.getCurrentServerEntry();
            updateCurrentServerAddress(server == null ? "" : server.address);
            // Session authorization is volatile and must be invalidated on a server change
            // even when both the ClickGUI and HUD notifications are closed/disabled.
            dataSources.automationSettings().snapshot();
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen instanceof ClickGuiScreen) {
                    client.setScreen(null);
                } else if (client.currentScreen == null) {
                    client.setScreen(new ClickGuiScreen(dataSources));
                }
            }
            HUD_OVERLAY.tick(dataSources);
        });
        Path liveConfigDirectory = FabricLoader.getInstance().getConfigDir()
                .resolve(ModConstants.CONFIG_DIRECTORY_NAME);
        productionStartup = ClientProductionRuntime.startAsync(
                liveConfigDirectory,
                net.minecraft.client.MinecraftClient.getInstance().getSession().getUsername(),
                Optional.ofNullable(net.minecraft.client.MinecraftClient.getInstance()
                                .getSession().getUuidOrNull())
                        .map(java.util.UUID::toString).orElse("")
        );
        productionStartup.whenComplete((runtime, error) -> {
            if (error != null) {
                LOGGER.warn("Live market startup failed; mock data remains active: {}", sanitized(error));
                return;
            }
            if (runtime.isEmpty()) {
                LOGGER.info("No DonutSMP API credential found at {}; mock data remains active.",
                        liveConfigDirectory.resolve("api-key.txt"));
                return;
            }
            ClientProductionRuntime started = runtime.orElseThrow();
            if (clientStopping.get()) {
                started.close();
                return;
            }
            productionRuntime = started;
            LOGGER.info("Live DonutSMP market scanning started.");
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            clientStopping.set(true);
            ClientProductionRuntime runtime = productionRuntime;
            if (runtime != null) {
                runtime.close();
            } else {
                MarketScanner scanner = marketScanner;
                if (scanner != null) {
                    scanner.shutdownAsync();
                }
            }
        });
    }

    /** Compatibility hook for integrations that compose an alternate live scanner. */
    public static void attachMarketScanner(MarketScanner scanner) {
        marketScanner = scanner;
    }

    public static void useLiveDataSources(ClientUiDataSources liveSources, MarketScanner scanner) {
        Objects.requireNonNull(liveSources, "liveSources");
        Objects.requireNonNull(scanner, "scanner");
        dataSourceRouter.select(liveSources);
        marketScanner = scanner;
        scanner.updateConfiguration(scanner.configuration().withMockDataMode(false));
    }

    public static void useMockDataSources() {
        dataSourceRouter.select(ClientUiDataSources.createMock());
        MarketScanner scanner = marketScanner;
        if (scanner != null) {
            scanner.updateConfiguration(scanner.configuration().withMockDataMode(true));
        }
    }

    public static String currentServerAddress() {
        return currentServerAddress;
    }

    static void updateCurrentServerAddress(String address) {
        currentServerAddress = Objects.requireNonNullElse(address, "");
    }

    private static String sanitized(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
