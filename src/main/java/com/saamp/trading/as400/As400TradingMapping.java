package com.saamp.trading.as400;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Centralise les comptes comptables valides, sans les confondre avec le compte client. */
@Component
record As400TradingMapping(
        @Value("${trading.as400.lfmp-company:B}") String lfmpCompany,
        @Value("${trading.as400.saamp-company:I}") String saampCompany,
        @Value("${trading.as400.lfmp-saamp-interco-nucli:15001}") int lfmpSaampNucli,
        @Value("${trading.as400.saamp-lfmp-interco-nucli:15002}") int saampLfmpNucli,
        @Value("${trading.as400.stonex-eur-nucli:15267}") int stonexEurNucli,
        @Value("${trading.as400.stonex-usd-nucli:15268}") int stonexUsdNucli) {
    int stonexNucli(String currency) {
        return switch(currency) {
            case "EUR" -> stonexEurNucli;
            case "USD" -> stonexUsdNucli;
            default -> throw new As400SyncDataException("AS400_CURRENCY_UNSUPPORTED");
        };
    }
}
