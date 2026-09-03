package com.saamp.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saamp.trading.account.*;
import com.saamp.trading.common.ApiExceptionHandler;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.SecurityConfig;
import com.saamp.trading.domain.*;
import com.saamp.trading.order.OrderExecutionService;
import com.saamp.trading.order.OrderRepository;
import com.saamp.trading.order.TradingOrder;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.RiskResult;
import com.saamp.trading.risk.RiskService;
import com.saamp.trading.security.CurrentTrader;
import com.saamp.trading.security.CurrentTraderService;
import com.saamp.trading.statement.AccountStatement;
import com.saamp.trading.statement.StatementLine;
import com.saamp.trading.statement.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AccountController.class, OrderController.class, StatementController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class ClientConsultationControllerTest {

    private static final long ACCOUNT_ID = 10L;
    private static final long COMPANY_ID = 42L;
    private static final String ACCOUNT_READ = "MYTRADING_ACCOUNT_READ";
    private static final String HISTORY_READ = "MYTRADING_HISTORY_READ";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-03T10:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private CurrentTraderService traders;
    @MockitoBean
    private AccountService accounts;
    @MockitoBean
    private BalanceRepository balances;
    @MockitoBean
    private ReservationService reservations;
    @MockitoBean
    private PositionService positions;
    @MockitoBean
    private RiskService risk;
    @MockitoBean
    private OrderExecutionService execution;
    @MockitoBean
    private OrderRepository orders;
    @MockitoBean
    private StatementService statements;

    private TradingAccount account;

    @BeforeEach
    void setUp() {
        account = new TradingAccount(ACCOUNT_ID, COMPANY_ID, Asset.EUR, AccountStatus.ACTIVE,
                new BigDecimal("50000"), new BigDecimal("100000"), null, 1, NOW, NOW);
        when(traders.current(any())).thenReturn(new CurrentTrader(7L, COMPANY_ID, "COMP", "client", Set.of()));
        when(accounts.requireByCompany(COMPANY_ID)).thenReturn(account);
    }

    @Test
    void accountReturnsAuthenticatedCompanyAccount() throws Exception {
        mvc.perform(get("/api/v1/accounts/me").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.baseCurrency").value("EUR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(accounts).requireByCompany(COMPANY_ID);
    }

    @Test
    void crossCompanyAccountIsHiddenAsNotFound() throws Exception {
        when(traders.current(any())).thenReturn(new CurrentTrader(8L, 99L, "OTHER", "other", Set.of()));
        when(accounts.requireByCompany(99L)).thenThrow(new TradingException(HttpStatus.NOT_FOUND,
                "TRADING_ACCOUNT_NOT_FOUND", "Compte Trading absent"));

        mvc.perform(get("/api/v1/accounts/me").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRADING_ACCOUNT_NOT_FOUND"));

        verify(accounts).requireByCompany(99L);
        verifyNoInteractions(balances, positions, risk, orders, statements);
    }

    @Test
    void balancesReadOnlyCurrentTradingAccount() throws Exception {
        when(balances.findAll(ACCOUNT_ID)).thenReturn(List.of(
                new Balance(ACCOUNT_ID, Asset.EUR, new BigDecimal("123.450000"), NOW),
                new Balance(ACCOUNT_ID, Asset.USD, new BigDecimal("999.000000"), NOW),
                new Balance(ACCOUNT_ID, Asset.XAU, new BigDecimal("2.000000"), NOW)));
        when(reservations.available(ACCOUNT_ID, Asset.EUR)).thenReturn(new BigDecimal("100.000000"));
        when(reservations.available(ACCOUNT_ID, Asset.XAU)).thenReturn(new BigDecimal("2.000000"));

        mvc.perform(get("/api/v1/accounts/me/balances").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].asset").value("EUR"))
                .andExpect(jsonPath("$[1].asset").value("XAU"))
                .andExpect(jsonPath("$.length()").value(2));

        verify(balances).findAll(ACCOUNT_ID);
    }

    @Test
    void positionsExposeNoInternalPricingField() throws Exception {
        when(positions.read(account)).thenReturn(List.of(new AccountPosition(Asset.XAU,
                new BigDecimal("2.000000"), new BigDecimal("2000.00"), new BigDecimal("4000.00"),
                Instant.parse("2026-09-03T10:00:00Z"))));

        mvc.perform(get("/api/v1/accounts/me/positions").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientPrice").value(2000.00))
                .andExpect(content().string(not(containsString("market_price"))))
                .andExpect(content().string(not(containsString("marketPrice"))))
                .andExpect(content().string(not(containsString("spread_applied"))))
                .andExpect(content().string(not(containsString("spreadApplied"))))
                .andExpect(content().string(not(containsString("saamp_revenue"))))
                .andExpect(content().string(not(containsString("saampRevenue"))));
    }

    @Test
    void positionsPropagateMarketPriceStaleAsProblemDetail() throws Exception {
        when(positions.read(account)).thenThrow(new TradingException(HttpStatus.CONFLICT,
                "MARKET_PRICE_STALE", "Prix périmé"));

        mvc.perform(get("/api/v1/accounts/me/positions").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_STALE"));
    }

    @Test
    void summaryDelegatesToExistingRiskService() throws Exception {
        var result = new RiskResult(new BigDecimal("1000.00"), new BigDecimal("200.00"),
                new BigDecimal("1200.00"), new BigDecimal("20.00"), new BigDecimal("1180.00"),
                new BigDecimal("200.00"), new BigDecimal("700.00"), RiskStatus.NORMAL);
        when(risk.computeAndStore(account)).thenReturn(result);

        var response = mvc.perform(get("/api/v1/accounts/me/summary").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk.netEquity").value(1200.00))
                .andExpect(jsonPath("$.positionLimit").value(100000))
                .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(response);
        assertThat(json.propertyStream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("accountId", "baseCurrency", "status", "dealLimit", "positionLimit", "risk");
        assertThat(json.get("risk").propertyStream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("totalFunds", "positionValuation", "netEquity", "marginRequirement",
                        "freeEquity", "grossPosition", "coveragePct", "riskStatus");

        verify(risk).computeAndStore(account);
    }

    @Test
    void ordersReadOnlyCurrentAccountAndHideProviderFields() throws Exception {
        TradingOrder order = mock(TradingOrder.class);
        when(order.id()).thenReturn(70L);
        when(order.asset()).thenReturn(Asset.XAU);
        when(order.side()).thenReturn(OrderSide.BUY);
        when(order.orderType()).thenReturn(OrderType.SPOT);
        when(order.requestedQuantity()).thenReturn(BigDecimal.ONE);
        when(order.requestedUnit()).thenReturn(QuantityUnit.OZ);
        when(order.quantityOz()).thenReturn(BigDecimal.ONE);
        when(order.status()).thenReturn(OrderStatus.PENDING_UNKNOWN);
        when(order.createdAt()).thenReturn(NOW);
        when(orders.findRecent(ACCOUNT_ID, 100)).thenReturn(List.of(order));

        mvc.perform(get("/api/v1/accounts/me/orders").with(jwtWith(HISTORY_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_UNKNOWN"))
                .andExpect(jsonPath("$[0].id").value(70L))
                .andExpect(jsonPath("$[0].clientOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist())
                .andExpect(content().string(not(containsString("stonex"))))
                .andExpect(content().string(not(containsString("spreadApplied"))))
                .andExpect(content().string(not(containsString("saampRevenue"))));

        verify(orders).findRecent(ACCOUNT_ID, 100);
    }

    @Test
    void statementUsesCurrentAccountServiceResultAndHidesInternalAuditFields() throws Exception {
        var statement = new AccountStatement(ACCOUNT_ID, Asset.EUR, Instant.parse("2026-09-03T10:00:00Z"),
                List.of(new StatementLine(1L, Asset.EUR, new BigDecimal("100.00"),
                        LedgerEntryType.ADJUSTMENT, null, new BigDecimal("100.00"),
                        Instant.parse("2026-09-03T09:00:00Z"))), 1L, true);
        when(statements.build(account, 9L, 25)).thenReturn(statement);

        mvc.perform(get("/api/v1/accounts/me/statement").param("cursor", "9").param("limit", "25")
                        .with(jwtWith(HISTORY_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].delta").value(100.00))
                .andExpect(jsonPath("$.nextCursor").value(1L))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(content().string(not(containsString("createdBy"))))
                .andExpect(content().string(not(containsString("transferRef"))));

        verify(statements).build(account, 9L, 25);
    }

    @Test
    void accountEndpointsRequireAccountReadPermission() throws Exception {
        mvc.perform(get("/api/v1/accounts/me").with(jwtWith(HISTORY_READ)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me/balances").with(jwtWith(HISTORY_READ)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me/positions").with(jwtWith(HISTORY_READ)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me/summary").with(jwtWith(HISTORY_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyEndpointsRequireHistoryReadPermission() throws Exception {
        mvc.perform(get("/api/v1/accounts/me/orders").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me/statement").with(jwtWith(ACCOUNT_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void consultationPathHasNoAs400Dependency() {
        var types = List.of(AccountController.class, OrderController.class, StatementController.class,
                PositionService.class, StatementService.class);
        assertThat(types).allSatisfy(type -> assertThat(List.of(type.getDeclaredFields()))
                .allSatisfy(field -> assertThat(field.getType().getName().toLowerCase()).doesNotContain("as400")));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtWith(String permission) {
        return jwt().authorities(new SimpleGrantedAuthority("MYTRADING_ACCESS"),
                new SimpleGrantedAuthority(permission));
    }
}
