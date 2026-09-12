package com.saamp.trading.account;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Un seul diagnostic en vol, sans file ni conservation de soldes entre operations. */
final class ShadowBalanceDiagnostics implements AutoCloseable {
    private final ExecutorService executor;
    private final LongSupplier nanoTime;
    private final long retryDelay;
    private boolean running;
    private boolean closed;
    private boolean coolingDown;
    private long failedAt;

    ShadowBalanceDiagnostics() {
        this(Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "effective-balance-shadow");
            thread.setDaemon(true);
            return thread;
        }), System::nanoTime, Duration.ofSeconds(30));
    }

    ShadowBalanceDiagnostics(ExecutorService executor, LongSupplier nanoTime, Duration retryDelay) {
        this.executor = executor;
        this.nanoTime = nanoTime;
        this.retryDelay = retryDelay.toNanos();
    }

    synchronized String submit(BooleanSupplier diagnostic) {
        if (closed) return "DIAGNOSTIC_STOPPED";
        if (running) return "DIAGNOSTIC_BUSY";
        if (coolingDown && nanoTime.getAsLong() - failedAt < retryDelay) return "CIRCUIT_OPEN";
        running = true;
        try {
            executor.execute(() -> {
                boolean available = false;
                try { available = diagnostic.getAsBoolean(); }
                catch (RuntimeException ignored) { /* Aucune exception diagnostique vers le client ou les logs bruts. */ }
                finally {
                    synchronized (this) {
                        coolingDown = !available;
                        if (!available) failedAt = nanoTime.getAsLong();
                        running = false;
                    }
                }
            });
            return "DIAGNOSTIC_PENDING";
        } catch (RejectedExecutionException rejected) {
            running = false;
            return "DIAGNOSTIC_STOPPED";
        }
    }

    /** Arrete uniquement le diagnostic local, sans attendre une connexion bloquee. */
    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
    }
}
