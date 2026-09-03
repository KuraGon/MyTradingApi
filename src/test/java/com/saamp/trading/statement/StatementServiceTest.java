package com.saamp.trading.statement;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import com.saamp.trading.ledger.LedgerEntry;
import com.saamp.trading.ledger.LedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementServiceTest {

    @Test
    void paginatesCurrentAccountLedgerWithoutDuplicatesInStableOrder() {
        var ledger = mock(LedgerRepository.class);
        var service = new StatementService(ledger);
        var account = new TradingAccount(10L, 42L, Asset.EUR, AccountStatus.ACTIVE,
                null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
        var createdAt = OffsetDateTime.parse("2026-09-03T10:00:00Z");
        when(ledger.findPage(10L, null, 3)).thenReturn(List.of(
                entry(9L, createdAt), entry(8L, createdAt.minusMinutes(1)), entry(7L, createdAt.minusMinutes(2))));
        when(ledger.findPage(10L, 8L, 3)).thenReturn(List.of(
                entry(7L, createdAt.minusMinutes(2)), entry(6L, createdAt.minusMinutes(3))));

        var first = service.build(account, null, 2);
        var second = service.build(account, first.nextCursor(), 2);

        assertThat(first.accountId()).isEqualTo(10L);
        assertThat(first.lines()).extracting(StatementLine::id).containsExactly(9L, 8L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(8L);
        assertThat(second.lines()).extracting(StatementLine::id).containsExactly(7L, 6L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(first.lines()).extracting(StatementLine::id)
                .doesNotContainAnyElementsOf(second.lines().stream().map(StatementLine::id).toList());
        verify(ledger).findPage(10L, null, 3);
        verify(ledger).findPage(10L, 8L, 3);
    }

    @Test
    void clampsPageSizeAndNeverRequestsAnotherAccount() {
        var ledger = mock(LedgerRepository.class);
        var service = new StatementService(ledger);
        var account = new TradingAccount(10L, 42L, Asset.EUR, AccountStatus.ACTIVE,
                null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
        when(ledger.findPage(10L, null, 501)).thenReturn(List.of());

        var statement = service.build(account, null, 10_000);

        assertThat(statement.lines()).isEmpty();
        verify(ledger).findPage(10L, null, 501);
    }

    private LedgerEntry entry(long id, OffsetDateTime createdAt) {
        return new LedgerEntry(id, 10L, Asset.EUR, new BigDecimal("100.00"), LedgerEntryType.ADJUSTMENT,
                null, null, new BigDecimal("100.00"), createdAt, "internal-user");
    }
}
