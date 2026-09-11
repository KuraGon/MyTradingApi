package com.saamp.trading.account;

import java.time.Duration;
import java.time.Instant;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Déploiement progressif, sans activation implicite de la source officielle. */
@Component
@ConfigurationProperties("trading.effective-balance")
public class EffectiveBalanceProperties {
    public enum Mode { LEGACY, SHADOW, ENFORCED }
    private Mode mode = Mode.LEGACY;
    private Duration maxSnapshotAge = Duration.ofSeconds(5);
    private Instant overlayCutoverAt;
    /** @return borne inclusive des executions couvertes, sans valeur implicite */
    public Instant getOverlayCutoverAt() { return overlayCutoverAt; }
    /** @param cutover instant de bascule explicitement choisi par l'exploitant */
    public void setOverlayCutoverAt(Instant cutover) { overlayCutoverAt = cutover; }
    /** Refuse une activation qui assimilerait silencieusement l'historique aux provisoires.
     * @throws IllegalStateException si le mode officiel ne dispose pas de cutover */
    @PostConstruct
    public void validateConfiguration() {
        if (mode != Mode.LEGACY && overlayCutoverAt == null)
            throw new IllegalStateException("trading.effective-balance.overlay-cutover-at is required in SHADOW/ENFORCED");
    }
    /** @return mode explicitement configuré */
    public Mode getMode() { return mode; }
    /** @param mode mode de décision, jamais déduit de la présence d'AS400 */
    public void setMode(Mode mode) { this.mode = java.util.Objects.requireNonNull(mode); }
    /** @return limite technique de validité depuis le début de lecture */
    public Duration getMaxSnapshotAge() { return maxSnapshotAge; }
    /** @param age durée strictement positive @throws IllegalArgumentException si invalide */
    public void setMaxSnapshotAge(Duration age) {
        if (age == null || age.isNegative() || age.isZero()) throw new IllegalArgumentException("max-snapshot-age must be positive");
        maxSnapshotAge = age;
    }
}
