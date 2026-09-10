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
import java.util.Properties;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.transaction.support.TransactionOperations;

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
        var mode=commitMode(env);
        var ds=new DriverManagerDataSource() {
            @Override protected Connection getConnectionFromDriverManager(String url, Properties properties) throws SQLException {
                var connection=super.getConnectionFromDriverManager(url,properties);
                if (mode==CommitMode.NONE) {
                    try {
                        connection.setTransactionIsolation(Connection.TRANSACTION_NONE);
                        connection.setAutoCommit(true);
                        if (connection.getTransactionIsolation()!=Connection.TRANSACTION_NONE || !connection.getAutoCommit())
                            throw new SQLException("AS400 NONE connection mode not applied");
                    } catch(SQLException failure) { connection.close();throw failure; }
                }
                return connection;
            }
        };
        ds.setDriverClassName("com.ibm.as400.access.AS400JDBCDriver");
        String url=env.getRequiredProperty("trading.as400.jdbc-url");
        if (mode==CommitMode.NONE) {
            if (java.util.regex.Pattern.compile("(?i);\\s*(transaction isolation|true autocommit)\\s*=").matcher(url).find())
                throw new IllegalArgumentException("AS400 NONE isolation must be configured by commit-mode, not JDBC URL");
            var properties=new Properties();
            properties.setProperty("transaction isolation","none");
            properties.setProperty("true autocommit","false");
            ds.setConnectionProperties(properties);
        }
        ds.setUrl(url);
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
    As400MovementGateway as400MovementGateway(@Qualifier("as400JdbcTemplate") JdbcTemplate jdbc, Environment env) {
        if (commitMode(env)==CommitMode.NONE)
            return new JdbcAs400MovementGateway(jdbc,Clock.systemUTC(),TransactionOperations.withoutTransaction());
        var localTransaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        // READ_COMMITTED limite la contention sur SICOUVI1, partage avec les traitements AS400.
        localTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        localTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        localTransaction.setTimeout(30);
        return new JdbcAs400MovementGateway(jdbc,Clock.systemUTC(),localTransaction);
    }

    private enum CommitMode { NONE, READ_COMMITTED }

    private static CommitMode commitMode(Environment env) {
        return CommitMode.valueOf(env.getProperty("trading.as400.commit-mode","READ_COMMITTED"));
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
