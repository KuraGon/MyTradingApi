package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reconstruit les faits FILLED, même sans événement ou lorsque la synchronisation est bloquée. */
@Repository
public class PendingTradingAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final EffectiveBalanceProperties config;
    /** @param jdbc accès PostgreSQL, indépendant de la projection de solde
     * @param config borne explicite de l'overlay, identique apres redemarrage */
    public PendingTradingAdjustmentRepository(JdbcTemplate jdbc, EffectiveBalanceProperties config) {
        this.jdbc = jdbc; this.config = config;
    }

    /** Identité CLIENT durable et montants exécutés, sans recalcul de prix. */
    public record Adjustment(long orderId, Asset metal, Asset currency, OrderSide side,
            BigDecimal quantityOz, BigDecimal grossAmount, Long eventId, Long movementId,
            String ste, Integer nucli, Integer sicoui, Integer siprov, String exid) { }

    /** @param accountId compte à lire @return faits FILLED depuis le cutover et leur seule jambe CLIENT
     * @throws IllegalStateException si le cutover requis est absent */
    public List<Adjustment> read(long accountId) {
        config.validateConfiguration();
        if (config.getMode() == EffectiveBalanceProperties.Mode.LEGACY) return List.of();
        return jdbc.query("""
            SELECT o.id,o.asset,o.pair,o.side,o.quantity_oz,o.gross_amount,
                   e.id event_id,m.id movement_id,COALESCE(m.siste,e.as400_ste) siste,COALESCE(m.nucli,e.nucli_trading) nucli,m.sicoui,m.siprov,m.siref3
            FROM trading_order o
            LEFT JOIN trading_as400_sync_outbox e ON e.order_id=o.id AND e.target='SICOUVI'
            LEFT JOIN trading_as400_movement m ON m.event_id=e.id AND m.leg_role='CLIENT'
            WHERE o.account_id=? AND o.status='FILLED' AND o.executed_at>=? ORDER BY o.id,m.id
            """, (r,n) -> new Adjustment(r.getLong("id"),Asset.valueOf(r.getString("asset")),
                Asset.valueOf(r.getString("pair").substring(3)),OrderSide.valueOf(r.getString("side")),
                r.getBigDecimal("quantity_oz"),r.getBigDecimal("gross_amount"),
                (Long)r.getObject("event_id"),(Long)r.getObject("movement_id"),r.getString("siste"),
                (Integer)r.getObject("nucli"),(Integer)r.getObject("sicoui"),(Integer)r.getObject("siprov"),r.getString("siref3")),
                accountId,config.getOverlayCutoverAt().atOffset(java.time.ZoneOffset.UTC));
    }
}
