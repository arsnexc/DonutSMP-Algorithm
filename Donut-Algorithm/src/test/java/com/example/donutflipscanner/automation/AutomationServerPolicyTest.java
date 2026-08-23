package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.service.AutomationServerPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationServerPolicyTest {
    @Test
    void blocksOfficialDonutSmpHostsIncludingSubdomainsAndPorts() {
        assertFalse(AutomationServerPolicy.permitsGameplayAutomation("donutsmp.net"));
        assertFalse(AutomationServerPolicy.permitsGameplayAutomation("play.donutsmp.net:25565"));
        assertFalse(AutomationServerPolicy.permitsGameplayAutomation("API.DONUTSMP.NET."));
    }

    @Test
    void leavesPrivateServerAuthorizationToTheExistingFailClosedGates() {
        assertTrue(AutomationServerPolicy.permitsGameplayAutomation("private.example:25565"));
        assertTrue(AutomationServerPolicy.permitsGameplayAutomation("localhost"));
    }
}
