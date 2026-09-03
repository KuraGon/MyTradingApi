package com.saamp.trading.statement;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;

/** Ligne historique client dérivée directement d'une écriture immuable du ledger. */
public record StatementLine(long id, Asset asset, BigDecimal delta, LedgerEntryType entryType,
                            Long orderId, BigDecimal balanceAfter, Instant createdAt) {}
