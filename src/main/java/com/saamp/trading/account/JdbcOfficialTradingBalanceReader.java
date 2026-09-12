package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.*;
import java.sql.Connection;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/** SELECT officiels séparés du gateway d'écriture SICOUVI et de la réconciliation historique. */
@Component
public class JdbcOfficialTradingBalanceReader implements OfficialTradingBalanceReader {
    private final ObjectProvider<JdbcTemplate> source;
    private final ObjectProvider<JdbcTemplate> shadowSource;
    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("platformBalanceJdbcTemplate")
    private ObjectProvider<JdbcTemplate> diagnosticSource;
    /** @param source datasource IBM i explicite ; son absence ne signifie jamais zéro
     * @param shadowSource connexion bornee exclusivement presente en mode SHADOW */
    public JdbcOfficialTradingBalanceReader(@Qualifier("as400JdbcTemplate") ObjectProvider<JdbcTemplate> source,
            @Qualifier("shadowBalanceJdbcTemplate") ObjectProvider<JdbcTemplate> shadowSource) {
        this.source=source; this.shadowSource=shadowSource;
    }
    /** @param account compte trading @param adjustments faits CLIENT
     * @return lecture complète @throws IllegalStateException si identité, devise ou données invalides */
    @Override public Reading read(TradingAccount account, List<PendingTradingAdjustmentRepository.Adjustment> adjustments) {
        AccountService.requireMode(account, com.saamp.trading.domain.TradingMode.LIVE);
        var jdbc=shadowSource.getIfAvailable();
        if(jdbc==null) jdbc=source.getIfAvailable();
        return readUsing(jdbc,account,adjustments);
    }

    /** @param account compte LIVE @param adjustments faits CLIENT
     * @return lecture bornée dédiée au statut, sans repli sur une connexion non bornée */
    @Override public Reading readDiagnostic(TradingAccount account, List<PendingTradingAdjustmentRepository.Adjustment> adjustments) {
        AccountService.requireMode(account, com.saamp.trading.domain.TradingMode.LIVE);
        return readUsing(diagnosticSource == null ? null : diagnosticSource.getIfAvailable(),account,adjustments);
    }

    private Reading readUsing(JdbcTemplate jdbc, TradingAccount account, List<PendingTradingAdjustmentRepository.Adjustment> adjustments) {
        if(jdbc==null) throw new IllegalStateException("AS400_NOT_CONFIGURED");
        try(Connection connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setReadOnly(true);
            var read=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            read.setQueryTimeout(5);
            return query(read,account,adjustments);
        } catch(java.sql.SQLException failure) { throw new IllegalStateException("AS400_READ_FAILED"); }
    }

    static Reading query(JdbcTemplate jdbc,TradingAccount account,List<PendingTradingAdjustmentRepository.Adjustment> adjustments) {
        if(account.baseCurrency()!=Asset.EUR) throw new IllegalStateException("OFFICIAL_CURRENCY_UNSUPPORTED");
        if(!"B".equals(account.as400Ste()) || account.as400NucliTrading()==null || account.as400NucliTrading()<=0)
            throw new IllegalStateException("OFFICIAL_TRADING_IDENTITY_UNSUPPORTED");
        var identity=jdbc.queryForList("SELECT NREPCO FROM GESCOMF.CLIENOP1 WHERE STE=? AND NUCLI=? AND NULIV=0",
                Integer.class,account.as400Ste(),account.as400NucliTrading());
        if(identity.size()!=1 || !Integer.valueOf(400).equals(identity.getFirst())) throw new IllegalStateException("OFFICIAL_TRADING_IDENTITY_INVALID");
        var result=new EnumMap<Asset,BigDecimal>(Asset.class);
        for(var asset:List.of(Asset.EUR,Asset.XAU,Asset.XAG,Asset.XPT,Asset.XPD)) result.put(asset,BigDecimal.ZERO.setScale(6));
        var seen=EnumSet.noneOf(Asset.class);
        jdbc.query("SELECT CIRCUI,SOLD03 FROM GESCOMF.CLCPDP03 WHERE STE=? AND NUCLI=? AND NULIV=0 AND CIRCUI IN ('O','A','P','D')",r->{
            Asset asset=switch(r.getString("CIRCUI").trim()) { case "O"->Asset.XAU;case "A"->Asset.XAG;case "P"->Asset.XPT;case "D"->Asset.XPD;default->throw new IllegalStateException("INVALID_METAL"); };
            BigDecimal grams=r.getBigDecimal("SOLD03");
            if(!seen.add(asset) || grams==null) throw new IllegalStateException("AMBIGUOUS_METAL_BALANCE");
            result.put(asset,grams.divide(new BigDecimal("31.1034768"),6,RoundingMode.HALF_UP));
        },account.as400Ste(),account.as400NucliTrading());
        // Identité validée séparément : aucune jointure multiplicative, aucun autre NUCLI.
        jdbc.query("SELECT L1SNS,L1MTT FROM FMPRO.PCGMLFCM WHERE L1SOC='LFM' AND L1NCA=? AND L1LET='  '",r->{
            String side=r.getString("L1SNS"); BigDecimal amount=r.getBigDecimal("L1MTT");
            if(side==null || amount==null || !Set.of("C","D").contains(side.trim())) throw new IllegalStateException("UNREADABLE_CURRENCY_BALANCE");
            result.merge(Asset.EUR,"C".equals(side.trim())?amount:amount.negate(),BigDecimal::add);
        },account.as400NucliTrading());
        var posted=new LinkedHashMap<Long,Boolean>();
        for(var adjustment:adjustments) {
            boolean absorbed=false;
            if(adjustment.movementId()!=null) {
                if(!account.as400Ste().equals(adjustment.ste()) || !account.as400NucliTrading().equals(adjustment.nucli()))
                    throw new IllegalStateException("CLIENT_IDENTITY_MISMATCH");
                Integer provisional=adjustment.siprov();
                // Le worker peut être arrêté : découvrir SIPROV sans attendre ACCEPTED/SYNCED PostgreSQL.
                var rows=jdbc.queryForList("SELECT SIPROV FROM SPECIF1.SICOUVI1 WHERE SISTE=? AND SICOUI=? AND SICLI=? AND SIREF3=? AND SIREF1='MYTRADING'",
                        Integer.class,adjustment.ste(),adjustment.sicoui(),adjustment.nucli(),adjustment.exid());
                if(rows.size()>1 || rows.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("AMBIGUOUS_CLIENT_CORRELATION");
                if(!rows.isEmpty() && (rows.getFirst()<0 || provisional!=null && !provisional.equals(rows.getFirst())))
                    throw new IllegalStateException("CLIENT_SIPROV_CHANGED");
                if(!rows.isEmpty() && rows.getFirst()>0) {
                    if(provisional!=null && !provisional.equals(rows.getFirst())) throw new IllegalStateException("CLIENT_SIPROV_CHANGED");
                    provisional=rows.getFirst();
                }
                if(provisional!=null) {
                    var states=jdbc.queryForList("SELECT ETPRO1 FROM GESCOMF.PROVISP1 WHERE STE=? AND NUCLI=? AND NUPROV=?",
                            String.class,adjustment.ste(),adjustment.nucli(),provisional);
                    if(states.size()!=1 || states.getFirst()==null) throw new IllegalStateException("CLIENT_PROVISIONAL_UNAVAILABLE");
                    absorbed="O".equals(states.getFirst().trim());
                }
            }
            if(posted.put(adjustment.orderId(),absorbed)!=null) throw new IllegalStateException("DUPLICATE_CLIENT_ADJUSTMENT");
        }
        return new Reading(result,posted);
    }
}
