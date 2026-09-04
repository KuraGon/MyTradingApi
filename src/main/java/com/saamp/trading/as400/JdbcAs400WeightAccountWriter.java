package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcOperations;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
/** Writer DB2 strictement borné à CLCPDP03, sans insertion de ligne manquante. */
public class JdbcAs400WeightAccountWriter implements As400WeightAccountWriter {
    private final JdbcOperations jdbc; private final Tdmv03Provider tdmv03;
    public JdbcAs400WeightAccountWriter(JdbcOperations jdbc,Tdmv03Provider tdmv03){this.jdbc=jdbc;this.tdmv03=tdmv03;}
    @Override public void writeCurrentBalance(String ste,int nucli,Asset asset,BigDecimal oz){
        String movementType=tdmv03.requiredValue(); LocalDate d=LocalDate.now();
        BigDecimal grams=oz.multiply(JdbcAs400AccountReader.GRAMS_PER_TROY_OUNCE).setScale(2,RoundingMode.HALF_UP);
        String circuit=switch(asset){case XAU->"O";case XAG->"A";case XPT->"P";case XPD->"D";default->throw new IllegalArgumentException("Metal required");};
        int rows=jdbc.update("UPDATE GESCOMF.CLCPDP03 SET SOLD03=?,ADMV03=?,MDMV03=?,JDMV03=?,TDMV03=? WHERE STE=? AND NUCLI=? AND NULIV=0 AND CIRCUI=?",
                grams,d.getYear()%100,d.getMonthValue(),d.getDayOfMonth(),movementType,ste,nucli,circuit);
        if(rows!=1) throw new IllegalStateException("AS400 weight row missing or ambiguous; no INSERT performed");
    }
}
