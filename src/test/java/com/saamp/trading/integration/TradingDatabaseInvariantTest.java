package com.saamp.trading.integration;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.reservation.ReservationRepository;
import com.saamp.trading.reservation.ReservationService;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradingDatabaseInvariantTest {

    private static PostgreSQLContainer<?> postgres;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ReservationService reservations;

    @BeforeAll
    void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the PostgreSQL invariant tests");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("trading")
                .withUsername("trading")
                .withPassword("trading");
        postgres.start();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        reservations = context.getBean(ReservationService.class);
    }

    @AfterAll
    void stopDatabase() {
        if (context != null) context.close();
        if (postgres != null) postgres.stop();
    }

    @Test
    void twoConcurrentReservationsOnSameBalanceAllowOnlyOne() throws Exception {
        long accountId = createAccount(1001L);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("100.000000"));
        long order1 = createDraftOrder(accountId, 1001L, "concurrent-1");
        long order2 = createDraftOrder(accountId, 1001L, "concurrent-2");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> calls = List.of(
                    () -> reserveAfterBarrier(ready, start, accountId, order1),
                    () -> reserveAfterBarrier(ready, start, accountId, order2));
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> call : calls) futures.add(executor.submit(call));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successes = 0;
            for (Future<Boolean> future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;

            assertThat(successes).isEqualTo(1);
            BigDecimal active = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity),0) FROM trading_reservation WHERE account_id=? AND asset='EUR' AND status='ACTIVE'",
                    BigDecimal.class, accountId);
            assertThat(active).isEqualByComparingTo("80.000000");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseTriggerRejectsNegativeCurrencyBalanceEvenWhenServiceIsBypassed() {
        long accountId = createAccount(1002L);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("10.000000"));

        assertThatThrownBy(() -> jdbc.update("UPDATE trading_balance SET quantity=-0.000001 WHERE account_id=? AND asset='EUR'", accountId))
                .isInstanceOf(DataAccessException.class);

        BigDecimal quantity = jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset='EUR'", BigDecimal.class, accountId);
        assertThat(quantity).isEqualByComparingTo("10.000000");
    }

    @Test
    void ledgerUpdateAndDeleteAreRejectedByAppendOnlyTrigger() {
        long accountId = createAccount(1003L);
        jdbc.update("INSERT INTO trading_balance(account_id,asset,quantity) VALUES (?,?,?)", accountId, "EUR", new BigDecimal("11.000000"));
        Long ledgerId = jdbc.queryForObject("""
                INSERT INTO trading_ledger_entry(account_id,asset,delta,entry_type,balance_after,created_by)
                VALUES (?,'EUR',1.000000,'ADJUSTMENT',11.000000,'test') RETURNING id
                """, Long.class, accountId);

        assertThatThrownBy(() -> jdbc.update("UPDATE trading_ledger_entry SET delta=2 WHERE id=?", ledgerId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM trading_ledger_entry WHERE id=?", ledgerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void liquibaseAppliedChangelogs001To005AndCriticalTriggersAreActive() {
        Integer changelogCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM databasechangelog
                WHERE id IN ('001-trading-core','002-trading-pricing','003-trading-defaults','004-trading-reconciliation','005-pmxconnect-corrections')
                """, Integer.class);
        assertThat(changelogCount).isEqualTo(5);

        List<String> triggers = jdbc.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE NOT tgisinternal
                  AND tgname IN ('trg_trading_balance_currency_non_negative','trg_trading_ledger_append_only')
                ORDER BY tgname
                """, String.class);
        assertThat(triggers).containsExactly(
                "trg_trading_balance_currency_non_negative",
                "trg_trading_ledger_append_only");

        Integer providerTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='trading_provider_account'", Integer.class);
        assertThat(providerTable).isEqualTo(1);
    }

    private boolean reserveAfterBarrier(CountDownLatch ready, CountDownLatch start, long accountId, long orderId) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            reservations.reserve(accountId, Asset.EUR, new BigDecimal("80.000000"), orderId);
            return true;
        } catch (RuntimeException expected) {
            return false;
        }
    }

    private long createAccount(long companyId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO trading_account(company_id,base_currency,status)
                VALUES (?,'EUR','ACTIVE') RETURNING id
                """, Long.class, companyId);
        return id;
    }

    private long createDraftOrder(long accountId, long companyId, String key) {
        Long id = jdbc.queryForObject("""
                INSERT INTO trading_order(
                    account_id,company_id,asset,pair,side,order_type,requested_quantity,requested_unit,
                    quantity_oz,status,indicative_price,indicative_client_price_raw,indicative_client_price,
                    spread_applied,spread_config_version,idempotency_key,cl_ord_id)
                VALUES (?,?,'XAU','XAUEUR','BUY','SPOT',1,'OZ',1,'DRAFT',100,100,100,0.001,1,?,?)
                RETURNING id
                """, Long.class, accountId, companyId, key, "TEST-" + key);
        return id;
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SpringLiquibase liquibase(DataSource dataSource) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
            return liquibase;
        }

        @Bean
        TradingProperties tradingProperties() {
            TradingProperties properties = new TradingProperties();
            properties.getReservations().setTtl(Duration.ofMinutes(2));
            return properties;
        }

        @Bean
        BalanceRepository balanceRepository(JdbcTemplate jdbc) {
            return new BalanceRepository(jdbc);
        }

        @Bean
        ReservationRepository reservationRepository(JdbcTemplate jdbc) {
            return new ReservationRepository(jdbc);
        }

        @Bean
        ReservationService reservationService(BalanceRepository balances, ReservationRepository reservations, TradingProperties properties) {
            return new ReservationService(balances, reservations, properties);
        }
    }
}
