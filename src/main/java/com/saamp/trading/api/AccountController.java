package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.PositionService;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.RiskService;
import com.saamp.trading.security.CurrentTraderService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts/me")
public class AccountController {
    private final CurrentTraderService traders;
    private final AccountService accounts;
    private final BalanceRepository balances;
    private final ReservationService reservations;
    private final PositionService positions;
    private final RiskService risk;

    /**
     * Assemble uniquement les lectures locales nécessaires aux écrans du compte client.
     *
     * @param traders résolution de l'identité authentifiée
     * @param accounts résolution du compte par société
     * @param balances lecture des soldes du compte de trading
     * @param reservations calcul des soldes disponibles
     * @param positions valorisation client des positions métal
     * @param risk calcul de risque existant
     */
    public AccountController(CurrentTraderService traders, AccountService accounts, BalanceRepository balances,
                             ReservationService reservations, PositionService positions, RiskService risk) {
        this.traders=traders; this.accounts=accounts; this.balances=balances; this.reservations=reservations;
        this.positions=positions; this.risk=risk;
    }

    /**
     * Retourne le compte rattaché à la société authentifiée, sans accepter d'identifiant client.
     *
     * @param authentication identité JWT validée par Spring Security
     * @return informations du compte utiles au front MyTrading
     */
    @GetMapping
    @Operation(summary = "Lire le compte courant", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ACCOUNT_READ.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public AccountView account(Authentication authentication) {
        var trader = traders.current(authentication);
        return AccountView.from(accounts.requireByCompany(trader.companyId()));
    }

    /**
     * Retourne uniquement les soldes du compte de trading de la société authentifiée.
     *
     * @param authentication identité JWT validée par Spring Security
     * @return soldes comptables et disponibles du compte courant
     */
    @GetMapping("/balances")
    @Operation(summary = "Lire les soldes du compte courant", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ACCOUNT_READ.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public List<BalanceView> balances(Authentication authentication) {
        var trader = traders.current(authentication);
        var account = accounts.requireByCompany(trader.companyId());
        return balances.findAll(account.id()).stream()
                .filter(balance -> balance.asset() == account.baseCurrency() || balance.asset().isMetal())
                .map(balance -> BalanceView.from(balance, reservations.available(account.id(), balance.asset())))
                .toList();
    }

    /**
     * Valorise les positions métal du compte avec des prix client, sans exposer les données fournisseur.
     *
     * @param authentication identité JWT validée par Spring Security
     * @return positions métal valorisées du compte courant
     */
    @GetMapping("/positions")
    @Operation(summary = "Lire les positions métal valorisées", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ACCOUNT_READ.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public List<PositionView> positions(Authentication authentication) {
        var trader = traders.current(authentication);
        var account = accounts.requireByCompany(trader.companyId());
        return positions.read(account).stream().map(PositionView::from).toList();
    }

    /**
     * Compose la synthèse du compte à partir du calcul de risque existant.
     *
     * @param authentication identité JWT validée par Spring Security
     * @return indicateurs financiers du compte courant
     */
    @GetMapping("/summary")
    @Operation(summary = "Lire la synthèse financière", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ACCOUNT_READ.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public AccountSummaryView summary(Authentication authentication) {
        var trader = traders.current(authentication);
        var account = accounts.requireByCompany(trader.companyId());
        return new AccountSummaryView(account.id(), account.baseCurrency(), account.status(), account.dealLimit(),
                account.positionLimit(), RiskSummaryView.from(risk.computeAndStore(account)));
    }
}
