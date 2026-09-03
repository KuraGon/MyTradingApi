package com.saamp.trading.ledger;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record LedgerEntry(long id, long accountId, Asset asset, BigDecimal delta, LedgerEntryType entryType,
                          Long orderId, String transferRef, BigDecimal balanceAfter, OffsetDateTime createdAt,
                          String createdBy) {}
