package com.saamp.trading.as400;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class As400ConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withBean(DataSource.class,()->mock(DataSource.class)).withUserConfiguration(As400Configuration.class);

    @Test
    void bootsWithoutAs400AndDefersAnyAttempt() {
        context.run(ctx->{
            assertThat(ctx).hasNotFailed().doesNotHaveBean("as400JdbcTemplate").hasSingleBean(JdbcTemplate.class);
            assertThatThrownBy(()->ctx.getBean(As400MovementGateway.class)
                    .submit(As400SyncFixtures.group(As400SyncState.PENDING)))
                    .hasMessage("AS400_NOT_CONFIGURED");
        });
    }

    @Test
    void configuredAs400DoesNotReplacePostgresOrConnectAtStartup() {
        context.withPropertyValues("trading.as400.jdbc-url=jdbc:as400://not-connected",
                "trading.as400.username=test","trading.as400.password=test-only").run(ctx->{
            assertThat(ctx).hasNotFailed().hasBean("as400JdbcTemplate").hasSingleBean(As400MovementGateway.class);
            assertThat(ctx.getBean(JdbcTemplate.class)).isSameAs(ctx.getBean("jdbcTemplate"));
            assertThat(ctx.getBean("as400JdbcTemplate")).isNotSameAs(ctx.getBean("jdbcTemplate"));
            assertThat(ctx.getBean(As400MovementGateway.class)).isInstanceOf(JdbcAs400MovementGateway.class);
            verifyNoInteractions(ctx.getBean(DataSource.class));
        });
    }

    @Test
    void emptyAs400UrlDoesNotRequireCredentials() {
        context.withPropertyValues("trading.as400.jdbc-url=").run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("as400JdbcTemplate");
            assertThat(ctx).hasSingleBean(As400MovementGateway.class);
        });
    }

    @Test void noneUsesRealToolboxPropertiesAndVerifiesAutocommit() throws Exception {
        var choices=new com.ibm.as400.access.AS400JDBCDriver().getPropertyInfo("jdbc:as400://not-connected",new java.util.Properties());
        assertThat(java.util.Arrays.stream(choices).filter(p->p.name.equals("transaction isolation")).findFirst().orElseThrow().choices).contains("none");
        assertThat(java.util.Arrays.stream(choices).filter(p->p.name.equals("true autocommit")).findFirst().orElseThrow().choices).contains("false");
        context.withPropertyValues("trading.as400.jdbc-url=jdbc:as400://not-connected",
                "trading.as400.username=test","trading.as400.password=test-only","trading.as400.commit-mode=NONE").run(ctx->{
            assertThat(ctx).hasNotFailed();
            var ds=((JdbcTemplate)ctx.getBean("as400JdbcTemplate")).getDataSource();
            var connection=mock(java.sql.Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_NONE);
            try(var driver=mockStatic(java.sql.DriverManager.class)) {
                driver.when(()->java.sql.DriverManager.getConnection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(java.util.Properties.class))).thenReturn(connection);
                try(var actual=ds.getConnection()) { assertThat(actual).isSameAs(connection); }
                var properties=org.mockito.ArgumentCaptor.forClass(java.util.Properties.class);
                driver.verify(()->java.sql.DriverManager.getConnection(org.mockito.ArgumentMatchers.eq("jdbc:as400://not-connected"),properties.capture()));
                assertThat(properties.getValue()).containsEntry("transaction isolation","none").containsEntry("true autocommit","false");
                var order=inOrder(connection);
                order.verify(connection).setTransactionIsolation(java.sql.Connection.TRANSACTION_NONE);
                order.verify(connection).setAutoCommit(true);
                verify(connection,never()).setAutoCommit(false);
                verify(connection,never()).commit();
                verify(connection,never()).rollback();
            }
            verifyNoInteractions(ctx.getBean(DataSource.class));
        });
    }

    @Test void invalidCommitModeFailsBeforeConnection() {
        context.withPropertyValues("trading.as400.jdbc-url=jdbc:as400://not-connected","trading.as400.commit-mode=AUTO")
                .run(ctx->assertThat(ctx).hasFailed());
    }

    @Test void noneRejectsConflictingJdbcUrlSettings() {
        context.withPropertyValues("trading.as400.jdbc-url=jdbc:as400://not-connected;true autocommit=true",
                "trading.as400.commit-mode=NONE").run(ctx->assertThat(ctx).hasFailed());
    }
}
