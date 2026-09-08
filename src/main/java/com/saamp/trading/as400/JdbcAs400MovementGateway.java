package com.saamp.trading.as400;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Soumet SICOUVI en une transaction locale ; aucune reparation implicite d'un groupe partiel. */
final class JdbcAs400MovementGateway implements As400MovementGateway {
    private static final String LOOKUP = """
            SELECT SIPROV,SIACFV,SIMET,SIPDS,SICOT,SITXCH FROM SPECIF1.SICOUVI1
            WHERE SISTE=? AND SICOUI=? AND SICLI=? AND SIREF3=?
            """;
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final TransactionOperations transactions;

    JdbcAs400MovementGateway(JdbcOperations jdbc, Clock clock, TransactionOperations transactions) {
        this.jdbc=jdbc; this.clock=clock; this.transactions=transactions;
    }

    /** Conserve lookup et les N INSERT dans une seule transaction DB2.
     * @param group groupe valide et durable
     * @return provisoires confirmes
     * @throws DataAccessException si le read-back ne confirme pas la soumission
     * @throws As400SyncDataException si un groupe partiel ou non conforme est detecte
     */
    @Override public List<Integer> submit(As400MovementGroup group) {
        group.validate();
        try {
            return transactions.execute(status -> {
                var existing=lookupGroup(group);
                if (!existing.isEmpty()) return existing;
                Instant now=clock.instant();
                for (var leg:group.legs()) insert(leg.data(),now);
                return java.util.Collections.nCopies(group.expectedLegCount(),0);
            });
        } catch (DataAccessException | TransactionException exception) {
            // REQUIRES_NEW : la transaction incertaine est terminee avant ce read-back.
            try {
                var confirmed=transactions.execute(status -> lookupGroup(group));
                if (!confirmed.isEmpty()) return confirmed;
            } catch (DataAccessException | TransactionException readFailure) {
                // Aucun resend ici ; le prochain essai recommence par le lookup complet.
            }
            throw exception;
        }
    }

    private void insert(SicouviMovement m, Instant now) {
        int rows=jdbc.update("""
                INSERT INTO SPECIF1.SICOUVI1
                (SISTE,SICOUI,SIPROV,SICLI,SIDTEC,SIHEUC,SIDEAC,SIACFV,SIMET,SICND,
                 SIPDS,SITXCH,SITYCO,SICOT,SIREF1,SIRELO,SIREF2,SIREF3,SIREF4,CREDAT,CREHEU,MAJDAT,MAJHEU)
                VALUES (?,?,0,?,?,?,?,?,?,?,?,?,'20',?,'MYTRADING','',?,?,0,?,?,0,0)
                """,m.ste(),m.sicoui(),m.nucli(),m.executionDate(),m.executionTime(),m.executionDate(),
                m.side(),m.metal(),m.condition(),m.grams(),m.fx(),m.quotation(),m.ref2(),m.ref3(),
                SicouviMovement.date(now),SicouviMovement.time(now));
        if (rows!=1) throw new As400SyncDataException("AS400_INSERT_ROW_COUNT_INVALID");
    }

    /** N'autorise aucun nouvel INSERT apres confirmation du groupe.
     * @param group groupe durable
     * @return provisoires courants
     */
    @Override public List<Integer> provisional(As400MovementGroup group) {
        group.validate();
        var result=transactions.execute(status -> lookupGroup(group));
        if (result.isEmpty()) throw new IllegalStateException("AS400_SUBMITTED_GROUP_NOT_FOUND");
        return result;
    }

    /** Ne conclut que sur l'indicateur definitif explicite du compte concerne.
     * @param leg provisoire confirme
     * @return traitement definitif confirme
     */
    @Override public boolean settled(As400Movement leg) {
        if (leg.siprov()==null) throw new As400SyncDataException("AS400_SIPROV_MISSING");
        return transactions.execute(status -> {
            var m=leg.data();
            List<String> states=jdbc.query("""
                    SELECT ETPRO1 FROM GESCOMF.PROVISP1 WHERE STE=? AND NUPROV=? AND NUCLI=?
                    """,(rs,n)->rs.getString(1),m.ste(),leg.siprov(),m.nucli());
            if (states.size()>1) throw new As400SyncDataException("AS400_PROVISIONAL_AMBIGUOUS");
            return states.size()==1 && "O".equals(states.getFirst()==null?null:states.getFirst().trim());
        });
    }

    private List<Integer> lookupGroup(As400MovementGroup group) {
        var result=new ArrayList<Integer>();
        for (var leg:group.legs()) lookup(leg.data()).ifPresent(result::add);
        if (!result.isEmpty() && result.size()!=group.expectedLegCount())
            throw new As400SyncDataException("AS400_PARTIAL_GROUP_REQUIRES_REVIEW");
        return List.copyOf(result);
    }

    private Optional<Integer> lookup(SicouviMovement m) {
        List<Integer> rows=jdbc.query(LOOKUP,(rs,n)->{
            if (!m.side().equals(rs.getString(2).trim()) || !m.metal().equals(rs.getString(3).trim())
                    || m.grams().compareTo(rs.getBigDecimal(4))!=0
                    || m.quotation().compareTo(rs.getBigDecimal(5))!=0
                    || m.fx().compareTo(rs.getBigDecimal(6))!=0)
                throw new As400SyncDataException("AS400_MOVEMENT_DATA_MISMATCH");
            return rs.getBigDecimal(1).intValueExact();
        },m.ste(),m.sicoui(),m.nucli(),m.ref3());
        if (rows.size()>1) throw new As400SyncDataException("AS400_MOVEMENT_AMBIGUOUS");
        if (!rows.isEmpty() && (rows.getFirst()<0 || rows.getFirst()>999999))
            throw new As400SyncDataException("AS400_SIPROV_INVALID");
        return rows.stream().findFirst();
    }
}
