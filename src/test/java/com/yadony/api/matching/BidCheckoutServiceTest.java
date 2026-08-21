package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.Role;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.matching.dto.BidCheckoutResponse;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidCheckoutServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private PaymentService paymentService;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private BidPhotoService bidPhotoService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private BidCheckoutService service;

    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidCheckoutRequest req;

    @BeforeEach
    void setUp() {
        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", UUID.randomUUID());
        sender.setFirebaseUid("uid-sender");
        sender.setRoles(new HashSet<>());

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));

        req = new BidCheckoutRequest(
            announcement.getId(),
            new BigDecimal("2.00"),
            "test", "OTHER",
            "Recipient", "+221771234567", true, null, null);

        lenient().when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        lenient().when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    private PaymentResponse stubPaymentResponse() {
        return new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), "secret_xyz",
                                   BigDecimal.TEN, BigDecimal.ONE, "PENDING", "pi_test123");
    }

    private PaymentResponse stubPaymentResponse(String currency) {
        return new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), "secret_xyz",
                                   BigDecimal.TEN, BigDecimal.ONE, "PENDING", "pi_test123", currency);
    }

    @Test
    void creates_bid_in_AWAITING_PAYMENT_and_delegates_to_payment_service() {
        ArgumentCaptor<BidEntity> savedBid = ArgumentCaptor.forClass(BidEntity.class);
        when(bidRepository.save(savedBid.capture())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        BidEntity bid = savedBid.getAllValues().get(0);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNotNull();
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
    }

    @Test
    void checkout_response_exposes_the_payment_currency_for_local_display() {
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse("usd"));

        BidCheckoutResponse response = service.checkout("uid-sender", req, httpRequest);

        assertThat(response.currency()).isEqualTo("usd");
    }

    // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
    // legacy, le bid doit être persisté avec le libellé canonique.
    @Test
    void checkout_legacyContentCategory_isNormalizedOnWrite() {
        BidCheckoutRequest legacyReq = new BidCheckoutRequest(
            announcement.getId(), new BigDecimal("2.00"),
            "test", "Hi-fi, Téléphone",
            "Recipient", "+221771234567", true, null, null);

        ArgumentCaptor<BidEntity> savedBid = ArgumentCaptor.forClass(BidEntity.class);
        when(bidRepository.save(savedBid.capture())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse());

        service.checkout("uid-sender", legacyReq, httpRequest);

        assertThat(savedBid.getAllValues().get(0).getContentCategory())
            .isEqualTo("Téléphone & électronique");
    }

    @Test
    void rejects_inactive_announcement() {
        announcement.setStatus(AnnouncementStatus.FULL);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void rejects_bidding_on_own_announcement() {
        announcement.setTravelerId(sender.getId());
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void rejects_checkout_on_dedicated_trip_with_surplus_not_open() {
        // Dedicated trip (linked to a negotiation) whose surplus has NOT been opened:
        // a third-party sender must not be able to drive an escrow against the reserved
        // capacity. The trip is ACTIVE with availableKg == reserved weight, so only this
        // guard stops the checkout.
        announcement.setLinkedPackageRequestId(UUID.randomUUID());
        announcement.setSurplusPublished(false);

        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> {
                YadonyBusinessException ex = (YadonyBusinessException) e;
                assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(ex.getErrorCode()).isEqualTo("surplus-not-open");
            });
        verify(bidRepository, never()).save(any());
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void allows_checkout_on_dedicated_trip_once_surplus_published() {
        // Surplus opened by the traveler + weight ≤ availableKg → the guard lets the
        // checkout proceed and an escrow is created.
        announcement.setLinkedPackageRequestId(UUID.randomUUID());
        announcement.setSurplusPublished(true);
        announcement.setReservedKg(new BigDecimal("5.00"));
        announcement.setAvailableKg(new BigDecimal("8.00")); // surplus = 8 kg, req weight = 2 kg

        when(bidRepository.save(any())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
        verify(paymentService).createEscrow(any(CreatePaymentRequest.class), eq("uid-sender"));
    }

    @Test
    void rejects_checkout_for_reserved_sender_on_own_dedicated_trip() {
        // The negotiating sender already holds the reserved capacity on this dedicated
        // trip. Even once the surplus is published, they must not be able to checkout a
        // second parcel on the same trip (would be two shipments for one sender).
        announcement.setLinkedPackageRequestId(UUID.randomUUID());
        announcement.setSurplusPublished(true);
        announcement.setReservedSenderId(sender.getId());      // this sender is the reserved one
        announcement.setReservedKg(new BigDecimal("5.00"));
        announcement.setAvailableKg(new BigDecimal("8.00"));   // surplus = 8 kg, req weight = 2 kg

        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> {
                YadonyBusinessException ex = (YadonyBusinessException) e;
                assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(ex.getErrorCode()).isEqualTo("reserved-sender-cannot-bid");
            });
        verify(bidRepository, never()).save(any());
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void rejects_weight_exceeding_capacity() {
        announcement.setAvailableKg(new BigDecimal("1.00"));
        announcement.setTotalKg(new BigDecimal("1.00"));
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void kgFree_allows_weight_above_stored_availableKg() {
        // KG_FREE : availableKg stocké = 1 (factice), poids demandé = 2 > 1.
        // Sans la garde KG_FREE, le checkout rejetterait à tort (422
        // weight-exceeds-capacity). Avec la garde, le bid est créé.
        announcement.setCapacityUnit(CapacityUnit.KG_FREE);
        announcement.setAvailableKg(new BigDecimal("1.00"));
        announcement.setTotalKg(new BigDecimal("1.00"));

        ArgumentCaptor<BidEntity> savedBid = ArgumentCaptor.forClass(BidEntity.class);
        when(bidRepository.save(savedBid.capture())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        BidEntity bid = savedBid.getAllValues().get(0);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(bid.getWeightKg()).isEqualByComparingTo("2.00");
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
    }

    @Test
    void rejects_existing_in_progress_bid() {
        when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                eq(sender.getId()), eq(announcement.getId()), any()))
            .thenReturn(true);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void auto_assigns_SENDER_role() {
        when(bidRepository.save(any())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(), anyString())).thenReturn(stubPaymentResponse());

        service.checkout("uid-sender", req, httpRequest);

        assertThat(sender.getRoles()).contains(Role.SENDER);
        verify(userRepository).save(sender);
    }

    @Test
    void throws_when_user_not_found() {
        when(userRepository.findByFirebaseUid("unknown-uid")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkout("unknown-uid", req, httpRequest))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("user-not-found"));
    }

    @Test
    void throws_when_announcement_not_found() {
        BidCheckoutRequest unknownAnn = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"),
            "test", "OTHER", "Recipient", "+221771234567", true, null, null);
        when(announcementRepository.findByIdForUpdate(unknownAnn.announcementId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkout("uid-sender", unknownAnn, httpRequest))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("announcement-not-found"));
    }

    @Test
    void resumes_awaiting_payment_bid_idempotently() {
        BidEntity existing = new BidEntity();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setStatus(BidStatus.AWAITING_PAYMENT);
        existing.setAwaitingPaymentExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));

        when(bidRepository.findBySenderIdAndAnnouncementIdAndStatus(
                sender.getId(), announcement.getId(), BidStatus.AWAITING_PAYMENT))
            .thenReturn(Optional.of(existing));
        when(paymentService.createEscrow(any(), eq("uid-sender"))).thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        assertThat(resp.bidId()).isEqualTo(existing.getId());
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
        verify(bidRepository, never()).save(any());
    }

    @Test
    void resolveClientIp_uses_last_x_forwarded_for_value() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8, 9.10.11.12");
        when(bidRepository.save(any())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(), anyString())).thenReturn(stubPaymentResponse());

        service.checkout("uid-sender", req, httpRequest);

        ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
        verify(bidRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getDisclaimerSignedIp()).isEqualTo("9.10.11.12");
    }

    // ── negotiationCheckout : payer un accord de prix déjà conclu ────────────────
    //
    // POST /bids/checkout crée toujours un bid neuf. Un accord négocié porte sur un bid
    // qui EXISTE déjà (il a porté toute la discussion), et le repasser par checkout en
    // créerait un doublon tout en perdant le prix convenu. D'où ce second chemin, qui
    // n'ouvre l'escrow que sur le bid existant.

    private BidEntity negotiatedBid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setAnnouncementId(announcement.getId());
        b.setSenderId(sender.getId());
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setNegotiatedGrossEur(new BigDecimal("45.00"));
        b.setNegotiatedNetEur(new BigDecimal("42.86"));
        b.setCommissionRate(new BigDecimal("0.05"));
        b.setAwaitingPaymentExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(24));
        return b;
    }

    @Test
    void negotiationCheckout_opensEscrowOnTheExistingBid_withoutCreatingANewOne() {
        BidEntity bid = negotiatedBid();
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<CreatePaymentRequest> payReq = ArgumentCaptor.forClass(CreatePaymentRequest.class);
        when(paymentService.createEscrow(payReq.capture(), eq("uid-sender")))
                .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.negotiationCheckout("uid-sender", bid.getId());

        assertThat(payReq.getValue().getBidId()).isEqualTo(bid.getId());
        // Aucun montant client : le serveur relit l'accord figé sur le bid.
        assertThat(payReq.getValue().getTotalNetEur()).isNull();
        assertThat(resp.bidId()).isEqualTo(bid.getId());
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
        assertThat(resp.expiresAt()).isEqualTo(bid.getAwaitingPaymentExpiresAt());
        // Le PaymentIntent est reporté sur le bid, sans quoi ni confirm-payment ni
        // AwaitingPaymentCleanupScheduler ne sauraient le retrouver.
        assertThat(bid.getPaymentIntentId()).isEqualTo("pi_test123");
    }

    // ── Auto-réparation d'un bid dont l'escrow est déjà actif ────────────────────
    //
    // Régression : quand NI le webhook Stripe NI POST /bids/{id}/confirm-payment
    // n'ont promu le bid, il reste AWAITING_PAYMENT alors que son paiement est bien
    // en ESCROW. L'expéditeur revoyait « à payer », et tout nouveau paiement se
    // heurtait au 409 payment-already-completed de createEscrow — sans issue, même
    // après annulation (la reprise d'idempotence de checkout() le ramenait là).

    @Test
    void negotiationCheckout_whenEscrowIsAlreadyActive_promotesTheBidAndStopsThePayment() {
        BidEntity bid = negotiatedBid();
        bid.setPaymentIntentId("pi_already_paid");
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        // Le filet rejoué depuis le serveur : Stripe confirme l'autorisation.
        when(paymentService.confirmBidPayment(bid.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("bid-already-paid"));
        // Aucun second escrow ouvert : c'est bien la promotion qui débloque.
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void negotiationCheckout_whenPaymentIsNotActuallyAuthorized_proceedsNormally() {
        BidEntity bid = negotiatedBid();
        // PaymentIntent présent (sheet abandonnée) mais jamais autorisé : le
        // paiement doit rester possible, pas être confondu avec un bid déjà réglé.
        bid.setPaymentIntentId("pi_abandoned");
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.confirmBidPayment(bid.getId())).thenReturn(false);
        when(paymentService.createEscrow(any(), eq("uid-sender")))
                .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.negotiationCheckout("uid-sender", bid.getId());

        assertThat(resp.bidId()).isEqualTo(bid.getId());
    }

    @Test
    void negotiationCheckout_whenStripeIsUnreachable_doesNotBlockALegitimatePayment() {
        BidEntity bid = negotiatedBid();
        bid.setPaymentIntentId("pi_unknown");
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // Incident transitoire côté Stripe : ne doit jamais se transformer en refus.
        when(paymentService.confirmBidPayment(bid.getId()))
                .thenThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "stripe-error", "Stripe Error", "Stripe injoignable"));
        when(paymentService.createEscrow(any(), eq("uid-sender")))
                .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.negotiationCheckout("uid-sender", bid.getId());

        assertThat(resp.bidId()).isEqualTo(bid.getId());
    }

    @Test
    void negotiationCheckout_withoutPaymentIntent_neverCallsStripeForNothing() {
        BidEntity bid = negotiatedBid();
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.createEscrow(any(), eq("uid-sender")))
                .thenReturn(stubPaymentResponse());

        service.negotiationCheckout("uid-sender", bid.getId());

        // Premier paiement : rien à rattraper, aucun aller-retour Stripe supplémentaire.
        verify(paymentService, never()).confirmBidPayment(any());
    }

    @Test
    void negotiationCheckout_byAnotherUser_isForbidden() {
        BidEntity bid = negotiatedBid();
        bid.setSenderId(UUID.randomUUID());
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("forbidden"));
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void negotiationCheckout_onAnOrdinaryBid_isRejected() {
        BidEntity bid = negotiatedBid();
        bid.setNegotiatedGrossEur(null);
        bid.setNegotiatedNetEur(null);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("bid-not-negotiated"));
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void negotiationCheckout_whenTheThreadIsStillOpen_isRejected() {
        BidEntity bid = negotiatedBid();
        bid.setStatus(BidStatus.NEGOTIATING);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("bid-not-awaiting-payment"));
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    /** Un accord en espèces vit en PENDING : il n'y a rien à encaisser en ligne. */
    @Test
    void negotiationCheckout_onACashAgreement_isRejected() {
        BidEntity bid = negotiatedBid();
        bid.setStatus(BidStatus.PENDING);
        bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("bid-not-awaiting-payment"));
    }

    @Test
    void negotiationCheckout_unknownBid_isNotFound() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", unknown))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("bid-not-found"));
    }

    // Lot B (revue round 3, Critical B) : negotiationCheckout appelait createEscrow sans
    // AUCUNE garde de statut d'annonce — sœur littérale de checkout(), qui en a une.
    @Test
    void negotiationCheckout_announcementRemovedByAdmin_isRejected() {
        BidEntity bid = negotiatedBid();
        announcement.setStatus(AnnouncementStatus.REMOVED_BY_ADMIN);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("announcement-not-active"));
        verify(paymentService, never()).createEscrow(any(), anyString());
    }

    @Test
    void negotiationCheckout_announcementCancelled_isRejected() {
        BidEntity bid = negotiatedBid();
        announcement.setStatus(AnnouncementStatus.CANCELLED);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("announcement-not-active"));
    }

    @Test
    void negotiationCheckout_announcementCompleted_isRejected() {
        BidEntity bid = negotiatedBid();
        announcement.setStatus(AnnouncementStatus.COMPLETED);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.negotiationCheckout("uid-sender", bid.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("announcement-not-active"));
    }

    // Important (revue round 3) : FULL ne doit PAS être bloqué — un trajet partagé peut
    // légitimement se remplir entre l'attachement du fil négocié et son paiement (un
    // autre expéditeur a payé entretemps). Bloquer ce paiement serait une régression sur
    // un chemin qui fonctionnait.
    @Test
    void negotiationCheckout_announcementFull_isNotBlocked() {
        BidEntity bid = negotiatedBid();
        announcement.setStatus(AnnouncementStatus.FULL);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.createEscrow(any(), eq("uid-sender"))).thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.negotiationCheckout("uid-sender", bid.getId());

        assertThat(resp.bidId()).isEqualTo(bid.getId());
        verify(paymentService).createEscrow(any(), eq("uid-sender"));
    }
}
