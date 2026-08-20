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

import java.math.BigDecimal;
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

    private WalletRefundRequestService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletRefundRequestService(
                walletService, refundRequestRepository, auditService, adminAlertService);
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

    @Nested
    @DisplayName("request()")
    class RequestTests {

        @Test
        @DisplayName("aucun solde positif → 422 wallet-balance-empty, rien n'est créé")
        void noPositiveBalance_throws422() {
            when(walletService.getAllBalances(USER_ID)).thenReturn(
                    List.of(walletOf("EUR", "0.00")));

            assertThatThrownBy(() -> service.request(USER_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "wallet-balance-empty");

            verify(refundRequestRepository, never()).save(any());
            verify(adminAlertService, never()).raise(any(), any(), any());
        }

        @Test
        @DisplayName("un solde positif → crée le ticket, audit loggé, admin alerté")
        void onePositiveBalance_createsTicketAndAlertsAdmin() {
            when(walletService.getAllBalances(USER_ID)).thenReturn(
                    List.of(walletOf("CAD", "45.00")));
            when(refundRequestRepository.findByUserIdAndCurrencyAndStatus(
                    USER_ID, "CAD", WalletRefundRequestStatus.PENDING)).thenReturn(Optional.empty());
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            List<WalletRefundRequestEntity> result = service.request(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCurrency()).isEqualTo("CAD");
            assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.00");
            assertThat(result.get(0).getStatus()).isEqualTo(WalletRefundRequestStatus.PENDING);

            verify(auditService).log(eq("wallet_refund_request"), any(), eq("REQUESTED"), eq(USER_ID), any());
            ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
            verify(adminAlertService).raise(eq("wallet-refund-requested"), any(), contextCaptor.capture());
            assertThat(contextCaptor.getValue()).containsEntry("currency", "CAD");
        }

        @Test
        @DisplayName("plusieurs devises en solde positif → un ticket par devise")
        void multiplePositiveBalances_createsOneTicketPerCurrency() {
            when(walletService.getAllBalances(USER_ID)).thenReturn(
                    List.of(walletOf("EUR", "10.00"), walletOf("CAD", "20.00")));
            when(refundRequestRepository.findByUserIdAndCurrencyAndStatus(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(refundRequestRepository.save(any())).thenAnswer(inv -> {
                WalletRefundRequestEntity e = inv.getArgument(0);
                assignId(e);
                return e;
            });

            List<WalletRefundRequestEntity> result = service.request(USER_ID);

            assertThat(result).hasSize(2);
            verify(adminAlertService, times(2)).raise(any(), any(), any());
        }

        @Test
        @DisplayName("ticket PENDING déjà ouvert pour cette devise → réutilisé, pas de doublon ni re-alerte")
        void existingPendingTicket_isReusedNotDuplicated() {
            WalletRefundRequestEntity existing = new WalletRefundRequestEntity();
            existing.setUserId(USER_ID);
            existing.setCurrency("EUR");
            existing.setAmount(new BigDecimal("30.00"));
            existing.setStatus(WalletRefundRequestStatus.PENDING);

            when(walletService.getAllBalances(USER_ID)).thenReturn(
                    List.of(walletOf("EUR", "30.00")));
            when(refundRequestRepository.findByUserIdAndCurrencyAndStatus(
                    USER_ID, "EUR", WalletRefundRequestStatus.PENDING)).thenReturn(Optional.of(existing));

            List<WalletRefundRequestEntity> result = service.request(USER_ID);

            assertThat(result).containsExactly(existing);
            verify(refundRequestRepository, never()).save(any());
            verify(adminAlertService, never()).raise(any(), any(), any());
        }
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

            verify(walletService, never()).debit(any(), any(), any(), any(), any());
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

            verify(walletService).debit(USER_ID, "CAD", new BigDecimal("40.00"),
                    WalletTransactionType.ADMIN_REFUND_OUT, null);
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

            verify(walletService, never()).debit(any(), any(), any(), any(), any());
            assertThat(result.getStatus()).isEqualTo(WalletRefundRequestStatus.RESOLVED);
        }
    }
}
