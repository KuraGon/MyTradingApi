package com.saamp.trading.as400;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Mouvement client unique ; tous les arrondis sont validés avant l'INSERT DB2. */
record SicouviMovement(String ste, int sicoui, int nucli, int executionDate, int executionTime,
        String side, String metal, String condition, BigDecimal grams, BigDecimal fx,
        BigDecimal quotation, String ref2, String ref3) {
    private static final BigDecimal LEGACY_OUNCE = new BigDecimal("31.10348");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneId.of("Europe/Paris"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneId.of("Europe/Paris"));

    static SicouviMovement from(As400SyncEvent event, Asset asset, OrderSide side,
            BigDecimal quantityOz, BigDecimal clientPrice, String currency, Instant executedAt) {
        if (event.ste()==null || event.ste().length()!=1 || event.ste().isBlank()
                || event.nucliTrading()==null || event.nucliTrading()<1 || event.nucliTrading()>999999)
            throw new As400SyncDataException("AS400_TRADING_ACCOUNT_MAPPING_MISSING");
        if (event.sicoui()<1 || event.sicoui()>999999)
            throw new As400SyncDataException("AS400_SICOUI_INVALID");
        if (!"EUR".equals(currency)) throw new As400SyncDataException("AS400_FX_RATE_UNAVAILABLE");
        if (quantityOz==null || quantityOz.signum()<=0 || clientPrice==null || clientPrice.signum()<=0 || executedAt==null || side==null)
            throw new As400SyncDataException("AS400_EXECUTION_DATA_INVALID");
        if (asset==null) throw new As400SyncDataException("AS400_METAL_UNSUPPORTED");
        Metal mapping = switch (asset) {
            case XAU -> new Metal("O","OR SPOT","OR SPOT");
            case XAG -> new Metal("A","ARGENT SPOT","ARGENT SPO");
            case XPT -> new Metal("P","PT SPOT","PT SPOT");
            case XPD -> new Metal("D","PD SPOT","PD SPOT");
            default -> throw new As400SyncDataException("AS400_METAL_UNSUPPORTED");
        };
        BigDecimal grams=quantityOz.multiply(JdbcAs400AccountReader.GRAMS_PER_TROY_OUNCE).setScale(2,RoundingMode.HALF_UP);
        if (grams.signum()==0) throw new As400SyncDataException("AS400_WEIGHT_ROUNDS_TO_ZERO");
        BigDecimal fx=new BigDecimal("1.00000");
        // Une seule division finale : aucun arrondi intermédiaire arbitraire.
        BigDecimal quotation=clientPrice.multiply(new BigDecimal("1000"))
                .divide(LEGACY_OUNCE.multiply(fx),0,RoundingMode.HALF_UP).setScale(4);
        if (grams.precision()>9) throw new As400SyncDataException("AS400_WEIGHT_OVERFLOW");
        if (quotation.precision()>10 || quotation.signum()<=0) throw new As400SyncDataException("AS400_QUOTATION_INVALID");
        return new SicouviMovement(event.ste(),event.sicoui(),event.nucliTrading(),date(executedAt),time(executedAt),
                side==OrderSide.BUY?"V":"A",mapping.code(),mapping.condition(),grams,fx,quotation,
                mapping.ref2(),reference(event.orderId()));
    }

    static String reference(long orderId) {
        if (orderId<=0) throw new As400SyncDataException("AS400_ORDER_ID_INVALID");
        return "MT-"+Long.toString(orderId,36).toUpperCase(Locale.ROOT);
    }
    static int date(Instant instant) { return Integer.parseInt(DATE.format(instant)); }
    static int time(Instant instant) { return Integer.parseInt(TIME.format(instant)); }
    private record Metal(String code,String condition,String ref2) {}
}
