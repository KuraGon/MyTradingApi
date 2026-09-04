package com.saamp.trading.as400;
import com.saamp.trading.account.*; import com.saamp.trading.domain.Asset;
import java.math.BigDecimal; import java.util.*;
/** Compare le NUCLI trading à PostgreSQL sans modifier aucun des deux systèmes. */
public class As400ReconciliationService {
 static final BigDecimal AS400_MAX_ROUNDING_ERROR_GRAMS=new BigDecimal("0.005");
 static final BigDecimal AS400_MAX_ROUNDING_ERROR_OZ=AS400_MAX_ROUNDING_ERROR_GRAMS.divide(JdbcAs400AccountReader.GRAMS_PER_TROY_OUNCE,18,java.math.RoundingMode.HALF_UP);
 private final BalanceRepository balances; private final As400AccountReader reader;
 public As400ReconciliationService(BalanceRepository balances,As400AccountReader reader){this.balances=balances;this.reader=reader;}
 /** Produit le diagnostic métaux; le centigramme AS400 induit une tolérance structurelle de conversion. @param account compte @return écarts */
 public List<As400ReconciliationResult> reconcileMetals(TradingAccount account){if(account.as400Ste()==null||account.as400NucliTrading()==null)throw new IllegalStateException("AS400 trading account mapping not configured");var external=reader.readMetalBalances(account.as400Ste(),account.as400NucliTrading());var result=new ArrayList<As400ReconciliationResult>();for(Asset asset:Asset.metals()){BigDecimal pg=balances.find(account.id(),asset).map(Balance::quantity).orElse(BigDecimal.ZERO);BigDecimal as=external.getOrDefault(asset,BigDecimal.ZERO);BigDecimal delta=pg.subtract(as);result.add(new As400ReconciliationResult(asset,pg,as,delta,delta.abs().compareTo(AS400_MAX_ROUNDING_ERROR_OZ)<=0));}return result;}
}
