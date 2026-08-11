package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock AuditService auditService;

    WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletAccountRepository, walletTransactionRepository, auditService);
    }

    @Test
    void getOrCreate_createsWalletInRequestedCurrencyIfNotExists() {
        UUID userId = UUID.randomUUID();
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "CAD")).thenReturn(Optional.empty());
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WalletAccountEntity wallet = walletService.getOrCreate(userId, "CAD");

        assertThat(wallet.getUserId()).isEqualTo(userId);
        assertThat(wallet.getCurrency()).isEqualTo("CAD");
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(walletAccountRepository).save(wallet);
    }

    @Test
    void getOrCreate_returnsExistingWalletForRequestedCurrency() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity existing = wallet(userId, "EUR", new BigDecimal("42.00"));
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "EUR")).thenReturn(Optional.of(existing));

        WalletAccountEntity result = walletService.getOrCreate(userId, "EUR");

        assertThat(result).isSameAs(existing);
        verify(walletAccountRepository, never()).save(any());
    }

    @Test
    void creditAndDebitAreScopedPerCurrency() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity eur = wallet(userId, "EUR", BigDecimal.ZERO);
        WalletAccountEntity cad = wallet(userId, "CAD", BigDecimal.ZERO);
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "EUR")).thenReturn(Optional.of(eur));
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "CAD")).thenReturn(Optional.of(cad));
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "EUR")).thenReturn(Optional.of(eur));
        when(walletAccountRepository.findAllByUserId(userId)).thenReturn(List.of(eur, cad));
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletTransactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(walletTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        walletService.credit(userId, "EUR", new BigDecimal("20.00"),
                WalletTransactionType.TOP_UP, "ref-eur", "idem-eur");
        walletService.credit(userId, "CAD", new BigDecimal("15.00"),
                WalletTransactionType.TOP_UP, "ref-cad", "idem-cad");
        walletService.debit(userId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.BID_PAYMENT, UUID.randomUUID());

        assertThat(walletService.getBalance(userId, "EUR")).isEqualByComparingTo("15.00");
        assertThat(walletService.getBalance(userId, "CAD")).isEqualByComparingTo("15.00");
        assertThat(walletService.getAllBalances(userId)).containsExactly(eur, cad);

        ArgumentCaptor<WalletTransactionEntity> transactions = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository, times(3)).save(transactions.capture());
        assertThat(transactions.getAllValues())
                .extracting(WalletTransactionEntity::getCurrency)
                .containsExactly("EUR", "CAD", "EUR");
    }

    @Test
    void credit_increasesBalanceAndLogsCurrencyOnTransaction() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "EUR", new BigDecimal("10.00"));
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "EUR")).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletTransactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(walletTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        walletService.credit(userId, "EUR", new BigDecimal("50.00"),
                WalletTransactionType.TOP_UP, "pi_123", "idem-001");

        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("60.00"));
        ArgumentCaptor<WalletTransactionEntity> transaction = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).save(transaction.capture());
        assertThat(transaction.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(transaction.getValue().getBalanceAfter()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(transaction.getValue().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void credit_idempotent_doesNothingIfKeyAlreadyProcessed() {
        UUID userId = UUID.randomUUID();
        when(walletTransactionRepository.findByIdempotencyKey("idem-001"))
                .thenReturn(Optional.of(new WalletTransactionEntity()));

        walletService.credit(userId, "EUR", new BigDecimal("50.00"),
                WalletTransactionType.TOP_UP, "pi_123", "idem-001");

        verify(walletAccountRepository, never()).save(any());
    }

    @Test
    void debit_sufficientBalance_decreasesRequestedCurrencyAndLogsIt() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "CAD", new BigDecimal("100.00"));
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "CAD")).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        walletService.debit(userId, "CAD", new BigDecimal("30.00"), WalletTransactionType.BID_PAYMENT, null);

        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("70.00"));
        ArgumentCaptor<WalletTransactionEntity> transaction = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).save(transaction.capture());
        assertThat(transaction.getValue().getCurrency()).isEqualTo("CAD");
    }

    @Test
    void debit_insufficientBalance_throwsException() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "EUR", new BigDecimal("10.00"));
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "EUR")).thenReturn(Optional.of(wallet));

        Throwable thrown = catchThrowable(() ->
                walletService.debit(userId, "EUR", new BigDecimal("50.00"), WalletTransactionType.BID_PAYMENT, null));

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        verify(walletAccountRepository, never()).save(any());
    }

    @Test
    void getBalance_returnsExistingWalletBalanceForRequestedCurrency() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "CAD", new BigDecimal("33.50"));
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "CAD")).thenReturn(Optional.of(wallet));

        assertThat(walletService.getBalance(userId, "CAD")).isEqualByComparingTo(new BigDecimal("33.50"));
    }

    @Test
    void getTransactions_returnsHistoryContent() {
        UUID userId = UUID.randomUUID();
        WalletTransactionEntity transaction = new WalletTransactionEntity();
        transaction.setUserId(userId);
        transaction.setCurrency("EUR");
        transaction.setType(WalletTransactionType.REFUND);
        transaction.setAmount(new BigDecimal("5.00"));
        when(walletTransactionRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(transaction)));

        assertThat(walletService.getTransactions(userId, 0)).containsExactly(transaction);
    }

    @Test
    void debit_createsRequestedCurrencyWalletWhenMissing_thenThrowsInsufficient() {
        UUID userId = UUID.randomUUID();
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "CAD")).thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdAndCurrency(userId, "CAD")).thenReturn(Optional.empty());
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Throwable thrown = catchThrowable(() ->
                walletService.debit(userId, "CAD", new BigDecimal("12.00"),
                        WalletTransactionType.COMMISSION_DEDUCTED, UUID.randomUUID()));

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        ArgumentCaptor<WalletAccountEntity> account = ArgumentCaptor.forClass(WalletAccountEntity.class);
        verify(walletAccountRepository).save(account.capture());
        assertThat(account.getValue().getCurrency()).isEqualTo("CAD");
    }

    @Test
    void debitWithPaymentRef_setsPaymentRefIdempotencyKeyAndCurrency() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "EUR", new BigDecimal("100.00"));
        when(walletTransactionRepository.findByIdempotencyKey("nego_commission_wallet_t1"))
                .thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "EUR")).thenReturn(Optional.of(wallet));
        when(walletAccountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(walletTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        walletService.debit(userId, "EUR", new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "nego_commission_wallet_t1");

        assertThat(wallet.getBalance()).isEqualByComparingTo(new BigDecimal("88.00"));
        ArgumentCaptor<WalletTransactionEntity> transaction = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).save(transaction.capture());
        assertThat(transaction.getValue().getBidId()).isNull();
        assertThat(transaction.getValue().getPaymentRef()).isEqualTo("thread-1");
        assertThat(transaction.getValue().getIdempotencyKey()).isEqualTo("nego_commission_wallet_t1");
        assertThat(transaction.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("-12.00"));
        assertThat(transaction.getValue().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void debitWithPaymentRef_idempotentSkipWhenKeyExists() {
        UUID userId = UUID.randomUUID();
        when(walletTransactionRepository.findByIdempotencyKey("dup-key"))
                .thenReturn(Optional.of(new WalletTransactionEntity()));

        walletService.debit(userId, "EUR", new BigDecimal("12.00"),
                WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "dup-key");

        verify(walletAccountRepository, never()).findByUserIdAndCurrencyForUpdate(any(), anyString());
        verify(walletAccountRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void debitWithPaymentRef_insufficientBalance_throws() {
        UUID userId = UUID.randomUUID();
        WalletAccountEntity wallet = wallet(userId, "EUR", new BigDecimal("5.00"));
        when(walletTransactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdAndCurrencyForUpdate(userId, "EUR")).thenReturn(Optional.of(wallet));

        Throwable thrown = catchThrowable(() ->
                walletService.debit(userId, "EUR", new BigDecimal("12.00"),
                        WalletTransactionType.COMMISSION_DEDUCTED, "thread-1", "k1"));

        assertThat(thrown).isInstanceOf(InsufficientWalletBalanceException.class);
        verify(walletTransactionRepository, never()).save(any());
    }

    private WalletAccountEntity wallet(UUID userId, String currency, BigDecimal balance) {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(userId);
        wallet.setCurrency(currency);
        wallet.setBalance(balance);
        return wallet;
    }
}
