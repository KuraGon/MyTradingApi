package com.saamp.trading.ledger;

import com.saamp.trading.account.DemoBalanceRepository;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Posts DEMO fills into their dedicated local projection, without any AS400 outbox interaction. */
@Service
public class DemoLedgerService {
    private final DemoBalanceRepository balances;
    private final DemoLedgerRepository ledger;

    public DemoLedgerService(DemoBalanceRepository balances, DemoLedgerRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
    }

    @Transactional
    public void postTrade(long accountId, Asset metal, BigDecimal metalDelta, Asset currency, BigDecimal cashDelta,
                          long orderId, String createdBy) {
        post(accountId, metal, metalDelta, orderId, createdBy);
        post(accountId, currency, cashDelta, orderId, createdBy);
    }

    private void post(long accountId, Asset asset, BigDecimal delta, long orderId, String createdBy) {
        if (delta == null || delta.signum() == 0) {
            throw new IllegalArgumentException("delta must be non-zero");
        }
        BigDecimal after = balances.lockQuantity(accountId, asset).add(delta)
                .setScale(6, java.math.RoundingMode.HALF_UP);
        balances.updateQuantity(accountId, asset, after);
        ledger.insert(accountId, asset, delta, LedgerEntryType.TRADE, orderId, after, createdBy);
    }
}
