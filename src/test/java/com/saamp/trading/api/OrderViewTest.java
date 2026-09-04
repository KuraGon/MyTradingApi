package com.saamp.trading.api;

import com.saamp.trading.domain.OrderStatus;
import com.saamp.trading.order.TradingOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderViewTest {

    @Test
    void rejectedPriceMovedHasStableClientReason() {
        TradingOrder order = mock(TradingOrder.class);
        when(order.status()).thenReturn(OrderStatus.REJECTED);
        when(order.stonexErrorCode()).thenReturn("PRICE_MOVED");

        assertThat(OrderView.from(order).reasonCode()).isEqualTo("PRICE_MOVED");
    }

    @Test
    void providerDetailsCollapseToSafeReasonAndStayAbsentForNonRejectedOrder() {
        TradingOrder rejected = mock(TradingOrder.class);
        when(rejected.status()).thenReturn(OrderStatus.REJECTED);
        when(rejected.stonexErrorCode()).thenReturn("631-sensitive-provider-code");
        TradingOrder pending = mock(TradingOrder.class);
        when(pending.status()).thenReturn(OrderStatus.PENDING_UNKNOWN);

        assertThat(OrderView.from(rejected).reasonCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(OrderView.from(pending).reasonCode()).isNull();
    }
}
