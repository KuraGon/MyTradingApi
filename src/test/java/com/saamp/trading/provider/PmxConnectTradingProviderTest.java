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
import java.time.Duration;
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
    void refusesStartupWithExpiredToken() {
        long exp = Instant.now().minusSeconds(60).getEpochSecond();
        properties.getProvider().getPmx().setTokenId(jwt("{\"alg\":\"none\"}",
                "{\"ClientId\":\"S0011\",\"env\":\"prod\",\"exp\":" + exp + "}"));

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiré");
    }

    @Test
    void refusesStartupWithoutTokenOrExpiryClaim() {
        properties.getProvider().getPmx().setTokenId(" ");
        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_ID");

        properties.getProvider().getPmx().setTokenId(jwt("{\"alg\":\"none\"}",
                "{\"ClientId\":\"S0011\",\"env\":\"prod\"}"));
        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exp");
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

    @Test
    void unknownRequestedPairReturnsNoQuote() {
        spotContexts("{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":100.1,\"Bid\":100.0}]}",
                "{\"result\":[{\"PAIR\":\"EURUSD\",\"ASK\":1.12,\"BID\":1.11}]}");
        server.start();

        var quotes = new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of("UNKNOWN"));

        assertThat(quotes).isEmpty();
    }

    @Test
    void invalidSpotResponseFailsExplicitly() {
        spotContexts("not-json", "{\"result\":[]}");
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illisible");
    }

    @Test
    void missingSpotValueFailsExplicitly() {
        spotContexts("{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":100.1}]}", "{\"result\":[]}");
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplète");
    }

    @Test
    void spotHttp400RemainsStructuredProviderFailure() {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 400,
                "{\"status\":400,\"error_code\":699,\"message\":\"Bad request\"}"));
        server.start();
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThatThrownBy(() -> provider.fetchSpotRates(Set.of()))
                .isInstanceOfSatisfying(PmxConnectException.class, error -> {
                    assertThat(error.httpStatus()).isEqualTo(400);
                    assertThat(error.errorCode()).isEqualTo(699);
                });
    }

    @Test
    void spotHttp500RemainsStructuredProviderFailure() {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 500,
                "{\"status\":500,\"message\":\"Unavailable\"}"));
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of()))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(500));
    }

    @Test
    void requestTimeoutRemainsAProviderFailureWithoutClosingGate() {
        properties.getProvider().getPmx().setRequestTimeout(Duration.ofMillis(50));
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"result\":[]}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of()))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isZero());
        verify(gate, never()).close(anyString());
    }

    @Test
    void requestStatusMapsProcessedInProcessAndFailed() {
        var responses = new java.util.ArrayDeque<>(java.util.List.of(
                "{\"Status\":\"Processed\",\"ClOrdId\":\"SAAMP-A\",\"ExID\":\"EX-1\",\"FillPrice\":100.25}",
                "{\"Status\":\"InProcess\",\"ClOrdId\":\"SAAMP-B\"}",
                "{\"Status\":\"Failed\",\"ClOrdId\":\"SAAMP-C\",\"ErrorCode\":\"700\"}"));
        server.createContext("/v1_3/GetRequestStatus", exchange -> respond(exchange, 200, responses.removeFirst()));
        server.start();
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThat(provider.queryRequestStatus("SAAMP-A").orElseThrow().state()).isEqualTo(ExecutionState.PROCESSED);
        assertThat(provider.queryRequestStatus("SAAMP-B").orElseThrow().state()).isEqualTo(ExecutionState.IN_PROCESS);
        assertThat(provider.queryRequestStatus("SAAMP-C").orElseThrow().state()).isEqualTo(ExecutionState.FAILED);
    }

    @Test
    void requestStatus401And500RemainFailuresAndOnlyAuthClosesGate() {
        var statuses = new java.util.ArrayDeque<>(java.util.List.of(401, 500));
        server.createContext("/v1_3/GetRequestStatus", exchange -> {
            int status = statuses.removeFirst();
            respond(exchange, status, "{\"status\":" + status + ",\"error_code\":561,\"message\":\"Failure\"}");
        });
        server.start();
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThatThrownBy(() -> provider.queryRequestStatus("SAAMP-A"))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(401));
        assertThatThrownBy(() -> provider.queryRequestStatus("SAAMP-B"))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(500));
        verify(gate, times(1)).close("PMXCONNECT_AUTH_FAILURE");
    }

    @Test
    void requestStatus403ClosesExecutionGate() {
        server.createContext("/v1_3/GetRequestStatus", exchange -> respond(exchange, 403,
                "{\"status\":403,\"error_code\":561,\"message\":\"Forbidden\"}"));
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .queryRequestStatus("SAAMP-A"))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(403));
        verify(gate).close("PMXCONNECT_AUTH_FAILURE");
    }

    @Test
    void requestStatusTimeoutRemainsIndeterminate() {
        properties.getProvider().getPmx().setRequestTimeout(Duration.ofMillis(50));
        server.createContext("/v1_3/GetRequestStatus", exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"Status\":\"Processed\"}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .queryRequestStatus("SAAMP-A"))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isZero());
        verify(gate, never()).close(anyString());
    }

    @Test
    void requestStatusNetworkFailureRemainsIndeterminate() {
        server.start();
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> provider.queryRequestStatus("SAAMP-A"))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isZero());
        verify(gate, never()).close(anyString());
    }

    @Test
    void invalidRequestStatusResponseFailsClosed() {
        server.createContext("/v1_3/GetRequestStatus", exchange -> respond(exchange, 200, "{\"result\":{}}"));
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .queryRequestStatus("SAAMP-A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aucun statut");
    }

    @Test
    void tradeSubmissionRemainsDisabledWithoutOfficialContract() {
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThat(provider.supportsSpotOrderSubmission()).isFalse();
        assertThatThrownBy(() -> provider.submitSpotOrder(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UAT");
    }

    private void spotContexts(String metals, String fx) {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 200, metals));
        server.createContext("/v1_3/GetSpotRates/FOR", exchange -> respond(exchange, 200, fx));
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
