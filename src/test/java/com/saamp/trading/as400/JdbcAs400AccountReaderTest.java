package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.math.BigDecimal;
import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAs400AccountReaderTest {
 @Test void mapsTrimmedCircuitsConvertsGramsAndKeepsNegativeBalances() throws Exception {
  JdbcOperations jdbc=mock(JdbcOperations.class); ResultSet rs=mock(ResultSet.class);
  when(rs.getString("CIRCUI")).thenReturn("O  ","A  ","P  ","D  ");
  when(rs.getBigDecimal("SOLD03")).thenReturn(new BigDecimal("31.1034768"),new BigDecimal("-31.1034768"),BigDecimal.ZERO,new BigDecimal("62.2069536"));
  doAnswer(inv->{RowCallbackHandler h=inv.getArgument(1);for(int i=0;i<4;i++)h.processRow(rs);return null;})
    .when(jdbc).query(anyString(),any(RowCallbackHandler.class),any(),any());
  var balances=new JdbcAs400AccountReader(jdbc).readMetalBalances("B",20662);
  assertThat(balances).containsEntry(Asset.XAU,new BigDecimal("1.000000")).containsEntry(Asset.XAG,new BigDecimal("-1.000000"))
    .containsEntry(Asset.XPT,new BigDecimal("0.000000")).containsEntry(Asset.XPD,new BigDecimal("2.000000"));
  verify(jdbc).query(contains("NULIV=0"),any(RowCallbackHandler.class),eq("B"),eq(20662));
 }

 @Test void currencyReadUsesValidatedAccountingFiltersAndParameters() {
  JdbcOperations jdbc=mock(JdbcOperations.class);
  when(jdbc.queryForObject(anyString(),eq(BigDecimal.class),any(),any())).thenReturn(new BigDecimal("125.50"));

  BigDecimal result=new JdbcAs400AccountReader(jdbc).readTradingCurrencyBalance("B",20662);

  assertThat(result).isEqualByComparingTo("125.50");
  verify(jdbc).queryForObject(argThat(sql -> sql.contains("L1NCG IN (401600, 411600)")
          && sql.contains("L1ETA = 1") && sql.contains("L1TYP = 1")
          && sql.contains("WHEN l.L1SNS = 'D' THEN l.L1MTT") && sql.contains("ELSE -l.L1MTT")),
          eq(BigDecimal.class),eq("B"),eq(20662));
 }

 @Test void currencyReadPreservesSignedResultFromDebitCreditExpression() {
  JdbcOperations jdbc=mock(JdbcOperations.class);
  when(jdbc.queryForObject(anyString(),eq(BigDecimal.class),any(),any())).thenReturn(new BigDecimal("-42.75"));
  assertThat(new JdbcAs400AccountReader(jdbc).readTradingCurrencyBalance("I",17492)).isEqualByComparingTo("-42.75");
 }

 @Test void currencyReadMapsNullAggregateToZero() {
  JdbcOperations jdbc=mock(JdbcOperations.class);
  when(jdbc.queryForObject(anyString(),eq(BigDecimal.class),any(),any())).thenReturn(null);
  assertThat(new JdbcAs400AccountReader(jdbc).readTradingCurrencyBalance("B",20662)).isEqualByComparingTo(BigDecimal.ZERO);
 }
}
