package com.saamp.trading.order;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {
    private static final TradingAccount ACCOUNT = new TradingAccount(10L, 42L, Asset.EUR, AccountStatus.ACTIVE,
            BigDecimal.TEN, BigDecimal.TEN, null, 1, OffsetDateTime.now(), OffsetDateTime.now());

    @Mock OrderRepository orders;
    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderQueryService(orders);
    }

    @Test
    void firstPageUsesDefaultLimitPlusOneAndReturnsExclusiveCursor() {
        var persistedOrders = descendingOrders(105L, 5);
        when(orders.findPage(10L, null, 101)).thenReturn(persistedOrders);

        var page = service.page(ACCOUNT, null, null);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(105L, 104L, 103L, 102L, 101L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void nextPagePreservesIdDescendingOrderAndUsesExclusiveCursor() {
        var persistedOrders = descendingOrders(100L, 4);
        when(orders.findPage(10L, 101L, 4)).thenReturn(persistedOrders);

        var page = service.page(ACCOUNT, 101L, 3);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(100L, 99L, 98L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(98L);
        verify(orders).findPage(10L, 101L, 4);
    }

    @Test
    void limitIsCappedAtFiveHundredBeforeLookAhead() {
        when(orders.findPage(10L, null, 501)).thenReturn(List.of());

        service.page(ACCOUNT, null, 10_000);

        verify(orders).findPage(10L, null, 501);
    }

    @Test
    void detailReadsOnlyTheResolvedAccount() {
        TradingOrder order = order(77L);
        when(orders.findByIdAndAccountId(77L, 10L)).thenReturn(Optional.of(order));

        assertThat(service.detail(ACCOUNT, 77L).id()).isEqualTo(77L);
        verify(orders).findByIdAndAccountId(77L, 10L);
    }

    @Test
    void absentOrOtherAccountOrderUsesTheSameNotFoundError() {
        when(orders.findByIdAndAccountId(77L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(ACCOUNT, 77L))
                .isInstanceOfSatisfying(TradingException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                    assertThat(exception.getCode()).isEqualTo("ORDER_NOT_FOUND");
                });
    }

    private List<TradingOrder> descendingOrders(long firstId, int count) {
        return java.util.stream.LongStream.range(0, count).mapToObj(index -> order(firstId - index)).toList();
    }

    private TradingOrder order(long id) {
        TradingOrder order = mock(TradingOrder.class);
        lenient().when(order.id()).thenReturn(id);
        lenient().when(order.asset()).thenReturn(Asset.XAU);
        lenient().when(order.status()).thenReturn(OrderStatus.PENDING_UNKNOWN);
        return order;
    }
}
