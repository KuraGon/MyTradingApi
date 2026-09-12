package com.saamp.trading.as400;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Copie diagnostique bornée ; ne change ni la connexion ENFORCED métier ni celle du worker. */
@Configuration(proxyBeanMethods=false)
@Conditional(As400Configuration.ConfiguredJdbcUrl.class)
public class PlatformBalanceJdbcConfiguration {
    @Bean("platformBalanceJdbcTemplate")
    JdbcTemplate platformBalanceJdbcTemplate(@Qualifier("as400JdbcTemplate") JdbcTemplate original) {
        return ShadowBalanceJdbcConfiguration.boundedTemplate(original);
    }
}
