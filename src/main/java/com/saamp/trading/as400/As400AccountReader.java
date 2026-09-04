package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.util.Map;

/** Lit les comptes historiques ou trading sans participer à une décision d'ordre. */
public interface As400AccountReader {
    /** Lit les poids et les convertit en onces troy. @param ste société @param nucli client @return soldes métal */
    Map<Asset, BigDecimal> readMetalBalances(String ste, int nucli);
    /** Lit la devise via la comptabilité en lecture seule. @param ste société @param nucli client @return solde devise */
    BigDecimal readTradingCurrencyBalance(String ste, int nucli);
}
