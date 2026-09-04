package com.saamp.trading.as400;
import java.util.UUID;
/** Événement interne rejouable. @param id identifiant @param orderId ordre @param target cible @param attemptCount tentatives @param claimToken propriétaire temporaire */
public record As400SyncEvent(long id,long orderId,As400SyncTarget target,int attemptCount,UUID claimToken) {}
