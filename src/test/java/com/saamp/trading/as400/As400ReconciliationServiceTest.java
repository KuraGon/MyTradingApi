package com.saamp.trading.as400;
import com.saamp.trading.account.*; import com.saamp.trading.domain.*;
import org.junit.jupiter.api.Test; import java.math.*; import java.time.OffsetDateTime; import java.util.*;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.Mockito.*;

class As400ReconciliationServiceTest {
 @Test void usesHalfCentigramAsMaximumRoundingTolerance(){
  BalanceRepository balances=mock(BalanceRepository.class); As400AccountReader reader=mock(As400AccountReader.class);
  BigDecimal postgres=BigDecimal.ONE; when(balances.find(5L,Asset.XAU)).thenReturn(Optional.of(new Balance(5L,Asset.XAU,postgres,OffsetDateTime.now())));
  BigDecimal within=new BigDecimal("0.005").divide(JdbcAs400AccountReader.GRAMS_PER_TROY_OUNCE,18,RoundingMode.HALF_UP);
  when(reader.readMetalBalances("B",20662)).thenReturn(Map.of(Asset.XAU,postgres.subtract(within)));
  TradingAccount account=new TradingAccount(5L,42L,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",17492,20662,1,OffsetDateTime.now(),OffsetDateTime.now());
  As400ReconciliationResult result=new As400ReconciliationService(balances,reader).reconcileMetals(account).stream().filter(r->r.asset()==Asset.XAU).findFirst().orElseThrow();
  assertThat(result.synchronizedBalance()).isTrue();

  BigDecimal above=new BigDecimal("0.006").divide(JdbcAs400AccountReader.GRAMS_PER_TROY_OUNCE,18,RoundingMode.HALF_UP);
  when(reader.readMetalBalances("B",20662)).thenReturn(Map.of(Asset.XAU,postgres.subtract(above)));
  result=new As400ReconciliationService(balances,reader).reconcileMetals(account).stream().filter(r->r.asset()==Asset.XAU).findFirst().orElseThrow();
  assertThat(result.synchronizedBalance()).isFalse();
 }
}
