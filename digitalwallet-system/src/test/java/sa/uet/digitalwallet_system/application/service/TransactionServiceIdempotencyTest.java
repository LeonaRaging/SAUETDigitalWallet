package sa.uet.digitalwallet_system.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sa.uet.digitalwallet_system.application.port.in.TopupCommand;
import sa.uet.digitalwallet_system.application.port.in.TransferCommand;
import sa.uet.digitalwallet_system.application.port.in.WithdrawCommand;
import sa.uet.digitalwallet_system.application.port.out.*;
import sa.uet.digitalwallet_system.domain.model.Transaction;
import sa.uet.digitalwallet_system.domain.model.Wallet;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceIdempotencyTest {

    @Mock
    private LoadWalletPort loadWalletPort;

    @Mock
    private SaveWalletPort saveWalletPort;

    @Mock
    private LoadLinkedBankPort loadLinkedBankPort;

    @Mock
    private SaveTransactionPort saveTransactionPort;

    @Mock
    private LoadTransactionPort loadTransactionPort;

    @InjectMocks
    private TransactionService service;

    static final String KEY = "3f6c1a2e-8b4d-4e9a-9c7b-5d2e1f0a7b31";

    @BeforeEach
    void wallets() {
        lenient().when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, "WAL10", 1000)));
        lenient().when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.of(wallet(20, "WAL20", 500)));
        lenient().when(saveWalletPort.debitIfSufficient(anyLong(), anyLong())).thenReturn(true);
        lenient().when(saveWalletPort.credit(anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void newKey_movesMoneyOnce_andStoresKeyAsReferenceCode() {
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.empty());
        when(saveTransactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction tx = service.transfer(new TransferCommand(10L, "WAL20", 250L, "coffee", KEY));

        assertEquals(KEY, tx.getReferenceCode());
        verify(saveWalletPort).debitIfSufficient(10L, 250L);
        verify(saveWalletPort).credit(20L, 250L);
        verify(saveTransactionPort).save(any());
    }

    @Test
    void sameKeySameRequest_returnsExistingTransaction_andMovesNoMoney() {
        Transaction existing = transfer(KEY, 10L, 20L, 250L);
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.of(existing));

        Transaction result = service.transfer(new TransferCommand(10L, "WAL20", 250L, "coffee", KEY));

        assertSame(existing, result);
        verifyNoInteractions(saveWalletPort);
        verify(saveTransactionPort, never()).save(any());
    }

    @Test
    void noKey_behavesAsBefore_newPaymentEveryTime() {
        when(saveTransactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction first = service.transfer(new TransferCommand(10L, "WAL20", 250L, "coffee", null));
        Transaction second = service.transfer(new TransferCommand(10L, "WAL20", 250L, "coffee", null));

        assertNotEquals(first.getReferenceCode(), second.getReferenceCode());
        verify(loadTransactionPort, never()).loadByReferenceCode(any());
        verify(saveTransactionPort, times(2)).save(any());
    }

    @Test
    void topupRetryWithSameKey_returnsExisting_andMovesNoMoney() {
        Transaction existing = Transaction.builder().id(201L).referenceCode(KEY).type("TOPUP")
                .targetWalletId(10L).linkedBankAccountId(5L).amount(300L).build();
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.of(existing));

        Transaction result = service.topup(new TopupCommand(10L, 5L, 300L, "nap tien", KEY));

        assertSame(existing, result);
        verifyNoInteractions(saveWalletPort);
        verify(saveTransactionPort, never()).save(any());
    }

    @Test
    void withdrawRetryWithSameKey_returnsExisting_andMovesNoMoney() {
        Transaction existing = Transaction.builder().id(202L).referenceCode(KEY).type("WITHDRAW")
                .sourceWalletId(10L).linkedBankAccountId(5L).amount(300L).build();
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.of(existing));

        Transaction result = service.withdraw(new WithdrawCommand(10L, 5L, 300L, "rut tien", KEY));

        assertSame(existing, result);
        verifyNoInteractions(saveWalletPort);
        verify(saveTransactionPort, never()).save(any());
    }

    private static Wallet wallet(long id, String number, long balance) {
        return Wallet.builder().id(id).userId(id).walletNumber(number)
                .balance(balance).status("ACTIVE").version(0L).build();
    }

    private static Transaction transfer(String key, Long from, Long to, Long amount) {
        return Transaction.builder().id(101L).referenceCode(key).type("TRANSFER").sourceWalletId(from)
                        .targetWalletId(to).amount(amount).build();
    }
}
