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
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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
    @Mock WalletRefundRequestService walletRefundRequestService;

    WalletSelfRefundService service;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletSelfRefundService(walletAccountRepository, walletTransactionRepository,
                refundRequestRepository, refundRequestItemRepository, walletService,
                auditService, adminAlertService, new ObjectMapper(), walletRefundRequestService);
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
        verify(adminAlertService).raise(eq("wallet-refund-allocation-invariant"), any(), any());
    }

    @Test
    void allocation_walletAbsent_vide() {
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.empty());

        assertThat(service.allocation(USER_ID, "EUR").refundableTotal()).isEqualByComparingTo("0");
    }

    @Test
    void isEligible_vraiQuandDuRemboursableExiste() {
        stubLedger("35.00", ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1"),
                ledgerTx(WalletTransactionType.BID_PAYMENT, "-5.00", null));
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);

        assertThat(service.isEligible(USER_ID, "EUR")).isTrue();
    }

    @Test
    void isEligible_fauxQuandInvariantCasse() {
        stubLedger("99.00", ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1"));

        assertThat(service.isEligible(USER_ID, "EUR")).isFalse();
    }

    @Test
    void isEligible_fauxQuandSoldeUniquementNonCash() {
        stubLedger("5.00", ledgerTx(WalletTransactionType.REFERRAL_REWARD, "5.00", null));
        when(refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any())).thenReturn(false);

        assertThat(service.isEligible(USER_ID, "EUR")).isFalse();
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
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(stale));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void request_sansSelection_rembourseToutLeRemboursableEnPartiel() {
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
        when(refundRequestItemRepository.findByRefundRequestId(any())).thenAnswer(inv -> List.of(lastSavedItem()));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = new Refund();
            refund.setId("re_1");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class))).thenReturn(refund);

            WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of());

            assertThat(saved.getAmount()).isEqualByComparingTo("35.00");
            assertThat(saved.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_STRIPE);
            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_1");
            assertThat(params.getValue().getAmount()).isEqualTo(3500L);
        }
    }

    @Test
    void request_deviseSansDecimales_montantAligneUniteMineureStripeAvantEnvoi() {
        // Régression : le ledger interne garde toujours 2 décimales (NUMERIC(10,2)), même pour
        // XOF (0 décimale). Sans mise à l'échelle avant la création de l'item, un reliquat comme
        // 9999.50 XOF faisait lever ArithmeticException dans Refund.create (longValueExact()),
        // non rattrapée par catch (StripeException), annulant toute la transaction.
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
        when(refundRequestItemRepository.findByRefundRequestId(any())).thenAnswer(inv -> List.of(lastSavedItem()));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = new Refund();
            refund.setId("re_xof");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class))).thenReturn(refund);

            WalletRefundRequestEntity saved = service.request(USER_ID, "XOF", List.of());

            assertThat(saved.getAmount()).isEqualByComparingTo("9999");
            assertThat(params.getValue().getAmount()).isEqualTo(9999L);
        }
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
        when(refundRequestItemRepository.findByRefundRequestId(any())).thenReturn(List.of());

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            WalletRefundRequestEntity saved = service.request(USER_ID, "EUR", List.of(b.getId()));

            assertThat(saved.getAmount()).isEqualByComparingTo("25.00");
        }
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
    void issueStripeRefund_echec_itemFailedAvecCodeStripe() {
        WalletTransactionEntity topup = ledgerTx(WalletTransactionType.TOP_UP, "40.00", "pi_1");
        stubLedger("40.00", topup);
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) assignId(r);
            return r;
        });
        List<WalletRefundRequestItemEntity> savedItems = new ArrayList<>();
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestItemEntity i = inv.getArgument(0);
            if (!savedItems.contains(i)) savedItems.add(i);
            return i;
        });
        when(refundRequestItemRepository.findByRefundRequestId(any())).thenAnswer(inv -> savedItems);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("already refunded", "amount", "req_1",
                            "charge_already_refunded", 400, null));

            service.request(USER_ID, "EUR", List.of());
        }

        assertThat(savedItems.get(0).getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(savedItems.get(0).getFailureReason()).isEqualTo("charge_already_refunded");
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), any(), any());
    }

    @Test
    void listForUser_reconcilesProcessingRequestsAgainstStripe() {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_ID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        setId(request, requestId);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        item.setStripeRefundId("re_stale");
        item.setAmount(new BigDecimal("10.00"));

        when(refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(USER_ID)).thenReturn(List.of(request));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_111")).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_ID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(item));
        when(refundRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));
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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(item));
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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_2")).thenReturn(Optional.of(ko));
        when(refundRequestItemRepository.findByRefundRequestId(request.getId())).thenReturn(List.of(ok, ko));
        when(refundRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

        service.handleRefundUpdated(failedRefundEvent("pi_2", "re_2"));

        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.FAILED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("20.00"), WalletTransactionType.SELF_REFUND_OUT);
        verify(walletRefundRequestService).openChildForFailedItems(request, new BigDecimal("15.00"));
    }

    @Test
    void handleRefundUpdated_autreRefundDuMemePaymentIntent_ignore() {
        // Symétrique de handleChargeRefunded_autreRefundDuMemeCharge_ignore : un refund en
        // échec sur le même PI, mais qui n'est pas le nôtre, ne doit pas faire échouer l'item.
        WalletRefundRequestEntity request = processingRequest("35.00");
        WalletRefundRequestItemEntity item = processingItem(request, "pi_1", "35.00", "re_1");
        when(refundRequestItemRepository.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(item));

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
        when(refundRequestItemRepository.findByPaymentIntentId("pi_unknown")).thenReturn(Optional.empty());
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn("pi_unknown");

        service.handleChargeRefunded(charge);

        verifyNoInteractions(walletService);
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
