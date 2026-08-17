package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidRejectRequest;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidService — tests unitaires")
class BidServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RatingRepository ratingRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private com.yadony.api.auth.BlockService blockService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.promo.PromoService promoService;
    @Mock private StorageService storageService;
    @Mock private BidPhotoService bidPhotoService;
    @Mock private com.yadony.api.auth.FirebaseContactService firebaseContact;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        org.mockito.Mockito.lenient()
                .when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }
    @Mock private HttpServletRequest httpRequest;
    @Spy private CurrencyMatchGuard currencyMatchGuard = new CurrencyMatchGuard();

    @InjectMocks private BidService bidService;

    private static final String SENDER_UID = "uid-sender-001";
    private static final String TRAVELER_UID = "uid-traveler-001";
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    // ─── Helpers ────────────────────────────────────────────────────────────────

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

    private UserEntity buildSender() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(SENDER_UID);
        // Le username est posé par le @PrePersist de UserEntity, qui ne s'exécute pas sur une
        // entité jamais persistée : sans lui, les replis testés ici rendraient null.
        u.setUsername("user1784907068");
        u.getRoles().add(Role.SENDER);
        setId(u, SENDER_ID);
        return u;
    }

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(TRAVELER_UID);
        u.getRoles().add(Role.TRAVELER);
        setId(u, TRAVELER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private BidEntity buildBid() {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setWeightKg(BigDecimal.valueOf(5));
        b.setDescription("Vêtements");
        b.setContentCategory("CLOTHING");
        b.setRecipientName("Aminata Diallo");
        b.setRecipientPhone("+221701234567");
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        setId(b, BID_ID);
        return b;
    }

    private BidRequest buildRequest(BigDecimal weight) {
        return new BidRequest(weight, "Vêtements", "CLOTHING",
                "Aminata Diallo", "+221701234567", true, null, null, null, null, null, null);
    }

    private UserBusinessPrefsEntity prefsWithCurrency(String code) {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(SENDER_ID);
        prefs.setCurrencyCode(code);
        return prefs;
    }

    @BeforeEach
    void stubCancellationRepository() {
        lenient().when(cancellationRepository.findAllByBidId(any()))
                .thenReturn(java.util.List.of());
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        // Les numéros viennent de Firebase, plus de la colonne users.phone_number
        lenient().when(firebaseContact.getContact(SENDER_UID)).thenReturn(
                new com.yadony.api.auth.FirebaseContactService.Contact("+33612345678", null));
        lenient().when(firebaseContact.getContact(TRAVELER_UID)).thenReturn(
                new com.yadony.api.auth.FirebaseContactService.Contact("+33611223344", null));
        // Pass-through for presigned avatar URLs — tests don't care about the URL value
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        // Return empty photos list by default — toResponse calls this for every bid
        lenient().when(bidPhotoService.activePhotos(any())).thenReturn(java.util.List.of());
        lenient().when(announcementRepository.findByIdForUpdate(any()))
                .thenAnswer(inv -> announcementRepository.findById(inv.getArgument(0)));
    }

    // ─── createBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createBid()")
    class CreateBidTests {

        @BeforeEach
        void setupHttpRequest() {
            lenient().when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
            lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        }

        @Test
        @DisplayName("demande valide → bid créé + audit enregistré")
        void createBid_valid_createsBidAndAudits() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                    SENDER_ID, ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED)))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse result = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest);

            assertThat(result).isNotNull();
            assertThat(result.weightKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(result.photos()).isEmpty();
            verify(auditService).log(eq("BID"), any(), eq("BID_CREATED"), any(), any());
            // Carte : pas d'événement avant l'autorisation Stripe, sinon le voyageur
            // recevrait une proposition abandonnée ou un doublon après le webhook.
            verify(eventPublisher, never()).publishEvent(any(BidCreatedEvent.class));
        }

        @Test
        @DisplayName("devise absente côté sender → fallback EUR, mismatch 422, aucun save irréversible")
        void createBid_missingSenderCurrencyFallsBackToEurAndFailsBeforeAnySave() {
            UserEntity sender = buildSender();
            sender.getRoles().clear();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCurrency("USD");
            BigDecimal availableKgBefore = announcement.getAvailableKg();
            AnnouncementStatus statusBefore = announcement.getStatus();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("currency-mismatch");
                    });

            verify(activeCurrencyResolver).resolve(SENDER_ID);
            verify(userRepository, never()).save(any(UserEntity.class));
            verify(bidRepository, never()).save(any(BidEntity.class));
            verify(announcementRepository, never()).save(any(AnnouncementEntity.class));
            verifyNoInteractions(auditService, eventPublisher);
            assertThat(sender.getRoles()).doesNotContain(Role.SENDER);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(availableKgBefore);
            assertThat(announcement.getStatus()).isEqualTo(statusBefore);
        }

        @Test
        @DisplayName("quand la devise matche, le bid copie exactement la devise de l'annonce")
        void createBid_matchingCurrencyCopiesExactAnnouncementCurrency() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCurrency("cad");

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(activeCurrencyResolver.resolve(SENDER_ID)).thenReturn("CAD");
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                    SENDER_ID, ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED)))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidResponse result = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)), httpRequest);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(result).isNotNull();
            assertThat(captor.getValue().getCurrency()).isEqualTo("cad");
        }

        // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
        // legacy, le bid doit être persisté avec le libellé canonique.
        @Test
        @DisplayName("contentCategory legacy ('Hi-fi') → persisté normalisé ('Téléphone & électronique')")
        void createBid_legacyContentCategory_isNormalizedOnWrite() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                    SENDER_ID, ANNOUNCEMENT_ID, List.of(BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED)))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidRequest req = new BidRequest(BigDecimal.valueOf(5),
                    "desc", "Hi-fi, Téléphone",
                    "Aminata Diallo", "+221701234567", true, null, null, null, null, null, null);

            BidResponse result = bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest);

            assertThat(result.contentCategory()).isEqualTo("Téléphone & électronique");
        }

        @Test
        @DisplayName("poids dépasse la capacité → 422 UNPROCESSABLE_ENTITY")
        void createBid_weightExceedsCapacity_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement(); // 20 kg available

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(25)), // 25 > 20
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("weight-exceeds-capacity");
                    });
        }

        @Test
        @DisplayName("createBid KG_FREE: 10 kg bid on availableKg=1 → bid created (placement check bypassed)")
        void createBid_kgFree_weightExceedsSentinel_allowed() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.KG_FREE);
            announcement.setAvailableKg(BigDecimal.ONE);  // sentinel

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            // 10 kg > availableKg=1, but KG_FREE — must not throw
            assertThatCode(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.TEN),
                    httpRequest)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("disclaimer non signé → 422 UNPROCESSABLE_ENTITY")
        void createBid_disclaimerNotSigned_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            BidRequest req = new BidRequest(BigDecimal.valueOf(5),
                    "Desc", "CAT", "Recip", "+221", false, null, null, null, null, null, null); // not signed

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("disclaimer-not-signed"));
        }

        @Test
        @DisplayName("bid déjà existant → 409 CONFLICT")
        void createBid_alreadyHasBid_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("already-bid"));
        }

        @Test
        @DisplayName("bid sur sa propre annonce → 409 CONFLICT")
        void createBid_ownAnnouncement_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setTravelerId(SENDER_ID); // Same user is the traveler

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("cannot-bid-own-announcement"));
        }

        @Test
        @DisplayName("bid sur trajet dédié sans surplus ouvert → 409 surplus-not-open")
        void createBid_dedicatedTripSurplusNotOpen_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setLinkedPackageRequestId(UUID.randomUUID()); // trajet dédié
            announcement.setSurplusPublished(false);                   // surplus non ouvert

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("surplus-not-open");
                    });
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("bid sur trajet dédié avec surplus ouvert + poids ≤ capacité → bid créé")
        void createBid_dedicatedTripSurplusOpen_createsBid() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setLinkedPackageRequestId(UUID.randomUUID()); // trajet dédié
            announcement.setSurplusPublished(true);                    // surplus ouvert
            announcement.setReservedKg(BigDecimal.valueOf(5));
            announcement.setAvailableKg(BigDecimal.valueOf(8));         // surplus = 8 kg

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidResponse result = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(8)),
                    httpRequest);

            assertThat(result).isNotNull();
            assertThat(result.weightKg()).isEqualByComparingTo(BigDecimal.valueOf(8));
            verify(bidRepository).save(any(BidEntity.class));
        }

        @Test
        @DisplayName("bid du sender réservé sur son propre trajet dédié (surplus ouvert) → 409 reserved-sender-cannot-bid")
        void createBid_reservedSender_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setLinkedPackageRequestId(UUID.randomUUID()); // trajet dédié
            announcement.setSurplusPublished(true);                    // surplus ouvert
            announcement.setReservedSenderId(SENDER_ID);               // ce sender est le réservé
            announcement.setReservedKg(BigDecimal.valueOf(5));
            announcement.setAvailableKg(BigDecimal.valueOf(8));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(2)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("reserved-sender-cannot-bid");
                    });
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("annonce non ACTIVE → 409 CONFLICT")
        void createBid_announcementNotActive_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.FULL);

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("announcement-not-active"));
        }

        @Test
        @DisplayName("annonce DRAFT (brouillon non publié) → 409 CONFLICT, aucun bid créé")
        void createBidOnDraft_rejected() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.DRAFT);

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)),
                    httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement-not-active");
                    });
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("IP extraite du header X-Forwarded-For (proxy)")
        void createBid_withForwardedFor_extractsClientIp() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 198.51.100.2");
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any())).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5)), httpRequest);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            // Last hop in X-Forwarded-For is used (added by trusted proxy, not spoofable by client)
            assertThat(captor.getValue().getDisclaimerSignedIp()).isEqualTo("198.51.100.2");
        }

        @Test
        @DisplayName("utilisateur sans rôle SENDER → rôle ajouté automatiquement")
        void createBid_userWithoutSenderRole_addsSenderRole() {
            UserEntity sender = new UserEntity();
            sender.setFirebaseUid(SENDER_UID);
            setId(sender, SENDER_ID);
            // No SENDER role

            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(userRepository.save(any())).thenReturn(sender);
            when(bidRepository.save(any())).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5)), httpRequest);

            assertThat(sender.getRoles()).contains(Role.SENDER);
        }

        /**
         * Le voyageur décide seul, et sans lui la question ne se pose pas : il n'y a
         * plus de garde KYC de plateforme sur la création d'offre. Un expéditeur non
         * vérifié doit donc être refusé par le réglage du voyageur, pas par
         * yadony.kyc.enforce, qui ne gouverne plus que la publication d'annonces.
         */
        @Test
        @DisplayName("expéditeur non vérifié + voyageur exigeant des profils vérifiés → 403 contact-kyc-required")
        void createBid_senderNotVerified_travelerRequiresVerified_throwsForbidden() {
            UserEntity sender = buildSender();
            // kycStatus null par défaut → != VERIFIED
            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            traveler.setContactKycOnly(true);
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5)), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("contact-kyc-required"));
        }

        @Test
        @DisplayName("expéditeur non vérifié + voyageur ouvert aux non vérifiés → offre créée")
        void createBid_senderNotVerified_travelerAcceptsUnverified_createsBid() {
            UserEntity sender = buildSender();
            // kycStatus null par défaut → l'expéditeur n'est PAS vérifié.
            UserEntity traveler = new UserEntity();
            setId(traveler, TRAVELER_ID);
            traveler.setContactKycOnly(false); // le voyageur a levé l'exigence
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidResponse response = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID,
                    buildRequest(BigDecimal.valueOf(5)), httpRequest);

            assertThat(response).isNotNull();
            verify(bidRepository).save(any(BidEntity.class));
        }

        @Test
        @DisplayName("paymentMethod=CASH + annonce accepte cash → bid créé avec PaymentMethod.CASH")
        void createBid_cashAccepted_setsCashOnBid() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.STRIPE,
                                         com.yadony.api.payments.cash.PaymentMethod.CASH));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidRequest cashReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567", true, "CASH", null, null, null, null, null);

            BidResponse result = bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, cashReq, httpRequest);

            assertThat(result.paymentMethod()).isEqualTo("CASH");
            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository).save(captor.capture());
            assertThat(captor.getValue().getPaymentMethod())
                    .isEqualTo(com.yadony.api.payments.cash.PaymentMethod.CASH);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isNotInstanceOf(BidCreatedEvent.class);
            assertThat(eventCaptor.getValue().getClass().getSimpleName())
                    .isEqualTo("CashBidCreatedEvent");
        }

        @Test
        @DisplayName("paymentMethod=CASH + annonce n'accepte pas cash → 422 UNPROCESSABLE_ENTITY")
        void createBid_cashNotAcceptedByAnnouncement_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement(); // default = STRIPE only

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            BidRequest cashReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567", true, "CASH", null, null, null, null, null);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, cashReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("cash-not-accepted");
                    });
        }

        @Test
        @DisplayName("paymentMethod=STRIPE + annonce n'accepte pas la carte → 422 card-not-accepted")
        void createBid_stripeNotAcceptedByAnnouncement_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.CASH));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            BidRequest stripeReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567", true, "STRIPE", null, null, null, null, null);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, stripeReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("card-not-accepted");
                    });
        }

        @Test
        @DisplayName("paymentMethod omis (défaut STRIPE) + annonce cash-only → 422 card-not-accepted")
        void createBid_noPaymentMethodDefaultsToStripe_cashOnlyAnnouncement_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.CASH));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            // paymentMethod = null → défaut STRIPE (comportement existant) → doit être rejeté
            // sur une annonce cash-only, pas silencieusement accepté.
            BidRequest noMethodReq = buildRequest(BigDecimal.valueOf(5));

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, noMethodReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("card-not-accepted");
                    });
        }

        @Test
        @DisplayName("paymentMethod invalide → 422 UNPROCESSABLE_ENTITY")
        void createBid_invalidPaymentMethod_throwsUnprocessable() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);

            BidRequest badReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567", true, "BITCOIN", null, null, null, null, null);

            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, badReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-payment-method"));
        }

        @Test
        @DisplayName("paymentMethod=WAVE → 422 mobile-money-bid-payment-retired (retiré)")
        void createBid_wave_isRejectedAsRetired() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.STRIPE,
                                         com.yadony.api.payments.cash.PaymentMethod.WAVE));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidRequest waveReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567",
                    true, "WAVE", "+221771234567", "SN", null, null, null);

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, waveReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-bid-payment-retired");
                    });
        }

        @Test
        @DisplayName("paymentMethod=WAVE, même si l'annonce l'accepterait → 422 retiré (avant tout autre check)")
        void createBid_waveEvenIfAnnouncementWouldAccept_isRejectedAsRetiredFirst() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement(); // default = STRIPE only, sans importance ici

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidRequest waveReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567",
                    true, "WAVE", "+221771234567", "SN", null, null, null);

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, waveReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-bid-payment-retired");
                    });
        }

        @Test
        @DisplayName("paymentMethod=ORANGE_MONEY, même sans phone → 422 retiré (avant le check phone)")
        void createBid_orangeMoneyEvenWithoutPhone_isRejectedAsRetiredFirst() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAcceptedPaymentMethods(
                    java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.ORANGE_MONEY));

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidRequest omReq = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567",
                    true, "ORANGE_MONEY", null, "CI", null, null, null); // phoneNumber null

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, omReq, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("mobile-money-bid-payment-retired");
                    });
        }

        @Test
        @DisplayName("createBid GRID → pricingMode GRID + bidGridItems sauvegardés")
        @SuppressWarnings("unchecked")
        void createBid_GRID_saves_grid_items() {
            UserEntity sender = buildSender();
            sender.setKycStatus(com.yadony.api.auth.KycStatus.VERIFIED); // passe le filtre contactKycOnly (défaut true)
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setPricingMode(com.yadony.api.matching.PricingMode.MIXED);
            UUID gridItemId = UUID.randomUUID();

            AnnouncementPriceGridItemEntity annGridItem = new AnnouncementPriceGridItemEntity();
            annGridItem.setAnnouncementId(ANNOUNCEMENT_ID);
            annGridItem.setLabel("Valise cabine");
            annGridItem.setUnitPriceNet(new BigDecimal("10.00"));
            annGridItem.setPosition(0);

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any())).thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });
            when(annGridItemRepository.findById(gridItemId)).thenReturn(Optional.of(annGridItem));
            when(bidGridItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(ratingRepository.existsByBidIdAndRaterId(any(), any())).thenReturn(false);
            lenient().when(cancellationRepository.findAllByBidId(any())).thenReturn(java.util.List.of());
            lenient().when(userRepository.findById(any())).thenReturn(Optional.of(sender));

            BidRequest req = new BidRequest(
                null,  // weightKg null → GRID mode
                "Mes affaires", "VETEMENTS",
                "Mamadou Diallo", "+221771234567",
                true, "STRIPE",
                null, null,  // phoneNumber, countryCode
                null,        // promoCode
                null,        // photoKeys
                List.of(new BidGridItemRequest(gridItemId, 2))
            );

            BidResponse resp = bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest);

            verify(bidGridItemRepository).saveAll(argThat(items -> {
                List<BidGridItemEntity> list = (List<BidGridItemEntity>) items;
                return list.size() == 1
                    && list.get(0).getQuantity() == 2
                    && list.get(0).getLabelSnapshot().equals("Valise cabine");
            }));
            assertThat(resp.pricingMode()).isEqualTo(BidPricingMode.GRID);
        }

        @Test
        @DisplayName("createBid — weightKg null ET gridItems vide → 422")
        void createBid_fails_when_both_weightKg_and_gridItems_absent() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();

            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any())).thenReturn(false);

            BidRequest req = new BidRequest(
                null,                    // weightKg null
                "Test", "CAT",
                "Name", "+33600000000",
                true, "STRIPE",
                null, null,              // phoneNumber, countryCode
                null,                    // promoCode
                null,                    // photoKeys
                List.of()               // gridItems vide
            );

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("photoKeys présents → attachPhotos appelé")
        void createBid_withPhotoKeys_attachesPhotos() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(any(), any(), any()))
                    .thenReturn(false);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> {
                BidEntity b = inv.getArgument(0);
                setId(b, BID_ID);
                return b;
            });

            BidRequest req = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "CLOTHING", "Aminata Diallo", "+221701234567", true,
                    null, null, null, null,
                    java.util.List.of("bids/" + SENDER_ID + "/1.jpg"), null);

            bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest);

            verify(bidPhotoService).attachPhotos(eq(BID_ID), eq(SENDER_ID),
                    eq(java.util.List.of("bids/" + SENDER_ID + "/1.jpg")));
        }

        @Test
        @DisplayName("catégorie refusée par l'annonce → 422 content-type-refused")
        void createBid_refusedCategory_throws422() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            // Reflète l'état réaliste post-C2/post-V171 : refusedTypes est toujours déjà
            // canonique en production (normalisé à l'écriture par AnnouncementService,
            // ou par V171 pour l'historique). Bid ici aussi déjà canonique — cas de base.
            announcement.setRefusedTypes(java.util.List.of("Téléphone & électronique"));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidRequest req = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "Téléphone & électronique", "Aminata Diallo", "+221701234567", true,
                    null, null, null, null, null, null);

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("content-type-refused"));
        }

        // C2 : reproduit le scénario exact du finding — announcement.refusedTypes déjà
        // normalisé (état réaliste post-fix : V171 ou écriture via AnnouncementService),
        // mais le bid entrant porte encore un libellé/code legacy ("Hi-fi", client pas à
        // jour). Sans la normalisation AVANT assertNotRefused, cette comparaison
        // échouerait à matcher et un refus explicite du voyageur passerait (plus de 422).
        @Test
        @DisplayName("catégorie refusée (déjà canonique) vs bid legacy non normalisé → 422 quand même")
        void createBid_refusedCanonicalCategory_vsLegacyBidCategory_stillThrows422() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setRefusedTypes(java.util.List.of("Téléphone & électronique"));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidRequest req = new BidRequest(BigDecimal.valueOf(5),
                    "Vêtements", "Hi-fi", "Aminata Diallo", "+221701234567", true,
                    null, null, null, null, null, null);

            assertThatThrownBy(() -> bidService.createBid(ANNOUNCEMENT_ID, SENDER_UID, req, httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("content-type-refused"));
        }
    }

    // ─── acceptBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptBid()")
    class AcceptBidTests {

        @Test
        @DisplayName("bid valide → accepté + tokens générés + capacité réduite + event")
        void acceptBid_valid_acceptsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse result = bidService.acceptBid(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            assertThat(bid.getQrToken()).isNotNull();
            assertThat(bid.getTrackingNumber()).startsWith("DON-");
            assertThat(bid.getTrackingToken()).isNotNull();
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(15));

            ArgumentCaptor<BidAcceptedEvent> captor = ArgumentCaptor.forClass(BidAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        }

        @Test
        @DisplayName("capacité insuffisante → 409 CONFLICT")
        void acceptBid_insufficientCapacity_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.valueOf(3)); // Less than bid weight (5 kg)

            BidEntity bid = buildBid();
            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("capacity-insufficient"));
        }

        @Test
        @DisplayName("pas propriétaire de l'annonce → 403 FORBIDDEN")
        void acceptBid_notOwner_throwsForbidden() {
            UserEntity otherTraveler = new UserEntity();
            setId(otherTraveler, UUID.randomUUID());
            otherTraveler.setFirebaseUid(TRAVELER_UID);

            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(otherTraveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid non PAYMENT_ESCROWED → 409 CONFLICT")
        void acceptBid_notPending_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED); // Already accepted

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("acceptBid remplit exactement la capacité → annonce passe FULL")
        void acceptBid_fillsCapacity_becomesFulls() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.valueOf(5)); // exact match avec le bid
            BidEntity bid = buildBid(); // weightKg = 5

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            bidService.acceptBid(BID_ID, TRAVELER_UID);

            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("annonce IN_PROGRESS → n'accepte plus de colis → 409 CONFLICT")
        void acceptBid_announcementInProgress_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
            BidEntity bid = buildBid(); // status = PAYMENT_ESCROWED

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("announcement-not-accepting"));
        }

        @Test
        @DisplayName("acceptBid — bid hérite de la date limite de dépôt de l'annonce")
        void acceptBid_copiesHandoverWindowFromAnnouncement() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            LocalDateTime start = LocalDate.now().plusDays(5).atTime(16, 0);
            LocalDateTime end   = LocalDate.now().plusDays(5).atTime(18, 0);
            announcement.setHandoverDeadline(end);
            announcement.setPickupAddressLabel("Gare du Nord");
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any(BidEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.acceptBid(BID_ID, TRAVELER_UID);

            ArgumentCaptor<BidEntity> captor = ArgumentCaptor.forClass(BidEntity.class);
            verify(bidRepository, atLeastOnce()).save(captor.capture());
            BidEntity saved = captor.getValue();
            assertThat(saved.getHandoverDeadline()).isEqualTo(end);
            assertThat(saved.getHandoverLocation()).isEqualTo("Gare du Nord");
        }
    }

    // ─── acceptBidBySystem ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptBidBySystem()")
    class AcceptBidBySystemTests {

        @Test
        @DisplayName("travelerId propriétaire → accepté sans résolution firebaseUid")
        void acceptBidBySystem_acceptsWithoutFirebaseUid() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            BidResponse response = bidService.acceptBidBySystem(BID_ID, TRAVELER_ID);

            assertThat(response.status()).isEqualTo(BidStatus.ACCEPTED.name());
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        }

        @Test
        @DisplayName("travelerId ne possède pas l'annonce → IllegalStateException")
        void acceptBidBySystem_throwsWhenAnnouncementNotOwnedByTravelerId() {
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            UUID otherTravelerId = UUID.randomUUID();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.acceptBidBySystem(BID_ID, otherTravelerId))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("travelerId propriétaire mais introuvable en base → IllegalStateException")
        void acceptBidBySystem_throwsWhenTravelerNotFound() {
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bidService.acceptBidBySystem(BID_ID, TRAVELER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Traveler not found: " + TRAVELER_ID);
        }
    }

    // ─── KgFree capacity — acceptance, cancel, and placement ──────────────────

    @Nested
    @DisplayName("KG_FREE capacity")
    class KgFreeCapacityTests {

        /** KG_FREE announcement: availableKg is stored as a small sentinel (e.g. 1).
         *  Accepting a 10 kg bid should NOT throw and should leave availableKg + status untouched. */
        @Test
        @DisplayName("acceptBid KG_FREE: 10 kg bid on availableKg=1 → accepted, no capacity error")
        void acceptBid_kgFree_largeWeightAllowed() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.KG_FREE);
            announcement.setAvailableKg(BigDecimal.ONE);  // sentinel, must stay unchanged
            BidEntity bid = buildBid();
            bid.setWeightKg(BigDecimal.TEN);              // 10 > 1 — would normally be rejected
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatCode(() -> bidService.acceptBid(BID_ID, TRAVELER_UID)).doesNotThrowAnyException();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        }

        @Test
        @DisplayName("acceptBid KG_FREE: availableKg unchanged after acceptance (no decrement)")
        void acceptBid_kgFree_doesNotDecrementAvailableKg() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.KG_FREE);
            announcement.setAvailableKg(BigDecimal.ONE);
            BidEntity bid = buildBid();
            bid.setWeightKg(BigDecimal.TEN);
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.acceptBid(BID_ID, TRAVELER_UID);

            // Sentinel must remain at 1 — not decremented, not set to 0
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ONE);
            // Status must NOT flip to FULL
            assertThat(announcement.getStatus()).isNotEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("cancelBid KG_FREE: cancelling an accepted bid does NOT re-increment availableKg")
        void cancelBid_kgFree_doesNotReIncrementAvailableKg() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.KG_FREE);
            announcement.setAvailableKg(BigDecimal.ONE);  // sentinel
            BidEntity bid = buildBid();
            bid.setWeightKg(BigDecimal.TEN);
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            // availableKg must remain at sentinel value — not inflated by 10
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ONE);
        }

        // ── Regression: non-KG_FREE behavior is unchanged ─────────────────────

        @Test
        @DisplayName("regression — SUITCASE_23KG: weight > availableKg → capacity-insufficient")
        void acceptBid_suitcase_weightExceedsCapacity_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setAvailableKg(BigDecimal.valueOf(5));
            BidEntity bid = buildBid();
            bid.setWeightKg(BigDecimal.TEN);  // 10 > 5
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("capacity-insufficient"));
        }

        @Test
        @DisplayName("regression — SUITCASE_23KG: weight == availableKg → accepted, status FULL")
        void acceptBid_suitcase_exactCapacity_becomesFull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setAvailableKg(BigDecimal.valueOf(5));
            BidEntity bid = buildBid();
            bid.setWeightKg(BigDecimal.valueOf(5));
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);

            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenReturn(announcement);
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.acceptBid(BID_ID, TRAVELER_UID);

            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }
    }

    // ─── rejectBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rejectBid()")
    class RejectBidTests {

        @Test
        @DisplayName("bid valide → rejeté + raison enregistrée + event publié")
        void rejectBid_valid_rejectsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Trop lourd"));

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
            assertThat(bid.getRejectionReason()).isEqualTo("Trop lourd");

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        }

        @Test
        @DisplayName("bid WAVE en PENDING → rejectBid réussit sans exception (bypass off-platform)")
        void rejectBid_wavePending_succeeds() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);
            bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.WAVE);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            assertThatCode(() ->
                bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Non compatible"))
            ).doesNotThrowAnyException();

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
        }

        @Test
        @DisplayName("bid ORANGE_MONEY en PENDING → rejectBid réussit sans exception (bypass off-platform)")
        void rejectBid_orangeMoneyPending_succeeds() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);
            bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.ORANGE_MONEY);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            assertThatCode(() ->
                bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Non compatible"))
            ).doesNotThrowAnyException();

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
        }

        @Test
        @DisplayName("bid STRIPE en PENDING → rejectBid lève 409 (STRIPE doit être en PAYMENT_ESCROWED)")
        void rejectBid_stripePending_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);
            bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.STRIPE);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() ->
                bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("raison"))
            ).isInstanceOf(YadonyBusinessException.class)
             .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                     .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("bid PAYMENT_ESCROWED rejeté par le voyageur → event rematchEligible=true")
        void rejectBid_onEscrowedBid_publishesRematchEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PAYMENT_ESCROWED par défaut

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Non compatible"));

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isTrue();
            assertThat(captor.getValue().getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
        }

        @Test
        @DisplayName("bid cash PENDING (off-platform) rejeté → event rematchEligible=false")
        void rejectBid_onOffPlatformPendingBid_publishesNonEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);
            bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.WAVE);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            bidService.rejectBid(BID_ID, TRAVELER_UID, new BidRejectRequest("Non compatible"));

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isFalse();
        }
    }

    // ─── rejectBidBySystem ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("rejectBidBySystem()")
    class RejectBidBySystemTests {

        @Test
        @DisplayName("travelerId propriétaire → rejeté avec raison, sans résolution firebaseUid")
        void rejectBidBySystem_rejectsWithReason() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            BidResponse response = bidService.rejectBidBySystem(
                    BID_ID, TRAVELER_ID, "Poids trop important pour la capacité restante.");

            assertThat(response.status()).isEqualTo(BidStatus.REJECTED.name());
            assertThat(bid.getRejectionReason()).isEqualTo("Poids trop important pour la capacité restante.");
        }

        @Test
        @DisplayName("travelerId ne possède pas l'annonce → IllegalStateException")
        void rejectBidBySystem_throwsWhenAnnouncementNotOwnedByTravelerId() {
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            UUID otherTravelerId = UUID.randomUUID();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.rejectBidBySystem(BID_ID, otherTravelerId, "raison"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("travelerId propriétaire mais introuvable en base → IllegalStateException")
        void rejectBidBySystem_throwsWhenTravelerNotFound() {
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bidService.rejectBidBySystem(BID_ID, TRAVELER_ID, "raison"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Traveler not found: " + TRAVELER_ID);
        }

        @Test
        @DisplayName("rejet automatisé (système) sur bid PAYMENT_ESCROWED → event rematchEligible=false")
        void rejectBidBySystem_publishesNonEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PAYMENT_ESCROWED par défaut

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            bidService.rejectBidBySystem(BID_ID, TRAVELER_ID, "Poids trop important pour la capacité restante.");

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isFalse();
        }
    }

    // ─── cancelBid ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelBid()")
    class CancelBidTests {

        @Test
        @DisplayName("bid PENDING annulé → kg NON restitués (only ACCEPTED triggers restore)")
        void cancelBid_pendingBid_cancelsWithoutRestoringKg() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement()));

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("cancelBid publie BidRejectedEvent avec reason CANCELLED_BY_SENDER")
        void cancelBid_publishes_BidRejectedEvent_with_CANCELLED_BY_SENDER_reason() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement()));

            bidService.cancelBid(BID_ID, SENDER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(bid.getId());
            assertThat(captor.getValue().getSenderId()).isEqualTo(bid.getSenderId());
            assertThat(captor.getValue().getReason()).isEqualTo("CANCELLED_BY_SENDER");
        }

        @Test
        @DisplayName("bid ACCEPTED annulé → kg restitués à l'annonce")
        void cancelBid_acceptedBid_restoresKg() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(25)); // 20+5
            verify(announcementRepository).save(announcement);
        }

        @Test
        @DisplayName("bid ACCEPTED annulé sur annonce FULL → annonce repasse ACTIVE")
        void cancelBid_acceptedBidOnFullAnnouncement_reactivates() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setAvailableKg(BigDecimal.ZERO);
            announcement.setStatus(AnnouncementStatus.FULL);
            BidEntity bid = buildBid(); // weightKg = 5
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.cancelBid(BID_ID, SENDER_UID);

            assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
        }

        @Test
        @DisplayName("tiers (ni expéditeur ni voyageur) → 403 FORBIDDEN")
        void cancelBid_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID()); // ni SENDER_ID ni TRAVELER_ID
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(otherUser));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement())); // travelerId = TRAVELER_ID

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("voyageur annule un bid ACCEPTED → autorisé, kg restitués, reason CANCELLED_BY_TRAVELER")
        void cancelBid_traveler_canCancelAcceptedBid() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));

            bidService.cancelBid(BID_ID, TRAVELER_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(25)); // 20+5
            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getSenderId()).isEqualTo(SENDER_ID);
            assertThat(captor.getValue().getReason()).isEqualTo("CANCELLED_BY_TRAVELER");
        }

        @Test
        @DisplayName("voyageur ne peut pas annuler un bid IN_TRANSIT (verrou D3) → 409 CONFLICT")
        void cancelBid_traveler_inTransit_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.IN_TRANSIT);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        /** Régression C1 (financier) : un colis ARRIVED est arrivé à destination, le
         *  voyageur a déjà fourni la prestation. Le laisser annulable rouvrait la fenêtre
         *  d'annulation remboursée après l'arrivée — l'expéditeur récupérait son argent
         *  ET pouvait retirer son colis. Le voyageur non plus ne peut plus se désister. */
        @Test
        @DisplayName("régression C1 — expéditeur ne peut pas annuler un bid ARRIVED → 409 CONFLICT")
        void cancelBid_sender_arrived_throwsConflict() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ARRIVED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
            verify(bidRepository, never()).save(any());
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ARRIVED);
        }

        @Test
        @DisplayName("régression C1 — voyageur ne peut pas annuler un bid ARRIVED → 409 CONFLICT")
        void cancelBid_traveler_arrived_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ARRIVED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("bid déjà annulé → 409 CONFLICT")
        void cancelBid_alreadyCancelled_throwsConflict() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("bid REJECTED → 409 CONFLICT")
        void cancelBid_alreadyRejected_throwsConflict() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> bidService.cancelBid(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("voyageur annule un bid ACCEPTED → event rematchEligible=true")
        void cancelBid_byTraveler_onAcceptedBid_publishesRematchEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));

            bidService.cancelBid(BID_ID, TRAVELER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isTrue();
            assertThat(captor.getValue().getAnnouncementId()).isEqualTo(announcement.getId());
        }

        @Test
        @DisplayName("voyageur annule un bid PAYMENT_ESCROWED → event rematchEligible=true")
        void cancelBid_byTraveler_onEscrowedBid_publishesRematchEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PAYMENT_ESCROWED par défaut

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));

            bidService.cancelBid(BID_ID, TRAVELER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isTrue();
        }

        @Test
        @DisplayName("expéditeur annule un bid ACCEPTED → event rematchEligible=false")
        void cancelBid_bySender_publishesNonEligibleEvent() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.cancelBid(BID_ID, SENDER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isFalse();
        }

        @Test
        @DisplayName("voyageur annule un bid PENDING → event rematchEligible=false")
        void cancelBid_byTraveler_onPendingBid_publishesNonEligibleEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));

            bidService.cancelBid(BID_ID, TRAVELER_UID);

            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().isRematchEligible()).isFalse();
        }
    }

    // ─── cancelBidForDeletedSender ─────────────────────────────────────────────
    // Système : l'expéditeur de ce bid a supprimé son compte (AccountFinalizationService).
    // Réutilise le même cœur que cancelBid (refund via BidRejectedEvent, restitution kg),
    // sans firebaseUid/ownership — l'acteur système n'a pas de session live.

    @Nested
    @DisplayName("cancelBidForDeletedSender()")
    class CancelBidForDeletedSenderTests {

        @Test
        @DisplayName("bid PENDING → CANCELLED + BidRejectedEvent, rematchEligible=false")
        void pendingBid_cancelsAndPublishesNonEligibleEvent() {
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(
                    Optional.of(buildAnnouncement()));

            bidService.cancelBidForDeletedSender(BID_ID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
            assertThat(captor.getValue().getSenderId()).isEqualTo(SENDER_ID);
            assertThat(captor.getValue().isRematchEligible()).isFalse();
        }

        @Test
        @DisplayName("bid ACCEPTED → kg restitués à l'annonce")
        void acceptedBid_restoresKg() {
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(bidRepository.save(any())).thenReturn(bid);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.cancelBidForDeletedSender(BID_ID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.valueOf(25)); // 20+5
            verify(announcementRepository).save(announcement);
        }

        @Test
        @DisplayName("bid déjà CANCELLED → idempotent, no-op")
        void alreadyCancelled_isNoOp() {
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            bidService.cancelBidForDeletedSender(BID_ID);

            verify(bidRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("bid REJECTED → idempotent, no-op")
        void rejectedBid_isNoOp() {
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            bidService.cancelBidForDeletedSender(BID_ID);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("bid COMPLETED → idempotent, no-op")
        void completedBid_isNoOp() {
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.COMPLETED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            bidService.cancelBidForDeletedSender(BID_ID);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("bid introuvable → no-op silencieux (pas d'exception)")
        void unknownBid_isNoOp() {
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

            assertThatCode(() -> bidService.cancelBidForDeletedSender(BID_ID))
                    .doesNotThrowAnyException();

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ─── hideBid ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hideBidForSender()")
    class HideBidTests {

        @Test
        @DisplayName("propriétaire → bid masqué")
        void hideBidForSender_owner_hides() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.hideBidForSender(BID_ID, SENDER_UID);

            assertThat(bid.isDeletedBySender()).isTrue();
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void hideBidForSender_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> bidService.hideBidForSender(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ─── confirmPresence ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirmPresence()")
    class ConfirmPresenceTests {

        @Test
        @DisplayName("bid accepté → voyageurConfirmed = true")
        void confirmPresence_acceptedBid_setsConfirmed() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            bidService.confirmPresence(BID_ID, TRAVELER_UID);

            assertThat(bid.isVoyageurConfirmed()).isTrue();
        }

        @Test
        @DisplayName("bid non ACCEPTED → 409 CONFLICT")
        void confirmPresence_bidNotAccepted_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PAYMENT_ESCROWED (not ACCEPTED)

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.confirmPresence(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("bid-not-accepted"));
        }
    }

    // ─── getMyBids ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMyBids → retourne les bids non masqués de l'expéditeur")
    void getMyBids_returnsVisibleBids() {
        UserEntity sender = buildSender();
        BidEntity visibleBid = buildBid();
        BidEntity hiddenBid = buildBid();
        setId(hiddenBid, UUID.randomUUID());
        hiddenBid.setDeletedBySender(true);

        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(SENDER_ID)).thenReturn(List.of(visibleBid, hiddenBid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(buildAnnouncement()));

        List<BidResponse> result = bidService.getMyBids(SENDER_UID);

        assertThat(result).hasSize(1);
    }

    // ─── getBidById ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expéditeur appelle getBidById → confirmationCode visible")
    void getBidById_callerIsSender_showsConfirmationCode() {
        UserEntity sender = buildSender();
        BidEntity bid = buildBid();
        bid.setConfirmationCode("654321");
        AnnouncementEntity announcement = buildAnnouncement();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        BidResponse result = bidService.getBidById(BID_ID, SENDER_UID);

        assertThat(result.confirmationCode()).isEqualTo("654321");
    }

    @Test
    @DisplayName("getBidById expose la devise du bid, pas toujours EUR")
    void getBidById_exposesBidCurrency() {
        UserEntity sender = buildSender();
        BidEntity bid = buildBid();
        bid.setCurrency("CAD");
        AnnouncementEntity announcement = buildAnnouncement();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        BidResponse result = bidService.getBidById(BID_ID, SENDER_UID);

        assertThat(result.currency()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("expéditeur : net masqué — pricePerKg + totalNetAmountEur null, brut exposé")
    void getBidById_callerIsSender_hidesNet() {
        UserEntity sender = buildSender();
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(commissionRateResolver.resolve(any(), any())).thenReturn(new BigDecimal("0.12"));

        BidResponse result = bidService.getBidById(BID_ID, SENDER_UID);

        // L'expéditeur ne reçoit jamais le net : tarif/kg net + total net masqués.
        assertThat(result.pricePerKg()).isNull();
        assertThat(result.totalNetAmountEur()).isNull();
        // Il reçoit le brut (net + commission) : tarif/kg brut + total brut.
        assertThat(result.pricePerKgSenderEur()).isNotNull();
        assertThat(result.totalSenderAmountEur()).isNotNull();
    }

    /**
     * En espèces l'expéditeur remet le brut (net + commission) en main propre au
     * voyageur, et Yadony prélève ensuite la commission sur le solde de celui-ci :
     * c'est donc bien l'expéditeur qui la paie, indirectement, et le voyageur
     * conserve le net. Annoncer le net à l'expéditeur lui sous-estimait la somme à
     * remettre, à l'endroit le plus sensible du parcours.
     */
    @Test
    @DisplayName("espèces : l'expéditeur voit le brut, comme en carte — jamais le net")
    void getBidById_cashBid_senderStillSeesGross() {
        UserEntity sender = buildSender();
        BidEntity bid = buildBid();
        bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
        AnnouncementEntity announcement = buildAnnouncement();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(commissionRateResolver.resolve(any(), any())).thenReturn(new BigDecimal("0.12"));

        BidResponse result = bidService.getBidById(BID_ID, SENDER_UID);

        // 5 kg × 5 € = 25 € net → 28 € brut à 12 %.
        assertThat(result.totalSenderAmountEur()).isEqualByComparingTo("28.00");
        assertThat(result.pricePerKgSenderEur()).isEqualByComparingTo("5.60");
        assertThat(result.totalNetAmountEur()).isNull();
        assertThat(result.pricePerKg()).isNull();
    }

    /**
     * Le voyageur, lui, doit voir exactement le net qu'il conservera : la commission
     * lui sera prélevée sur son solde.
     */
    @Test
    @DisplayName("espèces : le voyageur voit son net, pas le brut remis par l'expéditeur")
    void getBidById_cashBid_travelerSeesNet() {
        UserEntity traveler = buildTraveler();
        BidEntity bid = buildBid();
        bid.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
        AnnouncementEntity announcement = buildAnnouncement();
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(buildSender()));
        when(commissionRateResolver.resolve(any(), any())).thenReturn(new BigDecimal("0.12"));

        BidResponse result = bidService.getBidById(BID_ID, TRAVELER_UID);

        assertThat(result.totalNetAmountEur()).isEqualByComparingTo("25.00");
        assertThat(result.pricePerKg()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("tiers → 403 FORBIDDEN")
    void getBidById_foreignerForbidden() {
        UserEntity stranger = new UserEntity();
        setId(stranger, UUID.randomUUID());
        stranger.setFirebaseUid(SENDER_UID);
        BidEntity bid = buildBid();
        AnnouncementEntity announcement = buildAnnouncement();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(stranger));

        assertThatThrownBy(() -> bidService.getBidById(BID_ID, SENDER_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─── getBidsForAnnouncement ────────────────────────────────────────────────

    @Test
    @DisplayName("propriétaire de l'annonce → retourne les bids non masqués")
    void getBidsForAnnouncement_owner_returnsBids() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity announcement = buildAnnouncement();
        BidEntity visible = buildBid();
        BidEntity hidden = buildBid();
        setId(hidden, UUID.randomUUID());
        hidden.setDeletedByTraveler(true);

        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(bidRepository.findByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(List.of(visible, hidden));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        List<BidResponse> result = bidService.getBidsForAnnouncement(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getBidsForAnnouncement — non propriétaire → 403 FORBIDDEN")
    void getBidsForAnnouncement_notOwner_throwsForbidden() {
        UserEntity otherTraveler = new UserEntity();
        setId(otherTraveler, UUID.randomUUID()); // different UUID, not TRAVELER_ID
        AnnouncementEntity announcement = buildAnnouncement(); // travelerId = TRAVELER_ID

        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(otherTraveler));

        assertThatThrownBy(() -> bidService.getBidsForAnnouncement(ANNOUNCEMENT_ID, TRAVELER_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─── rejectBid null request ────────────────────────────────────────────────

    @Test
    @DisplayName("rejectBid avec request null → pas de raison de rejet")
    void rejectBid_nullRequest_noRejectionReason() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity announcement = buildAnnouncement();
        BidEntity bid = buildBid();

        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(bidRepository.save(any())).thenReturn(bid);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        bidService.rejectBid(BID_ID, TRAVELER_UID, null);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(bid.getRejectionReason()).isNull();
    }

    // ─── hideBidForTraveler ────────────────────────────────────────────────────

    @Nested
    @DisplayName("hideBidForTraveler()")
    class HideBidTravelerTests {

        @Test
        @DisplayName("bid REJECTED → masqué pour le voyageur")
        void hideBidForTraveler_rejectedBid_dismisses() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.hideBidForTraveler(BID_ID, TRAVELER_UID);

            assertThat(bid.isDeletedByTraveler()).isTrue();
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void hideBidForTraveler_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            otherUser.setFirebaseUid(TRAVELER_UID);
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> bidService.hideBidForTraveler(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("bid PENDING (non REJECTED/CANCELLED) → 409 CONFLICT")
        void hideBidForTraveler_activeBid_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid(); // status = PENDING

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> bidService.hideBidForTraveler(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-bid-status"));
        }
    }

    @Nested
    @DisplayName("toResponse() — champs cancellation")
    class ToResponseCancellationTests {

        @Test
        @DisplayName("cancellation présente → noShowStatus et contestationDeadline renseignés")
        void toResponse_withCancellation_populatesNoShowFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            OffsetDateTime deadline = OffsetDateTime.now().plusHours(2);
            CancellationEntity cancellation = new CancellationEntity();
            cancellation.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            cancellation.setContestationDeadline(deadline);

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.cancellationNoShowStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(resp.contestationDeadline()).isEqualTo(deadline);
        }

        @Test
        @DisplayName("pas de cancellation → champs null")
        void toResponse_withoutCancellation_fieldsAreNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of());

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.cancellationNoShowStatus()).isNull();
            assertThat(resp.contestationDeadline()).isNull();
        }

        @Test
        @DisplayName("delivery cancellation présente (signalée par le voyageur) → champs delivery no-show renseignés")
        void getBidResponse_exposesDeliveryNoShowStatusWhenPresent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.IN_TRANSIT);

            CancellationEntity delivery = new CancellationEntity();
            delivery.setScope(CancellationScope.DELIVERY);
            delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            OffsetDateTime deadline = OffsetDateTime.now().plusHours(24);
            delivery.setContestationDeadline(deadline);
            delivery.setReason("RECIPIENT_NO_SHOW"); // signalé par le voyageur

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID))
                    .thenReturn(java.util.List.of(delivery));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.deliveryNoShowStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(resp.deliveryNoShowContestationDeadline()).isEqualTo(deadline);
            assertThat(resp.deliveryNoShowReportedByTraveler()).isTrue();
        }

        @Test
        @DisplayName("pas de delivery cancellation → champs delivery no-show null")
        void getBidResponse_deliveryNoShowStatusNullWhenNoDeliveryCancellation() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID))
                    .thenReturn(java.util.List.of());

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.deliveryNoShowStatus()).isNull();
            assertThat(resp.deliveryNoShowContestationDeadline()).isNull();
            assertThat(resp.deliveryNoShowReportedByTraveler()).isNull();
        }

        @Test
        @DisplayName("trajet annulé (announcement CANCELLED) → tripCancellationId et rematchStatus renseignés")
        void toResponse_tripCancelled_populatesTripCancellationFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.CANCELLED);
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            UUID cancellationId = UUID.randomUUID();
            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setScope(CancellationScope.HANDOVER);
            cancellation.setReason("Vol annulé"); // texte libre saisi par le voyageur
            cancellation.setRematchStatus("SUGGESTED");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isEqualTo(cancellationId);
            assertThat(resp.tripCancellationRematchStatus()).isEqualTo("SUGGESTED");
        }

        @Test
        @DisplayName("bid actif (announcement ACTIVE) avec cancellation HANDOVER (ex: après remise) → champs trip cancellation null")
        void toResponse_activeAnnouncementWithNonTripCancellation_tripCancellationFieldsAreNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(); // status = ACTIVE
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            // Cancellation HANDOVER issue d'un flux qui n'annule pas le trajet entier
            // (ex: annulation après remise ou no-show) — announcement reste ACTIVE.
            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, UUID.randomUUID());
            cancellation.setScope(CancellationScope.HANDOVER);
            cancellation.setReason("SENDER_CANCEL_AFTER_HANDOVER");
            cancellation.setRematchStatus("NONE");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isNull();
            assertThat(resp.tripCancellationRematchStatus()).isNull();
        }

        @Test
        @DisplayName("régression LEGACY : no-show préexistant PUIS bulk CANCELLED sans CancellationEntity "
                + "(ancien mécanisme de suppression de compte, avant fix) → champs trip cancellation null")
        void toResponse_accountDeletionBulkCancelAfterPreexistingNoShow_tripCancellationFieldsAreNull() {
            UserEntity traveler = buildTraveler();
            // Scénario du reviewer, désormais HISTORIQUE : (1) reportSenderNoShow crée une
            // CancellationEntity HANDOVER (reason=SENDER_NO_SHOW) sur un bid ACCEPTED, l'annonce
            // reste ACTIVE à ce moment-là ; (2) l'ANCIEN AccountDeletionListener (avant le fix
            // account-deletion) basculait l'annonce (et le bid) en CANCELLED via un bulk UPDATE SQL
            // sans créer de nouvelle CancellationEntity. Ce test simule directement l'état DB qui en
            // résultait (toujours possible sur des lignes déjà annulées avant le fix) : la
            // cancellation no-show préexistante ne doit PAS être exposée comme "trajet annulé".
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setStatus(AnnouncementStatus.CANCELLED);
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            CancellationEntity noShowCancellation = new CancellationEntity();
            setId(noShowCancellation, UUID.randomUUID());
            noShowCancellation.setScope(CancellationScope.HANDOVER);
            noShowCancellation.setReason("SENDER_NO_SHOW");
            noShowCancellation.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            noShowCancellation.setRematchStatus("NONE");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(noShowCancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isNull();
            assertThat(resp.tripCancellationRematchStatus()).isNull();
        }

        @Test
        @DisplayName("bid actif sans cancellation → champs trip cancellation null")
        void toResponse_noCancellation_tripCancellationFieldsAreNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of());

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isNull();
            assertThat(resp.tripCancellationRematchStatus()).isNull();
        }

        @Test
        @DisplayName("bid CANCELLED par le voyageur (reason=BID_CANCELLED_BY_TRAVELER) sur annonce "
                + "ACTIVE → tripCancellationId et rematchStatus renseignés (rematch bid-only)")
        void toResponse_bidCancelledByTraveler_populatesRematchCancellationFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(); // status = ACTIVE
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            UUID cancellationId = UUID.randomUUID();
            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setScope(CancellationScope.HANDOVER);
            cancellation.setReason("BID_CANCELLED_BY_TRAVELER");
            cancellation.setRematchStatus("SUGGESTED");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isEqualTo(cancellationId);
            assertThat(resp.tripCancellationRematchStatus()).isEqualTo("SUGGESTED");
        }

        @Test
        @DisplayName("bid REJECTED après paiement (reason=BID_REJECTED_AFTER_PAYMENT) sur annonce "
                + "ACTIVE → tripCancellationId et rematchStatus renseignés (rematch bid-only)")
        void toResponse_bidRejectedAfterPayment_populatesRematchCancellationFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(); // status = ACTIVE
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.REJECTED);

            UUID cancellationId = UUID.randomUUID();
            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, cancellationId);
            cancellation.setScope(CancellationScope.HANDOVER);
            cancellation.setReason("BID_REJECTED_AFTER_PAYMENT");
            cancellation.setRematchStatus("SUGGESTED");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isEqualTo(cancellationId);
            assertThat(resp.tripCancellationRematchStatus()).isEqualTo("SUGGESTED");
        }

        @Test
        @DisplayName("bid CANCELLED avec reason SENDER_NO_SHOW sur annonce ACTIVE → champs null "
                + "(non-régression : ne doit PAS être traité comme rematch bid-only)")
        void toResponse_senderNoShowOnActiveAnnouncement_tripCancellationFieldsAreNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(); // status = ACTIVE
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.CANCELLED);

            CancellationEntity cancellation = new CancellationEntity();
            setId(cancellation, UUID.randomUUID());
            cancellation.setScope(CancellationScope.HANDOVER);
            cancellation.setReason("SENDER_NO_SHOW");
            cancellation.setRematchStatus("NONE");

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(cancellationRepository.findAllByBidId(BID_ID)).thenReturn(java.util.List.of(cancellation));

            BidResponse resp = bidService.getBidById(BID_ID, TRAVELER_UID);

            assertThat(resp.tripCancellationId()).isNull();
            assertThat(resp.tripCancellationRematchStatus()).isNull();
        }

        @Test
        @DisplayName("expéditeur avec prénom ET nom → nom complet dans la réponse")
        void toResponse_senderWithBothNames_abbreviatesLastName() {
            UserEntity sender = buildSender();
            sender.setFirstName("Alice");
            sender.setLastName("Dupont");
            BidEntity bid = buildBid();

            // announcementRepository non stubbed → Optional.empty() → announcement=null → traveler=null
            BidResponse resp = bidService.toResponse(bid, sender);

            // Ce DTO part vers le voyageur, pas vers le back-office : patronyme abrégé.
            assertThat(resp.senderName()).isEqualTo("Alice D.");
        }

        /**
         * Régression signalée en recette : l'écran « Demandes › À traiter » affichait
         * « Expéditeur » pour toute demande dont l'expéditeur n'avait pas renseigné de
         * prénom. buildSenderName rendait null, et le client comblait avec le rôle, si bien
         * que deux demandes de deux personnes différentes portaient le même nom.
         */
        @Test
        @DisplayName("expéditeur sans prénom → username, jamais null")
        void toResponse_senderWithoutFirstName_fallsBackToUsername() {
            UserEntity sender = buildSender();
            sender.setFirstName(null);
            sender.setLastName(null);
            BidEntity bid = buildBid();

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.senderName()).isEqualTo("user1784907068");
        }

        /** Chaîne vide et null doivent se comporter pareil : la base contient les deux. */
        @Test
        @DisplayName("expéditeur au prénom vide → username")
        void toResponse_senderWithBlankFirstName_fallsBackToUsername() {
            UserEntity sender = buildSender();
            sender.setFirstName("   ");
            sender.setLastName("");
            BidEntity bid = buildBid();

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.senderName()).isEqualTo("user1784907068");
        }

        @Test
        @DisplayName("senderAvatarUrl mappé depuis UserEntity")
        void toResponse_senderAvatarUrl_isMapped() {
            UserEntity sender = buildSender();
            sender.setAvatarUrl("https://cdn.example.com/sender.jpg");
            BidEntity bid = buildBid();

            // No announcement → traveler=null → travelerAvatarUrl=null
            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.senderAvatarUrl()).isEqualTo("https://cdn.example.com/sender.jpg");
            assertThat(resp.travelerAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("travelerAvatarUrl mappé depuis UserEntity du voyageur")
        void toResponse_travelerAvatarUrl_isMapped() {
            UserEntity sender = buildSender();
            UserEntity traveler = buildTraveler();
            traveler.setAvatarUrl("https://cdn.example.com/traveler.jpg");
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.travelerAvatarUrl()).isEqualTo("https://cdn.example.com/traveler.jpg");
        }

        @Test
        @DisplayName("arrivalInstructions dénormalisé depuis l'announcement liée")
        void toResponse_exposesArrivalInstructionsFromAnnouncement() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setArrivalInstructions("Devant la gare, portail nord");
            BidEntity bid = buildBid();

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.arrivalInstructions()).isEqualTo("Devant la gare, portail nord");
        }

        /** Régression I4 : le point de retrait était servi quel que soit le statut du bid.
         *  Un expéditeur dont l'offre a été refusée / annulée / expirée n'a plus de raison
         *  légitime de connaître l'adresse d'arrivée du voyageur. */
        @Test
        @DisplayName("régression I4 — arrivalInstructions masqué pour un bid REJECTED/CANCELLED/EXPIRED/PARCEL_REFUSED")
        void toResponse_hidesArrivalInstructionsForDeadBidStatuses() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setArrivalInstructions("Devant la gare, portail nord");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            for (BidStatus dead : List.of(BidStatus.REJECTED, BidStatus.CANCELLED,
                    BidStatus.PARCEL_REFUSED, BidStatus.EXPIRED)) {
                BidEntity bid = buildBid();
                bid.setStatus(dead);

                BidResponse resp = bidService.toResponse(bid, sender);

                assertThat(resp.arrivalInstructions())
                        .as("arrivalInstructions doit être masqué pour un bid %s", dead)
                        .isNull();
            }
        }

        /** Régression I4, versant positif : un bid ARRIVED (celui qui a justement besoin
         *  du point de retrait) continue de recevoir les instructions. */
        @Test
        @DisplayName("régression I4 — arrivalInstructions servi pour un bid ARRIVED")
        void toResponse_exposesArrivalInstructionsForArrivedBid() {
            UserEntity sender = buildSender();
            AnnouncementEntity announcement = buildAnnouncement();
            announcement.setArrivalInstructions("Devant la gare, portail nord");
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ARRIVED);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.arrivalInstructions()).isEqualTo("Devant la gare, portail nord");
        }

        /** Régression I1 : ARRIVED manquait dans PHONE_VISIBLE_STATUSES côté BidService
         *  alors que ConversationService l'avait déjà. Le numéro disparaissait donc en
         *  pleine coordination de retrait, exactement quand on en a besoin. */
        @Test
        @DisplayName("régression I1 — téléphone visible sur un bid ARRIVED")
        void toResponse_phoneVisibleForArrivedBid() {
            UserEntity sender = buildSender();
            sender.setHidePhoneNumber(false);
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ARRIVED);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.senderPhoneAvailable()).isTrue();
            assertThat(resp.recipientPhone()).isEqualTo("+221701234567");
        }

        @Test
        @DisplayName("sender null → senderAvatarUrl null")
        void toResponse_senderNull_avatarUrlNull() {
            BidEntity bid = buildBid();

            BidResponse resp = bidService.toResponse(bid, null);

            assertThat(resp.senderAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("recipientPhone masqué tant que le bid n'est pas accepté (PENDING → null)")
        void toResponse_recipientPhoneHiddenBeforeAcceptance() {
            // PII destinataire (tiers) : ne doit pas fuiter sur une offre PENDING,
            // sinon un voyageur moissonne des numéros via des offres qu'il refuse.
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.recipientPhone()).isNull();
        }

        @Test
        @DisplayName("recipientPhone révélé une fois le bid accepté")
        void toResponse_recipientPhoneRevealedWhenAccepted() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            BidResponse resp = bidService.toResponse(bid, sender);

            assertThat(resp.recipientPhone()).isEqualTo("+221701234567");
        }
    }

    @Nested
    @DisplayName("refuseParcel()")
    class RefuseParcelTests {

        @Test
        @DisplayName("colis refusé → status PARCEL_REFUSED + event + audit")
        void refuseParcel_valid_setsStatusAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.ACCEPTED);

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(bidRepository.save(any())).thenReturn(bid);
            when(userRepository.save(any())).thenReturn(sender);

            BidResponse resp = bidService.refuseParcel(BID_ID, TRAVELER_UID, "Colis endommagé", null);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.PARCEL_REFUSED);
            assertThat(bid.getRefusalReason()).isEqualTo("Colis endommagé");
            assertThat(resp).isNotNull();
            verify(eventPublisher).publishEvent(any(com.yadony.api.matching.events.ParcelRefusedEvent.class));
        }

        @Test
        @DisplayName("status invalide → 422")
        void refuseParcel_invalidStatus_throwsUnprocessable() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.PENDING);

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

            assertThatThrownBy(() -> bidService.refuseParcel(BID_ID, TRAVELER_UID, "raison", null))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("avec photo URL → photo stockée dans bid")
        void refuseParcel_withPhotoUrl_storesPhotoUrl() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement();
            BidEntity bid = buildBid();
            bid.setStatus(BidStatus.HANDED_OVER);

            when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());
            when(bidRepository.save(any())).thenReturn(bid);

            bidService.refuseParcel(BID_ID, TRAVELER_UID, "raison", "https://photo.jpg");

            assertThat(bid.getRefusalPhotoUrl()).isEqualTo("https://photo.jpg");
        }
    }

    @Nested
    @DisplayName("entités introuvables (orElseThrow lambdas)")
    class NotFoundTests {

        @Test
        @DisplayName("acceptBid — bid introuvable → 404")
        void acceptBid_bidNotFound_throwsNotFound() {
            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("acceptBid — annonce introuvable → 404")
        void acceptBid_announcementNotFound_throwsNotFound() {
            BidEntity bid = buildBid();
            when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bidService.acceptBid(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("getBidById — bid introuvable → 404")
        void getBidById_bidNotFound_throwsNotFound() {
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bidService.getBidById(BID_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("getBidsForAnnouncement — annonce introuvable → 404")
        void getBidsForAnnouncement_announcementNotFound_throwsNotFound() {
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bidService.getBidsForAnnouncement(ANNOUNCEMENT_ID, TRAVELER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("createBid — utilisateur introuvable → 404")
        void createBid_userNotFound_throwsNotFound() {
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> bidService.createBid(
                    ANNOUNCEMENT_ID, SENDER_UID, buildRequest(BigDecimal.valueOf(5)), httpRequest))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("assertSenderOwnsBid()")
    class AssertSenderOwnsBidTests {

        @Test
        @DisplayName("expéditeur propriétaire → pas d'exception")
        void assertSenderOwnsBid_ownerMatches_noException() {
            UserEntity sender = buildSender();
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));

            bidService.assertSenderOwnsBid(BID_ID, SENDER_UID);
        }

        @Test
        @DisplayName("autre utilisateur → 403")
        void assertSenderOwnsBid_notOwner_throwsForbidden() {
            UserEntity other = new UserEntity();
            setId(other, UUID.randomUUID());
            other.setFirebaseUid(SENDER_UID);
            BidEntity bid = buildBid();

            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> bidService.assertSenderOwnsBid(BID_ID, SENDER_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Test
    @DisplayName("getTravelerBids() → chaque colis porte l'EXPÉDITEUR réel, pas le voyageur connecté")
    void getTravelerBids_mapsRealSender_notTraveler() {
        UserEntity traveler = buildTraveler();
        traveler.setFirstName("drissa");
        UserEntity sender = buildSender();
        sender.setFirstName("abou");
        BidEntity bid = buildBid(); // senderId = SENDER_ID

        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        when(bidRepository.findByTravelerIdFiltered(eq(TRAVELER_ID), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(bid)));
        when(userRepository.findAllById(any())).thenReturn(List.of(sender));

        Page<BidResponse> page = bidService.getTravelerBids(TRAVELER_UID, null, null, null, 0, 20);

        BidResponse row = page.getContent().get(0);
        assertThat(row.senderId()).isEqualTo(SENDER_ID);
        // Avant le fix, toResponse recevait `traveler` → senderName aurait été "drissa".
        assertThat(row.senderName()).isEqualTo("abou");
    }
}
