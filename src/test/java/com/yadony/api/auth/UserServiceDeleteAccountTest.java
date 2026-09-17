package com.yadony.api.auth;

import com.yadony.api.auth.dto.DeletionEligibilityResponse;
import com.yadony.api.auth.dto.WalletSettlementDto;
import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.payments.wallet.WalletAllocationInvariantException;
import com.yadony.api.payments.wallet.WalletRefundAllocation;
import com.yadony.api.payments.wallet.WalletRefundRail;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestService;
import com.yadony.api.payments.wallet.WalletSelfRefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — suppression de compte")
class UserServiceDeleteAccountTest {

    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletAccountRepository walletAccountRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccountFinalizationService accountFinalizationService;
    @Mock private WalletRefundRequestService walletRefundRequestService;
    @Mock private WalletSelfRefundService walletSelfRefundService;

    @InjectMocks private UserService userService;

    private static final String FIREBASE_UID = "uid-test-001";
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        u.setFirebaseUid(FIREBASE_UID);
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("requestDeletion")
    class RequestDeletion {

        @Test
        @DisplayName("ESCROW actif → 422")
        void escrowActive_throws422() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> userService.requestDeletion(FIREBASE_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("succès → statut PENDING_DELETION, deletionRequestedAt set, event publié")
        void success_setsStatusAndPublishesEvent() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.requestDeletion(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
            assertThat(user.getDeletionRequestedAt()).isNotNull();

            ArgumentCaptor<AccountDeletionRequestedEvent> captor =
                ArgumentCaptor.forClass(AccountDeletionRequestedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("solde wallet positif → ne bloque plus, ticket ouvert, suppression poursuivie")
        void positiveWalletBalance_opensTicketAndSucceeds() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setCurrency("EUR");
            wallet.setBalance(java.math.BigDecimal.TEN);
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(java.util.List.of(wallet));
            when(walletSelfRefundService.allocation(USER_ID, "EUR"))
                    .thenReturn(allocation("10.00", "0"));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.requestDeletion(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
            verify(walletSelfRefundService).request(USER_ID, "EUR", List.of());
            verify(userRepository).save(any());
        }

        @Test
        @DisplayName("déjà PENDING_DELETION → idempotent, event non re-publié")
        void alreadyPending_idempotent() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            userService.requestDeletion(FIREBASE_UID);

            verify(eventPublisher, never()).publishEvent(any());
            verify(userRepository, never()).save(any());
        }
    }

    private WalletAccountEntity walletOf(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setUserId(USER_ID);
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    private static WalletRefundAllocation allocation(String refundable, String nonRefundable) {
        BigDecimal r = new BigDecimal(refundable);
        List<WalletRefundAllocation.RefundableTopup> list = r.signum() > 0
                ? List.of(new WalletRefundAllocation.RefundableTopup(UUID.randomUUID(), "pi_1", r, r,
                        BigDecimal.ZERO, WalletRefundRail.of("pi_1"), null))
                : List.of();
        return new WalletRefundAllocation(list, r, new BigDecimal(nonRefundable), BigDecimal.ZERO,
                BigDecimal.ZERO, r);
    }

    @Nested
    @DisplayName("settleWalletsForDeletion")
    class Settle {

        @Test
        @DisplayName("solde entamé → demande automatique sur tout le remboursable, non-cash conservé")
        void refundableAndNonCash_automaticRequestOnly() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("35.00", "5.00"));
            WalletRefundRequestEntity auto = new WalletRefundRequestEntity();
            when(walletSelfRefundService.request(USER_ID, "EUR", List.of())).thenReturn(auto);

            List<WalletRefundRequestEntity> opened = userService.settleWalletsForDeletion(USER_ID);

            assertThat(opened).containsExactly(auto);
            verify(walletRefundRequestService, never()).request(any(), any());
        }

        @Test
        @DisplayName("solde uniquement non-cash → aucune demande, aucun ticket")
        void nonCashOnly_nothingOpened() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "5.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("0", "5.00"));

            assertThat(userService.settleWalletsForDeletion(USER_ID)).isEmpty();
            verify(walletSelfRefundService, never()).request(any(), any(), any());
            verify(walletRefundRequestService, never()).request(any(), any());
        }

        @Test
        @DisplayName("invariant cassé → ticket manuel sur la devise")
        void invariantBroken_manualTicket() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR"))
                    .thenThrow(new WalletAllocationInvariantException(new BigDecimal("39.00"), new BigDecimal("40.00")));
            WalletRefundRequestEntity manual = new WalletRefundRequestEntity();
            when(walletRefundRequestService.request(USER_ID, "EUR")).thenReturn(manual);

            assertThat(userService.settleWalletsForDeletion(USER_ID)).containsExactly(manual);
        }

        @Test
        @DisplayName("reliquat arrondi à zéro à l'unité mineure (422 wallet-not-refund-eligible) → ignoré, la suppression continue")
        void nothingRefundableAfterScaling_ignored() {
            // Scénario réel (pas un doublon de demande, cf. commentaire de settleWalletsForDeletion) :
            // refundableTotal > 0 en 2 décimales côté allocation, mais chaque reliquat s'arrondit à
            // zéro une fois aligné sur l'unité mineure Stripe de la devise (ex. 0.50 XOF) — request()
            // lève alors wallet-not-refund-eligible, le seul code atteignable à ce point.
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("40.00", "0"));
            when(walletSelfRefundService.request(USER_ID, "EUR", List.of()))
                    .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-not-refund-eligible",
                            "Unprocessable", "Aucun montant remboursable sur ce solde"));

            assertThat(userService.settleWalletsForDeletion(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("solde nul → rien")
        void zeroBalance_nothing() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "0.00")));

            assertThat(userService.settleWalletsForDeletion(USER_ID)).isEmpty();
            verifyNoInteractions(walletSelfRefundService);
        }
    }

    @Nested
    @DisplayName("walletSettlement")
    class Settlement {

        @Test
        void stripeRail_montantsParDevise() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("35.00", "5.00"));

            List<WalletSettlementDto> s = userService.walletSettlement(USER_ID);

            assertThat(s).hasSize(1);
            assertThat(s.get(0).currency()).isEqualTo("EUR");
            assertThat(s.get(0).refundableAmount()).isEqualByComparingTo("35.00");
            assertThat(s.get(0).forfeitedAmount()).isEqualByComparingTo("5.00");
            assertThat(s.get(0).rail()).isEqualTo("STRIPE");
        }

        @Test
        void stripeRail_inFlightRemonteDeLAllocateur() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(
                    new WalletRefundAllocation(List.of(), new BigDecimal("20.00"),
                            new BigDecimal("5.00"), new BigDecimal("15.00"),
                            BigDecimal.ZERO, new BigDecimal("20.00")));

            List<WalletSettlementDto> s = userService.walletSettlement(USER_ID);

            assertThat(s.get(0).refundableAmount()).isEqualByComparingTo("20.00");
            assertThat(s.get(0).forfeitedAmount()).isEqualByComparingTo("5.00");
            assertThat(s.get(0).inFlightAmount()).isEqualByComparingTo("15.00");
            assertThat(s.get(0).rail()).isEqualTo("STRIPE");
        }

        @Test
        void pawapayRail_toutesLesCiblesMobileMoney_railPawapayFraisNetEtDestinationMasquee() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("XOF", "10000.00")));
            UUID depositId = UUID.randomUUID();
            WalletRefundAllocation a = new WalletRefundAllocation(
                    List.of(new WalletRefundAllocation.RefundableTopup(UUID.randomUUID(), "pawapay:" + depositId,
                            new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("200"),
                            WalletRefundRail.PAWAPAY, "ORANGE_CIV")),
                    new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("200"), new BigDecimal("9800"));
            when(walletSelfRefundService.allocation(USER_ID, "XOF")).thenReturn(a);
            when(walletSelfRefundService.destinationMasked(a)).thenReturn("+225 •••• 90");

            WalletSettlementDto s = userService.walletSettlement(USER_ID).get(0);

            assertThat(s.rail()).isEqualTo(WalletSettlementDto.RAIL_PAWAPAY).isEqualTo("PAWAPAY");
            assertThat(s.refundableAmount()).isEqualByComparingTo("10000");
            assertThat(s.feeAmount()).isEqualByComparingTo("200");
            assertThat(s.netAmount()).isEqualByComparingTo("9800");
            assertThat(s.destinationMasked()).isEqualTo("+225 •••• 90");
        }

        @Test
        void stripeRail_rechargeCarteJamaisUtilisee_fraisEtNetSansDestination() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            WalletRefundAllocation a = new WalletRefundAllocation(
                    List.of(new WalletRefundAllocation.RefundableTopup(UUID.randomUUID(), "pi_1",
                            new BigDecimal("40.00"), new BigDecimal("40.00"), new BigDecimal("0.85"),
                            WalletRefundRail.STRIPE, null)),
                    new BigDecimal("40.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("0.85"), new BigDecimal("39.15"));
            when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(a);

            WalletSettlementDto s = userService.walletSettlement(USER_ID).get(0);

            assertThat(s.rail()).isEqualTo("STRIPE");
            assertThat(s.feeAmount()).isEqualByComparingTo("0.85");
            assertThat(s.netAmount()).isEqualByComparingTo("39.15");
            assertThat(s.destinationMasked()).isNull();
        }

        @Test
        void railsMixtes_railStripe() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("XOF", "20000.00")));
            WalletRefundAllocation a = new WalletRefundAllocation(
                    List.of(new WalletRefundAllocation.RefundableTopup(UUID.randomUUID(), "pi_1",
                                    BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, WalletRefundRail.STRIPE, null),
                            new WalletRefundAllocation.RefundableTopup(UUID.randomUUID(), "pawapay:" + UUID.randomUUID(),
                                    BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, WalletRefundRail.PAWAPAY, "MTN")),
                    new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20"));
            when(walletSelfRefundService.allocation(USER_ID, "XOF")).thenReturn(a);

            assertThat(userService.walletSettlement(USER_ID).get(0).rail()).isEqualTo("STRIPE");
        }

        /**
         * Rail MANUAL : aucun montant de l'allocateur n'existe (le rejeu a échoué). Le repli
         * ouvre un ticket admin sur TOUT le solde, que la résolution admin rembourse en entier
         * et que la finalisation ne perd pas — {@code refundableAmount = solde},
         * {@code forfeitedAmount = 0} et {@code inFlightAmount = 0} décrivent donc bien ce qui
         * repart vers l'utilisateur par ce rail. Comportement figé ici : ne pas le remplacer
         * par un {@code refundableTotal} d'allocateur, qui n'est pas calculable dans ce cas.
         */
        @Test
        void invariantCasse_railManualSurToutLeSolde() {
            when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
            when(walletSelfRefundService.allocation(USER_ID, "EUR"))
                    .thenThrow(new WalletAllocationInvariantException(BigDecimal.ONE, BigDecimal.TEN));

            List<WalletSettlementDto> s = userService.walletSettlement(USER_ID);

            assertThat(s.get(0).rail()).isEqualTo("MANUAL");
            assertThat(s.get(0).refundableAmount()).isEqualByComparingTo("40.00");
            assertThat(s.get(0).forfeitedAmount()).isEqualByComparingTo("0");
            assertThat(s.get(0).inFlightAmount()).isEqualByComparingTo("0");
            assertThat(s.get(0).feeAmount()).isEqualByComparingTo("0");
            assertThat(s.get(0).netAmount()).isEqualByComparingTo("40.00");
            assertThat(s.get(0).destinationMasked()).isNull();
        }
    }

    @Test
    @DisplayName("checkDeletionEligibility expose walletSettlement et hasWalletBalance")
    void checkDeletionEligibility_exposeSettlement() {
        UserEntity user = makeUser(UserStatus.ACTIVE);
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
        when(walletAccountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(walletOf("EUR", "40.00")));
        when(walletSelfRefundService.allocation(USER_ID, "EUR")).thenReturn(allocation("35.00", "5.00"));

        DeletionEligibilityResponse r = userService.checkDeletionEligibility(FIREBASE_UID);

        assertThat(r.canDelete()).isTrue();
        assertThat(r.hasWalletBalance()).isTrue();
        assertThat(r.walletSettlement()).hasSize(1);
        assertThat(r.walletSettlement().get(0).refundableAmount()).isEqualByComparingTo("35.00");
    }

    @Nested
    @DisplayName("reactivateAccount")
    class Reactivate {

        @Test
        @DisplayName("PENDING_DELETION → ACTIVE, deletionRequestedAt null, audit log")
        void success_reactivates() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            user.setDeletionRequestedAt(Instant.now().minusSeconds(3600));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.reactivateAccount(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeletionRequestedAt()).isNull();
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_DELETION_CANCELLED"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("statut != PENDING_DELETION → 409")
        void notPending_throws409() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.reactivateAccount(FIREBASE_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("finalizeGdprDeletion")
    class FinalizeGdpr {

        @Test
        @DisplayName("délègue à AccountFinalizationService avec SOFT_GRACE_EXPIRED")
        void delegatesToFinalizationService() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);

            userService.finalizeGdprDeletion(user);

            verify(accountFinalizationService).finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
        }
    }
}
