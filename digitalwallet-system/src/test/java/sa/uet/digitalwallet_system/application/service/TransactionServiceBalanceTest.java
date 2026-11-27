package sa.uet.digitalwallet_system.application.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sa.uet.digitalwallet_system.application.port.in.TransferCommand;
import sa.uet.digitalwallet_system.application.port.in.WithdrawCommand;
import sa.uet.digitalwallet_system.application.port.out.*;
import sa.uet.digitalwallet_system.domain.exception.InsufficientBalanceException;
import sa.uet.digitalwallet_system.domain.exception.WalletNotActiveException;
import sa.uet.digitalwallet_system.domain.model.LinkedBankAccount;
import sa.uet.digitalwallet_system.domain.model.Wallet;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceBalanceTest {

    @Mock LoadWalletPort loadWalletPort;
    @Mock SaveWalletPort saveWalletPort;
    @Mock LoadLinkedBankPort loadLinkedBankPort;
    @Mock SaveTransactionPort saveTransactionPort;
    @InjectMocks TransactionService service;

    @Test
    void transfer_whenAtomicDebitFails_returnsInsufficientBalance_andSavesNoTransaction() {
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, "WAL10", 1000)));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.of(wallet(20, "WAL20", 0)));
        when(saveWalletPort.debitIfSufficient(10L, 250L)).thenReturn(false);   // request khác đã lấy tiền trước

        assertThrows(InsufficientBalanceException.class,
                () -> service.transfer(new TransferCommand(10L, "WAL20", 250L, "test", null)));

        verify(saveWalletPort, never()).credit(anyLong(), anyLong());
        verifyNoInteractions(saveTransactionPort);
    }

    @Test
    void transfer_toLowerId_creditsFirstThenDebits() {
        when(loadWalletPort.loadById(20L)).thenReturn(Optional.of(wallet(20, "WAL20", 1000)));
        when(loadWalletPort.loadByWalletNumber("WAL10")).thenReturn(Optional.of(wallet(10, "WAL10", 0)));
        when(saveWalletPort.credit(10L, 250L)).thenReturn(true);
        when(saveWalletPort.debitIfSufficient(20L, 250L)).thenReturn(true);
        when(saveTransactionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transfer(new TransferCommand(20L, "WAL10", 250L, "test", null));

        InOrder order = inOrder(saveWalletPort);
        order.verify(saveWalletPort).credit(10L, 250L);            // id nhỏ hơn (10) trước
        order.verify(saveWalletPort).debitIfSufficient(20L, 250L);
    }

    @Test
    void withdraw_whenAtomicDebitFails_returnsInsufficientBalance() {
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, "WAL10", 1000)));
        when(loadLinkedBankPort.loadByIdAndWalletId(5L, 10L)).thenReturn(Optional.of(
                LinkedBankAccount.builder().id(5L).walletId(10L).status("ACTIVE").build()));
        when(saveWalletPort.debitIfSufficient(10L, 300L)).thenReturn(false);

        assertThrows(InsufficientBalanceException.class,
                () -> service.withdraw(new WithdrawCommand(10L, 5L, 300L, "test", null)));
        verifyNoInteractions(saveTransactionPort);
    }

    @Test
    void transfer_whenCreditFails_throwsAndSavesNoTransaction() {
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, "WAL10", 1000)));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.of(wallet(20, "WAL20", 0)));
        when(saveWalletPort.debitIfSufficient(10L, 250L)).thenReturn(true);
        when(saveWalletPort.credit(20L, 250L)).thenReturn(false);   // receiver frozen meanwhile

        assertThrows(WalletNotActiveException.class,
                () -> service.transfer(new TransferCommand(10L, "WAL20", 250L, "test", null)));
        verifyNoInteractions(saveTransactionPort);
    }

    private static Wallet wallet(long id, String number, long balance) {
        return Wallet.builder().id(id).userId(id).walletNumber(number)
                .balance(balance).status("ACTIVE").version(0L).build();
    }
}

