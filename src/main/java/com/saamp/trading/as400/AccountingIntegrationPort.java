package com.saamp.trading.as400;
/** Frontière du futur programme d'intégration comptable, sans accès direct à PCGMLFCM. */
public interface AccountingIntegrationPort { /** Intègre la pièce d'un ordre. @param orderId ordre */ void synchronize(long orderId); }
