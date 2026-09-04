package com.saamp.trading.as400;
import org.springframework.stereotype.Component;
/** Conserve bruyamment les événements jusqu'à validation du contrat Patrick. */
@Component public class UnconfiguredAccountingIntegration implements AccountingIntegrationPort {
    @Override public void synchronize(long orderId){throw new IllegalStateException("AS400 accounting integration contract not configured");}
}
