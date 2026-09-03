package com.saamp.trading.statement;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.PricingService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Builds the client statement dataset without exposing internal margin or provider prices. */
@Service
public class StatementService {
    private final BalanceRepository balances;
    private final PricingService pricing;
    public StatementService(BalanceRepository balances, PricingService pricing) { this.balances=balances;this.pricing=pricing; }

    public AccountStatement build(TradingAccount account) {
        var lines = new ArrayList<StatementLine>();
        BigDecimal total = BigDecimal.ZERO;
        OffsetDateTime now = OffsetDateTime.now();
        for (var balance : balances.findAll(account.id())) {
            if (balance.quantity().signum()==0) continue;
            if (balance.asset()==account.baseCurrency()) {
                BigDecimal value=balance.quantity().setScale(2,RoundingMode.HALF_UP);
                lines.add(new StatementLine(balance.asset(),balance.quantity(),BigDecimal.ONE,value,now));
                total=total.add(value);
            } else if (balance.asset().isMetal()) {
                var quote=pricing.quoteForDisplay(account.companyId(),balance.asset(),account.baseCurrency());
                // Spec §5.4 defines liquidation_value with the current client SELL price.
                BigDecimal value=balance.quantity().multiply(quote.clientSellPrice()).setScale(2,RoundingMode.HALF_UP);
                lines.add(new StatementLine(balance.asset(),balance.quantity(),quote.clientSellPrice(),value,quote.priceAsOf()));
                total=total.add(value);
            }
        }
        return new AccountStatement(account.id(),account.companyId(),account.baseCurrency(),now,List.copyOf(lines),total.setScale(2,RoundingMode.HALF_UP));
    }
}
