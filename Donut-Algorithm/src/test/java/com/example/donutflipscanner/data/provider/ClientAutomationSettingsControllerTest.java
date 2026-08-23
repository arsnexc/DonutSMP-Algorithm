package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.data.FlipOpportunity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAutomationSettingsControllerTest {
    @Test
    void currentServerAllowlistAndDryRunRemainSeparatedFromRealExecution() {
        AtomicReference<AutomationConfig> config = new AtomicReference<>(AutomationConfig.defaults());
        TradeAutomationCoordinator coordinator = new TradeAutomationCoordinator(() -> config.get());
        ClientAutomationSettingsController controller = new ClientAutomationSettingsController(
                config::get,
                updated -> {
                    config.set(updated);
                    return CompletableFuture.completedFuture(null);
                },
                () -> "PRIVATE.EXAMPLE:25565",
                coordinator
        );

        controller.allowCurrentServer().join();
        assertEquals(java.util.Set.of("private.example:25565"), config.get().allowedServerAddresses());
        assertFalse(controller.armCurrentServer(TradeAutomationCoordinator.ARM_CONFIRMATION));

        FlipOpportunity value = new FlipOpportunity(
                "opportunity", "minecraft:netherite_ingot", "Netherite Ingot", 4,
                14_200_000L, 20_800_000L, 6_600_000L, 46.0D, 87.0D,
                18, 19_900_000L, 22_100_000L, "High", "1s ago", "Seller", "NEW",
                List.of("fixture"), List.of()
        );
        assertTrue(controller.runDryRun(value).join().successful());
        assertEquals(0, controller.snapshot().execution().purchasesThisSession());

        controller.setMode(AutomationMode.AUTOMATIC_AUTHORIZED_SERVER).join();
        controller.setEnabled(true).join();
        assertTrue(controller.armCurrentServer(TradeAutomationCoordinator.ARM_CONFIRMATION));
    }
}
