package com.yadony.api.payments.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.net.ApiResource;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletSelfRefundServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock WalletRefundRequestRepository refundRequestRepository;
    @Mock WalletRefundRequestItemRepository refundRequestItemRepository;
    @Mock WalletService walletService;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;
    @Mock AdminAlertEscalator adminAlertEscalator;
    @Mock WalletRefundRequestService walletRefundRequestService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PawapayOperationRepository pawapayOperationRepository;
    @Mock StripeFeeSource stripeFeeSource;
    @Mock PawapayFeeTable pawapayFeeTable;
    @Mock WalletRefundRailIssuer walletRefundRailIssuer;
    @Mock EntityManager entityManager;

    WalletSelfRefundService service;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletSelfRefundService(walletAccountRepository, walletTransactionRepository,
                refundRequestRepository, refundRequestItemRepository, walletService,
                auditService, adminAlertService, adminAlertEscalator, new ObjectMapper(),
                walletRefundRequestService, eventPublisher, pawapayOperationRepository,
                stripeFeeSource, pawapayFeeTable, walletRefundRailIssuer, entityManager);
        // Repli neutre : la plupart des scénarios ne testent pas le calcul de frais lui-même
        // (déjà couvert par WalletRefundAllocatorTest), seulement le branchement canal/montant
        // net. lenient() : tous les tests n'atteignent pas forcément une recharge intacte (la
        // seule situation où feeFor() interroge réellement ces sources, cf. FeeCalculator).
        lenient().when(stripeFeeSource.fee(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(stripeFeeSource.fallback(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(pawapayFeeTable.fee(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setUserId(USER_ID);
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private WalletAccountEntity wallet(String balance) {
        return wallet("EUR", balance);
    }

    private WalletTransactionEntity ledgerTx(String currency, WalletTransactionType type, String signedAmount, String paymentRef) {
        WalletTransactionEntity t = new WalletTransactionEntity();
        setField(t, "id", UUID.randomUUID());
        t.setUserId(USER_ID);
        t.setCurrency(currency);
        t.setType(type);
        t.setAmount(new BigDecimal(signedAmount));
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setPaymentRef(paymentRef);
        setField(t, "createdAt", Instant.now());
        return t;
    }

    private WalletTransactionEntity ledgerTx(WalletTransactionType type, String signedAmount, String paymentRef) {
        return ledgerTx("EUR", type, signedAmount, paymentRef);
    }

    private void stubLedger(String currency, String balance, WalletTransactionEntity... txs) {
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, currency)).thenReturn(Optional.of(wallet(currency, balance)));
        when(walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(USER_ID, currency))
                .thenReturn(List.of(txs));
        when(refundRequestItemRepository.findByWalletTransactionIdIn(any())).thenReturn(List.of());
    }

    private void stubLedger(String balance, WalletTransactionEntity... txs) {
        stubLedger("EUR", balance, txs);
    }

    /** Reprend le helper par réflexion de {@code WalletRefundAllocatorTest} (Tâche 2). */
    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalStateException("champ absent : " + name);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** Reprend le helper de {@code WalletRefundRequestServiceTest} : un {@code WalletRefundRequestEntity}
     *  construit à la main n'a jamais d'id (assigné par Hibernate au flush réel). */
    private static void assignId(WalletRefundRequestEntity entity) {
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Dernier item passé à {@code refundRequestItemRepository.save}, capturé sur le mock. */
    private WalletRefundRequestItemEntity lastSavedItem() {
        ArgumentCaptor<WalletRefundRequestItemEntity> captor = ArgumentCaptor.forClass(WalletRefundRequestItemEntity.class);
        verify(refundRequestItemRepository, atLeastOnce()).save(captor.capture());
        List<WalletRefundRequestItemEntity> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    @Test
    void allocation_rechargeEntamee_resteRemboursable() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("35.00", topup, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));

        WalletRefundAllocation a = service.allocation(USER_ID, "EUR");

        assertThat(a.refundableTotal()).isEqualByComparingTo("35.00");
        assertThat(a.refundable().get(0).paymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void allocation_invariantCasse_alerteEtRejette() {
        stubLedger("99.00", ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1"));

        assertThatThrownBy(() -> service.allocation(USER_ID, "EUR"))
                .isInstanceOf(WalletAllocationInvariantException.class);
        // raiseOnce : le type porte l'identifiant utilisateur, la dédup evite un INCIDENT
        // synchrone (log.error + Sentry + Telegram) a chaque GET /wallet/balance.
        verify(adminAlertEscalator).raiseOnce(eq("wallet-alloc-" + USER_ID), any(), any());
    }

    @Test
    void allocation_walletAbsent_vide() {
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.empty());

        assertThat(service.allocation(USER_ID, "EUR").refundableTotal()).isEqualByComparingTo("0");
    }

    @Test
    void isEligible_avecAllocationFournie_neRejouePasLeLedger() {
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);
        WalletRefundAllocation deja = new WalletRefundAllocation(List.of(),
                new BigDecimal("35.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("35.00"));

        assertThat(service.isEligible(USER_ID, "EUR", deja)).isTrue();
        verifyNoInteractions(walletAccountRepository, walletTransactionRepository);
    }

    @Test
    void isEligible_avecAllocationFournie_fauxQuandUneDemandeEstActive() {
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(true);
        WalletRefundAllocation deja = new WalletRefundAllocation(List.of(),
                new BigDecimal("35.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("35.00"));

        assertThat(service.isEligible(USER_ID, "EUR", deja)).isFalse();
    }

    @Test
    void listEligibleTopups_neLitLeLedgerQuUneFois() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("35.00", topup, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(USER_ID, "EUR",
                List.of(WalletRefundRequestStatus.PROCESSING))).thenReturn(Optional.empty());
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);

        service.listEligibleTopups(USER_ID, "EUR");

        verify(walletTransactionRepository, times(1))
                .findByUserIdAndCurrencyOrderByCreatedAtAsc(USER_ID, "EUR");
    }

    @Test
    void listEligibleTopups_renvoieLeRestantParRecharge() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("35.00", topup, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(USER_ID, "EUR",
                List.of(WalletRefundRequestStatus.PROCESSING))).thenReturn(Optional.empty());
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);

        List<WalletSelfRefundService.EligibleTopup> list = service.listEligibleTopups(USER_ID, "EUR");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).topup().getId()).isEqualTo(topup.getId());
        assertThat(list.get(0).remaining()).isEqualByComparingTo("35.00");
    }

    @Test
    void listEligibleTopups_emptyWhenActiveRequestExists() {
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(
                USER_ID, "EUR", List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING)))
                .thenReturn(true);

        assertThat(service.listEligibleTopups(USER_ID, "EUR")).isEmpty();
        verifyNoInteractions(walletAccountRepository, walletTransactionRepository);
    }

    @Test
    void listEligibleTopups_reconcilesStaleProcessingRequestBeforeCheckingActiveRequest() {
        // Régression : un item resté PROCESSING alors que Stripe a déjà terminé le
        // remboursement (webhook manqué, cf. PaymentStripeWebhookHandler.resolveCharge)
        // bloquait sinon la liste indéfiniment, même après une nouvelle recharge.
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity stale = new WalletRefundRequestEntity();
        stale.setUserId(USER_ID);
        stale.setCurrency("EUR");
        stale.setStatus(WalletRefundRequestStatus.PROCESSING);
        stale.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        setId(stale, requestId);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        item.setStripeRefundId("re_stale");
        item.setAmount(new BigDecimal("10.00"));

        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(
                USER_ID, "EUR", List.of(WalletRefundRequestStatus.PROCESSING)))
                .thenReturn(Optional.of(stale));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(stale));
        when(refundRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Après réconciliation, la demande n'est plus PROCESSING : plus aucun blocage.
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(
                USER_ID, "EUR", List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING)))
                .thenReturn(false);
        stubLedger("40.00", ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1"));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = mock(Refund.class);
            when(refund.getStatus()).thenReturn("succeeded");
            refundStatic.when(() -> Refund.retrieve("re_stale")).thenReturn(refund);

            service.listEligibleTopups(USER_ID, "EUR");
        }

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        assertThat(stale.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("10.00"),
                WalletTransactionType.SELF_REFUND_OUT);
    }

    @Test
    void request_sansSelection_creeLesItemsPendingEtPublieLEvenementSansAppelerStripe() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("35.00", topup, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of());

            assertThat(saved.getAmount()).isEqualByComparingTo("35.00");
            assertThat(saved.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_STRIPE);
            assertThat(saved.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);
            // Le remboursement Stripe ne part plus dans la transaction de la demande.
            refundStatic.verifyNoInteractions();
            verify(eventPublisher).publishEvent(new WalletRefundItemsCreatedEvent(saved.getId()));
        }
        WalletRefundRequestItemEntity item = lastSavedItem();
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PENDING);
        assertThat(item.getStripeRefundId()).isNull();
        assertThat(item.getPaymentIntentId()).isEqualTo("pi_1");
        assertThat(item.getAmount()).isEqualByComparingTo("35.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void request_auditItems_structureSerialisableEtPasUnToString() {
        // Régression : "items" partait en auditItems.toString(), soit
        // "[{paymentIntentId=pi_a, amount=20.00}]" dans un payload JSONB — ni requêtable
        // par jsonb_array_elements, ni relisible sans parsing maison.
        WalletTransactionEntity a = ledgerTx(WalletTransactionType.TOP_UP, "20.00", "pi_a");
        WalletTransactionEntity b = ledgerTx(WalletTransactionType.TOP_UP, "30.00", "pi_b");
        stubLedger("45.00", a, b, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.request(USER_ID, "EUR", List.of());

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("wallet_refund_request"), any(), eq("AUTOMATIC_REQUESTED"),
                eq(USER_ID), payload.capture());
        Object items = payload.getValue().get("items");
        assertThat(items).isInstanceOf(List.class);
        List<Map<String, String>> list = (List<Map<String, String>>) items;
        assertThat(list).hasSize(2);
        assertThat(list).allSatisfy(item -> assertThat(item)
                .containsOnlyKeys("paymentIntentId", "amount", "status"));
        assertThat(list).extracting(item -> item.get("paymentIntentId"))
                .containsExactlyInAnyOrder("pi_a", "pi_b");
        assertThat(list).extracting(item -> item.get("status")).containsOnly("PENDING");
        assertThat(payload.getValue().get("refundableTotal")).isEqualTo("45.00");
        assertThat(payload.getValue().get("nonRefundable")).isEqualTo("0");
    }

    @Test
    void request_deviseSansDecimales_montantAligneUniteMineureAvantCreationDeLItem() {
        // Régression : le ledger interne garde toujours 2 décimales (NUMERIC(10,2)), même pour
        // XOF (0 décimale). Sans mise à l'échelle avant la création de l'item, un reliquat comme
        // 9999.50 XOF faisait lever ArithmeticException dans Refund.create (longValueExact()).
        WalletTransactionEntity topup = ledgerTx("XOF", WalletTransactionType.TOP_UP, "13200", "pi_xof");
        stubLedger("XOF", "9999.50", topup, ledgerTx("XOF", WalletTransactionType.BID_PAYMENT, "-3200.50", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("XOF"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRefundRequestEntity saved = service.request(USER_ID, "XOF", List.of());

        assertThat(saved.getAmount()).isEqualByComparingTo("9999");
        assertThat(lastSavedItem().getAmount()).isEqualByComparingTo("9999");
    }

    /** Demande automatique PROCESSING en devise {@code currency}, servie verrouillée et par id. */
    private WalletRefundRequestEntity stubIssuable(String currency, String amount) {
        WalletRefundRequestEntity request = processingRequest(amount);
        request.setCurrency(currency);
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private WalletRefundRequestItemEntity pendingItem(WalletRefundRequestEntity r, String pi, String amount) {
        WalletRefundRequestItemEntity i = processingItem(r, pi, amount, null);
        i.setStatus(WalletRefundItemStatus.PENDING);
        return i;
    }

    @Test
    void issuePendingItems_emetLesItemsPendingEnUniteMineureAvecCleDIdempotence() {
        WalletRefundRequestEntity request = stubIssuable("XOF", "9999");
        WalletRefundRequestItemEntity item = pendingItem(request, "pi_xof", "9999");
        when(refundRequestItemRepository.findUnissuedForUpdate(request.getId(), WalletRefundItemStatus.PENDING))
                .thenReturn(List.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = new Refund();
            refund.setId("re_xof");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(params.capture(), options.capture())).thenReturn(refund);

            service.issuePendingItems(request.getId());

            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_xof");
            assertThat(params.getValue().getAmount()).isEqualTo(9999L);
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("wallet-self-refund-" + item.getId());
        }
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        assertThat(item.getStripeRefundId()).isEqualTo("re_xof");
        // Item encore PROCESSING : la demande reste ouverte, rien n'est débité.
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);
        verifyNoInteractions(walletService, walletRefundRequestService);
    }

    @Test
    void issuePendingItems_ignoreLesItemsDejaEmis() {
        // La requête verrouillée ne remonte que les items sans stripeRefundId : un item déjà
        // émis (PROCESSING) ne repart pas vers Stripe, la demande reste ouverte.
        WalletRefundRequestEntity request = stubIssuable("EUR", "35.00");
        WalletRefundRequestItemEntity emitted = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findUnissuedForUpdate(request.getId(), WalletRefundItemStatus.PENDING))
                .thenReturn(List.of());
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(emitted));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            service.issuePendingItems(request.getId());

            refundStatic.verifyNoInteractions();
        }
        assertThat(emitted.getStripeRefundId()).isEqualTo("re_1");
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);
    }

    @Test
    void issuePendingItems_demandeNonProcessing_neFaitRien() {
        WalletRefundRequestEntity request = stubIssuable("EUR", "35.00");
        request.setStatus(WalletRefundRequestStatus.FAILED);

        service.issuePendingItems(request.getId());

        verify(refundRequestItemRepository, never()).findUnissuedForUpdate(any(), any());
    }

    @Test
    void issuePendingItems_demandeManuelle_neFaitRien() {
        WalletRefundRequestEntity request = stubIssuable("EUR", "35.00");
        request.setChannel(WalletRefundChannel.MANUAL_ADMIN);

        service.issuePendingItems(request.getId());

        verify(refundRequestItemRepository, never()).findUnissuedForUpdate(any(), any());
    }

    @Test
    void issuePendingItems_demandeIntrouvable_neFaitRien() {
        UUID unknown = UUID.randomUUID();
        when(refundRequestRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        service.issuePendingItems(unknown);

        verifyNoInteractions(refundRequestItemRepository);
    }

    @Test
    void issuePendingItems_estEnRequiresNew() throws NoSuchMethodException {
        Transactional transactional = WalletSelfRefundService.class
                .getMethod("issuePendingItems", UUID.class).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void request_selectionAncienClient_rembourseLeRestantDesRechargesListees() {
        WalletTransactionEntity a = ledgerTx(WalletTransactionType.TOP_UP, "20.00", "pi_a");
        WalletTransactionEntity b = ledgerTx(WalletTransactionType.TOP_UP, "30.00", "pi_b");
        stubLedger("45.00", a, b, ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of(b.getId()));

        assertThat(saved.getAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void request_selectionSansRestant_422() {
        WalletTransactionEntity a = ledgerTx(WalletTransactionType.TOP_UP, "20.00", "pi_a");
        stubLedger("0.00", a, ledgerTx(WalletTransactionType.BID_PAYMENT, "-20.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(USER_ID, "EUR", List.of(a.getId())))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-not-refund-eligible");
    }

    @Test
    void request_demandeDejaEnCours_renvoieLExistante() {
        WalletRefundRequestEntity existing = new WalletRefundRequestEntity();
        assignId(existing);
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.of(existing));

        assertThat(service.request(USER_ID, "EUR", List.of())).isSameAs(existing);
        verifyNoInteractions(walletTransactionRepository);
    }

    @Test
    void request_sansRienDeRemboursable_422() {
        stubLedger("5.00", ledgerTx(WalletTransactionType.REFERRAL_REWARD, "5.00", null));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(USER_ID, "EUR", List.of()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-not-refund-eligible");
    }

    @Test
    void request_pawapayTargets_setsPawapayChannelAndFeeOnItem() {
        UUID opId = UUID.randomUUID();
        WalletTransactionEntity topup = ledgerTx("XOF", WalletTransactionType.TOP_UP, "10000", "pawapay:" + opId);
        stubLedger("XOF", "10000", topup);
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(op.getId()).thenReturn(opId);
        when(op.getProvider()).thenReturn("ORANGE_CIV");
        when(pawapayOperationRepository.findByUserIdAndPurposeAndKind(
                USER_ID, PawapayOperationPurpose.WALLET_TOPUP, PawapayOperationKind.DEPOSIT))
                .thenReturn(List.of(op));
        when(pawapayFeeTable.fee(eq("ORANGE_CIV"), any(), eq("XOF"))).thenReturn(new BigDecimal("200"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("XOF"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRefundRequestEntity saved = service.request(USER_ID, "XOF", List.of());

        assertThat(saved.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        WalletRefundRequestItemEntity item = lastSavedItem();
        assertThat(item.getAmount()).isEqualByComparingTo("10000");
        assertThat(item.getFeeAmount()).isEqualByComparingTo("200");

        // issuePendingItems delegue a WalletRefundRailIssuer, jamais a Stripe, pour ce canal.
        when(refundRequestRepository.findByIdForUpdate(saved.getId())).thenReturn(Optional.of(saved));
        when(refundRequestItemRepository.findUnissuedForUpdate(saved.getId(), WalletRefundItemStatus.PENDING))
                .thenReturn(List.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(saved.getId())).thenReturn(List.of(item));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            service.issuePendingItems(saved.getId());

            refundStatic.verifyNoInteractions();
        }
        verify(walletRefundRailIssuer).issue(saved, List.of(item));
    }

    @Test
    void request_stripeTargets_emitsNetAmount() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("40.00", topup);
        when(stripeFeeSource.fee("pi_1", "EUR")).thenReturn(new BigDecimal("1.51"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of());

        assertThat(saved.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_STRIPE);
        WalletRefundRequestItemEntity item = lastSavedItem();
        assertThat(item.getAmount()).isEqualByComparingTo("40.00");
        assertThat(item.getFeeAmount()).isEqualByComparingTo("1.51");

        when(refundRequestRepository.findByIdForUpdate(saved.getId())).thenReturn(Optional.of(saved));
        when(refundRequestItemRepository.findUnissuedForUpdate(saved.getId(), WalletRefundItemStatus.PENDING))
                .thenReturn(List.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(saved.getId())).thenReturn(List.of(item));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = new Refund();
            refund.setId("re_1");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class))).thenReturn(refund);

            service.issuePendingItems(saved.getId());

            assertThat(params.getValue().getAmount()).isEqualTo(3849L);
        }
        verifyNoInteractions(walletRefundRailIssuer);
    }

    @Test
    void request_targetWithNetZero_isSkipped() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "5.00", "pi_1");
        stubLedger("5.00", topup);
        when(stripeFeeSource.fee("pi_1", "EUR")).thenReturn(new BigDecimal("5.00"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(USER_ID, "EUR", List.of()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-not-refund-eligible");
        verifyNoInteractions(auditService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void request_uneCibleExclueParLesFrais_auditSkippedForFeesEtNetCorrects() {
        WalletTransactionEntity a = ledgerTx(WalletTransactionType.TOP_UP, "5.00", "pi_a");
        WalletTransactionEntity b = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_b");
        stubLedger("45.00", a, b);
        when(stripeFeeSource.fee("pi_a", "EUR")).thenReturn(new BigDecimal("5.00"));
        when(stripeFeeSource.fee("pi_b", "EUR")).thenReturn(new BigDecimal("1.51"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of());

        assertThat(saved.getAmount()).isEqualByComparingTo("40.00");
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("wallet_refund_request"), any(), eq("AUTOMATIC_REQUESTED"),
                eq(USER_ID), payload.capture());
        assertThat(payload.getValue().get("skippedForFees")).isEqualTo(1);
        assertThat(payload.getValue().get("fees")).isEqualTo("1.51");
        assertThat(payload.getValue().get("net")).isEqualTo("38.49");
    }

    @Test
    void request_xofFeeEgalAuMontantArrondi_estExclueApresArrondi() {
        // Régression (tour 1) : le filtre net<=0 comparait remaining (non arrondi) au fee, pas
        // le montant DEJA arrondi à l'unité mineure. En XOF (échelle 0), remaining=200.40 avec
        // fee=200 passait ce test non arrondi (0.40 > 0) mais, une fois amount arrondi DOWN à
        // 200 (item.amount = t.remaining().setScale(0, DOWN)), amount == fee : le net
        // réellement émis (issueStripeRefund : amount - feeAmount) était nul. Le filtre doit
        // désormais s'appliquer sur le montant arrondi.
        UUID opId = UUID.randomUUID();
        WalletTransactionEntity topup = ledgerTx("XOF", WalletTransactionType.TOP_UP, "200.40", "pawapay:" + opId);
        stubLedger("XOF", "200.40", topup);
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(op.getId()).thenReturn(opId);
        when(op.getProvider()).thenReturn("ORANGE_CIV");
        when(pawapayOperationRepository.findByUserIdAndPurposeAndKind(
                USER_ID, PawapayOperationPurpose.WALLET_TOPUP, PawapayOperationKind.DEPOSIT))
                .thenReturn(List.of(op));
        when(pawapayFeeTable.fee(eq("ORANGE_CIV"), any(), eq("XOF"))).thenReturn(new BigDecimal("200"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("XOF"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(USER_ID, "XOF", List.of()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-not-refund-eligible");

        verify(refundRequestRepository, never()).save(any());
        verify(refundRequestItemRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void request_railsMixtes_leveIllegalStateExceptionSansRienSauvegarder() {
        UUID opId = UUID.randomUUID();
        WalletTransactionEntity stripeTopup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        WalletTransactionEntity pawapayTopup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pawapay:" + opId);
        stubLedger("80.00", stripeTopup, pawapayTopup);
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(op.getId()).thenReturn(opId);
        when(op.getProvider()).thenReturn("ORANGE_CIV");
        when(pawapayOperationRepository.findByUserIdAndPurposeAndKind(
                USER_ID, PawapayOperationPurpose.WALLET_TOPUP, PawapayOperationKind.DEPOSIT))
                .thenReturn(List.of(op));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(USER_ID, "EUR", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("wallet-refund-mixed-rails");

        verify(refundRequestRepository, never()).save(any());
        verify(refundRequestItemRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void issuePendingItems_toutEnEchec_itemFailedAvecCodeStripeEtDemandeCloseAvecTicketEnfant() {
        WalletRefundRequestEntity request = stubIssuable("EUR", "40.00");
        WalletRefundRequestItemEntity item = pendingItem(request, "pi_1", "40.00");
        when(refundRequestItemRepository.findUnissuedForUpdate(request.getId(), WalletRefundItemStatus.PENDING))
                .thenReturn(List.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("already refunded", "amount", "req_1",
                            "charge_already_refunded", 400, null));

            service.issuePendingItems(request.getId());
        }

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("charge_already_refunded");
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), any(), any());
        // Tout a échoué : la demande se clôt tout de suite, sans débit, et ouvre l'enfant.
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.FAILED);
        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
        verify(walletRefundRequestService).openChildForFailedItems(request, List.of(item));
    }

    @Test
    void listForUser_reconcilesProcessingRequestsAgainstStripe() {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_ID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        request.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        setId(request, requestId);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        item.setStripeRefundId("re_stale");
        item.setAmount(new BigDecimal("10.00"));

        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(refundRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = mock(Refund.class);
            when(refund.getStatus()).thenReturn("failed");
            refundStatic.when(() -> Refund.retrieve("re_stale")).thenReturn(refund);

            List<WalletRefundRequestEntity> result = service.listForUser(USER_ID);

            assertThat(result).containsExactly(request);
        }

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.FAILED);
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), any(), any());
    }

    @Test
    void refundStatusByTransactionId_mapsProcessingAndRefundedButNotFailed() {
        UUID processingTxId = UUID.randomUUID();
        UUID refundedTxId = UUID.randomUUID();
        UUID failedTxId = UUID.randomUUID();

        WalletRefundRequestItemEntity processingItem = new WalletRefundRequestItemEntity();
        processingItem.setWalletTransactionId(processingTxId);
        processingItem.setStatus(WalletRefundItemStatus.PROCESSING);
        WalletRefundRequestItemEntity refundedItem = new WalletRefundRequestItemEntity();
        refundedItem.setWalletTransactionId(refundedTxId);
        refundedItem.setStatus(WalletRefundItemStatus.REFUNDED);
        WalletRefundRequestItemEntity failedItem = new WalletRefundRequestItemEntity();
        failedItem.setWalletTransactionId(failedTxId);
        failedItem.setStatus(WalletRefundItemStatus.FAILED);

        List<UUID> ids = List.of(processingTxId, refundedTxId, failedTxId);
        when(refundRequestItemRepository.findByWalletTransactionIdIn(ids))
                .thenReturn(List.of(processingItem, refundedItem, failedItem));

        Map<UUID, String> result = service.refundStatusByTransactionId(ids);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                processingTxId, "PROCESSING",
                refundedTxId, "REFUNDED"));
    }

    @Test
    void refundStatusByTransactionId_seulLItemLePlusRecentCompte() {
        UUID childPendingTxId = UUID.randomUUID();
        UUID childRefundedTxId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t1 = t0.plusSeconds(60);

        List<WalletRefundRequestItemEntity> all = List.of(
                datedItem(childPendingTxId, WalletRefundItemStatus.FAILED, t0),
                datedItem(childPendingTxId, WalletRefundItemStatus.PENDING, t1),
                datedItem(childRefundedTxId, WalletRefundItemStatus.FAILED, t0),
                datedItem(childRefundedTxId, WalletRefundItemStatus.REFUNDED, t1));
        List<UUID> ids = List.of(childPendingTxId, childRefundedTxId);
        when(refundRequestItemRepository.findByWalletTransactionIdIn(ids)).thenReturn(all);

        assertThat(service.refundStatusByTransactionId(ids)).containsExactlyInAnyOrderEntriesOf(Map.of(
                childPendingTxId, "PROCESSING",
                childRefundedTxId, "REFUNDED"));
    }

    private static WalletRefundRequestItemEntity datedItem(UUID txId, WalletRefundItemStatus status, Instant createdAt) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        i.setWalletTransactionId(txId);
        i.setStatus(status);
        setField(i, "createdAt", createdAt);
        return i;
    }

    @Test
    void handleRefundUpdated_neChercheQueLItemProcessingDuPaymentIntent() {
        // Depuis V259, un PaymentIntent porte aussi des items FAILED ou REFUNDED d'anciennes
        // demandes : le webhook ne doit lire que l'unique item PROCESSING.
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING))
                .thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        service.handleRefundUpdated(failedRefundEvent("pi_1", "re_1"));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.FAILED);
        verify(walletRefundRequestService).openChildForFailedItems(request, List.of(item));
    }

    @Test
    void refundStatusByTransactionId_emptyInputReturnsEmptyMap() {
        assertThat(service.refundStatusByTransactionId(List.of())).isEmpty();
        verifyNoInteractions(refundRequestItemRepository);
    }

    @Test
    void handleChargeRefunded_marksItemRefundedAndResolvesRequestWhenAllItemsTerminal() {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setPaymentIntentId("pi_111");
        item.setAmount(new BigDecimal("30.00"));
        item.setStripeRefundId("re_111");
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_111", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_ID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        setId(request, requestId);
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(refundRequestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Charge charge = new Charge();
        charge.setPaymentIntent("pi_111");
        charge.setAmount(3000L);
        charge.setAmountRefunded(3000L);
        Refund refund = new Refund();
        refund.setId("re_111");
        refund.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        charge.setRefunds(refunds);

        service.handleChargeRefunded(charge);

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("30.00"),
                WalletTransactionType.SELF_REFUND_OUT);
    }

    @Test
    void handleChargeRefunded_partiel_itemRefundedQuandLeRefundEstSucceeded() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        Charge charge = new Charge();
        charge.setPaymentIntent("pi_1");
        charge.setAmount(4000L);
        charge.setAmountRefunded(3500L);
        Refund refund = new Refund();
        refund.setId("re_1");
        refund.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        charge.setRefunds(refunds);

        service.handleChargeRefunded(charge);

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("35.00"), WalletTransactionType.SELF_REFUND_OUT);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
    }

    @Test
    void handleChargeRefunded_sansListeDeRefunds_retrieveParId() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        Charge charge = new Charge();
        charge.setPaymentIntent("pi_1");

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = new Refund();
            refund.setId("re_1");
            refund.setStatus("succeeded");
            refundStatic.when(() -> Refund.retrieve("re_1")).thenReturn(refund);

            service.handleChargeRefunded(charge);
        }

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
    }

    @Test
    void handleChargeRefunded_autreRefundDuMemeCharge_ignore() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(item));
        Charge charge = new Charge();
        charge.setPaymentIntent("pi_1");
        Refund other = new Refund();
        other.setId("re_other");
        other.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(other));
        charge.setRefunds(refunds);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund pending = new Refund();
            pending.setId("re_1");
            pending.setStatus("pending");
            refundStatic.when(() -> Refund.retrieve("re_1")).thenReturn(pending);

            service.handleChargeRefunded(charge);
        }

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
    }

    @Test
    void resolveIfComplete_itemFailed_ouvreUnTicketEnfant() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity ok = processingItem(request, "pi_1", "20.00", "re_1");
        ok.setStatus(WalletRefundItemStatus.REFUNDED);
        WalletRefundRequestItemEntity ko = processingItem(request, "pi_2", "15.00", "re_2");
        ko.setStatus(WalletRefundItemStatus.PROCESSING);
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_2", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(ko));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(ok, ko));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        service.handleRefundUpdated(failedRefundEvent("pi_2", "re_2"));

        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.FAILED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("20.00"), WalletTransactionType.SELF_REFUND_OUT);
        verify(walletRefundRequestService).openChildForFailedItems(request, List.of(ko));
    }

    @Test
    void handleRefundUpdated_autreRefundDuMemePaymentIntent_ignore() {
        // Symétrique de handleChargeRefunded_autreRefundDuMemeCharge_ignore : un refund en
        // échec sur le même PI, mais qui n'est pas le nôtre, ne doit pas faire échouer l'item.
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.of(item));

        service.handleRefundUpdated(failedRefundEvent("pi_1", "re_autre"));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verifyNoInteractions(walletRefundRequestService);
        verify(refundRequestItemRepository, never()).save(any());
    }

    private WalletRefundRequestEntity processingRequest(String amount) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        assignId(r);
        r.setUserId(USER_ID);
        r.setCurrency("EUR");
        r.setAmount(new BigDecimal(amount));
        r.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        r.setStatus(WalletRefundRequestStatus.PROCESSING);
        r.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        return r;
    }

    private WalletRefundRequestItemEntity processingItem(WalletRefundRequestEntity r, String pi, String amount, String refundId) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        setField(i, "id", UUID.randomUUID());
        i.setRefundRequestId(r.getId());
        i.setWalletTransactionId(UUID.randomUUID());
        i.setPaymentIntentId(pi);
        i.setAmount(new BigDecimal(amount));
        i.setStripeRefundId(refundId);
        i.setStatus(WalletRefundItemStatus.PROCESSING);
        return i;
    }

    /** {@code charge.refund.updated} en échec : l'objet de l'event EST le refund, son {@code id}
     *  est donc l'identifiant du refund concerné, pas celui de l'event. */
    private Event failedRefundEvent(String pi, String refundId) {
        String json = "{\"id\":\"evt_x\",\"object\":\"event\",\"type\":\"charge.refund.updated\","
                + "\"data\":{\"object\":{\"id\":\"" + refundId + "\",\"status\":\"failed\","
                + "\"payment_intent\":\"" + pi + "\"}}}";
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    @Test
    void handleChargeRefunded_noOpWhenPaymentIntentUnknown() {
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_unknown", WalletRefundItemStatus.PROCESSING)).thenReturn(Optional.empty());
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn("pi_unknown");

        service.handleChargeRefunded(charge);

        verifyNoInteractions(walletService);
    }

    /**
     * Test de contrat d'annotation : {@code request()} recalcule l'allocation en
     * auto-invocation et peut lever {@code WalletAllocationInvariantException} (RuntimeException).
     * Sans elle dans {@code noRollbackFor()}, la transaction participante de
     * {@code UserService#settleWalletsForDeletion} est marquée rollback-only et son commit
     * échoue en {@code UnexpectedRollbackException} même si l'appelant a déjà attrapé
     * l'exception pour basculer sur un ticket manuel. Fige la correction : la régression
     * (retrait de la classe de {@code noRollbackFor()}) doit faire échouer ce test, pas
     * un scénario d'intégration.
     */
    @Test
    void request_noRollbackFor_couvreYadonyBusinessExceptionEtWalletAllocationInvariantException() throws NoSuchMethodException {
        Method request = WalletSelfRefundService.class.getMethod(
                "request", UUID.class, String.class, List.class);
        Transactional transactional = request.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor())
                .contains(YadonyBusinessException.class, WalletAllocationInvariantException.class);
    }

    /**
     * Même contrat pour {@code allocation()} : l'invariant y est levé directement par le
     * rejeu du ledger (cf. sa javadoc) et ne doit pas non plus marquer rollback-only la
     * transaction participante de l'appelant (ex. {@code isEligible}, {@code UserService}).
     */
    @Test
    void allocation_noRollbackFor_couvreWalletAllocationInvariantException() throws NoSuchMethodException {
        Method allocation = WalletSelfRefundService.class.getMethod(
                "allocation", UUID.class, String.class);
        Transactional transactional = allocation.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(WalletAllocationInvariantException.class);
    }

    // ── Tâche 5 : atomicité de la résolution et réconciliation par rail ─────────────

    @Test
    void resolveIfComplete_demandeDejaResolueAuRafraichissement_aucunDebitNiTicket() {
        // Cache de premier niveau : l'entité verrouillée dit encore PROCESSING, la base dit
        // REFUNDED (un écouteur l'a résolue et a committé). Le rafraîchissement fait foi.
        WalletRefundRequestEntity request = processingRequest("35.00");
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        doAnswer(inv -> {
            request.setStatus(WalletRefundRequestStatus.REFUNDED);
            return null;
        }).when(entityManager).refresh(request);

        service.resolveIfComplete(request.getId());

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
        verifyNoInteractions(walletRefundRequestService, auditService);
        verify(refundRequestRepository, never()).saveAndFlush(any());
        verify(refundRequestItemRepository, never()).findByRefundRequestId(any());
    }

    @Test
    void listForUser_demandeResolueParUnEcouteurEntreTemps_aucunSecondDebit() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        doAnswer(inv -> {
            request.setStatus(WalletRefundRequestStatus.REFUNDED);
            return null;
        }).when(entityManager).refresh(request);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            assertThat(service.listForUser(USER_ID)).containsExactly(request);
            refundStatic.verifyNoInteractions();
        }

        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
        verifyNoInteractions(walletRefundRequestService, walletRefundRailIssuer);
        verify(refundRequestItemRepository, never()).findByRefundRequestId(any());
    }

    @Test
    void reconcile_verrouilleEtRelitLaDemandeAvantDeLireSesItems() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund pending = new Refund();
            pending.setId("re_1");
            pending.setStatus("pending");
            refundStatic.when(() -> Refund.retrieve("re_1")).thenReturn(pending);

            service.listForUser(USER_ID);
        }

        InOrder order = inOrder(refundRequestRepository, entityManager, refundRequestItemRepository);
        order.verify(refundRequestRepository).findByIdForUpdate(request.getId());
        order.verify(entityManager).refresh(request);
        order.verify(refundRequestItemRepository, atLeastOnce()).findByRefundRequestId(request.getId());
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    void reconcile_demandePawapay_delegueAuRailSansAppelerStripe() {
        WalletRefundRequestEntity request = processingRequest("10000");
        request.setCurrency("XOF");
        request.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        WalletRefundRequestItemEntity item = processingItem(request, "pawapay:" + UUID.randomUUID(), "10000", null);
        item.setPawapayRefundId(UUID.randomUUID());
        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        // Le rail applique l'issue COMPLETED lue localement.
        doAnswer(inv -> {
            item.setStatus(WalletRefundItemStatus.REFUNDED);
            return null;
        }).when(walletRefundRailIssuer).reconcile(request, item);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            service.listForUser(USER_ID);
            refundStatic.verifyNoInteractions();
        }

        verify(walletRefundRailIssuer).reconcile(request, item);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
        verify(walletService).debitConfirmedRefund(USER_ID, "XOF", new BigDecimal("10000"),
                WalletTransactionType.SELF_REFUND_OUT);
    }

    @Test
    void reconcile_demandeStripe_neDelegueJamaisAuRailPawapay() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund succeeded = new Refund();
            succeeded.setStatus("succeeded");
            refundStatic.when(() -> Refund.retrieve("re_1")).thenReturn(succeeded);
            service.listForUser(USER_ID);
        }

        verifyNoInteractions(walletRefundRailIssuer);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
    }

    @Test
    void handleChargeRefunded_itemTermineEntreTempsParLaReconciliation_neReecritRien() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING))
                .thenReturn(Optional.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        doAnswer(inv -> {
            request.setStatus(WalletRefundRequestStatus.REFUNDED);
            return null;
        }).when(entityManager).refresh(request);
        doAnswer(inv -> {
            item.setStatus(WalletRefundItemStatus.REFUNDED);
            return null;
        }).when(entityManager).refresh(item);
        Charge charge = new Charge();
        charge.setPaymentIntent("pi_1");
        Refund refund = new Refund();
        refund.setId("re_1");
        refund.setStatus("succeeded");
        RefundCollection refunds = new RefundCollection();
        refunds.setData(List.of(refund));
        charge.setRefunds(refunds);

        service.handleChargeRefunded(charge);

        InOrder order = inOrder(refundRequestRepository, entityManager);
        order.verify(refundRequestRepository).findByIdForUpdate(request.getId());
        order.verify(entityManager).refresh(item);
        verify(refundRequestItemRepository, never()).save(any());
        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    void handleRefundUpdated_verrouilleLaDemandeAvantDEcrireLItem() {
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentIdAndStatus("pi_1", WalletRefundItemStatus.PROCESSING))
                .thenReturn(Optional.of(item));
        when(refundRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));

        service.handleRefundUpdated(failedRefundEvent("pi_1", "re_1"));

        InOrder order = inOrder(refundRequestRepository, refundRequestItemRepository);
        order.verify(refundRequestRepository).findByIdForUpdate(request.getId());
        order.verify(refundRequestItemRepository).save(item);
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
    }

    // ── Tâche 5 : contrat (frais, net, destination masquée) ───────────────────────

    @Test
    void listEligibleTopups_exposeLeFraisDeChaqueRecharge() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("40.00", topup);
        when(stripeFeeSource.fee("pi_1", "EUR")).thenReturn(new BigDecimal("0.85"));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(USER_ID, "EUR",
                List.of(WalletRefundRequestStatus.PROCESSING))).thenReturn(Optional.empty());
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);

        List<WalletSelfRefundService.EligibleTopup> list = service.listEligibleTopups(USER_ID, "EUR");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).fee()).isEqualByComparingTo("0.85");
    }

    private PawapayOperationEntity depositOp(String msisdn) {
        return new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, USER_ID, null, null, new BigDecimal("10000"), "XOF",
                "ORANGE_CIV", "CI", msisdn);
    }

    @Test
    void details_destinationMasqueeDuPremierItemPawapay_jamaisLeNumeroEnClair() {
        PawapayOperationEntity deposit = depositOp("2250734567890");
        WalletRefundRequestEntity pawapay = processingRequest("10000");
        pawapay.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        WalletRefundRequestItemEntity pItem = processingItem(pawapay, "pawapay:" + deposit.getId(), "10000", null);
        WalletRefundRequestEntity stripe = processingRequest("35.00");
        WalletRefundRequestItemEntity sItem = processingItem(stripe, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByRefundRequestIdIn(List.of(pawapay.getId(), stripe.getId())))
                .thenReturn(List.of(pItem, sItem));
        when(pawapayOperationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));

        List<WalletSelfRefundService.RefundRequestDetails> details = service.details(List.of(pawapay, stripe));

        assertThat(details.get(0).items()).containsExactly(pItem);
        assertThat(details.get(0).destinationMasked()).isEqualTo(deposit.getMsisdnMasked()).isNotNull()
                .doesNotContain("0734567890");
        assertThat(details.get(1).items()).containsExactly(sItem);
        assertThat(details.get(1).destinationMasked()).isNull();
    }

    @Test
    void details_listeVide_aucuneRequete() {
        assertThat(service.details(List.of())).isEmpty();
        verifyNoInteractions(refundRequestItemRepository);
    }

    @Test
    void destinationMasked_premiereCiblePawapay_ouNullSansCiblePawapay() {
        PawapayOperationEntity deposit = depositOp("2250734567890");
        when(pawapayOperationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        WalletRefundAllocation.RefundableTopup stripeTarget = new WalletRefundAllocation.RefundableTopup(
                UUID.randomUUID(), "pi_1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, WalletRefundRail.STRIPE, null);
        WalletRefundAllocation.RefundableTopup pawapayTarget = new WalletRefundAllocation.RefundableTopup(
                UUID.randomUUID(), "pawapay:" + deposit.getId(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE,
                WalletRefundRail.PAWAPAY, "ORANGE_CIV");

        assertThat(service.destinationMasked(new WalletRefundAllocation(List.of(stripeTarget, pawapayTarget),
                new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("19"))))
                .isEqualTo(deposit.getMsisdnMasked());
        assertThat(service.destinationMasked(new WalletRefundAllocation(List.of(stripeTarget),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN))).isNull();
    }

    private WalletRefundRequestEntity refundedRequest(String currency, LocalDateTime resolvedAt) {
        WalletRefundRequestEntity r = processingRequest("0");
        r.setCurrency(currency);
        r.setStatus(WalletRefundRequestStatus.REFUNDED);
        r.setResolvedAt(resolvedAt);
        return r;
    }

    private WalletRefundRequestItemEntity refundedItem(WalletRefundRequestEntity r, String amount, String fee) {
        WalletRefundRequestItemEntity i = processingItem(r, "pawapay:" + UUID.randomUUID(), amount, null);
        i.setStatus(WalletRefundItemStatus.REFUNDED);
        i.setFeeAmount(new BigDecimal(fee));
        return i;
    }

    @Test
    void refundFeesByTransactionId_appariementUnivoque_fraisEtNet() {
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        WalletRefundRequestEntity r = refundedRequest("XOF", resolvedAt);
        WalletRefundRequestItemEntity item = refundedItem(r, "10000", "200");
        WalletTransactionEntity out = ledgerTx("XOF", WalletTransactionType.SELF_REFUND_OUT, "-10000", null);
        setField(out, "createdAt", resolvedAt.toInstant(ZoneOffset.UTC));
        WalletTransactionEntity topup = ledgerTx("XOF", WalletTransactionType.TOP_UP, "10000", "pawapay:x");
        when(refundRequestRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.REFUNDED))
                .thenReturn(List.of(r));
        when(refundRequestItemRepository.findByRefundRequestIdIn(List.of(r.getId()))).thenReturn(List.of(item));

        Map<UUID, WalletSelfRefundService.RefundFeeBreakdown> fees =
                service.refundFeesByTransactionId(USER_ID, List.of(out, topup));

        assertThat(fees).containsOnlyKeys(out.getId());
        assertThat(fees.get(out.getId()).feeAmount()).isEqualByComparingTo("200");
        assertThat(fees.get(out.getId()).netAmount()).isEqualByComparingTo("9800");
    }

    @Test
    void refundFeesByTransactionId_deuxDemandesExAequo_nonApparie() {
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        WalletRefundRequestEntity before = refundedRequest("XOF", resolvedAt.minusMinutes(5));
        WalletRefundRequestEntity after = refundedRequest("XOF", resolvedAt.plusMinutes(5));
        WalletTransactionEntity out = ledgerTx("XOF", WalletTransactionType.SELF_REFUND_OUT, "-10000", null);
        setField(out, "createdAt", resolvedAt.toInstant(ZoneOffset.UTC));
        when(refundRequestRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.REFUNDED))
                .thenReturn(List.of(before, after));
        when(refundRequestItemRepository.findByRefundRequestIdIn(List.of(before.getId(), after.getId())))
                .thenReturn(List.of(refundedItem(before, "10000", "200"), refundedItem(after, "10000", "200")));

        assertThat(service.refundFeesByTransactionId(USER_ID, List.of(out))).isEmpty();
    }

    @Test
    void refundFeesByTransactionId_deuxDebitsPourUneSeuleDemande_nonApparie() {
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        WalletRefundRequestEntity r = refundedRequest("XOF", resolvedAt);
        WalletTransactionEntity out1 = ledgerTx("XOF", WalletTransactionType.SELF_REFUND_OUT, "-10000", null);
        setField(out1, "createdAt", resolvedAt.toInstant(ZoneOffset.UTC));
        WalletTransactionEntity out2 = ledgerTx("XOF", WalletTransactionType.SELF_REFUND_OUT, "-10000", null);
        setField(out2, "createdAt", resolvedAt.plusMinutes(1).toInstant(ZoneOffset.UTC));
        when(refundRequestRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.REFUNDED))
                .thenReturn(List.of(r));
        when(refundRequestItemRepository.findByRefundRequestIdIn(List.of(r.getId())))
                .thenReturn(List.of(refundedItem(r, "10000", "200")));

        assertThat(service.refundFeesByTransactionId(USER_ID, List.of(out1, out2))).isEmpty();
    }

    @Test
    void refundFeesByTransactionId_montantOuDeviseDifferents_nonApparie() {
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 9, 17, 10, 0);
        WalletRefundRequestEntity r = refundedRequest("XOF", resolvedAt);
        WalletTransactionEntity otherAmount = ledgerTx("XOF", WalletTransactionType.SELF_REFUND_OUT, "-5000", null);
        WalletTransactionEntity otherCurrency = ledgerTx("EUR", WalletTransactionType.SELF_REFUND_OUT, "-10000", null);
        when(refundRequestRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.REFUNDED))
                .thenReturn(List.of(r));
        when(refundRequestItemRepository.findByRefundRequestIdIn(List.of(r.getId())))
                .thenReturn(List.of(refundedItem(r, "10000", "200")));

        assertThat(service.refundFeesByTransactionId(USER_ID, List.of(otherAmount, otherCurrency))).isEmpty();
    }

    @Test
    void refundFeesByTransactionId_sansSelfRefundOut_aucuneRequete() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");

        assertThat(service.refundFeesByTransactionId(USER_ID, List.of(topup))).isEmpty();
        verifyNoInteractions(refundRequestRepository, refundRequestItemRepository);
    }

    @Test
    void refundFeesByTransactionId_aucuneDemandeRemboursee_vide() {
        WalletTransactionEntity out = ledgerTx(WalletTransactionType.SELF_REFUND_OUT, "-40.00", null);
        when(refundRequestRepository.findAllByUserIdAndStatus(USER_ID, WalletRefundRequestStatus.REFUNDED))
                .thenReturn(List.of());

        assertThat(service.refundFeesByTransactionId(USER_ID, List.of(out))).isEmpty();
        verifyNoInteractions(refundRequestItemRepository);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> type = entity.getClass();
            Field field = null;
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField("id");
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                throw new NoSuchFieldException("id");
            }
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
