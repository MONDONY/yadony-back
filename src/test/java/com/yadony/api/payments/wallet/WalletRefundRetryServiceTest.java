package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletRefundRetryServiceTest {

    static final UUID USER_ID = UUID.randomUUID();
    static final UUID ADMIN_ID = UUID.randomUUID();
    static final String MSISDN = "2250734567890";

    @Mock WalletRefundRequestRepository requestRepository;
    @Mock WalletRefundRequestItemRepository itemRepository;
    @Mock WalletTransactionRepository transactionRepository;
    @Mock PawapayOperationRepository operationRepository;
    @Mock PawapayClient client;
    @Mock WalletPawapayRefundIssuer issuer;
    @Mock AuditService auditService;
    @Mock EntityManager entityManager;

    WalletRefundRetryService service;
    WalletRefundRequestEntity request;
    PawapayOperationEntity deposit;
    List<WalletRefundRequestItemEntity> items;

    @BeforeEach
    void setUp() {
        PawapayFeeTable table = new PawapayFeeTable(
                new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
                Map.of("ORANGE_CIV", new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0"))));
        PawapayRefundFunds funds = new PawapayRefundFunds(client, table, new BigDecimal("1.0"));
        service = new WalletRefundRetryService(requestRepository, itemRepository, transactionRepository,
                operationRepository, table, funds, issuer, auditService, entityManager);

        request = new WalletRefundRequestEntity();
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.setUserId(USER_ID);
        request.setCurrency("XOF");
        request.setAmount(new BigDecimal("500.00"));
        request.setChannel(WalletRefundChannel.MANUAL_ADMIN);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setParentRequestId(UUID.randomUUID());

        deposit = deposit(MSISDN, "ORANGE_CIV", "CI");
        items = new ArrayList<>(List.of(item(deposit, "500.00", "10.00", 1)));

        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(itemRepository.findByRefundRequestId(request.getId())).thenAnswer(inv -> items);
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(transactionRepository.findById(any())).thenReturn(Optional.empty());
        stubBalance("487.50");
        // Le rail réel lie une opération REFUND à chaque item confié.
        doAnswer(inv -> {
            List<WalletRefundRequestItemEntity> issued = inv.getArgument(1);
            issued.forEach(i -> {
                i.setPawapayRefundId(UUID.randomUUID());
                i.setStatus(WalletRefundItemStatus.PROCESSING);
            });
            return null;
        }).when(issuer).issue(any(), any(), anyBoolean());
    }

    static PawapayOperationEntity deposit(String msisdn, String provider, String country) {
        return new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, USER_ID, null, null, new BigDecimal("500"), "XOF",
                provider, country, msisdn);
    }

    WalletRefundRequestItemEntity item(PawapayOperationEntity source, String amount, String fee, int order) {
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(item, "createdAt", Instant.parse("2026-10-18T10:00:00Z").plusSeconds(order));
        item.setRefundRequestId(request.getId());
        item.setWalletTransactionId(UUID.randomUUID());
        item.setPaymentIntentId("pawapay:" + source.getId());
        item.setAmount(new BigDecimal(amount));
        item.setFeeAmount(new BigDecimal(fee));
        item.setStatus(WalletRefundItemStatus.PENDING);
        return item;
    }

    void stubBalance(String balance) {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("CIV", "XOF", new BigDecimal(balance)),
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("100000"))));
    }

    static YadonyBusinessException business(Throwable t) {
        return (YadonyBusinessException) t;
    }

    void assertNothingSent() {
        verify(issuer, never()).issue(any(), any(), anyBoolean());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.PENDING);
    }

    // ── Aperçu ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Aperçu, cas réel : dû 477, solde 487,50, sûr 473, manque 4, relance fermée (jamais de partiel)")
    void preview_casReel() {
        WalletRefundRetryService.Preview p = service.preview(request.getId());

        assertThat(p.amountDue()).isEqualByComparingTo("477");
        assertThat(p.grossAmount()).isEqualByComparingTo("500");
        assertThat(p.walletBalance()).isEqualByComparingTo("487.50");
        assertThat(p.maxSafeAmount()).isEqualByComparingTo("473");
        assertThat(p.suggestedAmount()).isNull();
        assertThat(p.topUpNeeded()).isEqualByComparingTo("4");
        assertThat(p.refundRatePercent()).isEqualByComparingTo("3.0");
        assertThat(p.country()).isEqualTo("CI");
        assertThat(p.currency()).isEqualTo("XOF");
        assertThat(p.provider()).isEqualTo("ORANGE_CIV");
        assertThat(p.msisdnMasked()).isEqualTo(deposit.getMsisdnMasked()).doesNotContain("0734567");
        assertThat(p.retryAvailable()).isFalse();
        assertThat(p.unavailableReason()).isEqualTo("pawapay-insufficient-funds");
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("Aperçu, wallet assez garni : suggéré = dû, aucun manque")
    void preview_walletGarni() {
        stubBalance("10000");
        WalletRefundRetryService.Preview p = service.preview(request.getId());
        assertThat(p.suggestedAmount()).isEqualByComparingTo("477");
        assertThat(p.topUpNeeded()).isZero();
        assertThat(p.retryAvailable()).isTrue();
        assertThat(p.unavailableReason()).isNull();
    }

    @Test
    @DisplayName("Aperçu, solde illisible : 200 dégradé, solde nul et relance indisponible")
    void preview_soldeIllisible() {
        when(client.walletBalances()).thenThrow(new RestClientException("down"));
        WalletRefundRetryService.Preview p = service.preview(request.getId());
        assertThat(p.amountDue()).isEqualByComparingTo("477");
        assertThat(p.walletBalance()).isNull();
        assertThat(p.maxSafeAmount()).isNull();
        assertThat(p.suggestedAmount()).isNull();
        assertThat(p.topUpNeeded()).isNull();
        assertThat(p.retryAvailable()).isFalse();
        assertThat(p.unavailableReason()).isEqualTo("pawapay-balance-unavailable");
    }

    @Test
    @DisplayName("Aperçu, wallet vide : rien de sûr, relance indisponible pour manque de fonds")
    void preview_walletVide() {
        stubBalance("0");
        WalletRefundRetryService.Preview p = service.preview(request.getId());
        assertThat(p.maxSafeAmount()).isZero();
        assertThat(p.suggestedAmount()).isNull();
        assertThat(p.topUpNeeded()).isEqualByComparingTo("492");
        assertThat(p.retryAvailable()).isFalse();
        assertThat(p.unavailableReason()).isEqualTo("pawapay-insufficient-funds");
    }

    @Test
    @DisplayName("Recharge entamée (montant d'origine supérieur) : pas de frais, le dû est le montant de l'item")
    void preview_rechargeEntamee_sansFrais() {
        WalletTransactionEntity topup = org.mockito.Mockito.mock(WalletTransactionEntity.class);
        when(topup.getAmount()).thenReturn(new BigDecimal("800.00"));
        when(transactionRepository.findById(items.get(0).getWalletTransactionId())).thenReturn(Optional.of(topup));
        stubBalance("10000");
        assertThat(service.preview(request.getId()).amountDue()).isEqualByComparingTo("500");
    }

    // ── Éligibilité ──────────────────────────────────────────────────────────

    @Test
    void introuvable_404() {
        UUID unknown = UUID.randomUUID();
        when(requestRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(unknown))
                .satisfies(t -> assertThat(business(t).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void pasManuel_422() {
        request.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        assertNotEligible(() -> service.retry(request.getId(), null, ADMIN_ID), "not-manual");
        assertNothingSent();
    }

    @Test
    void dejaResolu_422() {
        request.setStatus(WalletRefundRequestStatus.RESOLVED);
        assertNotEligible(() -> service.preview(request.getId()), "not-pending");
    }

    @Test
    void ticketSansItem_422() {
        items.clear();
        assertNotEligible(() -> service.preview(request.getId()), "no-mobile-money-source");
    }

    @Test
    void rechargeParCarte_422() {
        items.get(0).setPaymentIntentId("pi_card_123");
        assertNotEligible(() -> service.retry(request.getId(), null, ADMIN_ID), "not-mobile-money-topup");
        assertNothingSent();
    }

    @Test
    void depotIntrouvableOuIllisible_422() {
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.empty());
        assertNotEligible(() -> service.preview(request.getId()), "deposit-missing");
        items.get(0).setPaymentIntentId("pawapay:pas-un-uuid");
        assertNotEligible(() -> service.preview(request.getId()), "deposit-missing");
    }

    @Test
    void depotQuiNEstPasUneRecharge_422() {
        PawapayOperationEntity payout = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.WALLET_REFUND, USER_ID, null, null, BigDecimal.TEN, "XOF", "ORANGE_CIV", "CI",
                MSISDN);
        items.get(0).setPaymentIntentId("pawapay:" + payout.getId());
        when(operationRepository.findById(payout.getId())).thenReturn(Optional.of(payout));
        assertNotEligible(() -> service.preview(request.getId()), "deposit-missing");
    }

    @Test
    void deviseDifferente_422() {
        request.setCurrency("XAF");
        assertNotEligible(() -> service.preview(request.getId()), "currency-mismatch");
    }

    @Test
    void plusieursNumeros_422() {
        PawapayOperationEntity other = deposit("2250700000000", "ORANGE_CIV", "CI");
        when(operationRepository.findById(other.getId())).thenReturn(Optional.of(other));
        items.add(item(other, "200.00", "4.00", 2));
        assertNotEligible(() -> service.preview(request.getId()), "multiple-destinations");
    }

    @Test
    void fraisQuiAbsorbentTout_422() {
        items.get(0).setAmount(new BigDecimal("0"));
        assertNotEligible(() -> service.preview(request.getId()), "nothing-to-refund");
    }

    @Test
    @DisplayName("Double relance : la demande déjà PROCESSING est refusée sans rien envoyer")
    void doubleRelance_refusee() {
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-in-progress"));
        verify(issuer, never()).issue(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Relance concurrente : le premier passage voit PENDING, le verrou relit PROCESSING → 422")
    void relanceConcurrente_relueSousVerrou() {
        doAnswer(inv -> {
            request.setStatus(WalletRefundRequestStatus.PROCESSING);
            return null;
        }).when(entityManager).refresh(request);
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-in-progress"));
        verify(issuer, never()).issue(any(), any(), anyBoolean());
    }

    @Test
    void itemDejaLieAUneOperation_422EnCours() {
        items.get(0).setPawapayPayoutId(UUID.randomUUID());
        assertThatThrownBy(() -> service.preview(request.getId()))
                .satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-in-progress"));
    }

    @Test
    void operationPawapayOuverte_422EnCours() {
        when(operationRepository.existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn(eq(USER_ID),
                eq(PawapayOperationKind.PAYOUT), eq("XOF"), eq(PawapayOperationPurpose.WALLET_REFUND), any()))
                .thenReturn(true);
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-in-progress"));
        assertNothingSent();
    }

    // ── Montant ──────────────────────────────────────────────────────────────

    @Test
    void montantNulOuNegatif_422() {
        assertCode(() -> service.retry(request.getId(), BigDecimal.ZERO, ADMIN_ID), "wallet-refund-retry-amount-invalid");
        assertNothingSent();
    }

    @Test
    @DisplayName("Montant inférieur au dû : 422, jamais de versement partiel (l'écart serait perdu)")
    void montantInferieurAuDu_422() {
        stubBalance("100000");
        assertThatThrownBy(() -> service.retry(request.getId(), new BigDecimal("473"), ADMIN_ID))
                .satisfies(t -> {
                    assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-partial-not-allowed");
                    assertThat((BigDecimal) business(t).getProperties().get("amountDue")).isEqualByComparingTo("477");
                });
        assertNothingSent();
        verify(itemRepository, never()).save(any());
    }

    @Test
    void montantSuperieurAuDu_422() {
        stubBalance("100000");
        assertThatThrownBy(() -> service.retry(request.getId(), new BigDecimal("478"), ADMIN_ID))
                .satisfies(t -> {
                    assertThat(business(t).getErrorCode()).isEqualTo("wallet-refund-retry-amount-too-high");
                    assertThat((BigDecimal) business(t).getProperties().get("amountDue")).isEqualByComparingTo("477");
                });
        assertNothingSent();
    }

    @Test
    void montantAvecDecimalesEnXof_422() {
        assertCode(() -> service.retry(request.getId(), new BigDecimal("472.5"), ADMIN_ID),
                "wallet-refund-retry-amount-scale");
        assertNothingSent();
    }

    @Test
    @DisplayName("Cas réel, dû au-delà du sûr : 422 avec maxSafeAmount et topUpNeeded, aucun envoi à pawaPay")
    void montantAuDelaDuSur_422SansEnvoi() {
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> {
                    YadonyBusinessException e = business(t);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("wallet-refund-insufficient-pawapay-funds");
                    assertThat((BigDecimal) e.getProperties().get("maxSafeAmount")).isEqualByComparingTo("473");
                    assertThat((BigDecimal) e.getProperties().get("topUpNeeded")).isEqualByComparingTo("4");
                    assertThat((BigDecimal) e.getProperties().get("walletBalance")).isEqualByComparingTo("487.50");
                    assertThat(e.getProperties()).containsEntry("country", "CI");
                });
        assertNothingSent();
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("Wallet vide sans montant choisi : 422 manque de fonds, aucun envoi")
    void walletVide_sansMontant_422() {
        stubBalance("0");
        assertCode(() -> service.retry(request.getId(), null, ADMIN_ID), "wallet-refund-insufficient-pawapay-funds");
        assertNothingSent();
    }

    @Test
    @DisplayName("Solde illisible à la relance : 503, aucun envoi")
    void soldeIllisible_503() {
        when(client.walletBalances()).thenThrow(new RestClientException("down"));
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> {
                    assertThat(business(t).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(business(t).getErrorCode()).isEqualTo("pawapay-balance-unavailable");
                });
        assertNothingSent();
    }

    // ── Succès ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Succès : le dû entier (477) confié au rail, frais du barème (23), PROCESSING, audit")
    void succes_duEntierAvecFraisDuBareme() {
        stubBalance("491.50"); // le cas réel après avoir alimenté le wallet des 4 XOF manquants
        WalletRefundRetryService.Retried r = service.retry(request.getId(), null, ADMIN_ID);

        assertThat(r.amount()).isEqualByComparingTo("477");
        assertThat(r.amountDue()).isEqualByComparingTo("477");
        assertThat(r.maxSafeAmount()).isEqualByComparingTo("477");
        assertThat(r.walletBalance()).isEqualByComparingTo("491.50");
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WalletRefundRequestItemEntity>> issued = ArgumentCaptor.forClass(List.class);
        verify(issuer).issue(eq(request), issued.capture(), eq(false));
        WalletRefundRequestItemEntity item = issued.getValue().get(0);
        // Le rail envoie amount - feeAmount : 500 - 23 (barème 2,5 + 2 %) = 477, vers le dépôt d'origine.
        assertThat(item.getFeeAmount()).isEqualByComparingTo("23");
        assertThat(item.getAmount().subtract(item.getFeeAmount())).isEqualByComparingTo("477");
        assertThat(item.getPaymentIntentId()).isEqualTo("pawapay:" + deposit.getId());
        verify(entityManager).refresh(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("wallet_refund_request"), eq(request.getId()),
                eq(WalletRefundRetryService.AUDIT_ACTION), eq(ADMIN_ID), payload.capture());
        assertThat(payload.getValue()).containsEntry("amount", "477").containsEntry("amountDue", "477")
                .containsEntry("walletBalance", "491.50").containsEntry("maxSafe", "477")
                .containsEntry("country", "CI").containsEntry("provider", "ORANGE_CIV");
        assertThat(payload.getValue().toString()).doesNotContain(MSISDN);
    }

    @Test
    @DisplayName("Succès, montant confirmé égal au dû")
    void succes_montantConfirme() {
        stubBalance("10000");
        WalletRefundRetryService.Retried r = service.retry(request.getId(), new BigDecimal("477.00"), ADMIN_ID);
        assertThat(r.amount()).isEqualTo(new BigDecimal("477"));
    }

    @Test
    @DisplayName("Plusieurs recharges du même numéro : chaque item garde le frais du barème, tout est versé")
    void plusieursRecharges_toutVerse() {
        WalletRefundRequestItemEntity second = item(deposit, "200.00", "4.00", 2);
        items.add(second);
        stubBalance("100000");

        WalletRefundRetryService.Retried r = service.retry(request.getId(), null, ADMIN_ID);

        assertThat(r.amount()).isEqualByComparingTo("668"); // 477 + (200 - 9)
        assertThat(items.get(0).getFeeAmount()).isEqualByComparingTo("23");
        assertThat(second.getFeeAmount()).isEqualByComparingTo("9");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WalletRefundRequestItemEntity>> issued = ArgumentCaptor.forClass(List.class);
        verify(issuer).issue(eq(request), issued.capture(), eq(false));
        assertThat(issued.getValue()).containsExactly(items.get(0), second);
    }

    @Test
    void recetteDontLesFraisAbsorbentLeMontant_422() {
        WalletRefundRequestItemEntity tiny = item(deposit, "0.00", "0.00", 2);
        items.add(tiny);
        assertNotEligible(() -> service.preview(request.getId()), "nothing-to-refund");
    }

    @Test
    @DisplayName("Réservation impossible chez pawaPay : 502, tout est annulé (transaction)")
    void reservationImpossible_502() {
        stubBalance("10000");
        doAnswer(inv -> null).when(issuer).issue(any(), any(), anyBoolean());
        assertThatThrownBy(() -> service.retry(request.getId(), null, ADMIN_ID))
                .satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo("mobile-money-provider-unavailable"));
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    private static void assertNotEligible(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).satisfies(t -> {
            YadonyBusinessException e = business(t);
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo("wallet-refund-retry-not-eligible");
            assertThat(e.getProperties()).containsEntry("reason", reason);
        });
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).satisfies(t -> assertThat(business(t).getErrorCode()).isEqualTo(code));
    }
}
