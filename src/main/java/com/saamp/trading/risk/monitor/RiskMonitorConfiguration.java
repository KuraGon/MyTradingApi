package com.saamp.trading.risk.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saamp.trading.account.*;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.risk.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

/** Activation explicite sans toucher aux paramètres de production ni aux schedulers existants. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trading.risk-monitor.enabled",havingValue="true")
@EnableConfigurationProperties(RiskMonitorProperties.class)
public class RiskMonitorConfiguration {
    @Bean RiskMonitorRepository riskMonitorRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,AccountRepository accounts,ObjectMapper json) {
        var tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
        return new RiskMonitorRepository(jdbc,tx,accounts,json);
    }
    @Bean RiskMonitorService riskMonitorService(RiskMonitorRepository repository,AccountRepository accounts,EffectiveBalanceService balances,
            PricingService pricing,MarginRateRepository rates,RiskService risk,RiskMonitorProperties config) {
        return new RiskMonitorService(repository,accounts,balances,pricing,rates,risk,config,Clock.systemUTC());
    }
    @Bean RiskMonitorMailWorker riskMonitorMailWorker(RiskMonitorRepository repository,RiskMonitorProperties config) {
        var smtp=config.smtp();
        var mail=new JavaMailSenderImpl();
        mail.setHost(smtp.host());mail.setPort(smtp.port());mail.setUsername(smtp.username());mail.setPassword(smtp.password());
        var props=mail.getJavaMailProperties();
        props.setProperty("mail.smtp.auth",Boolean.toString(smtp.username()!=null && !smtp.username().isBlank()));
        props.setProperty("mail.smtp.starttls.enable",Boolean.toString(smtp.startTls()));
        props.setProperty("mail.smtp.starttls.required",Boolean.toString(smtp.startTls()));
        props.setProperty("mail.smtp.ssl.enable",Boolean.toString(smtp.ssl()));
        props.setProperty("mail.smtp.ssl.checkserveridentity","true");
        for (String name:new String[]{"connectiontimeout","timeout","writetimeout"})
            props.setProperty("mail.smtp."+name,Long.toString(smtp.timeout().toMillis()));
        return new RiskMonitorMailWorker(repository,mail,config);
    }
}
