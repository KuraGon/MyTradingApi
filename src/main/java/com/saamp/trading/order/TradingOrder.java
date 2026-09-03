package com.saamp.trading.order;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record TradingOrder(long id, long accountId, long companyId, Long batchId, Asset asset, String pair,
                           OrderSide side, OrderType orderType, BigDecimal requestedQuantity, QuantityUnit requestedUnit,
                           BigDecimal quantityOz, OrderStatus status, BigDecimal indicativePrice,
                           BigDecimal indicativeClientPriceRaw, BigDecimal indicativeClientPrice, BigDecimal marketPrice,
                           BigDecimal clientPriceRaw, BigDecimal clientPrice,
                           BigDecimal spreadApplied, Integer spreadConfigVersion, BigDecimal saampRevenue,
                           BigDecimal grossAmount, String idempotencyKey, String clOrdId, String stonexExid,
                           String stonexErrorCode, String stonexErrorMessage, int resolutionAttempts,
                           OffsetDateTime unknownSince, OffsetDateTime nextResolutionAt, OffsetDateTime manualReviewAt,
                           OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime executedAt) {}
