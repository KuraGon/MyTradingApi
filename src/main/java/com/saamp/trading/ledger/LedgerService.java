package com.saamp.trading.ledger;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import com.saamp.trading.common.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Only supported mutation path for trading balances.
 * Every balance change and the matching append-only ledger entry are committed atomically.
 */
@Service
public class LedgerService {
    private final BalanceRepository balances;
    private final LedgerRepository ledger;

    public LedgerService(BalanceRepository balances, LedgerRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
    }

    @Transactional
    public BigDecimal post(long accountId, Asset asset, BigDecimal delta, LedgerEntryType type,
                           Long orderId, String transferRef, String createdBy) {
        if (delta == null || delta.signum() == 0) throw new IllegalArgumentException("delta must be non-zero");
        BigDecimal current = balances.lockQuantity(accountId, asset);
        BigDecimal after = current.add(delta).setScale(6, java.math.RoundingMode.HALF_UP);
        if (asset.isCurrency() && after.signum() < 0) {
            throw new TradingException(HttpStatus.CONFLICT, "NEGATIVE_CURRENCY_BALANCE", "Solde devise négatif interdit");
        }
        balances.updateQuantity(accountId, asset, after);
        ledger.insert(accountId, asset, delta, type, orderId, transferRef, after, createdBy);
        return after;
    }

    /** Posts both legs of a trade in one database transaction. */
    @Transactional
    public void postTrade(long accountId, Asset metal, BigDecimal metalDelta, Asset currency, BigDecimal cashDelta,
                          long orderId, String createdBy) {
        post(accountId, metal, metalDelta, LedgerEntryType.TRADE, orderId, null, createdBy);
        post(accountId, currency, cashDelta, LedgerEntryType.TRADE, orderId, null, createdBy);
    }
}
