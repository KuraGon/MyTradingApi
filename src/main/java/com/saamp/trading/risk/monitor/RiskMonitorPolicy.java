package com.saamp.trading.risk.monitor;

import com.saamp.trading.risk.RiskCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Machine d'alerte post-position, indépendante des statuts publics et de l'admission. */
public final class RiskMonitorPolicy {
    /** Niveaux propres au monitor, sans action automatique de liquidation. */
    public enum Level { NO_POSITION, NORMAL, WARNING, CRITICAL, LIQUIDATION_REQUIRED, PRICE_STALE }
    private RiskMonitorPolicy() { }

    /** Mémoire des alertes décidées, y compris celles restant à livrer.
     * @param mask niveaux financiers déjà signalés
     * @param recoverySince début de récupération par seuil
     */
    public record Gates(int mask, List<Instant> recoverySince) {
        static Gates empty() { return new Gates(0, Arrays.asList(null,null,null)); }
    }
    /** Résultat de transition.
     * @param level niveau observ?
     * @param gates mémoire à persister
     * @param notifyEmail décision unique de notification
     */
    public record Decision(Level level, Gates gates, boolean notifyEmail) { }

    /** Classe sans recalculer la couverture.
     * @param evaluation résultat partagé
     * @param config seuils configurés communs à la classification et au réarmement
     * @return niveau avant arrondi d'affichage
     */
    public static Level classify(RiskCalculator.Evaluation evaluation, RiskMonitorProperties config) {
        if (evaluation.published().grossPosition().signum() == 0) return Level.NO_POSITION;
        BigDecimal c = evaluation.coverageBeforeDisplay();
        if (c.compareTo(config.liquidationRequiredThreshold()) <= 0) return Level.LIQUIDATION_REQUIRED;
        if (c.compareTo(config.criticalThreshold()) <= 0) return Level.CRITICAL;
        if (c.compareTo(config.warningThreshold()) <= 0) return Level.WARNING;
        return Level.NORMAL;
    }

    static Decision advance(Level previous, Gates gates, Instant lastAt, Level next, BigDecimal coverage,
                            Instant now, RiskMonitorProperties config) {
        if (next == Level.NO_POSITION) return new Decision(next, Gates.empty(), false);
        if (next == Level.PRICE_STALE)
            return new Decision(next, new Gates(gates.mask(), Arrays.asList(null,null,null)), previous != next);
        int mask = gates.mask();
        List<Instant> since = new ArrayList<>(gates.recoverySince());
        var thresholds=List.of(config.warningThreshold(),config.criticalThreshold(),config.liquidationRequiredThreshold());
        boolean contiguous = previous != Level.PRICE_STALE && lastAt != null && !lastAt.plus(config.maxObservationGap()).isBefore(now);
        for (int i=0;i<3;i++) {
            if (coverage.compareTo(thresholds.get(i).add(config.hysteresisPoints())) > 0) {
                if (!contiguous || since.get(i) == null) since.set(i, now);
                if (!since.get(i).plus(config.rearmDuration()).isAfter(now)) mask &= ~(1 << i);
            } else since.set(i, null);
        }
        int severity = switch(next) { case WARNING -> 1; case CRITICAL -> 2; case LIQUIDATION_REQUIRED -> 3; default -> 0; };
        boolean notify = severity > 0 && (mask & (1 << (severity-1))) == 0;
        if (severity > 0) mask |= (1 << severity)-1;
        return new Decision(next, new Gates(mask,since), notify);
    }
}
