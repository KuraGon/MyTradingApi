package com.saamp.trading.account;

import com.saamp.trading.domain.TradingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Starts the existing asynchronous official probes after a process restart. */
@Component
public final class OfficialBalanceAvailabilityWarmup {
    private static final Logger log = LoggerFactory.getLogger(OfficialBalanceAvailabilityWarmup.class);
    private final AccountRepository accounts;
    private final EffectiveBalanceService balances;

    public OfficialBalanceAvailabilityWarmup(AccountRepository accounts, EffectiveBalanceService balances) {
        this.accounts = accounts;
        this.balances = balances;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        try {
            for (var account : accounts.findActiveLiveMapped()) {
                if (account.accountMode() == TradingMode.LIVE) {
                    balances.officialAvailable(account);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("OFFICIAL_BALANCE_WARMUP_FAILED");
        }
    }
}
