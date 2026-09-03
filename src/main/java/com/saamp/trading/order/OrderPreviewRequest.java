package com.saamp.trading.order;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.domain.QuantityUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OrderPreviewRequest(
        @NotNull Asset asset,
        @NotNull OrderSide side,
        @NotNull @DecimalMin("0.000001") BigDecimal quantity,
        @NotNull QuantityUnit unit,
        @NotBlank @Size(max = 64) String idempotencyKey) {}
