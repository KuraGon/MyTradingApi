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
                           OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime executedAt,
                           TradingMode tradingMode, com.saamp.trading.pricing.SpreadType spreadType,
                           String spreadQuoteCurrency, String spreadPriceUnit, int spreadQuoteScale) {
    /** Compatibilite interne historique : aucune conversion de ratio. */
    public TradingOrder(long id, long accountId, long companyId, Long batchId, Asset asset, String pair,
                           OrderSide side, OrderType orderType, BigDecimal requestedQuantity, QuantityUnit requestedUnit,
                           BigDecimal quantityOz, OrderStatus status, BigDecimal indicativePrice,
                           BigDecimal indicativeClientPriceRaw, BigDecimal indicativeClientPrice, BigDecimal marketPrice,
                           BigDecimal clientPriceRaw, BigDecimal clientPrice,
                           BigDecimal spreadApplied, Integer spreadConfigVersion, BigDecimal saampRevenue,
                           BigDecimal grossAmount, String idempotencyKey, String clOrdId, String stonexExid,
                           String stonexErrorCode, String stonexErrorMessage, int resolutionAttempts,
                           OffsetDateTime unknownSince, OffsetDateTime nextResolutionAt, OffsetDateTime manualReviewAt,
                           OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime executedAt,
                           TradingMode tradingMode) {
        this(id, accountId, companyId, batchId, asset, pair, side, orderType, requestedQuantity, requestedUnit, quantityOz, status, indicativePrice, indicativeClientPriceRaw, indicativeClientPrice, marketPrice, clientPriceRaw, clientPrice, spreadApplied, spreadConfigVersion, saampRevenue, grossAmount, idempotencyKey, clOrdId, stonexExid, stonexErrorCode, stonexErrorMessage, resolutionAttempts, unknownSince, nextResolutionAt, manualReviewAt, createdAt, submittedAt, executedAt, tradingMode, com.saamp.trading.pricing.SpreadType.PERCENTAGE,
                pair == null ? null : pair.substring(pair.length()-3),"OZ",6);
    }
    /** @param baseCurrency devise du compte @return spread persistant de l'ordre
     * @throws IllegalStateException si l'identite monetaire n'est pas coherente */
    public com.saamp.trading.pricing.SpreadValue spreadSnapshot(Asset baseCurrency) {
        if (baseCurrency == null || !com.saamp.trading.common.TradingPair.metalAgainst(asset,baseCurrency).equals(pair)
                || !baseCurrency.name().equals(spreadQuoteCurrency) || spreadConfigVersion == null
                || spreadQuoteScale < 0 || spreadQuoteScale > 6)
            throw new IllegalStateException("ORDER_SPREAD_SNAPSHOT_INVALID");
        return new com.saamp.trading.pricing.SpreadValue(spreadType,spreadApplied,spreadPriceUnit);
    }
}
