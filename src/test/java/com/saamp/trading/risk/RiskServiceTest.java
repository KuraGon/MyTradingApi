package com.saamp.trading.risk;

import com.saamp.trading.account.*;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Vérifie le raccord réel entre liquidation client, positions et synthèse de risque. */
class RiskServiceTest {
    private static final OffsetDateTime AS_OF=OffsetDateTime.parse("2026-09-07T10:00:00Z");
    private final EffectiveBalanceService balances=mock(EffectiveBalanceService.class);
    private final MarginRateRepository margins=mock(MarginRateRepository.class);
    private final PricingRepository pricingRepository=mock(PricingRepository.class);
    private final MarketPriceService market=mock(MarketPriceService.class);
    private final MarketDataRefreshService refresh=mock(MarketDataRefreshService.class);
    private final PricingService pricing=new PricingService(pricingRepository,market,refresh);
    private final PositionService positions=new PositionService(balances,pricing,margins);
    private final RiskSnapshotRepository snapshots=mock(RiskSnapshotRepository.class);
    private final RiskService risk=new RiskService(balances,positions,snapshots);
    private final TradingAccount account=new TradingAccount(10L,42L,Asset.EUR,AccountStatus.ACTIVE,
            null,null,null,1,AS_OF,AS_OF);

    @Test
    void exactLongUsesSamePublishedPositionAndMarginInSummary() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"96959.90"),balance(Asset.XAU,"1.01")));
        configure(Asset.XAU,"3000","3010","0.003","0.003","0.05");
        var line=positions.read(account).getFirst();
        clearInvocations(balances,market,margins);

        var summary=risk.computeAndStore(account);

        assertThat(line.clientPrice()).isEqualByComparingTo("2991");
        assertThat(summary.positionValuation()).isEqualTo(line.valuation()).isEqualByComparingTo("3020.91");
        assertThat(summary.grossPosition()).isEqualTo(line.valuation().abs());
        assertThat(summary.marginRequirement()).isEqualTo(line.marginRequirement()).isEqualByComparingTo("151.05");
        assertThat(summary.netEquity()).isEqualByComparingTo("99980.81");
        assertThat(summary.freeEquity()).isEqualByComparingTo("99829.76");
        assertThat(summary.coveragePct()).isEqualByComparingTo("3409.63").isNotEqualByComparingTo(line.marginRatePct());
        verify(balances).findAll(10L);
        verifyNoMoreInteractions(balances);
        verify(market).requireFreshForDisplay("XAUEUR");
        verifyNoMoreInteractions(market);
        verify(margins).currentRate(10L,Asset.XAU);
        verify(snapshots).insert(10L,AS_OF,summary);
        verifyNoInteractions(refresh);
    }

    @Test
    void mixedLongAndShortUseSumOfSignedValuesAbsoluteValuesAndLineMargins() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"96959.90"),
                balance(Asset.XAU,"1.01"),balance(Asset.XAG,"-3"),balance(Asset.USD,"777")));
        configure(Asset.XAU,"3000","3010","0.003","0.003","0.05");
        configure(Asset.XAG,"29","30","0.005","0.005","0.07");
        var lines=positions.read(account);
        var summary=risk.computeAndStore(account);
        assertThat(summary.totalFunds()).isEqualByComparingTo("96959.90");
        assertThat(summary.positionValuation()).isEqualByComparingTo("2930.46")
                .isEqualTo(lines.stream().map(AccountPosition::valuation).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertThat(summary.grossPosition()).isEqualByComparingTo("3111.36")
                .isEqualTo(lines.stream().map(p->p.valuation().abs()).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertThat(summary.marginRequirement()).isEqualByComparingTo("157.38")
                .isEqualTo(lines.stream().map(AccountPosition::marginRequirement).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertThat(summary.netEquity()).isEqualByComparingTo("99890.36");
        assertThat(summary.freeEquity()).isEqualByComparingTo("99732.98");
    }

    @Test
    void changingQuoteCannotBeReadAgainForMarginOrEquityWithinOneCalculation() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"96959.90"),balance(Asset.XAU,"1.01")));
        configure(Asset.XAU,"3000","3010","0.003","0.003","0.05");
        var first=price(Asset.XAU,"3000","3010");
        var later=price(Asset.XAU,"6000","6020");
        when(market.requireFreshForDisplay("XAUEUR")).thenReturn(first,later);

        var summary=risk.computeAndStore(account);

        assertThat(summary.positionValuation()).isEqualByComparingTo("3020.91");
        assertThat(summary.marginRequirement()).isEqualByComparingTo("151.05");
        assertThat(summary.freeEquity()).isEqualByComparingTo("99829.76");
        verify(market,times(1)).requireFreshForDisplay("XAUEUR");
    }

    @Test
    void sumOfPublishedLinesDoesNotRoundTheirCombinedRawValueAgain() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"1000"),
                balance(Asset.XAU,"1"),balance(Asset.XAG,"1")));
        configure(Asset.XAU,"100.005","100.006","0","0","0.05");
        configure(Asset.XAG,"100.005","100.006","0","0","0.05");
        var lines=positions.read(account);
        var summary=risk.computeAndStore(account);
        assertThat(lines).allSatisfy(p->assertThat(p.valuation()).isEqualByComparingTo("100.01"));
        assertThat(summary.positionValuation()).isEqualByComparingTo("200.02");
        assertThat(summary.marginRequirement()).isEqualByComparingTo("10.00");
        assertThat(summary.netEquity()).isEqualByComparingTo("1200.02");
        assertThat(summary.freeEquity()).isEqualByComparingTo("1190.02");
    }

    @Test
    void sumOfPublishedMarginsEqualsLinesEvenAtRoundingBoundary() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"100"),
                balance(Asset.XAU,"1"),balance(Asset.XAG,"1")));
        configure(Asset.XAU,"1.10","1.11","0","0","0.05");
        configure(Asset.XAG,"1.10","1.11","0","0","0.05");
        var summary=risk.computeAndStore(account);
        assertThat(summary.marginRequirement()).isEqualByComparingTo("0.12");
        assertThat(summary.freeEquity()).isEqualByComparingTo("102.08");
    }

    @Test
    void noPositionsKeepsExistingCoverageSentinelWithoutFetchingMarketOrMargins() {
        when(balances.findAll(10L)).thenReturn(List.of(balance(Asset.EUR,"96959.90"),balance(Asset.XAU,"0")));
        var summary=risk.computeAndStore(account);
        assertThat(summary.positionValuation()).isEqualByComparingTo("0.00");
        assertThat(summary.marginRequirement()).isEqualByComparingTo("0.00");
        assertThat(summary.netEquity()).isEqualByComparingTo("96959.90");
        assertThat(summary.freeEquity()).isEqualByComparingTo("96959.90");
        assertThat(summary.coveragePct()).isEqualByComparingTo("999.99");
        assertThat(summary.status()).isEqualTo(com.saamp.trading.domain.RiskStatus.NO_POSITION);
        verifyNoInteractions(market,margins,pricingRepository,refresh);
    }

    private void configure(Asset asset,String bid,String ask,String buySpread,String sellSpread,String margin) {
        when(market.requireFreshForDisplay(asset.name()+"EUR")).thenReturn(price(asset,bid,ask));
        when(pricingRepository.findCurrentSpread(eq(42L),eq(asset),any())).thenReturn(Optional.of(
                new SpreadConfig(1L,42L,asset,new BigDecimal(buySpread),new BigDecimal(sellSpread),1,AS_OF,null)));
        when(pricingRepository.findAssetConfig(asset)).thenReturn(Optional.of(
                new AssetConfig(asset,new BigDecimal("0.000001"),6,new BigDecimal("0.002"),true)));
        when(margins.currentRate(10L,asset)).thenReturn(new BigDecimal(margin));
    }

    private MarketPrice price(Asset asset,String bid,String ask) {
        return new MarketPrice(asset.name()+"EUR",new BigDecimal(bid),new BigDecimal(ask),
                new BigDecimal("9999"),AS_OF,"SIMULATED",AS_OF);
    }

    private Balance balance(Asset asset,String quantity) {
        return new Balance(10L,asset,new BigDecimal(quantity),AS_OF);
    }
}
