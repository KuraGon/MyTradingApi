package com.saamp.trading.account;

import java.time.Duration;
import java.time.Instant;

/** Separate acquisition bound from freshness age of a completed snapshot. */
final class SnapshotTiming {
    private SnapshotTiming() { }
    static boolean captureWithin(Instant startedAt, Instant completedAt, Duration maxCaptureDuration) {
        return !completedAt.isBefore(startedAt) && !completedAt.isAfter(startedAt.plus(maxCaptureDuration));
    }
    static boolean fresh(Instant completedAt, Instant now, Duration maxSnapshotAge) {
        return !now.isBefore(completedAt) && now.isBefore(completedAt.plus(maxSnapshotAge));
    }
}
