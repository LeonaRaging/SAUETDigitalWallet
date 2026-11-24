package sa.uet.digitalwallet_system.application.port.out;

import sa.uet.digitalwallet_system.domain.model.Wallet;

public interface SaveWalletPort {
    Wallet save(Wallet wallet);

    boolean debitIfSufficient(Long walletId, double amount);

    boolean credit(Long walletId, double amount);
}
