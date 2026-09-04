package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset; import java.math.BigDecimal;
/** Bloque tout UPDATE réel tant que TDMV03 n'a pas été validé. */
public class BlockedWeightAccountWriter implements As400WeightAccountWriter {
    @Override public void writeCurrentBalance(String ste,int nucli,Asset asset,BigDecimal quantityOz){throw new IllegalStateException("AS400 TDMV03 not configured");}
}
