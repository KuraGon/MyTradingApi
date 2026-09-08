package com.saamp.trading.as400;

import java.util.List;

/** Isole la transaction DB2 du groupe et son read-back sans dependance au ledger. */
interface As400MovementGateway {
    /** Confirme le groupe complet ; ne repare jamais un groupe partiel.
     * @param group identites et donnees deja commitees dans PostgreSQL
     * @return SIPROV dans l'ordre des jambes
     */
    List<Integer> submit(As400MovementGroup group);
    /** Relit toutes les jambes sans autoriser de nouvel INSERT.
     * @param group groupe durable
     * @return SIPROV dans l'ordre des jambes
     */
    List<Integer> provisional(As400MovementGroup group);
    /** Attend exclusivement ETPRO1, jamais ETPRO2.
     * @param leg identite et provisoire persistants
     * @return vrai si le traitement definitif est confirme
     */
    boolean settled(As400Movement leg);
}
