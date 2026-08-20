package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.promo.PromoService;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.requests.event.PackageRequestDetailsCompletedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ThreadAcceptedBidListenerTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StorageService storageService;
    @Mock BidPhotoService bidPhotoService;
    @Mock PromoService promoService;
    @Mock CommissionVoucherService voucherService;
    @InjectMocks ThreadAcceptedBidListener listener;

    private static final UUID THREAD_ID = UUID.randomUUID();
    private static final UUID PACKAGE_REQUEST_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    private static final LocalDateTime DISCLAIMER_AT = LocalDateTime.now();

    private PackageRequestAcceptedEvent buildEvent() {
        return buildEvent(com.yadony.api.payments.cash.PaymentMethod.STRIPE);
    }

    private PackageRequestAcceptedEvent buildEvent(com.yadony.api.payments.cash.PaymentMethod paymentMethod) {
        return buildEvent(paymentMethod, null);
    }

    private PackageRequestAcceptedEvent buildEvent(
            com.yadony.api.payments.cash.PaymentMethod paymentMethod, String commissionChargedVia) {
        return buildEvent(paymentMethod, commissionChargedVia, null, null);
    }

    private PackageRequestAcceptedEvent buildEvent(
            com.yadony.api.payments.cash.PaymentMethod paymentMethod, String commissionChargedVia,
            String promoCode, BigDecimal commissionRate) {
        return new PackageRequestAcceptedEvent(
                THREAD_ID, PACKAGE_REQUEST_ID,
                SENDER_ID, TRAVELER_ID,
                BigDecimal.valueOf(50),
                ANNOUNCEMENT_ID,
                BigDecimal.valueOf(5),
                "Vêtements", "CLOTHING", "pi_test",
                "Fatou Diop", "+221771234567",
                DISCLAIMER_AT, "1.2.3.4",
                paymentMethod, java.util.List.of(), commissionChargedVia,
                promoCode, commissionRate);
    }

    @Nested
    @DisplayName("onPackageRequestAccepted()")
    class OnAcceptedTests {

        @BeforeEach
        void defaultStubs() {
            lenient().when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.empty());
            lenient().when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(announcementRepository.findById(any())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("bid créé et audité pour un thread sans bid existant")
        void createsBidAndAudits() {
            listener.onPackageRequestAccepted(buildEvent());

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(saved.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(saved.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            assertThat(saved.getLinkedNegotiationThreadId()).isEqualTo(THREAD_ID);
            assertThat(saved.getRecipientName()).isEqualTo("Fatou Diop");
            assertThat(saved.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(saved.getDisclaimerSignedAt()).isEqualTo(DISCLAIMER_AT);
            assertThat(saved.getDisclaimerSignedIp()).isEqualTo("1.2.3.4");
            // Net négocié figé depuis agreedPriceEur (= 50) de l'événement.
            assertThat(saved.getNegotiatedNetEur()).isEqualByComparingTo(BigDecimal.valueOf(50));
            verify(auditService).log(eq("BID"), any(), eq("CREATED_FROM_THREAD"), eq(SENDER_ID), any());
        }

        @Test
        @DisplayName("promoCode présent → redeem appelé avec le bid_id fraîchement matérialisé, rate stampé sur le bid")
        void promoCodePresent_redeemsAndStampsRate() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);

            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.STRIPE, null, "WELCOME6", new BigDecimal("0.06")));

            verify(promoService).redeem("WELCOME6", SENDER_ID, bidId, new BigDecimal("0.06"));
            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getCommissionRate()).isEqualByComparingTo("0.06");
        }

        @Test
        @DisplayName("promoCode absent → jamais de redeem")
        void noPromoCode_neverRedeems() {
            listener.onPackageRequestAccepted(buildEvent());

            verify(promoService, never()).redeem(any(), any(), any(), any());
        }

        @Test
        @DisplayName("promoCode présent mais commissionRate null (fallback tarif de base) → jamais de redeem, "
                + "évite la violation NOT NULL sur promo_redemptions.applied_rate")
        void promoCodePresentButNullCommissionRate_neverRedeems() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);

            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.STRIPE, null, "WELCOME6", null));

            verify(promoService, never()).redeem(any(), any(), any(), any());
            // Un seul save : pas de re-save pour stamper un commissionRate absent.
            verify(bidRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("redeem échoue → matérialisation du bid non impactée (best-effort)")
        void redeemFailure_doesNotBreakMaterialization() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);
            when(promoService.redeem(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.STRIPE, null, "WELCOME6", new BigDecimal("0.06")));

            verify(bidRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("bon de parrainage (lot 3) : consume() toujours tenté avec le bid_id fraîchement matérialisé")
        void voucherConsumeAlwaysAttempted() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);

            listener.onPackageRequestAccepted(buildEvent());

            verify(voucherService).consume(SENDER_ID, bidId);
        }

        @Test
        @DisplayName("consommation du bon échoue → matérialisation du bid non impactée (best-effort)")
        void voucherConsumeFailure_doesNotBreakMaterialization() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);
            when(voucherService.consume(any(), any())).thenThrow(new RuntimeException("boom"));

            listener.onPackageRequestAccepted(buildEvent());

            verify(bidRepository, atLeastOnce()).save(any());
        }

        private PackageRequestAcceptedEvent buildEventWithPhotos(java.util.List<String> keys) {
            return new PackageRequestAcceptedEvent(
                    THREAD_ID, PACKAGE_REQUEST_ID, SENDER_ID, TRAVELER_ID,
                    BigDecimal.valueOf(50), ANNOUNCEMENT_ID, BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "pi_test",
                    "Fatou Diop", "+221771234567",
                    DISCLAIMER_AT, "1.2.3.4",
                    com.yadony.api.payments.cash.PaymentMethod.STRIPE, keys, null, null, null);
        }

        private void saveSetsId(UUID bidId) {
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                org.springframework.test.util.ReflectionTestUtils.setField(b, "id", bidId);
                return b;
            });
        }

        @Test
        @DisplayName("photos demande → copiées vers bids/ puis attachées au bid")
        void copiesAndAttachesPhotos() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);
            when(storageService.copyObject(eq("package_requests/" + SENDER_ID + "/1.jpg"), eq("bids/" + SENDER_ID + "/")))
                    .thenReturn("bids/" + SENDER_ID + "/copy1.jpg");
            when(storageService.copyObject(eq("package_requests/" + SENDER_ID + "/2.jpg"), eq("bids/" + SENDER_ID + "/")))
                    .thenReturn("bids/" + SENDER_ID + "/copy2.jpg");

            listener.onPackageRequestAccepted(buildEventWithPhotos(java.util.List.of(
                    "package_requests/" + SENDER_ID + "/1.jpg",
                    "package_requests/" + SENDER_ID + "/2.jpg")));

            verify(bidPhotoService).attachPhotos(bidId, SENDER_ID, java.util.List.of(
                    "bids/" + SENDER_ID + "/copy1.jpg", "bids/" + SENDER_ID + "/copy2.jpg"));
        }

        @Test
        @DisplayName("échec copie d'une photo → best-effort, les autres sont attachées")
        void photoCopyFailureIsBestEffort() {
            UUID bidId = UUID.randomUUID();
            saveSetsId(bidId);
            when(storageService.copyObject(eq("package_requests/" + SENDER_ID + "/bad.jpg"), any()))
                    .thenThrow(new RuntimeException("s3 down"));
            when(storageService.copyObject(eq("package_requests/" + SENDER_ID + "/ok.jpg"), any()))
                    .thenReturn("bids/" + SENDER_ID + "/ok-copy.jpg");

            listener.onPackageRequestAccepted(buildEventWithPhotos(java.util.List.of(
                    "package_requests/" + SENDER_ID + "/bad.jpg",
                    "package_requests/" + SENDER_ID + "/ok.jpg")));

            verify(bidRepository).save(any(BidEntity.class));
            verify(bidPhotoService).attachPhotos(bidId, SENDER_ID, java.util.List.of("bids/" + SENDER_ID + "/ok-copy.jpg"));
        }

        @Test
        @DisplayName("aucune photo → ni copie ni attache")
        void noPhotos_noCopyNoAttach() {
            saveSetsId(UUID.randomUUID());

            listener.onPackageRequestAccepted(buildEventWithPhotos(java.util.List.of()));

            verify(storageService, never()).copyObject(any(), any());
            verify(bidPhotoService, never()).attachPhotos(any(), any(), any());
        }

        @Test
        @DisplayName("BidMaterializedEvent publié avec threadId + bidId après matérialisation")
        void publishesBidMaterializedEvent() {
            UUID bidId = UUID.randomUUID();
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                org.springframework.test.util.ReflectionTestUtils.setField(b, "id", bidId);
                return b;
            });

            listener.onPackageRequestAccepted(buildEvent());

            ArgumentCaptor<BidMaterializedEvent> captor =
                    ArgumentCaptor.forClass(BidMaterializedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            BidMaterializedEvent published = captor.getValue();
            assertThat(published.getNegotiationThreadId()).isEqualTo(THREAD_ID);
            assertThat(published.getBidId()).isEqualTo(bidId);
        }

        @Test
        @DisplayName("bid déjà existant → idempotence, aucune création")
        void idempotenceSkipsCreation() {
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.of(new BidEntity()));

            listener.onPackageRequestAccepted(buildEvent());

            verify(bidRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(BidMaterializedEvent.class));
        }

        @Test
        @DisplayName("travelerAnnouncementId null → skip avec warning")
        void nullAnnouncementIdSkips() {
            PackageRequestAcceptedEvent event = new PackageRequestAcceptedEvent(
                    THREAD_ID, PACKAGE_REQUEST_ID,
                    SENDER_ID, TRAVELER_ID,
                    BigDecimal.valueOf(50),
                    null, // no announcementId
                    BigDecimal.valueOf(5),
                    "desc", "CLOTHING", "pi_test",
                    "Fatou Diop", "+221771234567",
                    DISCLAIMER_AT, "1.2.3.4",
                    com.yadony.api.payments.cash.PaymentMethod.STRIPE, java.util.List.of(), null, null, null);

            listener.onPackageRequestAccepted(event);

            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("description null → utilise contentCategory comme fallback")
        void nullDescriptionFallsBackToContentCategory() {
            PackageRequestAcceptedEvent event = new PackageRequestAcceptedEvent(
                    THREAD_ID, PACKAGE_REQUEST_ID,
                    SENDER_ID, TRAVELER_ID,
                    BigDecimal.valueOf(50),
                    ANNOUNCEMENT_ID,
                    BigDecimal.valueOf(5),
                    null, "CLOTHING", "pi_test",
                    "Fatou Diop", "+221771234567",
                    DISCLAIMER_AT, "1.2.3.4",
                    com.yadony.api.payments.cash.PaymentMethod.STRIPE, java.util.List.of(), null, null, null);

            listener.onPackageRequestAccepted(event);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(captor.getValue().getDescription()).isEqualTo("CLOTHING");
        }

        @Test
        @DisplayName("event CASH via WALLET → bid avec commission CHARGED et commissionChargedVia=WALLET")
        void cashEventMarksBidCashAndCommissionCharged() {
            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.CASH, "WALLET"));

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getPaymentMethod())
                    .isEqualTo(com.yadony.api.payments.cash.PaymentMethod.CASH);
            assertThat(saved.getCommissionStatus())
                    .isEqualTo(com.yadony.api.payments.cash.CommissionStatus.CHARGED);
            // Sans cette propagation, BidCancelledCommissionRefundListener ne peut pas
            // rembourser la commission si ce bid est annulé avant remise (via=null).
            assertThat(saved.getCommissionChargedVia())
                    .isEqualTo(com.yadony.api.payments.cash.CommissionChargedVia.WALLET);
        }

        @Test
        @DisplayName("event CASH via CARD → bid avec commissionChargedVia=CARD")
        void cashEventViaCard_setsCommissionChargedViaCard() {
            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.CASH, "CARD"));

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(captor.getValue().getCommissionChargedVia())
                    .isEqualTo(com.yadony.api.payments.cash.CommissionChargedVia.CARD);
        }

        @Test
        @DisplayName("event STRIPE → bid avec paymentMethod STRIPE, commission non touchée")
        void stripeEventMarksBidStripe() {
            listener.onPackageRequestAccepted(
                    buildEvent(com.yadony.api.payments.cash.PaymentMethod.STRIPE));

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getPaymentMethod())
                    .isEqualTo(com.yadony.api.payments.cash.PaymentMethod.STRIPE);
            assertThat(saved.getCommissionStatus()).isNull();
        }

        @Test
        @DisplayName("annonce non-KgFree → availableKg décrémenté du poids du bid")
        void decrementsAvailableKgOnAcceptance() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            ann.setAvailableKg(BigDecimal.valueOf(32));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent()); // weightKg = 5

            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getAvailableKg())
                    .isEqualByComparingTo(BigDecimal.valueOf(27));
        }

        @Test
        @DisplayName("bid matérialisé hérite la devise de l'annonce, pas le défaut EUR "
            + "(régression bug devise-par-annonce : bid.currency n'était jamais posé ici)")
        void materializedBidCarriesAnnouncementCurrency() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            ann.setAvailableKg(BigDecimal.valueOf(32));
            ann.setCurrency("CAD");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent());

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrency()).isEqualTo("CAD");
        }

        @Test
        @DisplayName("annonce KgFree → availableKg inchangé")
        void kgFreeAnnouncementSkipsDecrement() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.KG_FREE);
            ann.setAvailableKg(BigDecimal.valueOf(32));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent());

            verify(announcementRepository).save(any()); // saved but kg unchanged
            assertThat(ann.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(32));
        }

        @Test
        @DisplayName("availableKg ≤ 0 après décrémentation → status FULL")
        void setsFullWhenCapacityExhausted() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            ann.setAvailableKg(BigDecimal.valueOf(5)); // exactement le poids du bid
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent()); // weightKg = 5

            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("Lot B (correction 2) : annonce REMOVED_BY_ADMIN — le bid est quand même " +
                "matérialisé (paiement déjà acquis) mais l'annonce n'est pas ressuscitée en FULL")
        void doesNotResurrectRemovedByAdminAnnouncementToFull() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            ann.setAvailableKg(BigDecimal.valueOf(5)); // exactement le poids du bid
            ann.setStatus(AnnouncementStatus.REMOVED_BY_ADMIN);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent()); // weightKg = 5

            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AnnouncementStatus.REMOVED_BY_ADMIN);
            // Le bid est bien créé malgré le statut de l'annonce (argent déjà engagé côté sender)
            verify(bidRepository).save(any(BidEntity.class));
        }

        @Test
        @DisplayName("trajet dédié (linkedPackageRequestId != null) → availableKg non décrémenté")
        void dedicatedTripSkipsKgDecrement() {
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            ann.setAvailableKg(BigDecimal.ZERO); // intentionnellement 0 sur un trajet dédié
            ann.setLinkedPackageRequestId(UUID.randomUUID());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent()); // weightKg = 5

            // Annonce sauvegardée (pour applyHandoverFrom) mais kg non touché
            verify(announcementRepository).save(any());
            assertThat(ann.getAvailableKg()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ann.getStatus()).isNotEqualTo(AnnouncementStatus.FULL);
            // Bid bien créé malgré le skip kg
            verify(bidRepository).save(any(BidEntity.class));
        }

        @Test
        @DisplayName("annonce trouvée → bid hérite date limite de dépôt + lieu de pickup")
        void copiesHandoverWindowFromAnnouncement() {
            LocalDateTime start = LocalDate.now().plusDays(5).atTime(16, 0);
            LocalDateTime end   = LocalDate.now().plusDays(5).atTime(18, 0);
            AnnouncementEntity ann = new AnnouncementEntity();
            ann.setHandoverDeadline(end);
            ann.setPickupAddressLabel("Gare du Nord");
            lenient().when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(ann));

            listener.onPackageRequestAccepted(buildEvent());

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getHandoverDeadline()).isEqualTo(end);
            assertThat(saved.getHandoverLocation()).isEqualTo("Gare du Nord");
        }
    }

    @Nested
    @DisplayName("onPackageRequestDetailsCompleted()")
    class OnDetailsCompletedTests {

        @Test
        @DisplayName("détails propagés sur le bid existant")
        void propagatesDetailsOnExistingBid() {
            BidEntity bid = new BidEntity();
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.of(bid));
            when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestDetailsCompletedEvent event = new PackageRequestDetailsCompletedEvent(
                    PACKAGE_REQUEST_ID, THREAD_ID, SENDER_ID,
                    "Aminata Diallo", "+221701234567",
                    LocalDateTime.now(), "127.0.0.1");

            listener.onPackageRequestDetailsCompleted(event);

            assertThat(bid.getRecipientName()).isEqualTo("Aminata Diallo");
            verify(bidRepository).save(bid);
        }

        @Test
        @DisplayName("aucun bid existant → skip silencieux")
        void noBidSkipsSilently() {
            when(bidRepository.findByLinkedNegotiationThreadId(THREAD_ID))
                    .thenReturn(Optional.empty());

            PackageRequestDetailsCompletedEvent event = new PackageRequestDetailsCompletedEvent(
                    PACKAGE_REQUEST_ID, THREAD_ID, SENDER_ID,
                    "Doe", "+33600000000",
                    LocalDateTime.now(), "127.0.0.1");

            listener.onPackageRequestDetailsCompleted(event);

            verify(bidRepository, never()).save(any());
        }
    }
}
