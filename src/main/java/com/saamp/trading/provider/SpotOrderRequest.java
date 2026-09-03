package com.saamp.trading.provider;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
public record SpotOrderRequest(String clientOrderId, String pair, OrderSide side, BigDecimal quantityOz) {}
