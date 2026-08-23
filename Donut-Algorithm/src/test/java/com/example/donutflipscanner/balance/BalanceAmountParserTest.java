package com.example.donutflipscanner.balance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BalanceAmountParserTest {
    @Test
    void parsesExactAndCompactMoneyFormatsWithoutFloatingPointLoss() {
        assertEquals(new BigDecimal("1234567"), BalanceAmountParser.parse("$1,234,567"));
        assertEquals(new BigDecimal("1250000000"), BalanceAmountParser.parse("1.25B"));
        assertEquals(new BigDecimal("66500"), BalanceAmountParser.parse(" £66.5K "));
    }

    @Test
    void rejectsAmbiguousNegativeAndExponentialValues() {
        assertThrows(IllegalArgumentException.class, () -> BalanceAmountParser.parse("Balance: $12M"));
        assertThrows(IllegalArgumentException.class, () -> BalanceAmountParser.parse("-$12"));
        assertThrows(IllegalArgumentException.class, () -> BalanceAmountParser.parse("1e9"));
        assertThrows(IllegalArgumentException.class, () -> BalanceAmountParser.parse("$12M $13M"));
    }
}
