package com.saamp.trading.risk.monitor;

import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import static com.saamp.trading.risk.monitor.RiskMonitorPolicy.Level.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Vérifie le corps réellement envoyé, sans réseau ni modification des données de risque. */
class RiskMonitorMailWorkerTest {
    private static final Instant AT=Instant.parse("2026-09-10T10:00:00Z");

    @Test void stalePriceWithoutReferenceUsesReadableText() throws Exception {
        String body=render(event(PRICE_STALE,null,"{}","MARKET_PRICE_STALE"));
        assertThat(body).contains("Prix de référence : Non disponible", "Indicateurs : {}")
                .doesNotContain("null");
    }

    @Test void stalePriceWithoutIndicatorsExplainsUnavailability() throws Exception {
        String body=render(event(PRICE_STALE,null,null,"MARKET_PRICE_STALE"));
        assertThat(body).contains("Prix de référence : Non disponible",
                "Indicateurs : Non disponibles — prix de marché obsolète").doesNotContain("null");
    }

    @Test void financialEventKeepsPresentValuesUnchanged() throws Exception {
        var event=event(WARNING,AT,"{\"coveragePct\":104.5}","TEST_REASON");
        assertThat(render(event)).isEqualTo("événement : "+event.id()+"\nCompte : 1\nNiveau : WARNING"
                +"\nCalcul : "+AT+"\nPrix de référence : "+AT
                +"\nMotif : TEST_REASON\nIndicateurs : {\"coveragePct\":104.5}"
                +"\nAucune liquidation automatique. Une analyse humaine est nécessaire.")
                .doesNotContain("null");
    }

    @Test void absentFinancialReasonDoesNotPrintLiteralNull() throws Exception {
        assertThat(render(event(WARNING,AT,"{\"coveragePct\":104.5}",null)))
                .contains("Motif : Non disponible", "Indicateurs : {\"coveragePct\":104.5}")
                .doesNotContain("null");
    }

    private RiskMonitorRepository.Event event(RiskMonitorPolicy.Level level,Instant price,String indicators,String reason) {
        return new RiskMonitorRepository.Event(UUID.randomUUID(),1,1,level,indicators,price,AT,reason,UUID.randomUUID(),1);
    }

    private String render(RiskMonitorRepository.Event event) throws Exception {
        var config=RiskMonitorPolicyTest.config();
        var repository=mock(RiskMonitorRepository.class);
        var mail=mock(JavaMailSender.class);
        var message=new MimeMessage((jakarta.mail.Session)null);
        when(repository.claim(1,config.claimLease(),config.maxAttempts())).thenReturn(List.of(event),List.of());
        when(repository.renew(event,config.claimLease())).thenReturn(true);
        when(repository.acknowledge(event)).thenReturn(true);
        when(mail.createMimeMessage()).thenReturn(message);
        new RiskMonitorMailWorker(repository,mail,config).deliver();
        verify(mail).send(message);
        verify(repository).acknowledge(event);
        return (String)message.getContent();
    }
}
