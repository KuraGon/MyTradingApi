package com.saamp.trading.as400;

/** Refuse une donnée impossible sans remettre en cause le dénouement PostgreSQL. */
public class As400SyncDataException extends RuntimeException {
    /** Conserve un code exploitable sans exposer de données de connexion.
     * @param code cause métier stable
     */
    public As400SyncDataException(String code) { super(code); }
}
