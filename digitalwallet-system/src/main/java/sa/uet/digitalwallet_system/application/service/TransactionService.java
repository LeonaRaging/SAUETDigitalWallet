package sa.uet.digitalwallet_system.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.application.port.in.*;
import sa.uet.digitalwallet_system.application.port.out.*;
import sa.uet.digitalwallet_system.domain.exception.*;
import sa.uet.digitalwallet_system.domain.model.Transaction;
import sa.uet.digitalwallet_system.domain.model.Wallet;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService implements TopupUseCase, WithdrawUseCase, TransferUseCase, GetTransactionsUseCase {

    private final LoadWalletPort loadWalletPort;
    private final SaveWalletPort saveWalletPort;
    private final LoadLinkedBankPort loadLinkedBankPort;
    private final SaveTransactionPort saveTransactionPort;
    private final LoadTransactionPort loadTransactionPort;

    private Optional<Transaction> findPrevious(String key, String type, Long sourceWalletId,
                                               Long targetWalletId, Long linkedBankId, Long amount) {
        if (key == null) {
            return Optional.empty();
        }
        return loadTransactionPort.loadByReferenceCode(key).map(existing -> {
            boolean sameRequest = type.equals(existing.getType())
                    && Objects.equals(sourceWalletId, existing.getSourceWalletId())
                    && Objects.equals(targetWalletId, existing.getTargetWalletId())
                    && Objects.equals(linkedBankId, existing.getLinkedBankAccountId())
                    && amount.equals(existing.getAmount());
            if (!sameRequest) {
                throw new IdempotencyKeyReusedException(key);
            }
            return existing;
        });
    }

    @Override
    @Transactional
    public Transaction topup(TopupCommand command) {
        Wallet wallet = loadWalletPort.loadById(command.getWalletId())
                .orElseThrow(WalletNotFoundException::new);

        String key = command.getIdempotencyKey();
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else {
            Optional<Transaction> existingTransaction = findPrevious(key, "TOPUP", null,
                    wallet.getId(), command.getLinkedBankAccountId(), command.getAmount());

            if (existingTransaction.isPresent()) {
                return existingTransaction.get();
            }
        }

        if (!"ACTIVE".equals(wallet.getStatus())) {
            throw new WalletNotActiveException(wallet.getWalletNumber());
        }

        // Verify bank account belongs to user's wallet
        var bank = loadLinkedBankPort.loadByIdAndWalletId(command.getLinkedBankAccountId(), wallet.getId())
                .orElseThrow(LinkedBankNotFoundException::new);
        if (!"ACTIVE".equals(bank.getStatus())) {
            throw new WalletNotActiveException(wallet.getWalletNumber());
        }

        if (!saveWalletPort.credit(wallet.getId(), command.getAmount())) {
            throw new WalletNotActiveException(wallet.getWalletNumber());
        }

        // Append ledger entry
        Transaction tx = Transaction.builder()
                .referenceCode(key)
                .type("TOPUP")
                .sourceWalletId(null)
                .targetWalletId(wallet.getId())
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now())
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional
    public Transaction withdraw(WithdrawCommand command) {
        Wallet wallet = loadWalletPort.loadById(command.getWalletId())
                .orElseThrow(WalletNotFoundException::new);
        String key = command.getIdempotencyKey();
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else {
            Optional<Transaction> existingTransaction = findPrevious(key, "WITHDRAW", wallet.getId(),
                    null, command.getLinkedBankAccountId(), command.getAmount());

            if (existingTransaction.isPresent()) {
                return existingTransaction.get();
            }
        }

        if (!"ACTIVE".equals(wallet.getStatus())) {
            throw new WalletNotActiveException(wallet.getWalletNumber());
        }
        if (wallet.getBalance() < command.getAmount()) {
            throw new InsufficientBalanceException();
        }

        // Verify bank account belongs to user's wallet
        var bank = loadLinkedBankPort.loadByIdAndWalletId(command.getLinkedBankAccountId(), wallet.getId())
                .orElseThrow(LinkedBankNotFoundException::new);
        if (!"ACTIVE".equals(bank.getStatus())) {
            throw new WalletNotActiveException(wallet.getWalletNumber());
        }

        if (!saveWalletPort.debitIfSufficient(wallet.getId(), command.getAmount())) {
            throw new InsufficientBalanceException();
        }

        Transaction tx = Transaction.builder()
                .referenceCode(key)
                .type("WITHDRAW")
                .sourceWalletId(wallet.getId())
                .targetWalletId(null)
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now())
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional
    public Transaction transfer(TransferCommand command) {
        Wallet source = loadWalletPort.loadById(command.getSourceWalletId())
                .orElseThrow(WalletNotFoundException::new);
        Wallet target = loadWalletPort.loadByWalletNumber(command.getTargetWalletNumber())
                .orElseThrow(() -> new WalletNotFoundException("Target wallet not found: " + command.getTargetWalletNumber()));
        String key = command.getIdempotencyKey();
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else {
            Optional<Transaction> existingTransaction = findPrevious(key, "TRANSFER", source.getId(),
                    target.getId(), null, command.getAmount());

            if (existingTransaction.isPresent()) {
                return existingTransaction.get();
            }
        }

        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Cannot transfer to the same wallet");
        }
        if (!"ACTIVE".equals(source.getStatus())) {
            throw new WalletNotActiveException(source.getWalletNumber());
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            throw new WalletNotActiveException(target.getWalletNumber());
        }
        if (source.getBalance() < command.getAmount()) {
            throw new InsufficientBalanceException();
        }

        LocalDateTime now = LocalDateTime.now();

        boolean debited;
        if (source.getId() < target.getId()) {
            debited = saveWalletPort.debitIfSufficient(source.getId(), command.getAmount());
            if (debited) {
                saveWalletPort.credit(target.getId(), command.getAmount());
            }
        } else {
            saveWalletPort.credit(target.getId(), command.getAmount());
            debited = saveWalletPort.debitIfSufficient(source.getId(), command.getAmount());
        }
        if (!debited) {
            throw new InsufficientBalanceException(); // @Transactional rollback cả khoản đã cộng
        }

        Transaction tx = Transaction.builder()
                .referenceCode(key)
                .type("TRANSFER")
                .sourceWalletId(source.getId())
                .targetWalletId(target.getId())
                .linkedBankAccountId(null)
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(now)
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> getTransactions(Long walletId) {
        return loadTransactionPort.loadByWalletId(walletId);
    }
}
