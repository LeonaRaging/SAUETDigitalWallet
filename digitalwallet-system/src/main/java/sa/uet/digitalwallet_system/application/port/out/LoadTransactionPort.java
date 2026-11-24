package sa.uet.digitalwallet_system.application.port.out;

import sa.uet.digitalwallet_system.domain.model.Transaction;

import java.util.List;
import java.util.Optional;

public interface LoadTransactionPort {
    List<Transaction> loadByWalletId(Long walletId);
    Optional<Transaction> loadByReferenceCode(String code);
}
