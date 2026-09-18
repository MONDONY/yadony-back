package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletRefundRequestServiceTest {

    @Mock WalletService walletService;
    @Mock WalletRefundRequestRepository refundRequestRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;
    @Mock WalletRefundRequestItemRepository refundRequestItemRepository;

    private WalletRefundRequestService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletRefundRequestService(
                walletService, refundRequestRepository, auditService, adminAlertService,
                refundRequestItemRepository);
    }

    private static WalletAccountEntity walletOf(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    /** Un {@code WalletRefundRequestEntity} construit à la main n'a jamais d'id (assigné par
     *  Hibernate au flush réel) — sans ça, {@code Map.of("requestId", saved.getId(), ...)} dans
     *  le service lève une NPE (valeur null interdite dans {@code Map.of}). */
    private static void assignId(WalletRefundRequestEntity entity) {
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void requestForCurrency_ouvreUnTicketPourLaDeviseSeule() {
        when(walletService.getAllBalances(USER_ID)).thenReturn(List.of(walletOf("EUR", "35.00"), walletOf("XOF", "1000")));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.empty());
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            assignId(r);
            return r;
        });

        WalletRefundRequestEntity ticket = service.request(USER_ID, "EUR");

        assertThat(ticket.getCurrency()).isEqualTo("EUR");
        assertThat(ticket.getAmount()).isEqualByComparingTo("35.00");
        assertThat(ticket.getChannel()).isEqualTo(WalletRefundChannel.MANUAL_ADMIN);
        verify(refundRequestRepository, times(1)).save(any());
    }

    @Test
    void requestForCurrency_soldeNul_422() {
        when(walletService.getAllBalances(USER_ID)).thenReturn(List.of(walletOf("EUR", "0.00")));

        assertThatThrownBy(() -> service.request(USER_ID, "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-balance-empty");
    }

    @Test
    @DisplayName("ticket PENDING/PROCESSING déjà ouvert pour cette devise → réutilisé, pas de doublon ni re-alerte")
    void existingPendingTicket_isReusedNotDuplicated() {
        WalletRefundRequestEntity existing = new WalletRefundRequestEntity();
        assignId(existing);
        existing.setUserId(USER_ID);
        existing.setCurrency("EUR");
        existing.setAmount(new BigDecimal("30.00"));
        existing.setStatus(WalletRefundRequestStatus.PENDING);

        when(walletService.getAllBalances(USER_ID)).thenReturn(List.of(walletOf("EUR", "30.00")));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.of(existing));

        WalletRefundRequestEntity result = service.request(USER_ID, "EUR");

        assertThat(result).isSameAs(existing);
        assertThat(existing.getChannel()).isEqualTo(WalletRefundChannel.MANUAL_ADMIN);
        verify(refundRequestRepository, never()).save(any());
        verify(adminAlertService, never()).raise(any(), any(), any());
    }

    @Test
    @DisplayName("demande AUTOMATIC_STRIPE en vol → renvoyée sans second ticket (index unique partiel)")
    void existingAutomaticRequest_neCreeJamaisUnSecondTicket() {
        // Idempotence par (user, devise, statut, canal) : la demande en cours n'est pas un
        // ticket admin. uq_wallet_refund_requests_pending (V229) interdit d'en ouvrir un
        // second sur la même devise : on renvoie la demande sans rien créer ni ré-alerter,
        // le rail automatique ouvrira lui-même son ticket enfant en cas d'échec d'item.
        WalletRefundRequestEntity enVol = new WalletRefundRequestEntity();
        assignId(enVol);
        enVol.setUserId(USER_ID);
        enVol.setCurrency("EUR");
        enVol.setAmount(new BigDecimal("35.00"));
        enVol.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        enVol.setStatus(WalletRefundRequestStatus.PROCESSING);

        when(walletService.getAllBalances(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(eq(USER_ID), eq("EUR"), any()))
                .thenReturn(Optional.of(enVol));

        WalletRefundRequestEntity result = service.request(USER_ID, "EUR");

        assertThat(result).isSameAs(enVol);
        assertThat(result.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_STRIPE);
        verify(refundRequestRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(adminAlertService, never()).raise(any(), any(), any());
    }

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

    private static WalletRefundRequestItemEntity itemOf(UUID requestId, String pi, String amount,
                                                        WalletRefundItemStatus status, Instant createdAt) {
        WalletRefundRequestItemEntity i = new WalletRefundRequestItemEntity();
        setField(i, "id", UUID.randomUUID());
        i.setRefundRequestId(requestId);
        i.setWalletTransactionId(UUID.randomUUID());
        i.setPaymentIntentId(pi);
        i.setStripeRefundId(status == WalletRefundItemStatus.PENDING ? null : "re_" + pi);
        i.setAmount(new BigDecimal(amount));
        i.setStatus(status);
        setField(i, "createdAt", createdAt);
        return i;
    }

    @Test
    @SuppressWarnings("unchecked")
    void openChildForFailedItems_creeUnTicketManuelLieAuParentAvecUnItemParEchec() {
        WalletRefundRequestEntity parent = new WalletRefundRequestEntity();
        assignId(parent);
        parent.setUserId(USER_ID);
        parent.setCurrency("EUR");
        parent.setAmount(new BigDecimal("35.00"));
        when(refundRequestRepository.existsByParentRequestId(parent.getId())).thenReturn(false);
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            assignId(r);
            return r;
        });
        WalletRefundRequestItemEntity failedA = itemOf(parent.getId(), "pi_a", "10.00",
                WalletRefundItemStatus.FAILED, Instant.now());
        WalletRefundRequestItemEntity failedB = itemOf(parent.getId(), "pi_b", "5.00",
                WalletRefundItemStatus.FAILED, Instant.now());

        WalletRefundRequestEntity child = service.openChildForFailedItems(parent, List.of(failedA, failedB));

        assertThat(child.getParentRequestId()).isEqualTo(parent.getId());
        assertThat(child.getAmount()).isEqualByComparingTo("15.00");
        assertThat(child.getChannel()).isEqualTo(WalletRefundChannel.MANUAL_ADMIN);
        assertThat(child.getStatus()).isEqualTo(WalletRefundRequestStatus.PENDING);
        ArgumentCaptor<List<WalletRefundRequestItemEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(refundRequestItemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2).allSatisfy(i -> {
            assertThat(i.getRefundRequestId()).isEqualTo(child.getId());
            assertThat(i.getStatus()).isEqualTo(WalletRefundItemStatus.PENDING);
            assertThat(i.getStripeRefundId()).isNull();
        });
        assertThat(saved.getValue()).extracting(WalletRefundRequestItemEntity::getWalletTransactionId)
                .containsExactly(failedA.getWalletTransactionId(), failedB.getWalletTransactionId());
        assertThat(saved.getValue()).extracting(WalletRefundRequestItemEntity::getPaymentIntentId)
                .containsExactly("pi_a", "pi_b");
        assertThat(saved.getValue()).extracting(WalletRefundRequestItemEntity::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("5.00"));
        verify(auditService).log(eq("wallet_refund_request"), eq(child.getId()), eq("MANUAL_CHILD_OPENED"), eq(USER_ID), any());
        verify(adminAlertService).raise(eq("wallet-refund-requested"), any(), any());
    }

    /**
     * Le frais du parent suit l'item enfant : sans lui, l'admin réglerait le brut et le résumé
     * de la demande annoncerait frais 0 / net = brut, alors que le rail automatique retenait
     * déjà ce frais sur la même recharge.
     */
    @Test
    @SuppressWarnings("unchecked")
    void openChildForFailedItems_reporteLeFraisSurLItemEnfant() {
        WalletRefundRequestEntity parent = new WalletRefundRequestEntity();
        assignId(parent);
        parent.setUserId(USER_ID);
        parent.setCurrency("EUR");
        when(refundRequestRepository.existsByParentRequestId(parent.getId())).thenReturn(false);
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity r = inv.getArgument(0);
            assignId(r);
            return r;
        });
        WalletRefundRequestItemEntity failed = itemOf(parent.getId(), "pi_a", "40.00",
                WalletRefundItemStatus.FAILED, Instant.now());
        failed.setFeeAmount(new BigDecimal("1.51"));

        service.openChildForFailedItems(parent, List.of(failed));

        ArgumentCaptor<List<WalletRefundRequestItemEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(refundRequestItemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(i -> {
                    assertThat(i.getAmount()).isEqualByComparingTo("40.00");
                    assertThat(i.getFeeAmount()).isEqualByComparingTo("1.51");
                });
    }

    @Test
    void openChildForFailedItems_idempotent() {
        WalletRefundRequestEntity parent = new WalletRefundRequestEntity();
        assignId(parent);
        parent.setUserId(USER_ID);
        parent.setCurrency("EUR");
        when(refundRequestRepository.existsByParentRequestId(parent.getId())).thenReturn(true);

        WalletRefundRequestEntity child = service.openChildForFailedItems(parent,
                List.of(itemOf(parent.getId(), "pi_a", "15.00", WalletRefundItemStatus.FAILED, Instant.now())));

        assertThat(child).isNull();
        verify(refundRequestRepository, never()).save(any());
        verify(refundRequestItemRepository, never()).saveAll(any());
    }

    @Test
    void openChildForFailedItems_sansItemEnEchec_neCreeRien() {
        WalletRefundRequestEntity parent = new WalletRefundRequestEntity();
        assignId(parent);

        assertThat(service.openChildForFailedItems(parent, List.of())).isNull();
        assertThat(service.openChildForFailedItems(parent, null)).isNull();
        verify(refundRequestRepository, never()).existsByParentRequestId(any());
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        private WalletRefundRequestEntity pendingRequest() {
            WalletRefundRequestEntity r = new WalletRefundRequestEntity();
            r.setUserId(USER_ID);
            r.setCurrency("CAD");
            r.setAmount(new BigDecimal("45.00"));
            r.setStatus(WalletRefundRequestStatus.PENDING);
            return r;
        }

        @Test
        @DisplayName("demande introuvable → 404")
        void notFound_throws404() {
            UUID requestId = UUID.randomUUID();
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(requestId, ADMIN_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "wallet-refund-request-not-found");
        }

        @Test
        @DisplayName("déjà résolue → 422 already-resolved, aucun débit")
        void alreadyResolved_throws422() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity resolved = pendingRequest();
            resolved.setStatus(WalletRefundRequestStatus.RESOLVED);
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(resolved));

            assertThatThrownBy(() -> service.resolve(requestId, ADMIN_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "already-resolved");

            verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
        }

        @Test
        @DisplayName("solde toujours positif au clic → débite le solde RÉEL (pas le montant snapshoté), "
            + "marque résolu avec resolvedBy/resolvedAt")
        void resolves_debitsLiveBalanceNotSnapshot() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity request = pendingRequest();
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
            // Le solde a bougé depuis la demande (ex. commission cash prélevée entre-temps).
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("40.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            WalletRefundRequestEntity result = service.resolve(requestId, ADMIN_ID);

            // debitConfirmedRefund et non debit : ce dernier passe par assertNotFrozen, que le
            // ticket PENDING en cours de résolution déclenche lui-même (422 wallet-refund-pending).
            verify(walletService).debitConfirmedRefund(USER_ID, "CAD", new BigDecimal("40.00"),
                    WalletTransactionType.ADMIN_REFUND_OUT);
            assertThat(result.getStatus()).isEqualTo(WalletRefundRequestStatus.RESOLVED);
            assertThat(result.getResolvedBy()).isEqualTo(ADMIN_ID);
            assertThat(result.getResolvedAt()).isNotNull();
            verify(auditService).log(eq("wallet_refund_request"), any(), eq("RESOLVED"), eq(ADMIN_ID), any());
        }

        @Test
        @DisplayName("solde déjà retombé à zéro → marque résolu sans appeler debit (éviterait une "
            + "InsufficientWalletBalanceException sur un montant nul)")
        void resolves_skipsDebitWhenBalanceAlreadyZero() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity request = pendingRequest();
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(BigDecimal.ZERO);
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            WalletRefundRequestEntity result = service.resolve(requestId, ADMIN_ID);

            verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
            assertThat(result.getStatus()).isEqualTo(WalletRefundRequestStatus.RESOLVED);
        }

        @Test
        @DisplayName("ticket enfant → débite le montant du ticket, pas tout le solde "
            + "(le reste peut être du non-cash)")
        void resolves_child_debitsTicketAmountNotWholeBalance() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity child = pendingRequest();
            child.setAmount(new BigDecimal("15.00"));
            child.setParentRequestId(UUID.randomUUID());
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(child));
            // 15 de cash en échec + 10 de parrainage : seuls les 15 du ticket partent.
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("25.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            WalletRefundRequestEntity result = service.resolve(requestId, ADMIN_ID);

            verify(walletService).debitConfirmedRefund(USER_ID, "CAD", new BigDecimal("15.00"),
                    WalletTransactionType.ADMIN_REFUND_OUT);
            assertThat(result.getStatus()).isEqualTo(WalletRefundRequestStatus.RESOLVED);
        }

        @Test
        @DisplayName("ticket enfant dont le solde est retombé sous le montant → débite le solde")
        void resolves_child_debitsBalanceWhenLowerThanTicket() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity child = pendingRequest();
            child.setAmount(new BigDecimal("15.00"));
            child.setParentRequestId(UUID.randomUUID());
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(child));
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("9.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            service.resolve(requestId, ADMIN_ID);

            verify(walletService).debitConfirmedRefund(USER_ID, "CAD", new BigDecimal("9.00"),
                    WalletTransactionType.ADMIN_REFUND_OUT);
        }

        @Test
        @DisplayName("ticket enfant avec items → tous passent REFUNDED")
        void resolves_child_marqueSesItemsRefunded() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity child = pendingRequest();
            assignId(child);
            child.setAmount(new BigDecimal("15.00"));
            child.setParentRequestId(UUID.randomUUID());
            Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
            WalletRefundRequestItemEntity a = itemOf(child.getId(), "pi_a", "10.00", WalletRefundItemStatus.PENDING, t0);
            WalletRefundRequestItemEntity b = itemOf(child.getId(), "pi_b", "5.00", WalletRefundItemStatus.PENDING, t0.plusSeconds(1));
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(child));
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("25.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(refundRequestItemRepository.findByRefundRequestId(child.getId())).thenReturn(List.of(b, a));

            service.resolve(requestId, ADMIN_ID);

            assertThat(a.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
            assertThat(b.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
            assertThat(a.getAmount()).isEqualByComparingTo("10.00");
            assertThat(b.getAmount()).isEqualByComparingTo("5.00");
            verify(refundRequestItemRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("ticket enfant, débit court → répartition dans l'ordre de création et balance-short")
        void resolves_child_debitCourt_repartitParOrdreDeCreation() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity child = pendingRequest();
            assignId(child);
            child.setAmount(new BigDecimal("30.00"));
            child.setParentRequestId(UUID.randomUUID());
            Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
            WalletRefundRequestItemEntity first = itemOf(child.getId(), "pi_1", "10.00", WalletRefundItemStatus.PENDING, t0);
            WalletRefundRequestItemEntity second = itemOf(child.getId(), "pi_2", "10.00", WalletRefundItemStatus.PENDING, t0.plusSeconds(1));
            WalletRefundRequestItemEntity third = itemOf(child.getId(), "pi_3", "10.00", WalletRefundItemStatus.PENDING, t0.plusSeconds(2));
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(child));
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("14.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(refundRequestItemRepository.findByRefundRequestId(child.getId()))
                    .thenReturn(List.of(third, first, second));

            service.resolve(requestId, ADMIN_ID);

            verify(walletService).debitConfirmedRefund(USER_ID, "CAD", new BigDecimal("14.00"),
                    WalletTransactionType.ADMIN_REFUND_OUT);
            assertThat(first.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
            assertThat(first.getAmount()).isEqualByComparingTo("10.00");
            assertThat(second.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
            assertThat(second.getAmount()).isEqualByComparingTo("4.00");
            assertThat(third.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
            assertThat(third.getFailureReason()).isEqualTo("balance-short");
            assertThat(third.getAmount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("ticket racine → aucun item touché")
        void resolves_racine_neLitPasLesItems() {
            UUID requestId = UUID.randomUUID();
            WalletRefundRequestEntity root = pendingRequest();
            when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(root));
            when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("45.00"));
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            service.resolve(requestId, ADMIN_ID);

            verify(refundRequestItemRepository, never()).findByRefundRequestId(any());
        }
    }
}
