package com.saamp.trading.risk.monitor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Paramètres explicites : aucune activation ni tolérance métier implicite.
 * @param interval période de calcul
 * @param maxPriceAge âge maximal des prix
 * @param hysteresisPoints écart de réarmement en points de couverture
 * @param rearmDuration durée observée de récupération
 * @param maxObservationGap interruption maximale entre observations
 * @param batchSize taille de page
 * @param claimLease durée du bail de livraison
 * @param retryInitial premier délai de reprise
 * @param retryMax plafond du délai de reprise
 * @param warningThreshold seuil d'avertissement approuvé
 * @param criticalThreshold seuil critique approuvé
 * @param liquidationRequiredThreshold seuil de liquidation à examiner humainement
 * @param maxAttempts plafond technique des tentatives SMTP
 * @param smtp transport externalis?
 */
@ConfigurationProperties("trading.risk-monitor")
public record RiskMonitorProperties(Duration interval, Duration maxPriceAge, BigDecimal hysteresisPoints,
        Duration rearmDuration, Duration maxObservationGap, int batchSize,
        Duration claimLease, Duration retryInitial, Duration retryMax, Smtp smtp,
        @DefaultValue("105") BigDecimal warningThreshold,
        @DefaultValue("104") BigDecimal criticalThreshold,
        @DefaultValue("102") BigDecimal liquidationRequiredThreshold,
        @DefaultValue("5") int maxAttempts) {
    RiskMonitorProperties(Duration interval, Duration maxPriceAge, BigDecimal hysteresisPoints,
            Duration rearmDuration, Duration maxObservationGap, int batchSize,
            Duration claimLease, Duration retryInitial, Duration retryMax, Smtp smtp) {
        this(interval,maxPriceAge,hysteresisPoints,rearmDuration,maxObservationGap,batchSize,
                claimLease,retryInitial,retryMax,smtp,new BigDecimal("105"),new BigDecimal("104"),new BigDecimal("102"),5);
    }
    /** Refuse une activation incomplètement configurée, sans afficher de secret. */
    @ConstructorBinding
    public RiskMonitorProperties {
        if (warningThreshold==null || criticalThreshold==null || liquidationRequiredThreshold==null
                || liquidationRequiredThreshold.compareTo(criticalThreshold)>=0
                || criticalThreshold.compareTo(warningThreshold)>=0 || maxAttempts<1)
            throw new IllegalArgumentException("Risk Monitor : seuils strictement ordonnés et max-attempts positif requis");
        for (Duration duration : new Duration[]{interval,maxPriceAge,rearmDuration,maxObservationGap,claimLease,retryInitial,retryMax})
            if (duration == null || duration.isNegative() || duration.isZero() || duration.toMillis() < 1) throw new IllegalArgumentException("Risk Monitor : durées positives requises");
        if (hysteresisPoints == null || hysteresisPoints.signum() <= 0 || batchSize <= 0 || batchSize > 1000
                || retryMax.compareTo(retryInitial) < 0 || maxObservationGap.compareTo(interval) < 0 || smtp == null)
            throw new IllegalArgumentException("Risk Monitor : paramètres de réarmement/lot/transport invalides");
        if (smtp.timeout() == null || smtp.timeout().isNegative() || smtp.timeout().isZero()
                || smtp.timeout().toMillis() < 1 || smtp.timeout().toMillis() > Integer.MAX_VALUE
                || smtp.timeout().multipliedBy(3).compareTo(claimLease) >= 0)
            throw new IllegalArgumentException("Risk Monitor : bail supérieur aux délais SMTP requis");
    }

    /** Configuration SMTP ; les identifiants ne doivent jamais être journalisés.
     * @param host serveur autoris?
     * @param port port
     * @param username identit? facultative
     * @param password secret facultatif
     * @param from expéditeur
     * @param recipients destinataires
     * @param startTls négociation TLS obligatoire
     * @param ssl TLS implicite
     * @param timeout délai borné des opérations réseau
     */
    public record Smtp(String host, int port, String username, String password, String from,
                       List<String> recipients, boolean startTls, boolean ssl, Duration timeout) {
        /** Valide les adresses et le transport sans les exposer dans les erreurs. */
        public Smtp {
            if (host == null || host.isBlank() || port <= 0 || port > 65535 || from == null
                    || recipients == null || recipients.isEmpty() || (ssl && startTls))
                throw new IllegalArgumentException("Risk Monitor : configuration SMTP incomplète");
            try {
                new jakarta.mail.internet.InternetAddress(from, true).validate();
                for (String address : recipients) {
                    if (address.contains("\r") || address.contains("\n")) throw new IllegalArgumentException();
                    new jakarta.mail.internet.InternetAddress(address, true).validate();
                }
                if (from.contains("\r") || from.contains("\n")) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("Risk Monitor : adresse SMTP invalide"); }
            recipients = List.copyOf(recipients);
        }
        /** Masque les paramètres SMTP dans les diagnostics. @return description sans secret */
        @Override public String toString() { return "Smtp[configuration masquée]"; }
    }
}
