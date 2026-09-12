package com.saamp.trading.security;

import com.saamp.trading.account.*;
import com.saamp.trading.api.AccountController;
import com.saamp.trading.common.ApiExceptionHandler;
import com.saamp.trading.config.SecurityConfig;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.*;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.RiskService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("prod")
@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class,ApiExceptionHandler.class,CurrentTraderService.class,TradingDemoGuard.class,
        AccountService.class,TradingProperties.class})
@TestPropertySource(properties={"trading.demo.enabled=true","trading.provider.mode=PMXCONNECT"})
class DemoProductionAccountIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AccountRepository accounts;
    @MockitoBean EffectiveBalanceService balances;
    @MockitoBean PositionService positions;
    @MockitoBean RiskService risk;
    @MockitoBean ReservationService reservations;
    @MockitoBean PricingService pricing;

    @Test void prodAcceptsOnlyTheAccountMatchingTheTokenMode() throws Exception {
        for(var accountMode:TradingMode.values()) {
            when(accounts.findByCompanyId(2)).thenReturn(Optional.of(new TradingAccount(1,2,Asset.EUR,
                    AccountStatus.ACTIVE,null,null,null,null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now(),accountMode)));
            for(var tokenMode:TradingMode.values()) {
                boolean demo=tokenMode==TradingMode.DEMO;
                mvc.perform(get("/api/v1/accounts/me").with(jwt().jwt(j->j.subject("99").claim("companyId",2L)
                        .claim("tradingMode",tokenMode.name()).claim("identityType",demo?"INTERNAL":"CLIENT")
                        .claim("accessMode",demo?"INTERNAL_DEMO":"CLIENT_SELF")
                        .claim("permissions",java.util.List.of("MYTRADING_ACCESS","MYTRADING_ACCOUNT_READ")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("MYTRADING_ACCESS"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("MYTRADING_ACCOUNT_READ"))))
                        .andExpect(status().is(tokenMode==accountMode?200:403));
            }
        }
        verifyNoInteractions(balances,positions,risk);
    }
}
