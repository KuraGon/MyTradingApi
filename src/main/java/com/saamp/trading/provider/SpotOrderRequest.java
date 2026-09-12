package com.saamp.trading.provider;
import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.domain.TradingMode;
import java.math.BigDecimal;
public record SpotOrderRequest(String clientOrderId, String pair, OrderSide side, BigDecimal quantityOz,
                               TradingMode tradingMode) {
    public SpotOrderRequest {
        tradingMode = tradingMode == null ? TradingMode.LIVE : tradingMode;
    }
    public SpotOrderRequest(String clientOrderId, String pair, OrderSide side, BigDecimal quantityOz) {
        this(clientOrderId, pair, side, quantityOz, TradingMode.LIVE);
    }
}
