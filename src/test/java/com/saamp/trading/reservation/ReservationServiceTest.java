package com.saamp.trading.reservation;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReservationServiceTest {

    @Test
    void insufficientBalanceNeverCreatesReservationRow() {
        BalanceRepository balances = mock(BalanceRepository.class);
        ReservationRepository repository = mock(ReservationRepository.class);
        TradingProperties properties = new TradingProperties();
        properties.getReservations().setTtl(Duration.ofMinutes(2));
        ReservationService service = new ReservationService(balances, repository, properties, new com.saamp.trading.account.EffectiveBalanceService(balances,null,null,null,new com.saamp.trading.account.EffectiveBalanceProperties()));

        when(balances.lockQuantity(1L, Asset.EUR)).thenReturn(new BigDecimal("100.000000"));
        when(repository.activeReserved(1L, Asset.EUR)).thenReturn(new BigDecimal("20.000000"));

        assertThatThrownBy(() -> service.reserveCash(1L, Asset.EUR, new BigDecimal("80.000001"), 10L))
                .isInstanceOfSatisfying(TradingException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE"));

        verify(repository, never()).upsert(anyLong(), any(), any(), anyLong(), any(), any());
        assertThat(new BigDecimal("100.000000").subtract(new BigDecimal("20.000000")))
                .isEqualByComparingTo("80.000000");
    }
}
