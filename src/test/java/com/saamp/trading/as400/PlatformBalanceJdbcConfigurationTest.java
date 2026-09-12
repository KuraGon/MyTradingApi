package com.saamp.trading.as400;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformBalanceJdbcConfigurationTest {
    @Test void enforcedProbeHasRealJt400BoundsWithoutChangingBusinessDatasourceOrConnecting() throws Exception {
        var options=new com.ibm.as400.access.AS400JDBCDriver().getPropertyInfo("jdbc:as400://not-connected",new java.util.Properties());
        assertThat(java.util.Arrays.stream(options).map(p->p.name)).contains("login timeout","socket timeout");
        new ApplicationContextRunner().withBean(DataSource.class,()->mock(DataSource.class))
            .withUserConfiguration(As400Configuration.class,PlatformBalanceJdbcConfiguration.class,ShadowBalanceJdbcConfiguration.class)
            .withPropertyValues("trading.effective-balance.mode=ENFORCED",
                "trading.as400.jdbc-url=jdbc:as400://not-connected;login timeout=60;socket timeout=60000",
                "trading.as400.username=fixture","trading.as400.password=fixture","trading.as400.commit-mode=NONE")
            .run(ctx->{
                assertThat(ctx).hasNotFailed().hasBean("platformBalanceJdbcTemplate").doesNotHaveBean("shadowBalanceJdbcTemplate");
                var original=(DriverManagerDataSource)ctx.getBean("as400JdbcTemplate",JdbcTemplate.class).getDataSource();
                var probe=(DriverManagerDataSource)ctx.getBean("platformBalanceJdbcTemplate",JdbcTemplate.class).getDataSource();
                assertThat(probe).isNotSameAs(original);
                assertThat(original.getUrl()).contains("login timeout=60","socket timeout=60000");
                assertThat(probe.getUrl()).doesNotContain("timeout");
                assertThat(probe.getConnectionProperties()).containsEntry("login timeout","1")
                    .containsEntry("socket timeout","1000").containsEntry("transaction isolation","none");
                verifyNoInteractions(ctx.getBean(DataSource.class));
            });
    }
}
