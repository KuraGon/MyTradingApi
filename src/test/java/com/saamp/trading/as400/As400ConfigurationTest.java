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
                    .submit(As400SyncFixtures.movement(As400SyncFixtures.event(As400SyncState.PENDING))))
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
}
