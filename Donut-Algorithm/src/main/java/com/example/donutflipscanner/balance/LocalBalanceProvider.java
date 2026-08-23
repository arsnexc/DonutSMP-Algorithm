package com.example.donutflipscanner.balance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A balance provider that works without an API key.
 * Returns a simulated balance for offline/local mode.
 */
public final class LocalBalanceProvider implements BalanceProvider {
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("1000000");
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);

    private final Clock clock;
    private final BigDecimal fixedBalance;
    private volatile BalanceSnapshot current;
    private volatile Instant lastAttempt = Instant.EPOCH;

    public LocalBalanceProvider() {
        this(Clock.systemUTC(), DEFAULT_BALANCE);
    }

    public LocalBalanceProvider(Clock clock, BigDecimal fixedBalance) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fixedBalance = Objects.requireNonNull(fixedBalance, "fixedBalance");
        this.current = BalanceSnapshot.available(fixedBalance, clock.instant());
    }

    @Override
    public BalanceSnapshot snapshot() {
        Instant now = clock.instant();
        if (lastAttempt.equals(Instant.EPOCH)
                || !now.isBefore(lastAttempt.plus(REFRESH_INTERVAL))) {
            refresh();
        }
        return current;
    }

    @Override
    public CompletableFuture<BalanceSnapshot> refresh() {
        lastAttempt = clock.instant();
        // Simulate a balance that fluctuates slightly for realism
        Random random = ThreadLocalRandom.current();
        BigDecimal variation = BigDecimal.valueOf(random.nextLong(10000) - 5000);
        BigDecimal balance = fixedBalance.add(variation).max(BigDecimal.ZERO);
        current = BalanceSnapshot.available(balance, clock.instant());
        return CompletableFuture.completedFuture(current);
    }

    @Override
    public boolean supportsAutomatedPurchaseVerification() {
        return false;
    }
}
