package com.saamp.trading.as400;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Timeouts exclusivement diagnostiques ; la datasource SICOUVI et ENFORCED reste intacte. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trading.effective-balance.mode", havingValue="SHADOW")
@Conditional(As400Configuration.ConfiguredJdbcUrl.class)
public class ShadowBalanceJdbcConfiguration {
    private static final Pattern TIMEOUT_OPTION = Pattern.compile("(?i);\\s*(login timeout|socket timeout)\\s*=[^;]*");

    @Bean("shadowBalanceJdbcTemplate")
    JdbcTemplate shadowBalanceJdbcTemplate(@Qualifier("as400JdbcTemplate") JdbcTemplate original) {
        var source = (DriverManagerDataSource) original.getDataSource();
        var bounded = new DriverManagerDataSource() {
            @Override protected Connection getConnectionFromDriverManager(String url, Properties properties) throws SQLException {
                var connection = super.getConnectionFromDriverManager(url, properties);
                try {
                    if ("none".equals(properties.getProperty("transaction isolation"))) {
                        connection.setTransactionIsolation(Connection.TRANSACTION_NONE);
                        connection.setAutoCommit(true);
                        if (connection.getTransactionIsolation()!=Connection.TRANSACTION_NONE || !connection.getAutoCommit())
                            throw new SQLException("AS400 NONE connection mode not applied");
                    }
                    connection.setReadOnly(true);
                    return connection;
                } catch (SQLException failure) { connection.close(); throw failure; }
            }
        };
        bounded.setDriverClassName("com.ibm.as400.access.AS400JDBCDriver");
        // jt400 donne priorite aux options URL : remplacer uniquement ces deux options
        // dans cette copie en memoire, jamais dans la configuration de l'environnement.
        bounded.setUrl(TIMEOUT_OPTION.matcher(source.getUrl()).replaceAll(""));
        bounded.setUsername(source.getUsername());
        bounded.setPassword(source.getPassword());
        var properties = new Properties();
        if (source.getConnectionProperties()!=null) properties.putAll(source.getConnectionProperties());
        properties.setProperty("login timeout", "1"); // jt400 21.0.6 : secondes
        properties.setProperty("socket timeout", "1000"); // jt400 21.0.6 : millisecondes
        bounded.setConnectionProperties(properties);
        return new JdbcTemplate(bounded);
    }
}
