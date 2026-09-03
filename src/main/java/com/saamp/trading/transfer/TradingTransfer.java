package com.saamp.trading.transfer;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.TransferDirection;
import com.saamp.trading.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradingTransfer(long id, long accountId, Asset asset, BigDecimal quantity,
                              TransferDirection direction, String externalRef, BigDecimal acquisitionPrice,
                              TransferStatus status, OffsetDateTime receivedAt, OffsetDateTime appliedAt,
                              String rejectedReason) {}
