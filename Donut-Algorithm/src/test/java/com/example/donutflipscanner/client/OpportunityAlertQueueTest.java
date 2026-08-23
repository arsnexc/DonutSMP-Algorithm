package com.example.donutflipscanner.client;

import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.gui.hud.OpportunityAlertQueue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityAlertQueueTest {
    @Test
    void boundsQueueSuppressesDuplicatesAndExpiresAlerts() {
        OpportunityAlertQueue queue = new OpportunityAlertQueue(
                Duration.ofSeconds(5), Duration.ofSeconds(30)
        );
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        FlipOpportunity first = opportunity("first");
        assertTrue(queue.offer(first, now));
        assertFalse(queue.offer(first, now.plusSeconds(1)));
        for (int index = 0; index < 20; index++) {
            queue.offer(opportunity("id-" + index), now.plusSeconds(1));
        }
        assertEquals(OpportunityAlertQueue.MAXIMUM_QUEUED_ALERTS, queue.size(now.plusSeconds(1)));
        queue.clear();
        assertEquals(0, queue.size(now.plusSeconds(1)));
        assertTrue(queue.offer(first, now.plusSeconds(2)));
        assertEquals(0, queue.size(now.plusSeconds(7)));
    }

    private static FlipOpportunity opportunity(String id) {
        return new FlipOpportunity(id, "minecraft:netherite_ingot", "Netherite Ingot", 1, 50, 40);
    }
}
