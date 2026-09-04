package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
/** Projection PostgreSQL nécessaire au reflet poids. */
record As400SyncContext(String ste,Integer nucliTrading,Asset asset,BigDecimal balance) {}
