package com.example.donutflipscanner.market.scanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiMarketScanWorkPageStrategyTest {
    @Test
    void interleavesNewestTransactionsWithHistoricalPages() {
        List<Integer> firstCycle = LongStream.range(0, 20)
                .mapToObj(ApiMarketScanWork::completedTransactionPage)
                .toList();

        assertEquals(List.of(
                1, 2, 1, 3, 1, 4, 1, 5, 1, 6,
                1, 7, 1, 8, 1, 9, 1, 10, 1, 2
        ), firstCycle);
    }
}
