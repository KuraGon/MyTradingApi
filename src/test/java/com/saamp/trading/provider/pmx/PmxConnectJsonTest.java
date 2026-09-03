package com.saamp.trading.provider.pmx;

import com.saamp.trading.domain.Asset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PmxConnectJsonTest {

    private final PmxConnectJson json = new PmxConnectJson();

    @Test
    void readsMetalSpotRatesWithInitialCapitalFields() {
        String payload = """
                {
                  "result": [
                    { "Pair": "XAUEUR", "Ask": 3826.856, "Bid": 3825.493 },
                    { "Pair": "XAGEUR", "Ask": 57.09196, "Bid": 56.98084 },
                    { "Pair": "XPTEUR", "Ask": 1551.083, "Bid": 1543.968 },
                    { "Pair": "XPDEUR", "Ask": 1145.123, "Bid": 1137.243 }
                  ]
                }
                """;
        OffsetDateTime asOf = OffsetDateTime.parse("2026-08-20T12:00:00Z");

        var quotes = json.readSpotRates(payload, asOf);

        assertThat(quotes).hasSize(4);
        assertThat(quotes.get(0).pair()).isEqualTo("XAUEUR");
        assertThat(quotes.get(0).ask()).isEqualByComparingTo("3826.856");
        assertThat(quotes.get(0).bid()).isEqualByComparingTo("3825.493");
        assertThat(quotes.get(1).ask()).isEqualByComparingTo("57.09196");
        assertThat(quotes).allSatisfy(q -> assertThat(q.asOf()).isEqualTo(asOf));
    }

    @Test
    void readsFxSpotRatesWithUppercaseFieldsWithoutPrecisionLoss() {
        String payload = """
                {
                  "result": [
                    { "PAIR": "EURUSD", "ASK": 1.16953, "BID": 1.16828 },
                    { "PAIR": "USDCHF", "ASK": 0.79805, "BID": 0.798 }
                  ]
                }
                """;
        OffsetDateTime asOf = OffsetDateTime.parse("2026-08-20T12:00:00Z");

        var quotes = json.readSpotRates(payload, asOf);

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).pair()).isEqualTo("EURUSD");
        assertThat(quotes.get(0).ask()).isEqualByComparingTo(new BigDecimal("1.16953"));
        assertThat(quotes.get(0).bid()).isEqualByComparingTo(new BigDecimal("1.16828"));
        assertThat(quotes.get(1).bid()).isEqualByComparingTo(new BigDecimal("0.798"));
    }

    @Test
    void readsObservedPositionsAndKeepsProviderAccountCodeDistinctFromTokenClientId() {
        String payload = """
                {
                  "result": [
                    { "AccountCode": "MT0184", "Cmdty": "XAU", "Position": 962.801 },
                    { "AccountCode": "MT0184", "Cmdty": "XAG", "Position": -28654.549 },
                    { "AccountCode": "MT0184", "Cmdty": "XPT", "Position": -9714.855 },
                    { "AccountCode": "MT0184", "Cmdty": "XPD", "Position": -371.527 },
                    { "AccountCode": "MT0184", "Cmdty": "EUR", "Position": 18913347.2 },
                    { "AccountCode": "MT0184", "Cmdty": "USD", "Position": 2442712.34 }
                  ]
                }
                """;

        var positions = json.readPositions(payload);

        assertThat(positions).hasSize(6);
        assertThat(positions).allSatisfy(p -> assertThat(p.accountCode()).isEqualTo("MT0184"));
        assertThat(positions.get(0).asset()).isEqualTo(Asset.XAU);
        assertThat(positions.get(0).quantity()).isEqualByComparingTo("962.801");
        assertThat(positions.get(5).asset()).isEqualTo(Asset.USD);
        assertThat(positions.get(5).quantity()).isEqualByComparingTo("2442712.34");
    }

    @Test
    void readsObservedRequestStatusError631Exactly() {
        String payload = """
                {
                  "status": 400,
                  "error": "Bad Request",
                  "error_code": 631,
                  "error_msg": "Client Order ID does not exist.",
                  "message": "631 - Client Order ID does not exist.",
                  "path": "/v1_3/GetRequestStatus"
                }
                """;

        var error = json.readError(400, payload);

        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.errorCode()).isEqualTo(631);
        assertThat(error.errorMessage()).isEqualTo("Client Order ID does not exist.");
    }
}
