package com.saamp.trading.as400;
import java.util.UUID;
/** Identité persistée avant tout accès AS400.
 * @param id événement
 * @param orderId ordre
 * @param target cible
 * @param attemptCount erreurs rencontrées
 * @param claimToken propriétaire temporaire
 * @param state progression métier
 * @param sicoui identifiant cyclique du mouvement
 * @param siprov provisoire attribué par AS400
 * @param ste société sans transformation de casse
 * @param nucliTrading compte trading dédié figé à la création
 */
public record As400SyncEvent(long id, long orderId, As400SyncTarget target, int attemptCount,
        UUID claimToken, As400SyncState state, int sicoui, Integer siprov, String ste, Integer nucliTrading) {}
