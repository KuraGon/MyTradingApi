package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Valide les quatre jambes avant toute ecriture DB2. */
record As400GroupPreparation(Asset asset, OrderSide side, BigDecimal quantityOz,
        BigDecimal clientPrice, BigDecimal fillPrice, String currency, Instant executedAt, String exid) {
    void validate() {
        if (side==null) throw new As400SyncDataException("AS400_EXECUTION_DATA_INVALID");
        if (exid==null || exid.isBlank()) throw new As400SyncDataException("AS400_EXID_MISSING");
        if (exid.length()>20) throw new As400SyncDataException("AS400_EXID_TOO_LONG");
        if (!"EUR".equals(currency) && !"USD".equals(currency))
            throw new As400SyncDataException("AS400_CURRENCY_UNSUPPORTED");
    }

    List<SicouviMovement> build(As400SyncEvent event, As400TradingMapping mapping,
            BigDecimal fx, List<Integer> identities) {
        validate();
        if (!mapping.lfmpCompany().equals(event.ste()))
            throw new As400SyncDataException("AS400_CLIENT_COMPANY_NOT_LFMP");
        if (identities.size()!=4) throw new As400SyncDataException("AS400_GROUP_INCOMPLETE");
        String clientSide=side==OrderSide.BUY?"V":"A";
        String opposite=side==OrderSide.BUY?"A":"V";
        var result=new ArrayList<SicouviMovement>();
        result.add(SicouviMovement.from(mapping.lfmpCompany(),identities.get(0),event.nucliTrading(),
                asset,clientSide,quantityOz,clientPrice,fx,executedAt,exid));
        result.add(SicouviMovement.from(mapping.lfmpCompany(),identities.get(1),mapping.lfmpSaampNucli(),
                asset,opposite,quantityOz,fillPrice,fx,executedAt,exid));
        result.add(SicouviMovement.from(mapping.saampCompany(),identities.get(2),mapping.saampLfmpNucli(),
                asset,clientSide,quantityOz,fillPrice,fx,executedAt,exid));
        result.add(SicouviMovement.from(mapping.saampCompany(),identities.get(3),mapping.stonexNucli(currency),
                asset,opposite,quantityOz,fillPrice,fx,executedAt,exid));
        return List.copyOf(result);
    }
}
