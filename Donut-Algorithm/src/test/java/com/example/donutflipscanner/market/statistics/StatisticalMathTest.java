package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.market.statistics.model.ComparableSale;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticalMathTest {
    @Test
    void calculatesOddAndEvenMedians() {
        assertDecimal("20", StatisticalMath.median(decimals("30", "10", "20")));
        assertDecimal("25", StatisticalMath.median(decimals("10", "20", "30", "40")));
    }

    @Test
    void interpolatesPercentilesAndDerivesIqr() {
        List<BigDecimal> values = decimals("10", "20", "30", "40");

        BigDecimal p25 = StatisticalMath.percentile(values, new BigDecimal("0.25"));
        BigDecimal p40 = StatisticalMath.percentile(values, new BigDecimal("0.40"));
        BigDecimal p75 = StatisticalMath.percentile(values, new BigDecimal("0.75"));

        assertDecimal("17.5", p25);
        assertDecimal("22", p40);
        assertDecimal("32.5", p75);
        assertDecimal("15", p75.subtract(p25));
    }

    @Test
    void calculatesMedianAbsoluteDeviation() {
        assertDecimal("1", StatisticalMath.medianAbsoluteDeviation(
                decimals("1", "2", "2", "2", "3", "4", "9")
        ));
    }

    @Test
    void weightedMedianFavorsRecentHighWeightSale() {
        ComparableSale older = comparable("old", "100", "0.20");
        ComparableSale recent = comparable("recent", "200", "1.00");

        assertDecimal("200", StatisticalMath.weightedMedian(List.of(older, recent)));
    }

    @Test
    void equalWeightTwoValueMedianUsesMidpoint() {
        assertDecimal("150", StatisticalMath.weightedMedian(List.of(
                comparable("first", "100", "1"),
                comparable("second", "200", "1")
        )));
    }

    private static ComparableSale comparable(String key, String price, String weight) {
        return new ComparableSale(
                key, new BigDecimal(price), 1, Optional.empty(), Optional.empty(),
                Instant.EPOCH, new BigDecimal(weight)
        );
    }

    private static List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
