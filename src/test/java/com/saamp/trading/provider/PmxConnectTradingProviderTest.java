package com.saamp.trading.provider;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.order.ExecutionGateRepository;
import com.saamp.trading.provider.pmx.PmxConnectException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PmxConnectTradingProviderTest {

    private HttpServer server;
    private ExecutionGateRepository gate;
    private ProviderAccountRepository providerAccounts;
    private TradingProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        gate = mock(ExecutionGateRepository.class);
        providerAccounts = mock(ProviderAccountRepository.class);
        properties = new TradingProperties();
        var pmx = properties.getProvider().getPmx();
        pmx.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        pmx.setEnvironment("prod");
        long exp = Instant.now().plusSeconds(86400 * 90L).getEpochSecond();
        pmx.setTokenId(jwt("{\"alg\":\"none\"}",
                "{\"ClientId\":\"S0011\",\"env\":\"prod\",\"exp\":" + exp + "}"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void mergesMetalAndFxEndpointsWithOneReferenceTimestamp() {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 200,
                "{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":3826.856,\"Bid\":3825.493}]}"));
        server.createContext("/v1_3/GetSpotRates/FOR", exchange -> respond(exchange, 200,
                "{\"result\":[{\"PAIR\":\"EURUSD\",\"ASK\":1.16953,\"BID\":1.16828}]}"));
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        var quotes = provider.fetchSpotRates(Set.of("XAUEUR", "EURUSD"));

        assertThat(quotes).hasSize(2);
        assertThat(quotes).extracting(MarketQuote::pair).containsExactly("XAUEUR", "EURUSD");
        assertThat(quotes.get(0).asOf()).isEqualTo(quotes.get(1).asOf());
    }

    @Test
    void error631IsTheOnlyHttp400ResolvedAsDefinitiveFailure() {
        server.createContext("/v1_3/GetRequestStatus", exchange -> respond(exchange, 400,
                "{\"status\":400,\"error_code\":631,\"error_msg\":\"Client Order ID does not exist.\",\"message\":\"631 - Client Order ID does not exist.\"}"));
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        var report = provider.queryRequestStatus("SAAMP-ABC").orElseThrow();

        assertThat(report.state()).isEqualTo(ExecutionState.FAILED);
        assertThat(report.errorCode()).isEqualTo("631");
        verify(gate, never()).close(anyString());
    }


    @Test
    void otherHttp400RemainsIndeterminateAndIsNotConvertedToRejected() {
        server.createContext("/v1_3/GetRequestStatus", exchange -> respond(exchange, 400,
                "{\"status\":400,\"error_code\":699,\"message\":\"Other bad request\"}"));
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThatThrownBy(() -> provider.queryRequestStatus("SAAMP-ABC"))
                .isInstanceOf(PmxConnectException.class)
                .satisfies(error -> assertThat(((PmxConnectException) error).errorCode()).isEqualTo(699));
        verify(gate, never()).close(anyString());
    }

    @Test
    void refusesStartupWhenTokenEnvironmentDoesNotMatchConfiguredEnvironment() {
        properties.getProvider().getPmx().setEnvironment("uat");

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void positionsPersistObservedAccountCodeSeparatelyFromTokenClientId() {
        server.createContext("/v1_3/GetCmdtyPositions", exchange -> respond(exchange, 200,
                "{\"result\":[{\"AccountCode\":\"MT0184\",\"Cmdty\":\"EUR\",\"Position\":18913347.2},{\"AccountCode\":\"MT0184\",\"Cmdty\":\"USD\",\"Position\":2442712.34}]}"));
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        var positions = provider.fetchPositions();

        assertThat(positions).hasSize(2);
        assertThat(positions.get(0).accountCode()).isEqualTo("MT0184");
        assertThat(positions.get(0).asset()).isEqualTo(Asset.EUR);
        verify(providerAccounts).observe("PMXCONNECT", "MT0184", "S0011");
    }

    @Test
    void authenticationFailureClosesExecutionGate() {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 401,
                "{\"status\":401,\"error_code\":561,\"message\":\"Invalid token environment\"}"));
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        try {
            provider.fetchSpotRates(Set.of("XAUEUR"));
        } catch (RuntimeException ignored) {
        }

        verify(gate).close("PMXCONNECT_AUTH_FAILURE");
    }

    private static String jwt(String header, String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
