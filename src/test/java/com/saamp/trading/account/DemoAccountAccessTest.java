package com.saamp.trading.account;

import com.saamp.trading.domain.*;
import com.saamp.trading.common.TradingException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoAccountAccessTest {
    @Test void onlyMatchingTokenAndAccountModesAreAllowed() {
        var repository=mock(AccountRepository.class);
        var service=new AccountService(repository);
        for (var mode:TradingMode.values()) {
            var account=new TradingAccount(1,2,Asset.EUR,AccountStatus.ACTIVE,null,null,null,
                    null,null,null,1,OffsetDateTime.now(),OffsetDateTime.now(),mode);
            when(repository.findByCompanyId(2)).thenReturn(Optional.of(account));
            assertThat(service.requireByCompany(2,mode)).isSameAs(account);
            var other=mode==TradingMode.DEMO?TradingMode.LIVE:TradingMode.DEMO;
            assertThatThrownBy(()->service.requireByCompany(2,other)).isInstanceOfSatisfying(TradingException.class,
                    e->assertThat(e.getCode()).isEqualTo("ACCOUNT_TRADING_MODE_MISMATCH"));
        }
    }
}
