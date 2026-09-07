package com.saamp.trading.as400;

import java.util.Optional;

/** Isole le protocole SICOUVI du stockage PostgreSQL et permet les tests sans AS400. */
interface As400MovementGateway {
    /** Confirme une insertion ou retrouve le même mouvement après une réponse perdue.
     * @param movement mouvement client
     * @return SIPROV courant, zéro tant que non attribué
     */
    int submit(SicouviMovement movement);
    /** Relit la corrélation complète sans jamais recréer un mouvement soumis.
     * @param event identité persistée
     * @return provisoire courant, vide si la ligne manque
     */
    Optional<Integer> provisional(As400SyncEvent event);
    /** Attend exclusivement ETPRO1, jamais ETPRO2.
     * @param event identité et provisoire persistés
     * @return vrai si le traitement définitif est confirmé
     */
    boolean settled(As400SyncEvent event);
}
