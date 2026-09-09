package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import com.saamp.trading.ledger.LedgerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Vérifie sur PostgreSQL que l'indisponibilité AS400 ne défait jamais un dénouement commité. */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:postgresql://localhost:5432/trading",
        "spring.datasource.username=trading",
        "spring.datasource.password=${TRADING_DB_PASSWORD}",
        "spring.liquibase.url=jdbc:postgresql://localhost:5432/trading",
        "spring.liquibase.user=trading",
        "spring.liquibase.password=${TRADING_DB_PASSWORD}",
        "trading.provider.mode=SIMULATED",
        "trading.as400.enabled=false",
        "trading.as400.jdbc-url=",
        "trading.reconciliation.enabled=false"
})
class As400FilledIsolationIntegrationTest {
    // Empêche tout batch automatique de modifier l'environnement local pendant ces scénarios explicites.
    @org.springframework.test.context.bean.override.mockito.MockitoBean(
            name="org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;

    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired As400SyncOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void unavailableAs400LeavesCommittedFilledLedgerAndBalancesIntact() {
        var tx=new TransactionTemplate(transactionManager);
        long accountId=jdbc.queryForObject("""
                INSERT INTO trading_account(company_id,base_currency,status,as400_ste,as400_nucli_trading)
                VALUES (?,'EUR','ACTIVE','B',123456) RETURNING id
                """,Long.class,System.currentTimeMillis());
        ledger.post(accountId,Asset.EUR,new BigDecimal("1000"),LedgerEntryType.ADJUSTMENT,null,null,"test:as400");
        long orderId=tx.execute(status->{
            long id=jdbc.queryForObject("""
                    INSERT INTO trading_order(account_id,company_id,asset,pair,side,order_type,requested_quantity,
                      requested_unit,quantity_oz,status,idempotency_key,cl_ord_id)
                    SELECT id,company_id,'XAU','XAUEUR','BUY','SPOT',1,'OZ',1,'DRAFT',?,?
                    FROM trading_account WHERE id=? RETURNING id
                    """,Long.class,UUID.randomUUID().toString(),UUID.randomUUID().toString(),accountId);
            ledger.postTrade(accountId,Asset.XAU,BigDecimal.ONE,Asset.EUR,new BigDecimal("-100.00"),id,"test:as400");
            jdbc.update("UPDATE trading_order SET status='FILLED',client_price=100,market_price=99,stonex_exid='EXID-FILLED-TEST',executed_at=NOW() WHERE id=?",id);
            return id;
        });
        var before=jdbc.queryForList("SELECT asset,quantity FROM trading_balance WHERE account_id=? ORDER BY asset",accountId);
        UUID token=UUID.randomUUID();
        var claimed=jdbc.query("""
                UPDATE trading_as400_sync_outbox SET status='PROCESSING',claim_token=?,next_attempt_at=NOW()+INTERVAL '5 minutes'
                WHERE order_id=? RETURNING id
                """,(rs,n)->new As400SyncEvent(rs.getLong(1),orderId,As400SyncTarget.SICOUVI,0,token,
                        As400SyncState.PENDING,"B",123456),token,orderId).getFirst();
        // Le batch est borné à notre fixture ; les mutations utilisent le vrai repository transactionnel.
        var isolated=mock(As400SyncOutboxRepository.class,org.mockito.AdditionalAnswers.delegatesTo(outbox));
        doReturn(List.of(claimed)).when(isolated).claimDue(anyInt(),any());
        var unavailable=mock(As400MovementGateway.class);
        var independent=new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        when(unavailable.submit(any())).thenAnswer(call -> {
            // Une autre transaction voit deja les quatre identites et le FX avant le premier acces DB2.
            independent.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_as400_movement WHERE event_id=?",Integer.class,claimed.id())).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT fx_rate=1 AND fx_frozen_at IS NOT NULL FROM trading_as400_sync_outbox WHERE id=?",Boolean.class,claimed.id())).isTrue();
            });
            throw new DataAccessResourceFailureException("AS400 unavailable");
        });
        new As400SyncWorker(isolated,jdbc,unavailable,As400SyncFixtures.MAPPING,mock(As400FxSource.class),true,Duration.ofMinutes(5),Duration.ofHours(1)).processDue();

        assertThat(jdbc.queryForObject("SELECT status FROM trading_order WHERE id=?",String.class,orderId)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_ledger_entry WHERE order_id=? AND entry_type='TRADE'",Integer.class,orderId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT asset,quantity FROM trading_balance WHERE account_id=? ORDER BY asset",accountId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT status='RETRY' AND sync_state='PENDING' AND attempt_count=1 AND claim_token IS NULL
                  AND last_error='AS400_TEMPORARY_DataAccessResourceFailureException'
                FROM trading_as400_sync_outbox WHERE order_id=?
                """,Boolean.class,orderId)).isTrue();
    }
}
