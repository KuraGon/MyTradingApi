package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingRepositoryGlobalDefaultTest {
  @Test void globalDefaultDoesNotDependOnAccountOrSte() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
      .thenAnswer(inv -> ((String)inv.getArgument(0)).contains("trading_spread_default") ?
        List.of(new SpreadConfig(1, 99, Asset.XAG, new java.math.BigDecimal("0.10"), new java.math.BigDecimal("0.10"), 1, OffsetDateTime.now(), null, SpreadType.ABSOLUTE, "OZ")) : List.of());
    var result = new PricingRepository(jdbc).findCurrentSpread(99, Asset.XAG, OffsetDateTime.now());
    assertThat(result).isPresent().get().extracting(SpreadConfig::spreadBuy, SpreadConfig::spreadSell, SpreadConfig::spreadType)
      .containsExactly(new java.math.BigDecimal("0.10"), new java.math.BigDecimal("0.10"), SpreadType.ABSOLUTE);
    verify(jdbc, never()).query(contains("trading_account"), any(RowMapper.class), any(Object[].class));
  }
}
