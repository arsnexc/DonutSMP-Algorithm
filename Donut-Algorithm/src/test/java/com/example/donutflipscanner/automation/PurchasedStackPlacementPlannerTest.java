package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.service.PurchasedStackPlacementPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchasedStackPlacementPlannerTest {
    private final PurchasedStackPlacementPlanner planner = new PurchasedStackPlacementPlanner();

    @Test
    void keepsAnExactHotbarStackSelectedWithoutMovingIt() {
        List<PurchasedStackPlacementPlanner.InventorySlotState> inventory = inventory();
        inventory.set(4, slot(4, 2, true));

        var plan = planner.plan(inventory, 2, 1);

        assertTrue(plan.accepted());
        assertFalse(plan.swapRequired());
        assertEquals(4, plan.targetHotbarSlot().orElseThrow());
    }

    @Test
    void movesUniqueMainInventoryStackToFirstEmptyHotbarSlot() {
        List<PurchasedStackPlacementPlanner.InventorySlotState> inventory = inventory();
        inventory.set(0, slot(0, 1, false));
        inventory.set(12, slot(12, 3, true));

        var plan = planner.plan(inventory, 3, 7);

        assertTrue(plan.accepted());
        assertTrue(plan.swapRequired());
        assertEquals(12, plan.sourceInventoryIndex().orElseThrow());
        assertEquals(1, plan.targetHotbarSlot().orElseThrow());
    }

    @Test
    void rejectsMissingAndAmbiguousPurchasedStacks() {
        assertFalse(planner.plan(inventory(), 1, 0).accepted());

        List<PurchasedStackPlacementPlanner.InventorySlotState> ambiguous = inventory();
        ambiguous.set(10, slot(10, 1, true));
        ambiguous.set(11, slot(11, 1, true));
        assertFalse(planner.plan(ambiguous, 1, 0).accepted());
    }

    private static List<PurchasedStackPlacementPlanner.InventorySlotState> inventory() {
        List<PurchasedStackPlacementPlanner.InventorySlotState> result = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            result.add(slot(index, 0, false));
        }
        return result;
    }

    private static PurchasedStackPlacementPlanner.InventorySlotState slot(
            int index, int count, boolean match
    ) {
        return new PurchasedStackPlacementPlanner.InventorySlotState(index, count, match);
    }
}
