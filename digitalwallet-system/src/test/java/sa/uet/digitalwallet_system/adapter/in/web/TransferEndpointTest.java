package sa.uet.digitalwallet_system.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import sa.uet.digitalwallet_system.adapter.in.security.JwtAuthenticationFilter;
import sa.uet.digitalwallet_system.adapter.in.security.SecurityConfig;
import sa.uet.digitalwallet_system.adapter.in.web.controller.TransactionController;
import sa.uet.digitalwallet_system.adapter.in.web.handler.GlobalExceptionHandler;
import sa.uet.digitalwallet_system.adapter.out.security.JwtTokenGenerator;
import sa.uet.digitalwallet_system.application.port.in.GetWalletUseCase;
import sa.uet.digitalwallet_system.application.port.out.*;
import sa.uet.digitalwallet_system.application.service.TransactionService;
import sa.uet.digitalwallet_system.domain.model.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TransactionController.class, properties = {
        "app.jwt.secret=transfer-endpoint-test-signing-key-2026", "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenGenerator.class,
        TransactionService.class, GlobalExceptionHandler.class})
class TransferEndpointTest {
    private static final String PATH = "/api/v1/transactions/transfer";
    private static final String BODY = "{\"targetWalletNumber\":\"WAL20\",\"amount\":250,\"description\":\"Chuyen tien\"}";
    private static final String KEY = "3f6c1a2e-8b4d-4e9a-9c7b-5d2e1f0a7b31";
    @Autowired WebApplicationContext context;
    @Autowired JwtTokenGenerator tokenGenerator;
    @MockitoBean GetWalletUseCase getWalletUseCase;
    @MockitoBean LoadWalletPort loadWalletPort;
    @MockitoBean SaveWalletPort saveWalletPort;
    @MockitoBean LoadLinkedBankPort loadLinkedBankPort;
    @MockitoBean SaveTransactionPort saveTransactionPort;
    @MockitoBean LoadTransactionPort loadTransactionPort;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void successDebitsSourceCreditsTargetAndRecordsTransfer() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Transfer successful"))
                .andExpect(jsonPath("$.data.type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.sourceWalletId").value(10))
                .andExpect(jsonPath("$.data.targetWalletId").value(20))
                .andExpect(jsonPath("$.data.amount").value(250))
                .andExpect(jsonPath("$.data.linkedBankAccountId").doesNotExist());
        verify(saveWalletPort).debitIfSufficient(10L, 250L);
        verify(saveWalletPort).credit(20L, 250L);
        verify(saveWalletPort, never()).save(any());
        verify(saveTransactionPort).save(any(Transaction.class));
    }

    @Test void sameWalletReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(10, 1000, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void insufficientBalanceReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 249, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void inactiveSourceReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "INACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void inactiveTargetReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "INACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void missingTargetReturns404WithoutWrites() throws Exception {
        when(getWalletUseCase.getWallet(99L)).thenReturn(wallet(10, 1000, "ACTIVE"));
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, 1000, "ACTIVE")));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.empty());
        request(BODY, token()).andExpect(status().isNotFound());
        noWrites();
    }

    @Test void blankTargetReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"  \",\"amount\":250}", token())
                .andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void zeroAmountReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"WAL20\",\"amount\":0}", token())
                .andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void missingAmountReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"WAL20\"}", token())
                .andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void missingTokenReturns401() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        noBusinessCalls();
    }

    @Test void invalidTokenReturns401() throws Exception {
        request(BODY, "invalid.jwt.token").andExpect(status().isUnauthorized());
        noBusinessCalls();
    }

    @Test void idempotencyKeyHeaderBecomeReferenceCode() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        requestWithKey(KEY).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceCode").value(KEY));
    }

    @Test void retryWithSameKeyReturnsSameTransactionWithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.of(
                Transaction.builder().id(101L).referenceCode(KEY).type("TRANSFER")
                        .sourceWalletId(10L).targetWalletId(20L).amount(250L).build()));
        requestWithKey(KEY).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
        noWrites();
    }

    @Test void sameKeyDifferentBodyReturns422WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        when(loadTransactionPort.loadByReferenceCode(KEY)).thenReturn(Optional.of(
                Transaction.builder().id(101L).referenceCode(KEY).type("TRANSFER")
                        .sourceWalletId(10L).targetWalletId(20L).amount(999L).build()));
        requestWithKey(KEY).andExpect(status().isUnprocessableContent());
        noWrites();
    }

    private ResultActions request(String body, String jwt) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt).content(body));
    }

    private ResultActions requestWithKey(String key) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token())
                .header("Idempotency-Key", key)
                .content(BODY));
    }

    private String token() { return tokenGenerator.generateToken(User.builder().id(99L)
            .username("transfer-user").role("ROLE_USER").build()); }

    private Wallet wallet(long id, long balance, String status) {
        return Wallet.builder().id(id).userId(id == 10 ? 99L : 100L).walletNumber("WAL" + id)
                .balance(balance).status(status).build();
    }

    private void stub(Wallet source, Wallet target) {
        when(getWalletUseCase.getWallet(99L)).thenReturn(source);
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(source));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.of(target));
        when(saveTransactionPort.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
        when(saveWalletPort.debitIfSufficient(anyLong(), anyLong())).thenReturn(true);
        when(saveWalletPort.credit(anyLong(), anyLong())).thenReturn(true);
    }

    private void noWrites() { verifyNoInteractions(saveWalletPort, saveTransactionPort); }
    private void noBusinessCalls() { verifyNoInteractions(getWalletUseCase, loadWalletPort,
            saveWalletPort, loadLinkedBankPort, saveTransactionPort, loadTransactionPort); }
}
