package com.saamp.trading.provider;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import com.saamp.trading.domain.TradingMode;
import com.saamp.trading.common.TradingException;
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
import java.math.BigDecimal;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    void requestedRuntimePairsUseMySaampSpcWithTokenBidAskAndOneTimestamp() {
        Map<String, AtomicReference<String>> tokenHeaders = new java.util.LinkedHashMap<>();
        Map<String, String> payloads = Map.of(
                "XAUEUR", "{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":3826.856,\"Bid\":3825.493}]}",
                "XAGEUR", "{\"result\":[{\"Pair\":\"XAGEUR\",\"Ask\":42.856,\"Bid\":42.493}]}",
                "XPTEUR", "{\"result\":[{\"Pair\":\"XPTEUR\",\"Ask\":1000.856,\"Bid\":1000.493}]}",
                "XPDEUR", "{\"result\":[{\"Pair\":\"XPDEUR\",\"Ask\":900.856,\"Bid\":900.493}]}",
                "EURUSD", "{\"result\":[{\"Pair\":\"EURUSD\",\"Ask\":1.16953,\"Bid\":1.16828}]}");
        payloads.forEach((pair, payload) -> {
            AtomicReference<String> tokenHeader = new AtomicReference<>();
            tokenHeaders.put(pair, tokenHeader);
            server.createContext("/v1_3/GetSpotRates/SPC/" + pair, exchange -> {
                tokenHeader.set(exchange.getRequestHeaders().getFirst("TokenID"));
                respond(exchange, 200, payload);
            });
        });
        server.start();

        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        var quotes = provider.fetchSpotRates(payloads.keySet());

        assertThat(quotes).hasSize(5);
        assertThat(quotes).extracting(MarketQuote::pair).containsExactly("EURUSD", "XAGEUR", "XAUEUR", "XPDEUR", "XPTEUR");
        assertThat(quotes).extracting(MarketQuote::bid).contains(new BigDecimal("3825.493"), new BigDecimal("1.16828"));
        assertThat(quotes).extracting(MarketQuote::ask).contains(new BigDecimal("3826.856"), new BigDecimal("1.16953"));
        assertThat(quotes).extracting(MarketQuote::asOf)
                .allSatisfy(asOf -> assertThat(asOf).isEqualTo(quotes.getFirst().asOf()));
        assertThat(tokenHeaders.values()).allSatisfy(header -> assertThat(header.get()).isNotBlank());
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
        server.createContext("/v1_3/GetSpotRates/SPC/XAUEUR", exchange -> respond(exchange, 401,
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
    void emptyRuntimePairsCannotSelectLegacyBulkPricing() {
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);
        assertThatThrownBy(() -> provider.fetchSpotRates(Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit pairs");
        assertThatThrownBy(() -> provider.fetchSpotRates(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit pairs");
    }

    @Test
    void unknownRequestedPairReturnsNoQuote() {
        server.createContext("/v1_3/GetSpotRates/SPC/UNKNOWN", exchange -> respond(exchange, 200,
                "{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":100.1,\"Bid\":100.0}]}"));
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
                .fetchLegacyBulkSpotRates())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illisible");
    }

    @Test
    void missingSpotValueFailsExplicitly() {
        spotContexts("{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":100.1}]}", "{\"result\":[]}");
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchLegacyBulkSpotRates())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplète");
    }

    @Test
    void spotHttp400RemainsStructuredProviderFailure() {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 400,
                "{\"status\":400,\"error_code\":699,\"message\":\"Bad request\"}"));
        server.start();
        var provider = new PmxConnectTradingProvider(properties, gate, providerAccounts);

        assertThatThrownBy(() -> provider.fetchLegacyBulkSpotRates())
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
                .fetchLegacyBulkSpotRates())
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
                .fetchLegacyBulkSpotRates())
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isZero());
        verify(gate, never()).close(anyString());
    }

    @Test
    void spcHttpFailureRemainsAProviderFailure() {
        server.createContext("/v1_3/GetSpotRates/SPC/XAUEUR", exchange -> respond(exchange, 500,
                "{\"status\":500,\"message\":\"Unavailable\"}"));
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .fetchSpotRates(Set.of("XAUEUR")))
                .isInstanceOfSatisfying(PmxConnectException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(500));
        verify(gate, never()).close(anyString());
    }

    @Test
    void spcTimeoutRemainsAProviderFailureWithoutClosingGate() {
        properties.getProvider().getPmx().setRequestTimeout(Duration.ofMillis(50));
        server.createContext("/v1_3/GetSpotRates/SPC/XAUEUR", exchange -> {
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
                .fetchSpotRates(Set.of("XAUEUR")))
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
    void spotRateUsesMySaampSpcEndpoint() {
        server.createContext("/v1_3/GetSpotRates/SPC/XAUEUR", exchange -> respond(exchange, 200,
                "{\"result\":[{\"Pair\":\"XAUEUR\",\"Ask\":3826.856,\"Bid\":3825.493}]}"));
        server.start();

        var rate = new PmxConnectTradingProvider(properties, gate, providerAccounts).fetchSpotRate("xaueur");

        assertThat(rate).isPresent();
        assertThat(rate.orElseThrow().pair()).isEqualTo("XAUEUR");
    }

    @Test
    void tradeBuyUsesMySaampPayloadAndPreservesExidRateAndQuantity() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> tokenHeader = new AtomicReference<>();
        server.createContext("/v1_3/Trade", exchange -> {
            method.set(exchange.getRequestMethod());
            tokenHeader.set(exchange.getRequestHeaders().getFirst("TokenID"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"result\":{\"Quantity\":1.250000,\"Rate\":3826.856,\"EXID\":\"EXID-42\"}}");
        });
        server.start();

        var acknowledgement = new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .submitSpotOrder(new SpotOrderRequest("CL-42", "XAUEUR", OrderSide.BUY, new BigDecimal("1.250000")));

        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
        assertThat(method.get()).isEqualTo("POST");
        assertThat(tokenHeader.get()).isNotBlank();
        assertThat(payload.get("ClOrdId").asText()).isEqualTo("CL-42");
        assertThat(payload.get("Pair").asText()).isEqualTo("XAUEUR");
        assertThat(payload.get("Deal").asInt()).isEqualTo(1);
        assertThat(payload.get("Quantity").decimalValue()).isEqualByComparingTo("1.250000");
        assertThat(payload.get("Remarks").asText()).isEqualTo("MyTrading CL-42");
        assertThat(acknowledgement.state()).isEqualTo(AcknowledgementState.FILLED);
        assertThat(acknowledgement.executionId()).isEqualTo("EXID-42");
        assertThat(acknowledgement.rate()).isEqualByComparingTo("3826.856");
    }

    @Test
    void tradeSellMapsDealTwoAndUsesCanonicalQuantityOz() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1_3/Trade", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"result\":{\"Quantity\":2.5,\"Rate\":40.2,\"EXID\":\"EXID-SELL\"}}");
        });
        server.start();

        new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .submitSpotOrder(new SpotOrderRequest("CL-SELL", "XAGEUR", OrderSide.SELL, new BigDecimal("2.5"), "demo-safe"));

        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
        assertThat(payload.get("Deal").asInt()).isEqualTo(2);
        assertThat(payload.get("Quantity").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(payload.get("Remarks").asText()).isEqualTo("demo-safe");
    }

    @Test
    void ambiguousTradeResponseFailsClosedOnQuantityMismatch() {
        server.createContext("/v1_3/Trade", exchange -> respond(exchange, 200,
                "{\"result\":{\"Quantity\":2,\"Rate\":3826.856,\"EXID\":\"EXID-42\"}}"));
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .submitSpotOrder(new SpotOrderRequest("CL-42", "XAUEUR", OrderSide.BUY, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantité incohérente");
    }

    @Test
    void tradeTimeoutDoesNotRetryThePost() {
        properties.getProvider().getPmx().setRequestTimeout(Duration.ofMillis(50));
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1_3/Trade", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"result\":{\"Quantity\":1,\"Rate\":1,\"EXID\":\"late\"}}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .submitSpotOrder(new SpotOrderRequest("CL-timeout", "XAUEUR", OrderSide.BUY, BigDecimal.ONE)))
                .isInstanceOf(PmxConnectException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void demoJwtCannotReachTradeEvenIfPmxConnectIsConfigured() {
        properties.getDemo().setEnabled(true);
        Jwt jwt = new Jwt("demo", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
                Map.of("sub", "10", "companyId", 1L, "tradingMode", "DEMO", "identityType", "INTERNAL", "accessMode", "INTERNAL_DEMO"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                    .submitSpotOrder(new SpotOrderRequest("CL-demo", "XAUEUR", OrderSide.BUY, BigDecimal.ONE)))
                    .isInstanceOfSatisfying(TradingException.class,
                            error -> assertThat(error.getCode()).isEqualTo("DEMO_REAL_PROVIDER_FORBIDDEN"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void explicitDemoRequestIsRejectedBeforeTradeWithoutAnySecurityContext() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> new PmxConnectTradingProvider(properties, gate, providerAccounts)
                .submitSpotOrder(new SpotOrderRequest("CL-demo-explicit", "XAUEUR", OrderSide.BUY,
                        BigDecimal.ONE, null, TradingMode.DEMO)))
                .isInstanceOfSatisfying(TradingException.class,
                        error -> assertThat(error.getCode()).isEqualTo("DEMO_REAL_PROVIDER_FORBIDDEN"));
    }

    private void spotContexts(String metals, String fx) {
        server.createContext("/v1_3/GetSpotRates/MTL", exchange -> respond(exchange, 200, metals));
        server.createContext("/v1_3/GetSpotRates/FOR", exchange -> respond(exchange, 200, fx));
    }

    @Test
    void demoContextCanReadSpcButExecutionRouterSelectsOnlyTheSimulator() throws Exception {
        properties.getDemo().setEnabled(true);
        properties.getProvider().setMode("PMXCONNECT");
        var production=new org.springframework.mock.env.MockEnvironment();
        production.setActiveProfiles("prod");
        server.createContext("/v1_3/GetSpotRates/SPC/XAUEUR", exchange -> respond(exchange,200,
                "{\"result\":[{\"Pair\":\"XAUEUR\",\"Bid\":100,\"Ask\":101}]}"));
        AtomicInteger tradeCalls=new AtomicInteger();
        server.createContext("/v1_3/Trade", exchange -> { tradeCalls.incrementAndGet(); respond(exchange,500,"{}"); });
        server.start();
        var pmx=new PmxConnectTradingProvider(properties,gate,providerAccounts,
                new com.saamp.trading.security.TradingDemoGuard(properties,production));
        var prices=org.mockito.Mockito.mock(com.saamp.trading.pricing.PricingRepository.class);
        org.mockito.Mockito.when(prices.findMarketPrice("XAUEUR")).thenReturn(java.util.Optional.of(
                new com.saamp.trading.pricing.MarketPrice("XAUEUR",new BigDecimal("100"),new BigDecimal("101"),
                        new BigDecimal("100.5"),java.time.OffsetDateTime.now(),"PMXCONNECT",java.time.OffsetDateTime.now())));
        var simulator=new SimulatedTradingProvider(prices);
        var router=new TradingExecutionProviderRouter(pmx,simulator);
        Jwt jwt=new Jwt("fixture",Instant.now(),Instant.now().plusSeconds(60),Map.of("alg","none"),
                Map.of("sub","99","companyId",2L,"tradingMode","DEMO","accessMode","INTERNAL_DEMO","identityType","INTERNAL"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            assertThat(pmx.fetchSpotRates(java.util.Set.of("XAUEUR"))).hasSize(1);
            assertThat(router.forMode(TradingMode.LIVE)).isSameAs(pmx);
            assertThat(router.forMode(TradingMode.DEMO)).isSameAs(simulator);
            var ack=router.forMode(TradingMode.DEMO).submitSpotOrder(
                    new SpotOrderRequest("DEMO-READ-ONLY","XAUEUR",OrderSide.BUY,BigDecimal.ONE,null,TradingMode.DEMO));
            assertThat(ack.executionId()).startsWith("SIM-");
            assertThat(ack.rate()).isEqualByComparingTo("101");
            assertThat(tradeCalls).hasValue(0);
        } finally { SecurityContextHolder.clearContext(); }
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
