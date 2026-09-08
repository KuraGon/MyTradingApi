package com.saamp.trading.as400;
import java.util.UUID;

/** Claim technique ; les identites physiques sont exclusivement dans les mouvements enfants.
 * @param id evenement
 * @param orderId ordre
 * @param target cible
 * @param attemptCount erreurs rencontrees
 * @param claimToken proprietaire temporaire
 * @param state progression du groupe
 * @param ste societe client figee
 * @param nucliTrading compte trading dedie
 */
public record As400SyncEvent(long id, long orderId, As400SyncTarget target, int attemptCount,
        UUID claimToken, As400SyncState state, String ste, Integer nucliTrading) {}
