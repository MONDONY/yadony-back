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
        assertThat(failures.getIfPresent(userId).get()).isZero();
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
    void redeem_devisEnLitige_422MotifLitige() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isDisputeHeld(userId, "XOF")).thenReturn(true);
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "wallet-dispute-hold"));
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void redeem_courseSurLaContrainteUnique_traduiteEnDejaUtilise() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(false);
        stubLedger();
        when(redemptions.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq",
                new org.hibernate.exception.ConstraintViolationException("dup", new java.sql.SQLException("dup"),
                        "uq_topup_code_redemption_user")));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-already-redeemed"));
    }

    @Test
    void redeem_contrainteUniqueDansLeMessageDuPilote_traduiteEnDejaUtilise() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(false);
        stubLedger();
        when(redemptions.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("x",
                new java.sql.SQLException("ERROR: duplicate key value violates unique constraint "
                        + "\"uq_topup_code_redemption_user\"", "23505")));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-already-redeemed"));
    }

    @Test
    void redeem_autreViolationDIntegrite_remonteTelleQuelle() {
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(100, 3)));
        when(redemptions.existsByCodeIdAndUserId(codeId, userId)).thenReturn(false);
        when(walletService.isFrozen(userId, "XOF")).thenReturn(false);
        stubLedger();
        org.springframework.dao.DataIntegrityViolationException fk = new org.springframework.dao.DataIntegrityViolationException("fk",
                new org.hibernate.exception.ConstraintViolationException("fk", new java.sql.SQLException("fk"),
                        "wallet_topup_code_redemptions_user_id_fkey"));
        when(redemptions.saveAndFlush(any())).thenThrow(fk);
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000")).isSameAs(fk);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void redeem_codeTrouve_laisseLeCompteurInchange() {
        failures.put(userId, new AtomicInteger(4));
        when(codes.findByCodeForUpdate("DAKAR2000")).thenReturn(Optional.of(activeCode(2, 2)));
        assertThatThrownBy(() -> service.redeem(userId, "DAKAR2000"))
                .satisfies(t -> assertCode(t, HttpStatus.UNPROCESSABLE_ENTITY, "topup-code-exhausted"));
        assertThat(failures.getIfPresent(userId)).hasValue(4);
    }

    @Test
    void redeem_parallele_uneSeuleTentativeAtteintLaBase() throws Exception {
        failures.put(userId, new AtomicInteger(4));
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(codes.findByCodeForUpdate("NOPE")).thenAnswer(inv -> {
            lookups.incrementAndGet();
            return Optional.empty();
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> res = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            res.add(pool.submit(() -> {
                start.await();
                try {
                    service.redeem(userId, "NOPE");
                    return "ok";
                } catch (YadonyBusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();
        int tooMany = 0;
        for (java.util.concurrent.Future<String> f : res) {
            if ("topup-code-too-many-attempts".equals(f.get(10, java.util.concurrent.TimeUnit.SECONDS))) tooMany++;
        }
        pool.shutdown();
        assertThat(lookups.get()).isEqualTo(1);
        assertThat(tooMany).isEqualTo(2);
        assertThat(failures.getIfPresent(userId)).hasValue(5);
    }
}
