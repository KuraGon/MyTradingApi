package com.saamp.trading.order;

import com.saamp.trading.account.AccountRepository;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.provider.ExecutionState;
import com.saamp.trading.provider.TradingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Resolves PMXConnect InProcess/timeout states without ever retransmitting the original order. */
@Service
public class PendingUnknownResolver {
    private static final Logger log = LoggerFactory.getLogger(PendingUnknownResolver.class);
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final TradingProvider provider;
    private final PricingService pricing;
    private final ExecutionEventHandler events;

    public PendingUnknownResolver(OrderRepository orders, AccountRepository accounts, TradingProvider provider,
                                  PricingService pricing, ExecutionEventHandler events) {
        this.orders = orders; this.accounts = accounts; this.provider = provider; this.pricing = pricing; this.events = events;
    }

    @Scheduled(fixedDelay = 1000)
    public void resolveDue() {
        for (TradingOrder candidate : orders.findPendingUnknownDue(50)) {
            // Defense supplementaire : aucune resolution provider d'un ancien ordre DEMO.
            if (candidate.tradingMode() == com.saamp.trading.domain.TradingMode.DEMO) continue;
            TradingOrder order = candidate;
            try {
                if (order.status() == com.saamp.trading.domain.OrderStatus.PENDING) {
                    orders.markPendingUnknown(order.id(), "SUBMISSION_STATE_UNKNOWN", "Recovery after interrupted provider submission");
                    order = orders.findById(order.id()).orElseThrow();
                }
                log.debug("Querying provider status for order {} / ClOrdId {}", order.id(), order.clOrdId());
                var report = provider.queryRequestStatus(order.clOrdId());
                if (report.isPresent()) {
                    log.info("Provider status {} received for order {} / ClOrdId {}",
                            report.get().state(), order.id(), order.clOrdId());
                    if (report.get().state() == ExecutionState.PROCESSED) {
                        var account = accounts.findById(order.accountId()).orElseThrow();
                        var quote = pricing.quoteForExecution(order.companyId(), order.asset(), account.baseCurrency());
                        events.handleFilled(order, account, report.get().executionId(), report.get().fillRate(), quote, "system:request-status");
                        continue;
                    }
                    if (report.get().state() == ExecutionState.FAILED) {
                        if ("631".equals(report.get().errorCode())) {
                            log.warn("Provider confirms unknown ClOrdId for order {} (error 631); rejecting without retransmission",
                                    order.id());
                        }
                        events.handleRejected(order, report.get().errorCode(), report.get().errorMessage());
                        continue;
                    }
                }
                scheduleAgain(order);
            } catch (RuntimeException e) {
                log.warn("Unable to resolve order {} / ClOrdId {} ({})",
                        order.id(), order.clOrdId(), e.getClass().getSimpleName());
                scheduleAgain(order);
            }
        }
    }

    private void scheduleAgain(TradingOrder order) {
        int attempts = order.resolutionAttempts() + 1;
        long delaySeconds = switch (attempts) {
            case 1 -> 5;
            case 2 -> 15;
            case 3 -> 30;
            default -> 60;
        };
        OffsetDateTime now = OffsetDateTime.now();
        boolean manualReview = order.unknownSince() != null && Duration.between(order.unknownSince(), now).compareTo(Duration.ofMinutes(15)) >= 0;
        orders.scheduleNextResolution(order.id(), attempts, now.plusSeconds(delaySeconds), manualReview);
        if (manualReview && order.manualReviewAt() == null) {
            log.error("Order {} remained PENDING_UNKNOWN for 15 minutes and requires back-office review", order.id());
        }
    }
}
