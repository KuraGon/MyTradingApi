package com.saamp.trading.reservation;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Conserve séparément liquidité, fermeture exclusive et capacité de risque. */
@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;
    private static final String ACTIVE = """
            r.status='ACTIVE' AND (o.status IN ('PENDING','PENDING_UNKNOWN')
              OR (o.status='DRAFT' AND r.expires_at>NOW())
              OR (r.reservation_kind='LEGACY' AND o.id IS NULL))
            """;

    /**
     * @param jdbc accès à la vérité PostgreSQL */
    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /**
     * @param accountId compte
     * @param asset actif
     * @return cash ou fermetures SELL, jamais RISK */
    public BigDecimal activeReserved(long accountId, Asset asset) {
        return asset.isCurrency() ? cashReserved(accountId,asset,-1)
                : closeReserved(accountId,asset,OrderSide.SELL,-1);
    }

    /**
     * @param accountId compte
     * @param asset devise
     * @param excludedOrder ordre recalculé
     * @return liquidité engagée */
    public BigDecimal cashReserved(long accountId, Asset asset, long excludedOrder) {
        return sum(accountId,asset,excludedOrder,"r.reservation_kind IN ('CASH','LEGACY')");
    }

    /**
     * @param accountId compte
     * @param asset devise
     * @param excludedOrder ordre recalculé
     * @return capacité engagée */
    public BigDecimal riskReserved(long accountId, Asset asset, long excludedOrder) {
        return sum(accountId,asset,excludedOrder,"r.reservation_kind IN ('RISK','LEGACY')");
    }

    /**
     * @param accountId compte
     * @param asset métal
     * @param side sens fermant
     * @param excludedOrder ordre recalculé
     * @return fermetures déjà attribuées */
    public BigDecimal closeReserved(long accountId, Asset asset, OrderSide side, long excludedOrder) {
        return sum(accountId,asset,excludedOrder,
                "(r.reservation_kind='LEGACY' OR (r.reservation_kind='POSITION_CLOSE' AND o.side='"+side.name()+"'))");
    }

    /**
     * Refuse une nouvelle admission tant qu'un engagement transmis conserve une sémantique historique ambiguë.
     * @param accountId compte déjà verrouillé par l'admission
     * @return présence d'un LEGACY ACTIVE transmis, indépendamment de son TTL et de son actif
     */
    public boolean hasActiveTransmittedLegacy(long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM trading_reservation r JOIN trading_order o ON o.id=r.order_id
                  WHERE r.account_id=? AND r.reservation_kind='LEGACY' AND r.status='ACTIVE'
                    AND o.status IN ('PENDING','PENDING_UNKNOWN'))
                """,Boolean.class,accountId));
    }

    /**
     * Relit les fermetures opposables pour attribuer une seule fois la position comptabilisée.
     * Les transmissions restent présentes après TTL ; aucun engagement n'est modifié.
     * @param accountId compte dont l'admission détient le verrou
     * @param excludedOrder ordre en cours de recalcul, ou -1 pour préparer les cotations
     * @return engagements ordonnés par identité, avec leurs enveloppes historiques
     */
    public java.util.List<CloseCommitment> activeCloseCommitments(long accountId,long excludedOrder) {
        return jdbc.query("""
                SELECT o.asset,o.side,o.quantity_oz,o.indicative_client_price,c.drift_tolerance,
                  o.status IN ('PENDING','PENDING_UNKNOWN') AS transmitted,
                  SUM(r.quantity) FILTER (WHERE r.reservation_kind='POSITION_CLOSE') AS close_quantity,
                  COALESCE(SUM(r.quantity) FILTER (WHERE r.reservation_kind='RISK'),0) AS risk_quantity
                FROM trading_order o JOIN trading_reservation r ON r.order_id=o.id
                LEFT JOIN trading_asset_config c ON c.asset=o.asset
                WHERE o.account_id=? AND o.id<>? AND
                """+ACTIVE+"""
                GROUP BY o.id,c.drift_tolerance
                HAVING SUM(r.quantity) FILTER (WHERE r.reservation_kind='POSITION_CLOSE')>0
                ORDER BY o.id
                """,(rs,n)->new CloseCommitment(Asset.valueOf(rs.getString("asset")),
                        OrderSide.valueOf(rs.getString("side")),rs.getBigDecimal("quantity_oz"),
                        rs.getBigDecimal("close_quantity"),rs.getBigDecimal("risk_quantity"),
                        rs.getBigDecimal("indicative_client_price"),rs.getBigDecimal("drift_tolerance"),
                        rs.getBoolean("transmitted")),accountId,excludedOrder);
    }

    /**
     * Instantané de lecture ; ne remplace aucune réservation persistée.
     * @param asset métal engagé
     * @param side sens de l'engagement
     * @param quantity quantité totale transmise
     * @param close fermeture historiquement attribuée
     * @param risk risque déjà réservé
     * @param anchor prix client indicatif de l'ordre
     * @param drift tolérance configurée existante
     * @param transmitted engagement irréversible, même après TTL
     */
    public record CloseCommitment(Asset asset,OrderSide side,BigDecimal quantity,BigDecimal close,
                                  BigDecimal risk,BigDecimal anchor,BigDecimal drift,boolean transmitted) { }

    /**
     * Termine les engagements d'un brouillon expiré sous les verrous compte/ordre de l'appelant.
     * @param orderId brouillon dont l'échéance a été contrôlée
     * @return nombre de réservations expirées
     */
    public int expireForOrder(long orderId) {
        return jdbc.update("UPDATE trading_reservation SET status='EXPIRED' WHERE order_id=? AND status='ACTIVE'",orderId);
    }

    private BigDecimal sum(long accountId, Asset asset, long excludedOrder, String kind) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity),0) FROM trading_reservation r
                LEFT JOIN trading_order o ON o.id=r.order_id
                WHERE r.account_id=? AND r.asset=? AND (r.order_id IS NULL OR r.order_id<>?) AND
                """+ACTIVE+" AND "+kind, BigDecimal.class,accountId,asset.name(),excludedOrder);
    }

    /**
     * Remplace une enveloppe sous verrou compte/ordre sans produire de LEGACY.
     *
     * @param accountId compte
     *
     * @param asset actif
     *
     * @param quantity montant strictement positif
     *
     * @param orderId ordre
     *
     * @param kind sémantique
     *
     * @param expiresAt échéance du preview
     *
     * @return identité persistante
     * @throws IllegalArgumentException si une création historique est demandée
     */
    public long upsert(long accountId, Asset asset, BigDecimal quantity, long orderId,
                       ReservationKind kind, OffsetDateTime expiresAt) {
        if (kind==ReservationKind.LEGACY) throw new IllegalArgumentException("LEGACY is migration-only");
        return jdbc.queryForObject("""
                INSERT INTO trading_reservation(account_id,asset,quantity,order_id,reservation_kind,status,expires_at)
                VALUES (?,?,?,?,?,'ACTIVE',?)
                ON CONFLICT (order_id,reservation_kind,asset)
                DO UPDATE SET quantity=EXCLUDED.quantity,status='ACTIVE',expires_at=EXCLUDED.expires_at
                RETURNING id
                """,Long.class,accountId,asset.name(),quantity,orderId,kind.name(),expiresAt);
    }

    /**
     * @param orderId ordre
     * @return présence d'un engagement opposable */
    public boolean hasActiveForOrder(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation r JOIN trading_order o ON o.id=r.order_id WHERE r.order_id=? AND "+ACTIVE,
                Integer.class,orderId)>0;
    }

    /**
     * @param orderId ordre
     * @return première échéance persistée */
    public Optional<OffsetDateTime> findEarliestExpiryForOrder(long orderId) {
        return jdbc.query("SELECT MIN(expires_at) FROM trading_reservation WHERE order_id=?",
                (rs,n)->rs.getObject(1,OffsetDateTime.class),orderId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * @param orderId ordre réglé */
    public void consumeForOrder(long orderId) {
        jdbc.update("UPDATE trading_reservation SET status='CONSUMED' WHERE order_id=? AND status='ACTIVE'",orderId);
    }

    /**
     * @param orderId ordre rejeté ou recalculé sous verrou */
    public void releaseForOrder(long orderId) {
        jdbc.update("UPDATE trading_reservation SET status='RELEASED' WHERE order_id=? AND status='ACTIVE'",orderId);
    }

    /**
     * Exige la transaction englobante de ReservationService.expireDue : les verrous compte/ordre
     * doivent survivre à leur SELECT jusqu'à la relecture d'état et l'UPDATE final.
     * @param ttl échéance de secours identique à celle du submit, lorsqu'aucune réservation n'existe
     * @return nombre de réservations de brouillons expirées, jamais d'engagements transmis */
    public int expireDue(java.time.Duration ttl) {
        var candidates=jdbc.query("""
                SELECT DISTINCT o.account_id,o.id FROM trading_order o
                WHERE o.status='DRAFT' AND COALESCE(
                  (SELECT MIN(r.expires_at) FROM trading_reservation r WHERE r.order_id=o.id),
                  o.created_at+CAST(? AS interval))<=clock_timestamp()
                ORDER BY o.account_id,o.id
                """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},ttl.toString());
        int count=0;
        for (var candidate:candidates) {
            jdbc.queryForObject("SELECT id FROM trading_account WHERE id=? FOR UPDATE",Long.class,candidate[0]);
            String state=jdbc.queryForObject("SELECT status FROM trading_order WHERE id=? FOR UPDATE",String.class,candidate[1]);
            if ("DRAFT".equals(state) && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT COALESCE((SELECT MIN(r.expires_at) FROM trading_reservation r WHERE r.order_id=o.id),
                      o.created_at+CAST(? AS interval))<=clock_timestamp()
                    FROM trading_order o WHERE o.id=?
                    """,Boolean.class,ttl.toString(),candidate[1]))) {
                count+=expireForOrder(candidate[1]);
                jdbc.update("UPDATE trading_order SET status='EXPIRED' WHERE id=? AND status='DRAFT'",candidate[1]);
            }
        }
        return count;
    }
}
