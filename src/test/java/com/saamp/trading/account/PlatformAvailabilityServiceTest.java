package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import com.saamp.trading.common.TradingException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class PlatformAvailabilityServiceTest {
    final BalanceRepository projection=mock(BalanceRepository.class);
    final OfficialTradingBalanceReader reader=mock(OfficialTradingBalanceReader.class);
    final PendingTradingAdjustmentRepository overlay=mock(PendingTradingAdjustmentRepository.class);
    final EffectiveBalanceProperties config=new EffectiveBalanceProperties();
    final TradingAccount live=OfficialBalanceAvailabilityTest.account(1,TradingMode.LIVE);
    EffectiveBalanceService balances;
    PlatformAvailabilityService platform;
    @BeforeEach void setup() {
        config.setMode(EffectiveBalanceProperties.Mode.ENFORCED);
        config.setOverlayCutoverAt(Instant.parse("2026-01-01T00:00:00Z"));
        config.setMaxSnapshotAge(Duration.ofSeconds(15));
        balances=new EffectiveBalanceService(projection,mock(AccountRepository.class),overlay,reader,config);
        platform=new PlatformAvailabilityService(balances);
        when(overlay.read(1)).thenReturn(List.of());
    }
    @AfterEach void cleanup() { balances.close(); }
    private OfficialTradingBalanceReader.Reading reading() {
        var values=new EnumMap<Asset,BigDecimal>(Asset.class);
        for(var asset:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD)) values.put(asset,BigDecimal.ZERO);
        return new OfficialTradingBalanceReader.Reading(values,Map.of());
    }
    @Test void demoIsOpenWithoutAnyOfficialOrProjectionRead() {
        assertThat(platform.status(OfficialBalanceAvailabilityTest.account(2,TradingMode.DEMO),TradingMode.DEMO).status()).isEqualTo("OPEN");
        verifyNoInteractions(reader,overlay,projection);
    }
    @Test void accountModeMismatchIsRejectedBeforeAnyRead() {
        assertThatThrownBy(()->platform.status(live,TradingMode.DEMO)).isInstanceOf(TradingException.class);
        verifyNoInteractions(reader,overlay,projection);
    }
    @Test void officialCaptureFeedsStatusAndBusinessStillRequiresItsOwnCapture() {
        doReturn(reading()).when(reader).read(any(),anyList());
        balances.capture(live);
        assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("OPEN");
        verify(reader,times(2)).read(any(),anyList());
        verify(reader,never()).readDiagnostic(any(),anyList());
        when(reader.read(any(),anyList())).thenThrow(new IllegalStateException("internal test failure"));
        assertThatThrownBy(()->balances.captureForOperation(live)).isInstanceOf(TradingException.class);
        assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("TECHNICAL_CLOSURE");
        doReturn(reading()).when(reader).read(any(),anyList());
        balances.capture(live);
        assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("OPEN");
    }
    @Test void blockedProbeDoesNotBlockHttpOrQueueDuplicateCapturesThenRecovers() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(reader.readDiagnostic(any(),anyList())).thenAnswer(call->{
            entered.countDown();
            if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("fixture blocked");
            return reading();
        });
        try {
            assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("TECHNICAL_CLOSURE");
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofMillis(500),()-> {
                for(int i=0;i<100;i++) assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("TECHNICAL_CLOSURE");
            });
            verify(reader,times(1)).readDiagnostic(any(),anyList());
            release.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->
                assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("OPEN"));
            verify(reader,times(2)).readDiagnostic(any(),anyList());
            verify(reader,never()).read(any(),anyList());
            verifyNoInteractions(projection);
        } finally { release.countDown(); }
    }
    @Test void ambiguousOfficialReadNeverDeclaresOpen() {
        var values=new EnumMap<Asset,BigDecimal>(Asset.class);
        values.putAll(reading().balances()); values.put(Asset.EUR,BigDecimal.ONE);
        when(reader.read(any(),anyList())).thenReturn(reading(),new OfficialTradingBalanceReader.Reading(values,Map.of()));
        assertThatThrownBy(()->balances.capture(live)).isInstanceOf(TradingException.class);
        assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("TECHNICAL_CLOSURE");
        verifyNoInteractions(projection);
    }
    @Test void legacyProjectionCannotDeclareOfficialAvailability() {
        config.setMode(EffectiveBalanceProperties.Mode.LEGACY);
        assertThat(platform.status(live,TradingMode.LIVE).status()).isEqualTo("TECHNICAL_CLOSURE");
        verifyNoInteractions(projection);
    }
}
