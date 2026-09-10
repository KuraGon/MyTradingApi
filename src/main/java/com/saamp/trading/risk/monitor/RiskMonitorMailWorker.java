package com.saamp.trading.risk.monitor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Livre l'outbox hors transaction ; SMTP peut exceptionnellement accepter deux retries du même message. */
public final class RiskMonitorMailWorker {
    private enum Ownership { OWNED, UNKNOWN, LOST }
    private final RiskMonitorRepository repository;
    private final JavaMailSender mail;
    private final RiskMonitorProperties config;
    RiskMonitorMailWorker(RiskMonitorRepository repository,JavaMailSender mail,RiskMonitorProperties config) {
        this.repository=repository;this.mail=mail;this.config=config;
    }
    /** Réclame un événement à la fois pour ne pas laisser vieillir un lot sous bail pendant SMTP. */
    @Scheduled(fixedDelayString="${trading.risk-monitor.interval}")
    public void deliver() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("SMTP requires no transaction");
        for (int i=0;i<config.batchSize();i++) {
            var claimed=repository.claim(1,config.claimLease(),config.maxAttempts());
            if (claimed.isEmpty()) return;
            var event=claimed.getFirst();
            var ownership=new AtomicReference<>(renewOwnership(event));
            if (ownership.get()==Ownership.LOST) continue;
            try (var heartbeat=Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("risk-monitor-lease").factory())) {
                long period=Math.max(1,config.claimLease().toMillis()/3);
                var renewal=heartbeat.scheduleWithFixedDelay(()->{
                    if (ownership.get()!=Ownership.LOST) ownership.set(renewOwnership(event));
                },period,period,TimeUnit.MILLISECONDS);
                try {
                try {
                var message=mail.createMimeMessage();
                var helper=new MimeMessageHelper(message,false,"UTF-8");
                helper.setFrom(config.smtp().from());
                helper.setTo(config.smtp().recipients().toArray(String[]::new));
                helper.setSubject("MyTrading Risk Monitor — "+event.level());
                helper.setText("événement : "+event.id()+"\nCompte : "+event.accountId()+"\nNiveau : "+event.level()
                        +"\nCalcul : "+event.createdAt()+"\nPrix de référence : "+event.priceAsOf()
                        +"\nMotif : "+event.reason()+"\nIndicateurs : "+event.indicators()
                        +"\nAucune liquidation automatique. Une analyse humaine est nécessaire.");
                String address=new jakarta.mail.internet.InternetAddress(config.smtp().from()).getAddress();
                String domain=address.substring(address.lastIndexOf('@')+1);
                message.setHeader("Message-ID","<risk-monitor-"+event.id()+"@"+domain+">");
                // JavaMailSenderImpl préserve le Message-ID fourni lors de saveChanges/send.
                mail.send(message);
                } catch (Exception smtpFailure) {
                    try { repository.retry(event,backoff(event.attempts()),config.maxAttempts()); }
                    catch (RuntimeException unavailable) { /* Le bail permet la reprise sans inventer de nouvel événement. */ }
                    continue;
                }
                try { repository.acknowledge(event); }
                catch (RuntimeException unavailable) { /* Succès SMTP non durable : reprise at-least-once acceptée. */ }
                } finally { renewal.cancel(false); }
            }
        }
    }
    private Ownership renewOwnership(RiskMonitorRepository.Event event) {
        try { return repository.renew(event,config.claimLease()) ? Ownership.OWNED : Ownership.LOST; }
        catch (RuntimeException unavailable) { return Ownership.UNKNOWN; }
    }
    Duration backoff(int attempts) {
        Duration delay=config.retryInitial();
        for (int i=1;i<attempts && delay.compareTo(config.retryMax())<0;i++) {
            if (delay.compareTo(config.retryMax().dividedBy(2))>0) return config.retryMax();
            delay=delay.multipliedBy(2);
        }
        return delay.compareTo(config.retryMax())>0?config.retryMax():delay;
    }
}
