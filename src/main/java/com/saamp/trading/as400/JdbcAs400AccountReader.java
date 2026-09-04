package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcOperations;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/** Implémentation DB2 for i limitée aux fichiers physiques validés. */
public class JdbcAs400AccountReader implements As400AccountReader {
    static final BigDecimal GRAMS_PER_TROY_OUNCE=new BigDecimal("31.1034768");
    private final JdbcOperations jdbc;
    public JdbcAs400AccountReader(JdbcOperations jdbc) { this.jdbc=jdbc; }

    @Override public Map<Asset,BigDecimal> readMetalBalances(String ste,int nucli) {
        var result=new EnumMap<Asset,BigDecimal>(Asset.class);
        jdbc.query("SELECT CIRCUI,SOLD03 FROM GESCOMF.CLCPDP03 WHERE STE=? AND NUCLI=? AND NULIV=0 AND CIRCUI IN ('O','A','P','D')",
                rs -> { String code=rs.getString("CIRCUI").trim(); Asset asset=switch(code){case "O"->Asset.XAU;case "A"->Asset.XAG;case "P"->Asset.XPT;case "D"->Asset.XPD;default->null;};
                    if(asset!=null) result.put(asset,rs.getBigDecimal("SOLD03").divide(GRAMS_PER_TROY_OUNCE,6,RoundingMode.HALF_UP)); },ste,nucli);
        return result;
    }

    @Override public BigDecimal readTradingCurrencyBalance(String ste,int nucli) {
        BigDecimal balance = jdbc.queryForObject("""
                SELECT SUM(
                    CASE
                        WHEN l.L1SNS = 'D' THEN l.L1MTT
                        ELSE -l.L1MTT
                    END
                ) AS SOLDE
                FROM FMPRO.PCGMLFCM l
                JOIN GESCOMF.PARSOCP1 s
                  ON s.STECPT = l.L1SOC
                WHERE s.STE   = ?
                  AND l.L1NCA = ?
                  AND l.L1NCG IN (401600, 411600)
                  AND l.L1ETA = 1
                  AND l.L1TYP = 1
                """, BigDecimal.class, ste, nucli);
        return balance == null ? BigDecimal.ZERO : balance;
    }
}
