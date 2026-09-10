package com.yadony.api.requests.service;

import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.entity.*;
import com.yadony.api.requests.event.NegotiationDepositPendingEvent;
import com.yadony.api.requests.event.NegotiationDepositRevertedEvent;
import com.yadony.api.requests.event.NegotiationCancelledEvent;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.requests.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class NegotiationServiceMobileMoneyTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private com.yadony.api.requests.NegotiationProperties negotiationProperties;
    @Mock private CommissionProperties commissionProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock private NegotiationMobileMoneyPort mobileMoneyPort;

    NegotiationService service;

    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    PackageRequestEntity request;
    NegotiationThreadEntity thread;

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

    @BeforeEach
    void setUp() {
        service = new NegotiationService(requestRepo, threadRepo, messageRepo, userRepository, announcementRepo,
            eventPublisher, auditService, config, negotiationProperties, commissionProperties, cashGatePort,
            escrowPort, storageService, photoService, commissionRateResolver, exchangeRateService, mobileMoneyPort);

        request = new PackageRequestEntity();
        setId(request, UUID.randomUUID());
        request.setSenderId(senderId);
        request.setCurrency("XOF");
        request.setStatus(PackageRequestStatus.NEGOTIATING);
        request.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        request.setRecipientName("Awa");
        request.setRecipientPhone("+221770000000");

        thread = new NegotiationThreadEntity();
        setId(thread, UUID.randomUUID());
        thread.setPackageRequestId(request.getId());
        thread.setTravelerId(travelerId);
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setCurrency("XOF");
        thread.setCurrentPriceEur(new BigDecimal("30000"));
        thread.setCommissionRate(new BigDecimal("0.10"));
        thread.setAvailablePaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        thread.setTravelerAvailableKg(new BigDecimal("5"));
        thread.setTravelerTravelDate(LocalDate.now().plusDays(3));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now());

        lenient().when(threadRepo.findPackageRequestIdById(thread.getId())).thenReturn(Optional.of(request.getId()));
        lenient().when(requestRepo.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        lenient().when(requestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        lenient().when(threadRepo.findById(thread.getId())).thenReturn(Optional.of(thread));
        lenient().when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        // Compte de versement actif en XOF : source unique via UserEntity#canReceiveMobileMoney.
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        traveler.setMobileMoneyCurrency("XOF");
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @Test
    void prepare_awaitingPayment_movesToAwaitingDeposit_createsPayment_publishesPending() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        when(mobileMoneyPort.createPendingDeposit(thread.getId(), senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.10"), "XOF"))
                .thenReturn(new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), new BigDecimal("33000"), new BigDecimal("3000"), expiresAt));

        var prepared = service.prepareMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
        assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.MOBILE_MONEY);
        assertThat(thread.getDepositExpiresAt()).isEqualTo(expiresAt);
        assertThat(prepared.gross()).isEqualByComparingTo("33000");
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(NegotiationDepositPendingEvent.class);
    }

    /**
     * Revue finale, I1 : le promo copié sur le fil à start() (taux non figé) doit être résolu au
     * dépôt, comme sur la carte, sinon le scellement le rachète au taux non remisé.
     */
    @Test
    void prepare_withValidPromoOnThread_freezesDiscountedRate_andKeepsPromo() {
        thread.setCommissionRate(null);
        thread.setPromoCode("WELCOME05");
        when(commissionRateResolver.resolve(travelerId, senderId, "WELCOME05")).thenReturn(new BigDecimal("0.05"));
        when(mobileMoneyPort.createPendingDeposit(thread.getId(), senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.05"), "XOF"))
                .thenReturn(new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), new BigDecimal("31500"), new BigDecimal("1500"), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30)));

        service.prepareMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getCommissionRate()).isEqualByComparingTo("0.05");
        assertThat(thread.getPromoCode()).isEqualTo("WELCOME05");
        verify(commissionRateResolver, never()).resolve(travelerId, senderId);
    }

    @Test
    void prepare_withInvalidPromoOnThread_fallsBackToBaseRate_andClearsPromo() {
        thread.setCommissionRate(null);
        thread.setPromoCode("EXPIRED");
        when(commissionRateResolver.resolve(travelerId, senderId, "EXPIRED"))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-expired", "Promo Expired", "Ce code est expiré."));
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.12"));
        when(mobileMoneyPort.createPendingDeposit(thread.getId(), senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.12"), "XOF"))
                .thenReturn(new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), new BigDecimal("33600"), new BigDecimal("3600"), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30)));

        service.prepareMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getCommissionRate()).isEqualByComparingTo("0.12");
        assertThat(thread.getPromoCode()).isNull();
    }

    @Test
    void prepare_withoutPromo_andNoFrozenRate_usesBaseRate() {
        thread.setCommissionRate(null);
        when(commissionRateResolver.resolve(travelerId, senderId)).thenReturn(new BigDecimal("0.12"));
        when(mobileMoneyPort.createPendingDeposit(thread.getId(), senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.12"), "XOF"))
                .thenReturn(new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), new BigDecimal("33600"), new BigDecimal("3600"), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30)));

        service.prepareMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getCommissionRate()).isEqualByComparingTo("0.12");
        verify(commissionRateResolver, never()).resolve(any(), any(), any());
    }

    @Test
    void prepare_alreadyAwaitingDeposit_isIdempotent() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(mobileMoneyPort.createPendingDeposit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NegotiationMobileMoneyPort.PendingDeposit(UUID.randomUUID(), new BigDecimal("33000"), new BigDecimal("3000"), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30)));

        var prepared = service.prepareMobileMoneyDeposit(senderId, thread.getId());

        assertThat(prepared.expiresAt()).isEqualTo(thread.getDepositExpiresAt()); // l'échéance initiale est conservée
        verify(eventPublisher, never()).publishEvent(any(NegotiationDepositPendingEvent.class));
    }

    @Test
    void prepare_methodNotAvailableOnThread_is422() {
        thread.setAvailablePaymentMethods(EnumSet.of(PaymentMethod.CASH));
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment-method/not-in-available-set");
    }

    @Test
    void prepare_travelerCannotReceiveInThreadCurrency_is422() {
        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/traveler-cannot-receive-mobile-money");
    }

    @Test
    void prepare_notSender_is403() {
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(UUID.randomUUID(), thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/not-thread-participant");
    }

    @Test
    void prepare_methodNotAcceptedByRequest_is422() {
        request.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("payment-method/not-accepted");
    }

    @Test
    void prepare_recipientDetailsMissing_is422() {
        request.setRecipientPhone(null);
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("request/details-incomplete");
    }

    @Test
    void prepare_awaitingDepositExpired_isConflict() {
        // AWAITING_DEPOSIT dont l'échéance est passée n'est plus "déjà en cours" (alreadyPending
        // faux) ni AWAITING_PAYMENT : ni idempotent, ni relançable tel quel.
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("thread/not-awaiting-payment");
    }

    @Test
    void prepare_travelerAnnouncementRemovedByAdmin_isRefused() {
        UUID announcementId = UUID.randomUUID();
        thread.setTravelerAnnouncementId(announcementId);
        com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
        ann.setStatus(com.yadony.api.matching.AnnouncementStatus.REMOVED_BY_ADMIN);
        when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(ann));

        assertThatThrownBy(() -> service.prepareMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("announcement/not-active");
        verify(mobileMoneyPort, never()).createPendingDeposit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void finalizeAfterDeposit_awaitingDeposit_sealsThread_withMobileMoneyMethod_andNullPaymentIntent() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        thread.setDepositExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(threadRepo.findByPackageRequestId(request.getId())).thenReturn(List.of(thread));

        service.finalizeAfterMobileMoneyDeposit(thread.getId());

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
        assertThat(thread.getDepositExpiresAt()).isNull();
        assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(ev.capture());
        var accepted = ev.getAllValues().stream().filter(PackageRequestAcceptedEvent.class::isInstance)
                .map(PackageRequestAcceptedEvent.class::cast).findFirst().orElseThrow();
        assertThat(accepted.paymentMethod()).isEqualTo(PaymentMethod.MOBILE_MONEY);
        assertThat(accepted.paymentIntentId()).isNull();
        verify(mobileMoneyPort, never()).refundEscrowedDeposit(any());
    }

    @Test
    void finalizeAfterDeposit_threadAutoRejectedMeanwhile_refundsInsteadOfSealing() {
        thread.setStatus(NegotiationThreadStatus.AUTO_REJECTED);

        service.finalizeAfterMobileMoneyDeposit(thread.getId());

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
        verify(mobileMoneyPort).refundEscrowedDeposit(thread.getId());
        verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
    }

    @Test
    void revert_awaitingDeposit_backToAwaitingPayment_publishesReverted() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now().plusMinutes(10));

        service.revertMobileMoneyDeposit(thread.getId(), "deposit-failed");

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
        assertThat(thread.getDepositExpiresAt()).isNull();
        verify(eventPublisher).publishEvent(any(NegotiationDepositRevertedEvent.class));
    }

    @Test
    void revert_notAwaitingDeposit_isNoop() {
        service.revertMobileMoneyDeposit(thread.getId(), "deposit-failed");
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void revert_requestMissing_publishesWithNullSenderId() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setPackageRequestId(UUID.randomUUID()); // aucune demande stubbée pour cet id

        service.revertMobileMoneyDeposit(thread.getId(), "deposit-expired");

        ArgumentCaptor<NegotiationDepositRevertedEvent> ev = ArgumentCaptor.forClass(NegotiationDepositRevertedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().senderId()).isNull();
    }

    @Test
    void cancelDeposit_notSender_is403_threadUntouched() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        assertThatThrownBy(() -> service.cancelMobileMoneyDeposit(UUID.randomUUID(), thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/not-thread-participant");
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
    }

    @Test
    void cancelDeposit_notAwaitingDeposit_is409() {
        // Statut par défaut du fil : AWAITING_PAYMENT.
        assertThatThrownBy(() -> service.cancelMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/not-awaiting-deposit");
    }

    @Test
    void cancelDeposit_nothingPending_backToAwaitingPayment() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.NOTHING_PENDING);

        service.cancelMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
    }

    @Test
    void cancelDeposit_depositInFlight_is409_threadUntouched() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN);

        assertThatThrownBy(() -> service.cancelMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/deposit-in-flight");
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
    }

    @Test
    void cancelDeposit_released_backToAwaitingPayment() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.CANCELLED);

        service.cancelMobileMoneyDeposit(senderId, thread.getId());

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
    }

    @Test
    void expire_due_isReverted() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.CANCELLED);

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.REVERTED);
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
    }

    @Test
    void expire_notDueYet_isIgnored() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.IGNORED);
        verify(mobileMoneyPort, never()).releasePendingDeposit(any());
    }

    /**
     * Revue finale, I2 : dépôt COMPLETED mais confirmation jamais appliquée (paiement PENDING) :
     * le balayage n'expire pas, il rejoue la confirmation par le port ; l'événement republié
     * scellera le fil, qui reste AWAITING_DEPOSIT d'ici là.
     */
    @Test
    void expire_depositCompletedNotApplied_replaysConfirmationViaPort_threadUntouched() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.REPAIRED);

        verify(mobileMoneyPort).repairDepositCompletedNotApplied(thread.getId());
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
        verify(eventPublisher, never()).publishEvent(any(NegotiationDepositRevertedEvent.class));
    }

    /** Revue finale, I2 : séquestre posé mais scellement jamais passé : le balayage scelle. */
    @Test
    void expire_escrowNotSealed_sealsTheThread() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.ESCROW_NOT_SEALED);
        when(threadRepo.findByPackageRequestId(request.getId())).thenReturn(List.of(thread));

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.REPAIRED);

        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
        assertThat(thread.getDepositExpiresAt()).isNull();
        assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
        verify(mobileMoneyPort, never()).repairDepositCompletedNotApplied(any());
        verify(mobileMoneyPort, never()).refundEscrowedDeposit(any());
        verify(eventPublisher, never()).publishEvent(any(NegotiationDepositRevertedEvent.class));
    }

    /** Une réparation qui lève remonte telle quelle : le runner attrape et alerte. */
    @Test
    void expire_repairThrows_propagates() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);
        org.mockito.Mockito.doThrow(new IllegalStateException("deposit disparu")).when(mobileMoneyPort).repairDepositCompletedNotApplied(thread.getId());

        assertThatThrownBy(() -> service.expireMobileMoneyDeposit(thread.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deposit disparu");
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
    }

    @Test
    void cancelDeposit_escrowNotSealed_is409_threadUntouched() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.ESCROW_NOT_SEALED);

        assertThatThrownBy(() -> service.cancelMobileMoneyDeposit(senderId, thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/deposit-in-flight");
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
    }

    @Test
    void expire_nothingPending_isReverted() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.NOTHING_PENDING);

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.REVERTED);
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
    }

    @Test
    void expire_depositOpen_isIgnored_threadUntouched() {
        thread.setStatus(NegotiationThreadStatus.AWAITING_DEPOSIT);
        thread.setDepositExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(mobileMoneyPort.releasePendingDeposit(thread.getId())).thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN);

        assertThat(service.expireMobileMoneyDeposit(thread.getId())).isEqualTo(NegotiationService.DepositExpiryOutcome.IGNORED);
        assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_DEPOSIT);
    }

    @Test
    void expire_threadMissing_isIgnored() {
        assertThat(service.expireMobileMoneyDeposit(UUID.randomUUID())).isEqualTo(NegotiationService.DepositExpiryOutcome.IGNORED);
        verifyNoInteractions(mobileMoneyPort);
    }

    @Test
    void requireParticipantThread_sender_ok() {
        assertThat(service.requireParticipantThread(senderId, thread.getId())).isSameAs(thread);
    }

    @Test
    void requireParticipantThread_traveler_ok() {
        assertThat(service.requireParticipantThread(travelerId, thread.getId())).isSameAs(thread);
    }

    @Test
    void requireParticipantThread_thirdParty_is403() {
        assertThatThrownBy(() -> service.requireParticipantThread(UUID.randomUUID(), thread.getId()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("negotiation/not-thread-participant");
    }

    @Test
    void cancelledEvent_releaseEscrow_isTrueForAwaitingDeposit() {
        var e = new NegotiationCancelledEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "x",
                NegotiationThreadStatus.AWAITING_DEPOSIT);
        assertThat(e.releaseEscrow()).isTrue();
    }
}
