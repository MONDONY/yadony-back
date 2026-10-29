package com.yadony.api.payments.wallet;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.param.RefundListParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletExternalRefundServiceTest {

    @Mock WalletTransactionRepository transactionRepository;
    @Mock WalletRefundRequestItemRepository refundItemRepository;
    @Mock WalletExternalRefundHoldRepository holdRepository;
    @Mock WalletService walletService;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;

    WalletExternalRefundService service;

    static final UUID USER_ID = UUID.randomUUID();
    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String PI = "pi_topup_1";

    @BeforeEach
    void setUp() {
        service = new WalletExternalRefundService(transactionRepository, refundItemRepository, holdRepository,
                walletService, auditService, adminAlertService);
    }

    private void topup(String currency) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(USER_ID);
        tx.setCurrency(currency);
        tx.setType(WalletTransactionType.TOP_UP);
        tx.setPaymentRef(PI);
        when(transactionRepository.findByPaymentRefAndType(PI, WalletTransactionType.TOP_UP)).thenReturn(List.of(tx));
    }

    private static Refund refund(String id, String status, long amount, Map<String, String> metadata) {
        Refund r = new Refund();
        r.setId(id);
        r.setStatus(status);
        r.setAmount(amount);
        r.setMetadata(metadata);
        return r;
    }

    private static Charge charge(Refund... refunds) {
        Charge c = new Charge();
        c.setPaymentIntent(PI);
        RefundCollection collection = new RefundCollection();
        collection.setData(List.of(refunds));
        collection.setHasMore(false);
        c.setRefunds(collection);
        return c;
    }

    private void savesWithId() {
        when(holdRepository.saveAndFlush(any())).thenAnswer(i -> {
            WalletExternalRefundHoldEntity h = i.getArgument(0);
            ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
            return h;
        });
    }

    @Test
    void sansPaymentIntent_rien() {
        assertThat(service.recordFromCharge(new Charge())).isZero();
        verifyNoInteractions(transactionRepository, holdRepository, adminAlertService);
    }

    @Test
    void chargeQuiNEstPasUneRecharge_rien() {
        when(transactionRepository.findByPaymentRefAndType(PI, WalletTransactionType.TOP_UP)).thenReturn(List.of());

        assertThat(service.recordFromCharge(charge(refund("re_1", "succeeded", 2500, null)))).isZero();
        verify(holdRepository, never()).saveAndFlush(any());
    }

    @Test
    void remboursementExterne_gelPose_auditEtAlerteAvecLaVoieDeRegularisation() {
        topup("EUR");
        savesWithId();
        when(walletService.getBalance(USER_ID, "EUR")).thenReturn(new BigDecimal("40.00"));

        assertThat(service.recordFromCharge(charge(refund("re_ext", "succeeded", 2500, Map.of())))).isEqualTo(1);

        ArgumentCaptor<WalletExternalRefundHoldEntity> hold = ArgumentCaptor.forClass(WalletExternalRefundHoldEntity.class);
        verify(holdRepository).saveAndFlush(hold.capture());
        assertThat(hold.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(hold.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(hold.getValue().getAmount()).isEqualByComparingTo("25.00");
        assertThat(hold.getValue().getStripeRefundId()).isEqualTo("re_ext");
        assertThat(hold.getValue().getStatus()).isEqualTo(WalletExternalRefundHoldStatus.OPEN);
        verify(auditService).log(eq("wallet_external_refund_hold"), any(), eq("WALLET_EXTERNAL_REFUND_HOLD_OPENED"),
                eq(USER_ID), anyMap());
        verify(adminAlertService).raise(eq(WalletExternalRefundService.ALERT_CODE), contains("correction de solde"),
                anyMap());
    }

    @Test
    void demandeDeRemboursementEnCours_alerteDitDeRésoudreSansCorrection() {
        topup("EUR");
        savesWithId();
        when(walletService.isRefundRequestPending(USER_ID, "EUR")).thenReturn(true);

        service.recordFromCharge(charge(refund("re_ext", "succeeded", 2500, null)));

        verify(adminAlertService).raise(eq(WalletExternalRefundService.ALERT_CODE), contains("sans correction en plus"),
                anyMap());
    }

    @Test
    void francCfa_sansCentimes() {
        topup("XOF");
        savesWithId();

        service.recordFromCharge(charge(refund("re_xof", "succeeded", 15000, null)));

        ArgumentCaptor<WalletExternalRefundHoldEntity> hold = ArgumentCaptor.forClass(WalletExternalRefundHoldEntity.class);
        verify(holdRepository).saveAndFlush(hold.capture());
        assertThat(hold.getValue().getAmount()).isEqualByComparingTo("15000");
    }

    @Test
    void remboursementsEmisParLApp_echoues_ouDejaGeles_ignores() {
        topup("EUR");
        when(refundItemRepository.existsByStripeRefundId("re_item_connu")).thenReturn(true);
        when(holdRepository.existsByStripeRefundId("re_deja_gele")).thenReturn(true);

        int opened = service.recordFromCharge(charge(
                refund("re_app", "succeeded", 1000, Map.of(WalletSelfRefundService.REFUND_ITEM_METADATA, "x")),
                refund("re_item_connu", "succeeded", 1000, null),
                refund("re_echoue", "failed", 1000, null),
                refund("re_deja_gele", "succeeded", 1000, null),
                refund(null, "succeeded", 1000, null)));

        assertThat(opened).isZero();
        verify(holdRepository, never()).saveAndFlush(any());
        verifyNoInteractions(adminAlertService);
    }

    @Test
    void montantNul_ignore() {
        topup("EUR");

        assertThat(service.recordFromCharge(charge(refund("re_zero", "succeeded", 0, null)))).isZero();
        verify(holdRepository, never()).saveAndFlush(any());
    }

    @Test
    void webhookRejoueEnParallele_contrainteUnique_sansSecondeAlerte() {
        topup("EUR");
        when(holdRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThat(service.recordFromCharge(charge(refund("re_ext", "succeeded", 2500, null)))).isZero();
        verifyNoInteractions(adminAlertService, auditService);
    }

    @Test
    void listeNonEmbarquee_luePresDeStripe() throws Exception {
        topup("EUR");
        savesWithId();
        Charge c = new Charge();
        c.setPaymentIntent(PI);
        RefundCollection listed = new RefundCollection();
        listed.setData(List.of(refund("re_lu", "succeeded", 500, null)));
        try (MockedStatic<Refund> refunds = mockStatic(Refund.class)) {
            refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(listed);

            assertThat(service.recordFromCharge(c)).isEqualTo(1);
        }
    }

    @Test
    void listeIllisible_leveePourQueLeWebhookSoitRejoue() {
        topup("EUR");
        Charge c = new Charge();
        c.setPaymentIntent(PI);
        try (MockedStatic<Refund> refunds = mockStatic(Refund.class)) {
            refunds.when(() -> Refund.list(any(RefundListParams.class)))
                    .thenThrow(new ApiConnectionException("réseau"));

            assertThatThrownBy(() -> service.recordFromCharge(c)).isInstanceOf(IllegalStateException.class);
        }
        verify(holdRepository, never()).saveAndFlush(any());
    }

    // ── Levée ────────────────────────────────────────────────────────────────

    private WalletExternalRefundHoldEntity openHold(UUID id, UUID owner) {
        WalletExternalRefundHoldEntity h = new WalletExternalRefundHoldEntity();
        ReflectionTestUtils.setField(h, "id", id);
        h.setUserId(owner);
        h.setCurrency("EUR");
        h.setAmount(new BigDecimal("25.00"));
        return h;
    }

    private static void assertCode(Throwable t, HttpStatus status, String code) {
        YadonyBusinessException e = (YadonyBusinessException) t;
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getErrorCode()).isEqualTo(code);
    }

    @Test
    void release_noteObligatoire() {
        assertThatThrownBy(() -> service.release(USER_ID, UUID.randomUUID(), ADMIN_ID, "  "))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "wallet-external-refund-hold-note-invalid"));
        assertThatThrownBy(() -> service.release(USER_ID, UUID.randomUUID(), ADMIN_ID, "x".repeat(501)))
                .satisfies(t -> assertCode(t, HttpStatus.BAD_REQUEST, "wallet-external-refund-hold-note-invalid"));
        verifyNoInteractions(holdRepository);
    }

    @Test
    void release_gelDUnAutreUtilisateur_404() {
        UUID id = UUID.randomUUID();
        when(holdRepository.findById(id)).thenReturn(Optional.of(openHold(id, UUID.randomUUID())));

        assertThatThrownBy(() -> service.release(USER_ID, id, ADMIN_ID, "Solde débité"))
                .satisfies(t -> assertCode(t, HttpStatus.NOT_FOUND, "wallet-external-refund-hold-not-found"));
        verify(holdRepository, never()).release(any(), any(), any(), any(), anyString());
    }

    @Test
    void release_dejaLeve_409() {
        UUID id = UUID.randomUUID();
        when(holdRepository.findById(id)).thenReturn(Optional.of(openHold(id, USER_ID)));
        when(holdRepository.release(eq(id), eq(USER_ID), eq(ADMIN_ID), any(), eq("Solde débité"))).thenReturn(0);

        assertThatThrownBy(() -> service.release(USER_ID, id, ADMIN_ID, " Solde débité "))
                .satisfies(t -> assertCode(t, HttpStatus.CONFLICT, "wallet-external-refund-hold-already-released"));
        verifyNoInteractions(auditService);
    }

    @Test
    void release_leveEtTrace() {
        UUID id = UUID.randomUUID();
        WalletExternalRefundHoldEntity hold = openHold(id, USER_ID);
        when(holdRepository.findById(id)).thenReturn(Optional.of(hold));
        when(holdRepository.release(eq(id), eq(USER_ID), eq(ADMIN_ID), any(), eq("Solde débité de 25 €")))
                .thenReturn(1);

        assertThat(service.release(USER_ID, id, ADMIN_ID, "Solde débité de 25 €")).isSameAs(hold);
        verify(auditService).log(eq("wallet_external_refund_hold"), eq(id), eq("WALLET_EXTERNAL_REFUND_HOLD_RELEASED"),
                eq(ADMIN_ID), anyMap());
    }

    @Test
    void holds_listeLesGelsDeLUtilisateur() {
        WalletExternalRefundHoldEntity hold = openHold(UUID.randomUUID(), USER_ID);
        when(holdRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(hold));

        assertThat(service.holds(USER_ID)).containsExactly(hold);
    }
}
