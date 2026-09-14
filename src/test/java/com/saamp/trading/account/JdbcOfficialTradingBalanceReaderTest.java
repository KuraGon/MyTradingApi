package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdbcOfficialTradingBalanceReaderTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    TradingAccount account=new TradingAccount(1,1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",17492,20662,1,OffsetDateTime.now(),OffsetDateTime.now());
    List<Map<String,Object>> identities=new ArrayList<>(),metals=new ArrayList<>(),cash=new ArrayList<>();
    @BeforeEach void setup() {
        identities.add(identity("B",20662,0,400));
        doAnswer(call->{
            RowMapper<Boolean> mapper=call.getArgument(1);
            var values=new ArrayList<Boolean>();
            for(var row:identities) {
                var rs=mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(a->row.get(a.getArgument(0)));
                when(rs.getBigDecimal(anyString())).thenAnswer(a->row.get(a.getArgument(0)));
                values.add(mapper.mapRow(rs,values.size()));
                verify(rs,never()).getInt("NREPCO");
                verify(rs,never()).getBigDecimal("NREPCO");
            }
            return values;
        }).when(jdbc).query(startsWith("SELECT STE,NUCLI,NULIV"),org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any(),any(Object[].class));
        doAnswer(call->{
            String sql=call.getArgument(0);
            RowCallbackHandler callback=call.getArgument(1);
            for(var row:sql.contains("CLCPDP03")?metals:cash) {
                var rs=mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(a->row.get(a.getArgument(0)));
                when(rs.getBigDecimal(anyString())).thenAnswer(a->row.get(a.getArgument(0)));
                callback.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(),any(RowCallbackHandler.class),any(Object[].class));
    }
    Map<String,Object> identity(String ste,int nucli,int nuliv,int nrepco) {
        return Map.of("STE",ste,"NUCLI",BigDecimal.valueOf(nucli),"NULIV",BigDecimal.valueOf(nuliv),"NREPCO",BigDecimal.valueOf(nrepco));
    }
    OfficialTradingBalanceReader.Reading read() { return JdbcOfficialTradingBalanceReader.query(jdbc,account,List.of()); }
    @Test void existingTradingAccountWithoutRowsHasFiveZeros() {
        assertThat(read().balances()).hasSize(5).allSatisfy((a,v)->assertThat(v).isZero());
        verify(jdbc).query(contains("L1NCA=?"),any(RowCallbackHandler.class),eq(20662));
        verify(jdbc).query(contains("CLCPDP03"),any(RowCallbackHandler.class),eq("B"),eq(20662));
    }
    @Test void absentIdentityIsUnavailable() {
        identities.clear();
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void duplicateIdentityIsUnavailable() {
        identities.addAll(List.copyOf(identities));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void duplicateMetalIsNotSilentlyOverwritten() {
        metals.add(Map.of("CIRCUI","A","SOLD03",new BigDecimal("0.01")));metals.addAll(List.copyOf(metals));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void gramsConversionPreservesMicroQuantity() {
        metals.add(Map.of("CIRCUI","A  ","SOLD03",new BigDecimal("0.01")));
        assertThat(read().balances().get(Asset.XAG)).isEqualByComparingTo("0.000322");
    }
    @Test void euroUsesCreditMinusDebitOnTradingAccount() {
        cash.add(Map.of("L1SNS","C","L1MTT",new BigDecimal("100")));
        cash.add(Map.of("L1SNS","D","L1MTT",new BigDecimal("10")));
        assertThat(read().balances().get(Asset.EUR)).isEqualByComparingTo("90");
        verify(jdbc).query(eq("SELECT L1SNS,L1MTT FROM FMPRO.PCGMLFCM WHERE L1SOC='LFM' AND L1NCA=? AND L1LET='  '"),any(RowCallbackHandler.class),eq(20662));
    }
    @Test void unknownCurrencySenseIsUnavailable() {
        cash.add(Map.of("L1SNS","?","L1MTT",BigDecimal.ONE));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void jdbcTimeoutIsNeverAnEmptyBalance() {
        doThrow(new org.springframework.dao.QueryTimeoutException("synthetic")).when(jdbc).query(
                startsWith("SELECT STE,NUCLI,NULIV"),org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any(),eq("B"),eq(20662));
        assertThatThrownBy(this::read).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }
    @ParameterizedTest @ValueSource(strings={"O"," O ","0"," ","N"})
    void onlyExactTrimmedLetterORemovesClientAdjustment(String value) {
        var fact=new PendingTradingAdjustmentRepository.Adjustment(22,Asset.XAG,Asset.EUR,OrderSide.BUY,new BigDecimal("0.000322"),new BigDecimal("0.01"),13L,1L,"B",20662,1,null,"SIM-TTnhKlkhlfxyQZfE");
        when(jdbc.queryForList(startsWith("SELECT SIPROV"),eq(Integer.class),eq("B"),eq(1),eq(20662),eq(fact.exid()))).thenReturn(List.of(905627));
        when(jdbc.queryForList(startsWith("SELECT ETPRO1"),eq(String.class),eq("B"),eq(20662),eq(905627))).thenReturn(List.of(value));
        assertThat(JdbcOfficialTradingBalanceReader.query(jdbc,account,List.of(fact)).clientPosted()).containsEntry(22L,"O".equals(value.trim()));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void missingPreparedClientStaysPending() {
        var fact=new PendingTradingAdjustmentRepository.Adjustment(22,Asset.XAG,Asset.EUR,OrderSide.BUY,new BigDecimal("0.000322"),new BigDecimal("0.01"),13L,null,null,null,null,null,null);
        assertThat(JdbcOfficialTradingBalanceReader.query(jdbc,account,List.of(fact)).clientPosted()).containsEntry(22L,false);
    }
    @ParameterizedTest @ValueSource(ints={10,0,400,999})
    void lfmp10002UsesExplicitMappingRegardlessOfRepresentative(int representative) {
        account=new TradingAccount(2,3,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",null,10002,1,OffsetDateTime.now(),OffsetDateTime.now());
        identities.clear();identities.add(identity("B ",10002,0,representative));
        metals.add(Map.of("CIRCUI","A","SOLD03",new BigDecimal("66.87")));
        assertThat(read().balances().get(Asset.XAG)).isEqualByComparingTo(new BigDecimal("66.87").divide(new BigDecimal("31.1034768"),6,java.math.RoundingMode.HALF_UP));
        verify(jdbc).query(eq("SELECT STE,NUCLI,NULIV FROM GESCOMF.CLIENOP1 WHERE STE=? AND NUCLI=? AND NULIV=0"),org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any(),eq("B"),eq(10002));
        verify(jdbc).query(eq("SELECT CIRCUI,SOLD03 FROM GESCOMF.CLCPDP03 WHERE STE=? AND NUCLI=? AND NULIV=0 AND CIRCUI IN ('O','A','P','D')"),any(RowCallbackHandler.class),eq("B"),eq(10002));
        verify(jdbc).query(contains("L1NCA=?"),any(RowCallbackHandler.class),eq(10002));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void anotherCompanyIsRejectedBeforeReadingAnyBalance() {
        identities.clear();identities.add(identity("Z",20662,0,10));
        assertInvalidIdentityWithoutBalanceRead();
    }
    @Test void anotherCustomerIsRejectedBeforeReadingAnyBalance() {
        identities.clear();identities.add(identity("B",10002,0,10));
        assertInvalidIdentityWithoutBalanceRead();
    }
    @Test void deliveryAddressIsRejectedBeforeReadingAnyBalance() {
        identities.clear();identities.add(identity("B",20662,1,10));
        assertInvalidIdentityWithoutBalanceRead();
    }
    @Test void incompleteIdentityIsRejectedBeforeReadingAnyBalance() {
        identities.clear();identities.add(Map.of("STE","B","NUCLI",BigDecimal.valueOf(20662)));
        assertInvalidIdentityWithoutBalanceRead();
    }
    private void assertInvalidIdentityWithoutBalanceRead() {
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessage("OFFICIAL_TRADING_IDENTITY_INVALID");
        verify(jdbc,never()).query(anyString(),any(RowCallbackHandler.class),any(Object[].class));
    }
}
