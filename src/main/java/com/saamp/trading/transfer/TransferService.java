package com.saamp.trading.transfer;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.LedgerEntryType;
import com.saamp.trading.domain.TransferDirection;
import com.saamp.trading.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Idempotent ingestion of externally initiated deposits and withdrawals. */
@Service
public class TransferService {
    private final TransferRepository transfers;
    private final LedgerService ledger;

    public TransferService(TransferRepository transfers, LedgerService ledger) {
        this.transfers = transfers;
        this.ledger = ledger;
    }

    @Transactional
    public TradingTransfer ingest(TransferCommand command, String actor) {
        var existing = transfers.findByExternalRef(command.externalRef());
        if (existing.isPresent()) return existing.get();

        long id = transfers.insertReceived(command.accountId(), command.asset(), command.quantity(), command.direction(),
                command.externalRef(), command.acquisitionPrice());
        if (id < 0) return transfers.findByExternalRef(command.externalRef()).orElseThrow();

        BigDecimal delta = command.direction() == TransferDirection.IN ? command.quantity() : command.quantity().negate();
        try {
            ledger.post(command.accountId(), command.asset(), delta,
                    command.direction() == TransferDirection.IN ? LedgerEntryType.TRANSFER_IN : LedgerEntryType.TRANSFER_OUT,
                    null, command.externalRef(), actor);
            transfers.markApplied(id);
        } catch (TradingException e) {
            if (!"NEGATIVE_CURRENCY_BALANCE".equals(e.getCode())) throw e;
            transfers.markRejected(id, "INSUFFICIENT_AVAILABLE_CURRENCY");
        }
        return transfers.findByExternalRef(command.externalRef()).orElseThrow();
    }
}
