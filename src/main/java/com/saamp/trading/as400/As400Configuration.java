package com.saamp.trading.as400;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Active les accès DB2 uniquement quand leur configuration explicite est fournie. */
@Configuration public class As400Configuration {
 @Bean("as400JdbcTemplate") @ConditionalOnProperty("trading.as400.jdbc-url")
 JdbcTemplate as400JdbcTemplate(org.springframework.core.env.Environment env){var ds=new DriverManagerDataSource();ds.setUrl(env.getRequiredProperty("trading.as400.jdbc-url"));ds.setUsername(env.getRequiredProperty("trading.as400.username"));ds.setPassword(env.getRequiredProperty("trading.as400.password"));return new JdbcTemplate(ds);}
 @Bean @ConditionalOnBean(name="as400JdbcTemplate") As400AccountReader as400Reader(@Qualifier("as400JdbcTemplate") JdbcTemplate jdbc){return new JdbcAs400AccountReader(jdbc);}
 @Bean @ConditionalOnBean(name="as400JdbcTemplate") @ConditionalOnProperty("trading.as400.tdmv03") As400WeightAccountWriter configuredWeightWriter(@Qualifier("as400JdbcTemplate") JdbcTemplate jdbc,org.springframework.core.env.Environment env){return new JdbcAs400WeightAccountWriter(jdbc,()->env.getRequiredProperty("trading.as400.tdmv03"));}
 @Bean @ConditionalOnMissingBean As400WeightAccountWriter blockedWeightWriter(){return new BlockedWeightAccountWriter();}
}
