package com.saamp.trading.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SnapshotTimingTest {
    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

    @Test void acquisitionDurationDoesNotMakeFreshCompletedSnapshotStale() {
        Instant completed = START.plusSeconds(2);
        assertThat(SnapshotTiming.captureWithin(START, completed, Duration.ofSeconds(5))).isTrue();
        assertThat(SnapshotTiming.fresh(completed, completed, Duration.ofSeconds(5))).isTrue();
    }

    @Test void completedSnapshotBecomesStaleOnlyAfterFreshnessAge() {
        Instant completed = START.plusSeconds(2);
        assertThat(SnapshotTiming.fresh(completed, completed.plusSeconds(4), Duration.ofSeconds(5))).isTrue();
        assertThat(SnapshotTiming.fresh(completed, completed.plusSeconds(5), Duration.ofSeconds(5))).isFalse();
    }

    @Test void captureThatExceedsAcquisitionBoundFailsClosed() {
        assertThat(SnapshotTiming.captureWithin(START, START.plusSeconds(6), Duration.ofSeconds(5))).isFalse();
    }
}
