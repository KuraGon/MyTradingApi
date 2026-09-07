package com.saamp.trading.as400;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Écrit uniquement SICOUVI ; une réponse INSERT perdue est vérifiée par lecture. */
final class JdbcAs400MovementGateway implements As400MovementGateway {
    private static final String LOOKUP = """
            SELECT SIPROV FROM SPECIF1.SICOUVI1
            WHERE SISTE=? AND SICOUI=? AND SICLI=? AND SIREF3=?
            """;
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final TransactionOperations transactions;

    JdbcAs400MovementGateway(JdbcOperations jdbc, Clock clock, TransactionOperations transactions) {
        this.jdbc=jdbc; this.clock=clock; this.transactions=transactions;
    }

    /** Évite la retransmission d'un mouvement déjà accepté.
     * @param m mouvement validé
     * @return numéro de provisoire courant
     * @throws DataAccessException si aucune confirmation fiable n'est disponible
     */
    @Override public int submit(SicouviMovement m) {
        try {
            return transactions.execute(status -> insertIfAbsent(m));
        } catch (DataAccessException | TransactionException exception) {
            // Le read-back est une NOUVELLE transaction, après rollback/commit incertain.
            try {
                var confirmed=transactions.execute(status -> lookup(m.ste(),m.sicoui(),m.nucli(),m.ref3()));
                if (confirmed.isPresent()) return confirmed.get();
            } catch (DataAccessException | TransactionException readFailure) {
                // Aucune confirmation fiable : conserver l'événement retryable.
            }
            throw exception;
        }
    }

    private int insertIfAbsent(SicouviMovement m) {
        // SERIALIZABLE protège aussi l'absence de ligne contre un INSERT concurrent.
        var existing=lookup(m.ste(),m.sicoui(),m.nucli(),m.ref3());
        if (existing.isPresent()) return existing.get();
        Instant now=clock.instant();
        int rows=jdbc.update("""
                INSERT INTO SPECIF1.SICOUVI1
                (SISTE,SICOUI,SIPROV,SICLI,SIDTEC,SIHEUC,SIDEAC,SIACFV,SIMET,SICND,
                 SIPDS,SITXCH,SITYCO,SICOT,SIREF1,SIRELO,SIREF2,SIREF3,SIREF4,CREDAT,CREHEU,MAJDAT,MAJHEU)
                VALUES (?,?,0,?,?,?,?,?,?,?,?,?,'20',?,'MYTRADING','',?,?,0,?,?,0,0)
                """,m.ste(),m.sicoui(),m.nucli(),m.executionDate(),m.executionTime(),m.executionDate(),
                m.side(),m.metal(),m.condition(),m.grams(),m.fx(),m.quotation(),m.ref2(),m.ref3(),
                SicouviMovement.date(now),SicouviMovement.time(now));
        if (rows!=1) throw new As400SyncDataException("AS400_INSERT_ROW_COUNT_INVALID");
        return 0;
    }

    /** Conserve SUBMITTED si la ligne n'est plus visible, sans nouvel INSERT.
     * @param event identité figée
     * @return provisoire courant si présent
     */
    @Override public Optional<Integer> provisional(As400SyncEvent event) {
        return transactions.execute(status -> lookup(event.ste(),event.sicoui(),event.nucliTrading(),SicouviMovement.reference(event.orderId())));
    }

    /** Ne conclut que sur un indicateur définitif explicite du client concerné.
     * @param event provisoire confirmé
     * @return traitement définitif confirmé
     */
    @Override public boolean settled(As400SyncEvent event) {
        return transactions.execute(status -> definitive(event));
    }

    private boolean definitive(As400SyncEvent event) {
        List<String> states=jdbc.query("""
                SELECT ETPRO1 FROM GESCOMF.PROVISP1 WHERE STE=? AND NUPROV=? AND NUCLI=?
                """,(rs,n)->rs.getString(1),event.ste(),event.siprov(),event.nucliTrading());
        if (states.size()>1) throw new As400SyncDataException("AS400_PROVISIONAL_AMBIGUOUS");
        return states.size()==1 && "O".equals(states.getFirst()==null?null:states.getFirst().trim());
    }

    private Optional<Integer> lookup(String ste,int sicoui,int nucli,String reference) {
        List<Integer> rows=jdbc.query(LOOKUP,(rs,n)->rs.getBigDecimal(1).intValueExact(),ste,sicoui,nucli,reference);
        if (rows.size()>1) throw new As400SyncDataException("AS400_MOVEMENT_AMBIGUOUS");
        if (!rows.isEmpty() && (rows.getFirst()<0 || rows.getFirst()>999999))
            throw new As400SyncDataException("AS400_SIPROV_INVALID");
        return rows.stream().findFirst();
    }
}
