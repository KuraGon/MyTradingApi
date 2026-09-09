package com.saamp.trading.order;

import com.saamp.trading.account.*;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.*;
import com.saamp.trading.reservation.*;
import com.saamp.trading.risk.MarginRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Distingue les limites historiques des prix client de la capacité B, jusque dans les arrondis. */
class OrderCapacityServiceTest {
    BalanceRepository balances=mock(BalanceRepository.class);
    ReservationRepository reservations=mock(ReservationRepository.class);
    MarginRateRepository margins=mock(MarginRateRepository.class);
    TradingAccount account=mock(TradingAccount.class);
    TradingOrder order=mock(TradingOrder.class);
    OrderCapacityService service=new OrderCapacityService(balances,reservations,margins,new TradingProperties());
    Map<Asset,ClientQuote> quotes=new EnumMap<>(Asset.class);
    AssetConfig config=new AssetConfig(Asset.XAU,b("0.000001"),6,b("0.002"),true);

    @BeforeEach void setup() {
        when(account.id()).thenReturn(1L);
        when(account.baseCurrency()).thenReturn(Asset.EUR);
        when(order.id()).thenReturn(2L);
        when(order.asset()).thenReturn(Asset.XAU);
        when(order.side()).thenReturn(OrderSide.BUY);
        when(order.quantityOz()).thenReturn(BigDecimal.ONE);
        when(order.indicativeClientPrice()).thenReturn(b("105"));
        when(balances.findAll(1)).thenReturn(List.of(new Balance(1,Asset.EUR,b("10000"),null)));
        when(reservations.cashReserved(anyLong(),any(),anyLong())).thenReturn(BigDecimal.ZERO);
        when(reservations.riskReserved(anyLong(),any(),anyLong())).thenReturn(BigDecimal.ZERO);
        when(reservations.closeReserved(anyLong(),any(),any(),anyLong())).thenReturn(BigDecimal.ZERO);
        when(margins.currentRate(anyLong(),any())).thenReturn(b("0.05"));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","101","105","95"));
    }

    @Test void longLimitRejectsMarketExposureEvenWhenClientLiquidationWouldFit() {
        when(account.positionLimit()).thenReturn(b("97"));
        assertCode("POSITION_LIMIT_EXCEEDED");
        verify(reservations,never()).releaseForOrder(anyLong());
    }

    @Test void shortLimitAcceptsMarketExposureWhileRiskStillUsesClientBuy() {
        when(order.side()).thenReturn(OrderSide.SELL);
        when(order.indicativeClientPrice()).thenReturn(b("95"));
        when(account.positionLimit()).thenReturn(b("103"));
        var admission=reserve();
        assertThat(admission.cash()).isZero();
        assertThat(admission.risk()).isEqualByComparingTo("15.44");
    }

    @Test void dealLimitUsesClientPriceAndNotMarketAsk() {
        when(account.dealLimit()).thenReturn(b("102"));
        assertCode("DEAL_LIMIT_EXCEEDED");
        when(account.dealLimit()).thenReturn(b("105"));
        assertThat(reserve().risk()).isEqualByComparingTo("14.96");
    }

    @Test void positionLimitIncludesBoundaryAndRoundsOnlyAfterSummingMetals() {
        when(balances.findAll(1)).thenReturn(List.of(new Balance(1,Asset.EUR,b("10000"),null),
                new Balance(1,Asset.XAG,BigDecimal.ONE,null)));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100.004","101","105","95"));
        quotes.put(Asset.XAG,quote(Asset.XAG,"100.004","101","105","95"));
        when(account.positionLimit()).thenReturn(b("200"));
        assertCode("POSITION_LIMIT_EXCEEDED");
        when(account.positionLimit()).thenReturn(b("200.01"));
        assertThat(reserve().cash()).isEqualByComparingTo("105.21");
    }

    @Test void dealLimitKeepsCeilingAtTwoDecimals() {
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","101","105.001","95"));
        when(order.indicativeClientPrice()).thenReturn(b("105.001"));
        when(account.dealLimit()).thenReturn(b("105"));
        assertCode("DEAL_LIMIT_EXCEEDED");
        when(account.dealLimit()).thenReturn(b("105.01"));
        assertThat(reserve().cash()).isEqualByComparingTo("105.220020");
    }

    @Test void previewLimitsUseTheirOwnFreshMarketSnapshotWithoutChangingClientRiskPrices() {
        when(account.positionLimit()).thenReturn(b("100"));
        var fresh=Map.of(Asset.XAU,quote(Asset.XAU,"100.02","101","105","95"));
        assertThatThrownBy(()->service.reserve(account,order,quotes,fresh,config,
                Instant.now().plusSeconds(120).atOffset(ZoneOffset.UTC),false))
                .isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo("POSITION_LIMIT_EXCEEDED"));
    }

    @Test void allReservationsExcludeTheRecalculatedOrder() {
        reserve();
        verify(reservations).cashReserved(1,Asset.EUR,2);
        verify(reservations).riskReserved(1,Asset.EUR,2);
        verify(reservations).closeReserved(1,Asset.XAU,OrderSide.BUY,2);
    }

    @Test void cashCoversRoundedDebitAtAdmittedClientPrice() {
        when(order.indicativeClientPrice()).thenReturn(b("100"));
        when(order.quantityOz()).thenReturn(b("0.03"));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","100","100","100"));
        assertThat(reserve().cash()).isEqualByComparingTo("3.010000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "0.049999,0.001,5.005000", "0.05,0.001,5.010000", "0.050001,0.001,5.015010",
        "0.03,0.001666,3.004998", "0.03,0.001667,3.010000"
    })
    void cashKeepsHistoricalFloorAndCoversHalfCent(String quantity,String drift,String expected) {
        when(order.indicativeClientPrice()).thenReturn(b("100"));
        when(order.quantityOz()).thenReturn(b(quantity));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","100","100","100"));
        config=new AssetConfig(Asset.XAU,b("0.000001"),6,b(drift),true);
        assertThat(reserve().cash()).isEqualByComparingTo(expected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "BUY,0.000049,0,false", "BUY,0.000050,0,true", "BUY,0.000051,0,true",
        "SELL,0.000049,0,false", "SELL,0.000050,0,true", "SELL,0.000051,0,true",
        "BUY,0.000050,0.002,false", "SELL,0.000050,0.002,false",
        "BUY,0.000051,0.002,true", "SELL,0.000051,0.002,true"
    })
    void settlementGuardUsesLowerAdmittedPrice(OrderSide side,String quantity,String drift,boolean accepted) {
        when(order.side()).thenReturn(side);
        when(order.indicativeClientPrice()).thenReturn(b("100"));
        when(order.quantityOz()).thenReturn(b(quantity));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","100","100","100"));
        config=new AssetConfig(Asset.XAU,b("0.000001"),6,b(drift),true);
        if (accepted) assertThatCode(this::reserve).doesNotThrowAnyException();
        else { assertCode("ORDER_AMOUNT_NOT_SETTLEABLE"); verify(reservations,never()).releaseForOrder(anyLong()); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(OrderSide.class)
    void publishedPriceGridDoesNotRejectAnActuallySettleableHalfCent(OrderSide side) {
        when(order.side()).thenReturn(side);
        when(order.indicativeClientPrice()).thenReturn(b("100"));
        when(order.quantityOz()).thenReturn(b("0.00005"));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100","100","100","100"));
        config=new AssetConfig(Asset.XAU,b("0.000001"),0,b("0.002"),true);
        // Sur cette grille, 99 et 101 sont hors drift : le seul prix admis est 100, soit 0.01.
        assertThat(PriceMath.driftRatio(b("100"),b("99"))).isGreaterThan(config.driftTolerance());
        assertThatCode(this::reserve).doesNotThrowAnyException();
    }

    @Test void cashIncludesTheExistingDriftRatioRoundingWithoutAddingTolerance() {
        when(order.indicativeClientPrice()).thenReturn(b("100000000"));
        when(order.quantityOz()).thenReturn(b("1000"));
        when(balances.findAll(1)).thenReturn(List.of(new Balance(1,Asset.EUR,b("200000000000"),null)));
        quotes.put(Asset.XAU,quote(Asset.XAU,"100000000","100000000","100000000","100000000"));
        assertThat(PriceMath.driftRatio(b("100000000"),b("100200000.000049"))).isEqualByComparingTo("0.002");
        assertThat(PriceMath.driftRatio(b("100000000"),b("100200000.000050"))).isGreaterThan(b("0.002"));
        assertThat(reserve().cash()).isEqualByComparingTo("100200000000.05");
    }

    private OrderCapacityService.Admission reserve() {
        return service.reserve(account,order,quotes,config,Instant.now().plusSeconds(120).atOffset(ZoneOffset.UTC),true);
    }
    private void assertCode(String code) {
        assertThatThrownBy(this::reserve).isInstanceOfSatisfying(TradingException.class,e->assertThat(e.getCode()).isEqualTo(code));
    }
    private static ClientQuote quote(Asset asset,String bid,String ask,String buy,String sell) {
        return new ClientQuote(asset,asset.name()+"EUR",b(bid),b(ask),b(buy),b(sell),b(buy),b(sell),
                BigDecimal.ZERO,BigDecimal.ZERO,1,Instant.now().atOffset(ZoneOffset.UTC));
    }
    private static BigDecimal b(String value) { return new BigDecimal(value); }
}
