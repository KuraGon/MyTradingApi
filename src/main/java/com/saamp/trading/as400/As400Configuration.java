package com.saamp.trading.as400;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.List;

/** Active DB2 uniquement sur configuration explicite ; PostgreSQL demeure la datasource principale. */
@Configuration
public class As400Configuration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(name="jdbcTemplate")
    JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }

    @Bean("as400JdbcTemplate")
    @Conditional(ConfiguredJdbcUrl.class)
    JdbcTemplate as400JdbcTemplate(Environment env) {
        var ds=new DriverManagerDataSource();
        ds.setDriverClassName("com.ibm.as400.access.AS400JDBCDriver");
        ds.setUrl(env.getRequiredProperty("trading.as400.jdbc-url"));
        ds.setUsername(env.getRequiredProperty("trading.as400.username"));
        ds.setPassword(env.getRequiredProperty("trading.as400.password"));
        var jdbc=new JdbcTemplate(ds);
        jdbc.setQueryTimeout(30);
        return jdbc;
    }

    @Bean
    @ConditionalOnBean(name="as400JdbcTemplate")
    As400AccountReader as400Reader(@Qualifier("as400JdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcAs400AccountReader(jdbc);
    }

    @Bean
    @ConditionalOnBean(name="as400JdbcTemplate")
    As400MovementGateway as400MovementGateway(@Qualifier("as400JdbcTemplate") JdbcTemplate jdbc) {
        var localTransaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        // READ_COMMITTED limite la contention sur SICOUVI1, partage avec les traitements AS400.
        localTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        localTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        localTransaction.setTimeout(30);
        return new JdbcAs400MovementGateway(jdbc,Clock.systemUTC(),localTransaction);
    }

    @Bean
    @ConditionalOnMissingBean(As400MovementGateway.class)
    As400MovementGateway unconfiguredMovementGateway() {
        return new As400MovementGateway() {
            @Override public List<Integer> submit(As400MovementGroup group) { throw unavailable(); }
            @Override public List<Integer> provisional(As400MovementGroup group) { throw unavailable(); }
            @Override public boolean settled(As400Movement leg) { throw unavailable(); }
            private IllegalStateException unavailable() { return new IllegalStateException("AS400_NOT_CONFIGURED"); }
        };
    }

    /** Évite de rendre AS400 obligatoire quand une variable UAT existe mais reste vide. */
    static final class ConfiguredJdbcUrl implements Condition {
        /** Active DB2 seulement pour une adresse exploitable.
         * @param context propriétés de démarrage
         * @param metadata déclaration conditionnelle
         * @return présence effective de la configuration
         */
        @Override public boolean matches(ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return org.springframework.util.StringUtils.hasText(context.getEnvironment().getProperty("trading.as400.jdbc-url"));
        }
    }
}
