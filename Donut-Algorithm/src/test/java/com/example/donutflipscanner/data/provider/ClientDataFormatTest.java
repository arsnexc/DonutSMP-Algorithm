package com.example.donutflipscanner.data.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientDataFormatTest {
    @Test
    void roundsFractionalCurrencyInsteadOfTreatingItAsOverflow() {
        assertEquals(37_250L, ClientDataFormat.saturatedLong(new BigDecimal("37250.49")));
        assertEquals(37_251L, ClientDataFormat.saturatedLong(new BigDecimal("37250.50")));
        assertEquals(-13L, ClientDataFormat.saturatedLong(new BigDecimal("-12.50")));
    }

    @Test
    void onlySaturatesValuesOutsideTheLongRange() {
        assertEquals(Long.MAX_VALUE, ClientDataFormat.saturatedLong(
                new BigDecimal(Long.MAX_VALUE).add(BigDecimal.ONE)
        ));
        assertEquals(Long.MIN_VALUE, ClientDataFormat.saturatedLong(
                new BigDecimal(Long.MIN_VALUE).subtract(BigDecimal.ONE)
        ));
    }
}
