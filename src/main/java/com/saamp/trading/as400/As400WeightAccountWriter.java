package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
/** Écrit exclusivement le reflet poids du NUCLI trading. */
public interface As400WeightAccountWriter {
    /** Remplace le solde AS400 par la projection PostgreSQL. @param ste société @param nucliTrading compte trading @param asset métal @param quantityOz solde courant */
    void writeCurrentBalance(String ste,int nucliTrading,Asset asset,BigDecimal quantityOz);
}
