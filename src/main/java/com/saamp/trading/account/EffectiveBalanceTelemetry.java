package com.saamp.trading.account;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Metriques sans cardinalite compte ; logs echantillonnes sans montant ni exception brute. */
final class EffectiveBalanceTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(EffectiveBalanceTelemetry.class);
    private final MeterRegistry metrics;
    private final LinkedHashMap<Long, LastLog> lastLogs = new LinkedHashMap<>();
    private record LastLog(boolean available, String reason, long at) { }

    EffectiveBalanceTelemetry(MeterRegistry metrics) { this.metrics = metrics; }

    void record(long accountId, EffectiveBalanceProperties.Mode mode, boolean available,
                long captureNanos, long officialNanos, int facts, String reason) {
        try { recordSafely(accountId, mode, available, captureNanos, officialNanos, facts, reason); }
        catch (RuntimeException ignored) { /* L'observabilite ne modifie jamais une decision. */ }
    }

    private void recordSafely(long accountId, EffectiveBalanceProperties.Mode mode, boolean available,
                              long captureNanos, long officialNanos, int facts, String reason) {
        var tags = new String[]{"mode", mode.name(), "available", Boolean.toString(available), "reason", reason};
        metrics.timer("trading.effective.balance.capture", tags).record(captureNanos, TimeUnit.NANOSECONDS);
        metrics.timer("trading.effective.balance.official", tags).record(officialNanos, TimeUnit.NANOSECONDS);
        metrics.summary("trading.effective.balance.overlay.facts", "mode", mode.name()).record(facts);
        long now = System.nanoTime();
        synchronized (lastLogs) {
            var last = lastLogs.get(accountId);
            if (last != null && last.available == available && last.reason.equals(reason)
                    && now - last.at < Duration.ofMinutes(1).toNanos()) return;
            if (lastLogs.size() >= 256 && !lastLogs.containsKey(accountId)) lastLogs.remove(lastLogs.keySet().iterator().next());
            lastLogs.put(accountId, new LastLog(available, reason, now));
        }
        LOG.info("Effective balance {} accountId={} available={} captureMs={} officialMs={} overlayFacts={} reason={}",
                mode, accountId, available, TimeUnit.NANOSECONDS.toMillis(captureNanos),
                TimeUnit.NANOSECONDS.toMillis(officialNanos), facts, reason);
    }

    void skipped(String reason) {
        try { metrics.counter("trading.effective.balance.shadow.skipped", "reason", reason).increment(); }
        catch (RuntimeException ignored) { /* Diagnostic uniquement. */ }
    }
}
