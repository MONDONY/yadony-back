package com.yadony.api.payments.wallet.topupcode;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.payments.wallet.topupcode.dto.TopupCodeRedeemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletTopupCodeRedemptionServiceTest {

    @Mock WalletTopupCodeRepository codes;
    @Mock WalletTopupCodeRedemptionRepository redemptions;
    @Mock WalletService walletService;
    @Mock WalletTransactionRepository transactions;
    @Mock AuditService auditService;

    Cache<UUID, AtomicInteger> failures;
    WalletTopupCodeRedemptionService service;

    final UUID userId = UUID.randomUUID();
    final UUID codeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        failures = Caffeine.newBuilder().build();
        service = new WalletTopupCodeRedemptionService(codes, redemptions, walletService, transactions,
                auditService, failures);
    }

    private WalletTopupCodeEntity activeCode(int max, int used) {
        WalletTopupCodeEntity c = new WalletTopupCodeEntity();
        ReflectionTestUtils.setField(c, "id", codeId);
        c.setCode("DAKAR2000");
        c.setAmount(new BigDecimal("2000"));
        c.setCurrency("XOF");
        c.setMaxRedemptions(max);
        c.setRedeemedCount(used);
        c.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(10));
        return c;
    }

    private void stubLedger() {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        tx.setBalanceAfter(new BigDecimal("3500"));
        when(transactions.findByIdempotencyKey("topup-code:" + codeId + ":" + userId)).thenReturn(Optional.of(tx));
    }

    private static void assertCode(Throwable t, HttpStatus status, String code) {
        assertThat(t).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }

    @Test
    void normalize_retireEspacesEtMetEnMajuscules() {
        assertThat(WalletTopupCodeRedemptionService.normalize(" dakar 2000\n")).isEqualTo("DAKAR2000");
        assertThat(WalletTopupCodeRedemptionService.normalize("yd-ab12-cd34")).isEqualTo("YD-AB12-CD34");
    }

    @Test
    void redeem_succes_crediteTopUpCodeSansPaymentRef_incrementeEtTrace() {
        WalletTopupCodeEntity code = activeCode(100, 37);
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(code));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(false);
        stubLedger();

        TopupCodeRedeemResponse r = service.redeem(userId, "dakar 2000");

        verify(walletService).credit(userId, "XOF", new BigDecimal("2000"), WalletTransactionType.TOP_UP_CODE,
                null, "topup-code:" + codeId + ":" + userId);
        assertThat(code.getRedeemedCount()).isEqualTo(38);
        ArgumentCaptor<WalletTopupCodeRedemptionEntity> saved = ArgumentCaptor.forClass(WalletTopupCodeRedemptionEntity.class);
        verify(redemptions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getCodeId()).isEqualTo(codeId);
        assertThat(r.amount()).isEqualByComparingTo("2000");
        assertThat(r.currency()).isEqualTo("XOF");
        assertThat(r.balance()).isEqualByComparingTo("3500");
        verify(auditService).log(eq("wallet_topup_code"), eq(codeId), eq("WALLET_TOPUP_CODE_REDEEMED"), eq(userId), any());
    }

    @Test
    void redeem_codeInconnu_422_etCompteUnEchec() {
        when(codes.findByCodeForUpdate("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.redeem(userId, "nope"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-not-found"));
        assertThat(failures.getIfPresent(userId)).hasValue(1);
    }

    @Test
    void redeem_formatInvalide_traiteCommeInconnuSansToucherLaBase() {
        assertThatThrownBy(() -> service.redeem(userId, "a!"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-not-found"));
        verify(codes, never()).findByCodeForUpdate(anyString());
        assertThat(failures.getIfPresent(userId)).hasValue(1);
    }

    @Test
    void redeem_apres5Echecs_429SansConsulterLaBase() {
        failures.put(userId, new AtomicInteger(5));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.TOO_MANY_REQUESTS, "topup-code-too-many-attempts"));
        verify(codes, never()).findByCodeForUpdate(anyString());
    }

    @Test
    void redeem_revoque_422() {
        WalletTopupCodeEntity code = activeCode(10, 0);
        code.setStatus(WalletTopupCodeStatus.REVOKED);
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(code));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-revoked"));
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void redeem_expire_422() {
        WalletTopupCodeEntity code = activeCode(10, 0);
        code.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(code));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-expired"));
    }

    @Test
    void redeem_epuise_422() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(2, 2)));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-exhausted"));
    }

    @Test
    void redeem_dejaUtiliseParCeCompte_422_etNeComptePasUnEchec() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(true);
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-already-redeemed"));
        assertThat(failures.getIfPresent(userId)).isNull();
    }

    @Test
    void redeem_portefeuilleGele_422() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(true);
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-pending"));
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void redeem_courseSurLaContrainteUnique_traduiteEnDejaUtilise() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(false);
        stubLedger();
        when(redemptions.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq"));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-already-redeemed"));
    }
}
