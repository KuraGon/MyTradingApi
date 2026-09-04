package com.saamp.trading.configsync;
import com.saamp.trading.domain.*; import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AccountConfigCommandTest {
 @Test void commercialAndTradingNucliMustDiffer(){assertThatIllegalArgumentException().isThrownBy(()->new AccountConfigCommand(1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",17492,17492,2));}
 @Test void distinctNuclisAreAccepted(){assertThatCode(()->new AccountConfigCommand(1,Asset.EUR,AccountStatus.ACTIVE,null,null,null,"B",17492,20662,2)).doesNotThrowAnyException();}
}
