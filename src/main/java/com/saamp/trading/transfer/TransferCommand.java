package com.saamp.trading.transfer;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.TransferDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferCommand(
        long accountId,
        @NotNull Asset asset,
        @NotNull @DecimalMin(value = "0.000001") BigDecimal quantity,
        @NotNull TransferDirection direction,
        @NotBlank @Size(max = 64) String externalRef,
        BigDecimal acquisitionPrice) {}
