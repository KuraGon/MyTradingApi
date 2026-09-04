package com.saamp.trading.as400;
/** Fournit la valeur métier TDMV03 sans permettre au writer de la deviner. */
public interface Tdmv03Provider { /** @return valeur validée @throws IllegalStateException si inconnue */ String requiredValue(); }
