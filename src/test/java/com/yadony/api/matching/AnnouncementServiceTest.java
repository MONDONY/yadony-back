package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.events.AnnouncementDeletedEvent;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.assertj.core.api.ThrowableAssert;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService — tests unitaires")
class AnnouncementServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PriceGridService priceGridService;
    @Mock private com.yadony.api.country.FlagService flagService;
    @Mock private com.yadony.api.common.StorageService storageService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        org.mockito.Mockito.lenient()
                .when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }
    @Mock private com.yadony.api.requests.repository.PackageRequestRepository packageRequestRepository;
    @Mock private com.yadony.api.requests.repository.NegotiationThreadRepository negotiationThreadRepository;
    @Mock private com.yadony.api.notifications.NotificationDispatcher notificationDispatcher;

    private AnnouncementService announcementService;

    @org.junit.jupiter.api.BeforeEach
    void initService() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        // Pass-through: return the key/URL as-is so avatar URL assertions remain valid
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        // Real mapper wired to the same mocks so SearchTests assertions remain valid
        AnnouncementSearchMapper realMapper = new AnnouncementSearchMapper(
                userRepository, bidRepository, priceGridService, storageService, config);
        announcementService = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                auditService, eventPublisher, config, priceGridService, flagService,
                storageService, favoriteRepository, activeCurrencyResolver, realMapper, packageRequestRepository,
                negotiationThreadRepository, notificationDispatcher);
    }

    private static final String FIREBASE_UID = "uid-traveler-001";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

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

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(FIREBASE_UID);
        u.getRoles().add(Role.TRAVELER);
        setId(u, USER_ID);
        return u;
    }

    private AnnouncementEntity buildAnnouncement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        setId(a, ANNOUNCEMENT_ID);
        return a;
    }

    private AnnouncementRequest buildRequest() {
        return buildRequest(TransportMode.PLANE);
    }

    private AnnouncementRequest buildRequest(TransportMode mode) {
        LocalDate departure = LocalDate.now().plusDays(10);
        return new AnnouncementRequest(
                "Paris", "Dakar",
                departure,
                LocalTime.of(10, 0), LocalTime.of(22, 0),
                new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                new AddressDto("Aéroport LSS", 14.739, -17.490),
                BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                mode,
                null, null, null, null, null, null,
                null, null,
                departure.atTime(9, 0),
                null,
                null
        );
    }

    private AnnouncementRequest draftRequest() {
        LocalDate departure = LocalDate.now().plusDays(10);
        return new AnnouncementRequest(
                "Paris", "Dakar",
                departure,
                LocalTime.of(10, 0), LocalTime.of(22, 0),
                new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                new AddressDto("Aéroport LSS", 14.739, -17.490),
                BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                TransportMode.PLANE,
                null, null, null, null, null, null,
                null, null,
                departure.atTime(9, 0),
                true,
                null
        );
    }

    /**
     * Variante de {@link #buildRequest()} paramétrée sur les modes de paiement déclarés
     * (Task 2 — gate Stripe conditionnel). {@code methods == null} exerce le défaut de
     * {@code resolvePaymentMethods}.
     */
    private AnnouncementRequest requestWithPaymentMethods(java.util.Set<PaymentMethod> methods) {
        LocalDate departure = LocalDate.now().plusDays(10);
        return new AnnouncementRequest(
                "Paris", "Dakar",
                departure,
                LocalTime.of(10, 0), LocalTime.of(22, 0),
                new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                new AddressDto("Aéroport LSS", 14.739, -17.490),
                BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                TransportMode.PLANE,
                null, null, null, methods, null, null,
                null, null,
                departure.atTime(9, 0),
                null,
                null
        );
    }

    private UserEntity buildTravelerWithCommissionMethod() {
        UserEntity u = buildTraveler();
        u.setCommissionPaymentMethodId("pm_test");
        return u;
    }

    private UserEntity proUser() {
        UserEntity u = buildTraveler();
        u.setProAccount(true);
        return u;
    }

    private UserEntity standardUser() {
        UserEntity u = buildTraveler();
        u.setProAccount(false);
        return u;
    }

    private UserEntity verifiedProUser() {
        UserEntity u = proUser();
        u.setKycStatus(KycStatus.VERIFIED);
        return u;
    }

    private AnnouncementEntity draftEntityOwnedBy(UserEntity traveler) {
        AnnouncementEntity a = buildAnnouncement(traveler);
        a.setStatus(AnnouncementStatus.DRAFT);
        return a;
    }

    private static void assertYadonyError(ThrowableAssert.ThrowingCallable callable, String expectedErrorCode) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo(expectedErrorCode);
    }

    private BidEntity buildBid(BidStatus status, UUID announcementId) {
        BidEntity b = new BidEntity();
        setId(b, UUID.randomUUID());
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        return b;
    }

    // ─── createAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAnnouncement()")
    class CreateTests {

        @Test
        @DisplayName("données valides → annonce créée + audit enregistré")
        void create_validRequest_createsAndAudits() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("EUR");
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(result.departureCity()).isEqualTo("Paris");
            assertThat(result.arrivalCity()).isEqualTo("Dakar");
            assertThat(result.status()).isEqualTo("ACTIVE");
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_CREATED"), any(), any());
        }

        @Test
        @DisplayName("devise business du créateur = CAD → persistée sur l'annonce")
        void createAnnouncement_assignsCurrencyFromCreatorBusinessPrefs() {
            UserEntity traveler = buildTraveler();
            UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
            prefs.setUserId(traveler.getId());
            prefs.setCurrencyCode("CAD");
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn(prefs.getCurrencyCode());
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrency()).isEqualTo("CAD");
        }

        @Test
        @DisplayName("sans business prefs → fallback EUR à la création")
        void createAnnouncement_defaultsToEurWhenNoBusinessPrefs() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("EUR");
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
        }

        // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
        // legacy dans acceptedContentTypes/refusedTypes, doivent être persistés canoniques.
        @Test
        @DisplayName("acceptedContentTypes/refusedTypes legacy → persistés normalisés")
        void create_legacyContentTypes_areNormalizedOnWrite() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            LocalDate departure = LocalDate.now().plusDays(10);
            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", departure,
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null,
                    List.of("Hi-fi", "Téléphone"),
                    List.of("Nourriture"),
                    null, null, null, null, null,
                    departure.atTime(9, 0),
                    null,
                    null
            );

            announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(captor.getValue().getAcceptedContentTypes())
                    .containsExactly("Téléphone & électronique");
            assertThat(captor.getValue().getRefusedTypes())
                    .containsExactly("Alimentation sèche");
        }

        @Test
        @DisplayName("codes pays dans la requête → persistés sur l'entité + drapeaux résolus dans la réponse")
        void create_withCountryCodes_storesCodesAndResolvesFlags() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(flagService.getFlag("US")).thenReturn("🇺🇸"); // 🇺🇸
            when(flagService.getFlag("SN")).thenReturn("🇸🇳"); // 🇸🇳

            AnnouncementRequest req = new AnnouncementRequest(
                    "New York", "Dakar",
                    LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("JFK", 40.641, -73.778),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    "US", "SN",
                    LocalDate.now().plusDays(10).atTime(9, 0),
                    null,
                    null
            );

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, req);

            // Codes persistés sur l'entité
            assertThat(captor.getValue().getDepartureCountryCode()).isEqualTo("US");
            assertThat(captor.getValue().getArrivalCountryCode()).isEqualTo("SN");
            // Codes + drapeaux dans la réponse
            assertThat(result.departureCountryCode()).isEqualTo("US");
            assertThat(result.arrivalCountryCode()).isEqualTo("SN");
            assertThat(result.departureFlag()).isEqualTo("🇺🇸");
            assertThat(result.arrivalFlag()).isEqualTo("🇸🇳");
        }

        @Test
        @DisplayName("codes pays absents → codes et drapeaux null dans la réponse")
        void create_withoutCountryCodes_nullCodesAndFlags() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(flagService.getFlag(null)).thenReturn(null);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(result.departureCountryCode()).isNull();
            assertThat(result.arrivalCountryCode()).isNull();
            assertThat(result.departureFlag()).isNull();
            assertThat(result.arrivalFlag()).isNull();
        }

        @Test
        @DisplayName("utilisateur sans rôle TRAVELER → rôle ajouté automatiquement")
        void create_userWithoutTravelerRole_addsTravelerRole() {
            UserEntity user = new UserEntity();
            user.setFirebaseUid(FIREBASE_UID);
            setId(user, USER_ID);
            // No TRAVELER role initially
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(UserEntity.class))).thenReturn(user);
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(user.getRoles()).contains(Role.TRAVELER);
            verify(userRepository, atLeastOnce()).save(user);
        }

        @Test
        @DisplayName("STRIPE déclaré explicitement + compte Stripe non configuré → 403 stripe-onboarding-incomplete")
        void create_stripeNotOnboarded_throwsForbidden() throws Exception {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            // stripeAccountStatus defaults to NOT_CREATED — Stripe not set up
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            Field enforceField = AnnouncementService.class.getDeclaredField("enforceStripeOnboarding");
            enforceField.setAccessible(true);
            enforceField.set(announcementService, true);

            // Task 2 : le défaut de resolvePaymentMethods n'inclut plus STRIPE pour un compte
            // non onboardé — il faut le déclarer explicitement pour exercer le gate.
            AnnouncementRequest req = requestWithPaymentMethods(
                    java.util.Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH));

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("stripe-onboarding-incomplete");
                    });
        }

        @Test
        @DisplayName("Task 2 — cash-only sans Stripe → succès (D3/D4, voyageur universel)")
        void createAnnouncement_cashOnly_withoutStripe_succeeds() throws Exception {
            UserEntity traveler = buildTraveler();
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
            traveler.setKycStatus(KycStatus.VERIFIED);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            Field enforceField = AnnouncementService.class.getDeclaredField("enforceStripeOnboarding");
            enforceField.setAccessible(true);
            enforceField.set(announcementService, true);

            AnnouncementRequest req = requestWithPaymentMethods(java.util.Set.of(PaymentMethod.CASH));

            AnnouncementResponse resp = announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(resp.acceptedPaymentMethods()).containsExactly("CASH");
        }

        @Test
        @DisplayName("Task 2 — STRIPE déclaré sans compte Stripe → 403 stripe-onboarding-incomplete")
        void createAnnouncement_declaringStripe_withoutStripeAccount_throws403() throws Exception {
            UserEntity traveler = buildTraveler();
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
            traveler.setKycStatus(KycStatus.VERIFIED);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            Field enforceField = AnnouncementService.class.getDeclaredField("enforceStripeOnboarding");
            enforceField.setAccessible(true);
            enforceField.set(announcementService, true);

            AnnouncementRequest req = requestWithPaymentMethods(
                    java.util.Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH));

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "stripe-onboarding-incomplete");
        }

        @Test
        @DisplayName("Task 2 — défaut sans méthode déclarée + Stripe non onboardé → CASH seul")
        void resolvePaymentMethods_defaultsToCash_whenStripeNotOnboarded() {
            UserEntity traveler = buildTraveler();
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
            traveler.setKycStatus(KycStatus.VERIFIED);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = requestWithPaymentMethods(null);

            AnnouncementResponse resp = announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(resp.acceptedPaymentMethods()).containsExactly("CASH");
        }

        @Test
        @DisplayName("Task 2 — défaut sans méthode déclarée + Stripe onboardé → STRIPE + CASH")
        void resolvePaymentMethods_defaultsToStripeAndCash_whenOnboarded() {
            UserEntity traveler = buildTraveler();
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            traveler.setKycStatus(KycStatus.VERIFIED);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = requestWithPaymentMethods(null);

            AnnouncementResponse resp = announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(resp.acceptedPaymentMethods()).containsExactlyInAnyOrder("STRIPE", "CASH");
        }

        @Test
        @DisplayName("utilisateur introuvable → 404 NOT_FOUND")
        void create_unknownUser_throwsNotFound() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, buildRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("voyageur suspendu de publication → 403 publishing-suspended (D4)")
        void create_publishingSuspended_throwsForbidden() {
            UserEntity traveler = buildTraveler();
            traveler.setPublishingSuspended(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, buildRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("publishing-suspended");
                    });
        }

        @Test
        @DisplayName("création → totalKg = availableKg")
        void create_setsTotalKgEqualToAvailableKg() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            AnnouncementEntity saved = captor.getValue();
            assertThat(saved.getTotalKg()).isEqualByComparingTo(saved.getAvailableKg());
            assertThat(result.totalKg()).isEqualByComparingTo(result.availableKg());
        }

        @Test
        @DisplayName("audit payload contient le transportMode")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void create_writesTransportModeToAuditPayload() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest(TransportMode.TRAIN));

            ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_CREATED"), any(), captor.capture());
            assertThat(captor.getValue()).containsEntry("transportMode", "TRAIN");
        }

        @Test
        @DisplayName("CASH sans carte commission → autorisé (vérification reportée à l'acceptation du bid)")
        void create_cashWithoutCommissionMethod_isAllowed() {
            // Règle métier : la carte de commission n'est plus requise à la création d'annonce.
            // La capacité de paiement (wallet ou carte) est vérifiée à l'acceptation du bid.
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(com.yadony.api.auth.KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, java.util.Set.of(com.yadony.api.payments.cash.PaymentMethod.STRIPE, com.yadony.api.payments.cash.PaymentMethod.CASH), null, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(18, 0),
                    null,
                    null
            );

            // Ne doit PAS lever CommissionMethodMissingException
            org.assertj.core.api.Assertions.assertThatNoException()
                    .isThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req));
        }

        @Test
        @DisplayName("CASH avec carte commission enregistrée → acceptedPaymentMethods inclut CASH")
        void create_cashWithCommissionMethod_setsPaymentMethods() {
            UserEntity traveler = buildTravelerWithCommissionMethod();
            traveler.setKycStatus(com.yadony.api.auth.KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, java.util.Set.of(com.yadony.api.payments.cash.PaymentMethod.STRIPE, com.yadony.api.payments.cash.PaymentMethod.CASH), null, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(18, 0),
                    null,
                    null
            );

            announcementService.createAnnouncement(FIREBASE_UID, req);

            org.mockito.ArgumentCaptor<AnnouncementEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(AnnouncementEntity.class);
            verify(announcementRepository).save(captor.capture());
            assertThat(captor.getValue().getAcceptedPaymentMethods())
                    .contains(com.yadony.api.payments.cash.PaymentMethod.CASH);
        }

        @Test
        @DisplayName("pricingMode MIXED → snapshotToAnnouncement appelé + pricingMode MIXED dans l'entité")
        void createAnnouncement_MIXED_calls_snapshotToAnnouncement() {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, PricingMode.MIXED,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(9, 0),
                    null,
                    null
            );

            AnnouncementResponse result = announcementService.createAnnouncement(FIREBASE_UID, req);

            verify(priceGridService).snapshotToAnnouncement(USER_ID, ANNOUNCEMENT_ID);
            assertThat(captor.getValue().getPricingMode()).isEqualTo(PricingMode.MIXED);
            assertThat(result.pricingMode()).isEqualTo(PricingMode.MIXED);
        }

        @Test
        @DisplayName("pricingMode MIXED + grille vide → 422 propagé")
        void createAnnouncement_MIXED_propagates_422_when_grid_empty() {
            UserEntity traveler = buildTraveler();
            traveler.setKycStatus(KycStatus.VERIFIED);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            doThrow(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "price-grid-empty: au moins 1 article requis pour le mode MIXED"))
                    .when(priceGridService).snapshotToAnnouncement(any(), any());

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, PricingMode.MIXED,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(9, 0),
                    null,
                    null
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                            .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    // ─── getMyAnnouncements ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyAnnouncements()")
    class GetMyTests {

        @Test
        @DisplayName("voyageur avec annonces → page retournée")
        void getMyAnnouncements_withAnnouncements_returnsPage() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(2L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(1L);
            when(announcementRepository.findByTravelerIdFiltered(
                    eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of(a)));

            Page<AnnouncementResponse> result = announcementService.getMyAnnouncements(
                    FIREBASE_UID, null, null, null, null, null, null, null, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).departureCity()).isEqualTo("Paris");
        }

        @Test
        @DisplayName("réponse expose reservedKg / surplusEligible / surplusPublished")
        void getMyAnnouncements_exposesSurplusFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setReservedKg(BigDecimal.valueOf(5));
            a.setSurplusEligible(true);
            a.setSurplusPublished(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            when(announcementRepository.findByTravelerIdFiltered(
                    eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of(a)));

            Page<AnnouncementResponse> result = announcementService.getMyAnnouncements(
                    FIREBASE_UID, null, null, null, null, null, null, null, PageRequest.of(0, 10));

            AnnouncementResponse r = result.getContent().get(0);
            assertThat(r.reservedKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(r.surplusEligible()).isTrue();
            assertThat(r.surplusPublished()).isTrue();
        }
    }

    // ─── getAnnouncementDetail ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getAnnouncementDetail()")
    class DetailTests {

        @Test
        @DisplayName("annonce existante → retourne le détail")
        void getDetail_existingAnnouncement_returnsDetail() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(3L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.departureCity()).isEqualTo("Paris");
            assertThat(result.bidsCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("détail expose le nombre de colis acceptés (confirmedParcelCount)")
        void getDetail_exposesConfirmedParcelCount() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(5L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(2L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.confirmedParcelCount()).isEqualTo(2L);
            assertThat(result.bidsCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("détail expose reservedKg / surplusEligible / surplusPublished")
        void getDetail_exposesSurplusFields() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setReservedKg(BigDecimal.valueOf(5));
            a.setSurplusEligible(true);
            a.setSurplusPublished(true);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.reservedKg()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(result.surplusEligible()).isTrue();
            assertThat(result.surplusPublished()).isTrue();
        }

        @Test
        @DisplayName("détail expose la devise de l'annonce, pas toujours EUR")
        void getDetail_exposesAnnouncementCurrency() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setCurrency("CAD");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.currency()).isEqualTo("CAD");
        }

        @Test
        @DisplayName("détail expose les instructions d'arrivée au voyageur propriétaire")
        void getDetail_exposesArrivalInstructions() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setArrivalInstructions("Métro Châtelet, sortie 3");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.arrivalInstructions()).isEqualTo("Métro Châtelet, sortie 3");
        }

        /** Régression I4 : les instructions décrivent un point de rendez-vous physique.
         *  GET /announcements/{id} étant ouvert à tout utilisateur authentifié, le champ
         *  fuitait à n'importe quel curieux. Seules les parties du trajet y ont droit. */
        @Test
        @DisplayName("régression I4 — instructions d'arrivée masquées pour un tiers authentifié")
        void getDetail_hidesArrivalInstructionsFromStranger() {
            UserEntity traveler = buildTraveler();
            UserEntity stranger = buildTraveler();
            setId(stranger, UUID.randomUUID());
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setArrivalInstructions("Métro Châtelet, sortie 3");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(stranger));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);
            when(bidRepository.existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                    eq(ANNOUNCEMENT_ID), any(UUID.class), anyCollection()))
                    .thenReturn(false);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.arrivalInstructions()).isNull();
        }

        /** Régression I4, versant positif : un expéditeur ayant un colis actif sur le
         *  trajet doit continuer à voir le point de retrait. */
        @Test
        @DisplayName("régression I4 — instructions d'arrivée visibles par un expéditeur avec colis actif")
        void getDetail_exposesArrivalInstructionsToActiveSender() {
            UserEntity traveler = buildTraveler();
            UserEntity sender = buildTraveler();
            UUID senderId = UUID.randomUUID();
            setId(sender, senderId);
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setArrivalInstructions("Métro Châtelet, sortie 3");
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(sender));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(1L);
            when(bidRepository.existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                    eq(ANNOUNCEMENT_ID), eq(senderId), anyCollection()))
                    .thenReturn(true);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.arrivalInstructions()).isEqualTo("Métro Châtelet, sortie 3");
        }

        @Test
        @DisplayName("annonce KG_FREE → capacityUnit présent dans le détail (regression)")
        void getDetail_kgFreeAnnouncement_returnsCapacityUnit() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setCapacityUnit(CapacityUnit.KG_FREE);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.capacityUnit()).isEqualTo(CapacityUnit.KG_FREE);
        }

        @Test
        @DisplayName("annonce acceptant le CASH → cashAccepted=true dans le détail (regression)")
        void getDetail_cashAccepted_returnsCashAcceptedTrue() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setAcceptedPaymentMethods(java.util.EnumSet.of(
                    com.yadony.api.payments.cash.PaymentMethod.STRIPE,
                    com.yadony.api.payments.cash.PaymentMethod.CASH));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.cashAccepted()).isTrue();
            assertThat(result.acceptedPaymentMethods()).contains("CASH");
        }

        @Test
        @DisplayName("annonce STRIPE seul → cashAccepted=false dans le détail")
        void getDetail_stripeOnly_returnsCashAcceptedFalse() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.cashAccepted()).isFalse();
        }

        @Test
        @DisplayName("annonce introuvable → 404 NOT_FOUND")
        void getDetail_unknownAnnouncement_throwsNotFound() {
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("brouillon → le propriétaire peut voir le détail")
        void getDetail_draftOwner_returnsDetail() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = draftEntityOwnedBy(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("brouillon → un tiers reçoit 404 (pas de fuite d'existence)")
        void getDetail_draftNonOwner_throwsNotFoundWithoutLeaking() {
            UserEntity owner = buildTraveler();
            AnnouncementEntity a = draftEntityOwnedBy(owner);

            UserEntity other = new UserEntity();
            other.setFirebaseUid("uid-other-traveler");
            setId(other, UUID.randomUUID());

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid("uid-other-traveler")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, "uid-other-traveler"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement-not-found");
                    });
        }

        @Test
        @DisplayName("annonce ACTIVE → un tiers peut voir le détail (non-régression, pas de résolution du viewer)")
        void getDetail_activeNonOwner_returnsDetail() {
            UserEntity owner = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(owner);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result = announcementService.getAnnouncementDetail(
                    ANNOUNCEMENT_ID, "uid-other-traveler-2");

            assertThat(result.status()).isEqualTo("ACTIVE");
            // Le lookup viewer (findByFirebaseUid) ne doit se déclencher que pour un DRAFT ;
            // seul le lookup du profil voyageur affiché (findById) est attendu ici.
            verify(userRepository, never()).findByFirebaseUid(any());
        }
    }

    // ─── updateAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateAnnouncement()")
    class UpdateTests {

        @Test
        @DisplayName("propriétaire + pas de bids acceptés → mise à jour réussie")
        void update_ownerNoBids_updatesAndAudits() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(announcementRepository.save(any())).thenReturn(a);
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Lyon", "Abidjan", LocalDate.now().plusDays(15),
                    null, null,
                    new AddressDto("Gare Part-Dieu, Lyon", 45.760, 4.860),
                    new AddressDto("Aéroport FHB, Abidjan", 5.261, -3.927),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().plusDays(15).atTime(18, 0),
                    null,
                    null
            );

            AnnouncementDetailResponse result = announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, req);

            assertThat(result.departureCity()).isEqualTo("Lyon");
            assertThat(result.arrivalCity()).isEqualTo("Abidjan");
            verify(auditService).log(eq("USER"), any(), eq("ANNOUNCEMENT_UPDATED"), any(), any());
        }

        /** Régression I3 : même trou côté modification — un trajet dont un colis est
         *  ARRIVED ne doit plus pouvoir être réécrit (villes, dates, tarifs). */
        @Test
        @DisplayName("régression I3 — modification refusée si un colis est ARRIVED")
        void update_withArrivedBid_isRefused() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANNOUNCEMENT_ID),
                    argThat(statuses -> statuses != null && statuses.contains(BidStatus.ARRIVED))))
                    .thenReturn(true);

            LocalDate departure = LocalDate.now().plusDays(15);
            AnnouncementRequest req = new AnnouncementRequest(
                    "Lyon", "Abidjan", departure,
                    null, null,
                    new AddressDto("Gare Part-Dieu, Lyon", 45.760, 4.860),
                    new AddressDto("Aéroport FHB, Abidjan", 5.261, -3.927),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null, null, null,
                    null, null, null, null, null,
                    departure.atTime(18, 0),
                    null,
                    null
            );

            assertYadonyError(
                    () -> announcementService.updateAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID, req),
                    "modification-impossible");
            verify(announcementRepository, never()).save(any());
        }

        // C2 : normalisation à l'écriture — s'applique aussi à updateAnnouncement().
        @Test
        @DisplayName("acceptedContentTypes/refusedTypes legacy → persistés normalisés")
        void update_legacyContentTypes_areNormalizedOnWrite() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(announcementRepository.save(any())).thenReturn(a);
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

            LocalDate departure = LocalDate.now().plusDays(15);
            AnnouncementRequest req = new AnnouncementRequest(
                    "Lyon", "Abidjan", departure,
                    null, null,
                    new AddressDto("Gare Part-Dieu, Lyon", 45.760, 4.860),
                    new AddressDto("Aéroport FHB, Abidjan", 5.261, -3.927),
                    BigDecimal.valueOf(25), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null,
                    List.of("Hi-fi"),
                    List.of("Nourriture", "Nourriture"),
                    null, null, null, null, null,
                    departure.atTime(18, 0),
                    null,
                    null
            );

            announcementService.updateAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID, req);

            assertThat(a.getAcceptedContentTypes()).containsExactly("Téléphone & électronique");
            assertThat(a.getRefusedTypes()).containsExactly("Alimentation sèche");
        }

        @Test
        @DisplayName("bids acceptés existants → 409 CONFLICT")
        void update_withAcceptedBids_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(true);

            assertThatThrownBy(() -> announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, buildRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("modification-impossible");
                    });
        }

        @Test
        @DisplayName("update sans bids acceptés → totalKg synchronisé avec availableKg")
        void update_setsTotalKgEqualToAvailableKg() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            // Existing announcement starts at 20 kg total
            assertThat(a.getTotalKg()).isEqualByComparingTo("20");

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar", LocalDate.now().plusDays(15),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(35), BigDecimal.valueOf(6),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().plusDays(15).atTime(18, 0),
                    null,
                    null
            );

            announcementService.updateAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID, req);

            assertThat(a.getAvailableKg()).isEqualByComparingTo("35");
            assertThat(a.getTotalKg()).isEqualByComparingTo("35");
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void update_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            otherUser.setFirebaseUid(FIREBASE_UID);
            setId(otherUser, UUID.randomUUID()); // Different ID

            AnnouncementEntity a = new AnnouncementEntity();
            a.setTravelerId(UUID.randomUUID()); // Different traveler
            setId(a, ANNOUNCEMENT_ID);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> announcementService.updateAnnouncement(
                    ANNOUNCEMENT_ID, FIREBASE_UID, buildRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ─── deleteAnnouncement ────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteAnnouncement()")
    class DeleteTests {

        /** Statuts liquidés par deleteAnnouncement (round 4 : alignée sur removeByAdmin,
         *  + AWAITING_PAYMENT/NEGOTIATING). */
        private final List<BidStatus> LIQUIDATABLE_STATUSES = List.of(
                BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED,
                BidStatus.AWAITING_PAYMENT, BidStatus.NEGOTIATING);

        /** Régression I3 : un colis ARRIVED (arrivé, pas encore retiré) est un engagement
         *  encore ouvert. La garde ne listait que ACCEPTED/HANDED_OVER/IN_TRANSIT, donc le
         *  voyageur pouvait supprimer le trajet sous les pieds d'expéditeurs non servis. */
        @Test
        @DisplayName("régression I3 — suppression refusée si un colis est ARRIVED")
        void delete_withArrivedBid_isRefused() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANNOUNCEMENT_ID),
                    argThat(statuses -> statuses != null && statuses.contains(BidStatus.ARRIVED))))
                    .thenReturn(true);

            assertYadonyError(
                    () -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID),
                    "deletion-impossible");
            assertThat(a.getDeletedAt()).isNull();
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("annonce active sans bids → soft-delete + audit")
        void delete_activeNoBids_softDeletes() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, LIQUIDATABLE_STATUSES))
                    .thenReturn(List.of());

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(a.getDeletedAt()).isNotNull();
            verify(announcementRepository).save(a);
            verify(auditService).log(eq("ANNOUNCEMENT"), any(), eq("ANNOUNCEMENT_DELETED"), any(), any());
        }

        @Test
        @DisplayName("annonce active avec bids PENDING/PAYMENT_ESCROWED → bids rejetés + soft-delete")
        void delete_activeWithPendingBids_rejectsBidsAndDeletes() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);

            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(ANNOUNCEMENT_ID);
            bid.setSenderId(UUID.randomUUID());
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);
            setId(bid, UUID.randomUUID());

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, LIQUIDATABLE_STATUSES))
                    .thenReturn(List.of(bid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
            // Rejet marqué « technique » → exclu du taux d'acceptation du voyageur.
            assertThat(bid.getRejectionReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
            verify(bidRepository).save(bid);
            assertThat(a.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("round 4 (revue) : liquide aussi AWAITING_PAYMENT (sans quoi confirmer le " +
                "paiement dans la fenêtre lève IllegalStateException sur l'annonce soft-deleted) " +
                "et NEGOTIATING (fermé en NEGOTIATION_CLOSED, jamais REJECTED)")
        void delete_activeWithAwaitingPaymentAndNegotiatingBids_liquidatesBoth() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);

            UUID awaitingPaymentBidId = UUID.randomUUID();
            UUID awaitingPaymentSenderId = UUID.randomUUID();
            BidEntity awaitingPaymentBid = new BidEntity();
            awaitingPaymentBid.setAnnouncementId(ANNOUNCEMENT_ID);
            awaitingPaymentBid.setSenderId(awaitingPaymentSenderId);
            awaitingPaymentBid.setStatus(BidStatus.AWAITING_PAYMENT);
            setId(awaitingPaymentBid, awaitingPaymentBidId);

            UUID negotiatingBidId = UUID.randomUUID();
            UUID negotiatingSenderId = UUID.randomUUID();
            BidEntity negotiatingBid = new BidEntity();
            negotiatingBid.setAnnouncementId(ANNOUNCEMENT_ID);
            negotiatingBid.setSenderId(negotiatingSenderId);
            negotiatingBid.setStatus(BidStatus.NEGOTIATING);
            setId(negotiatingBid, negotiatingBidId);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, LIQUIDATABLE_STATUSES))
                    .thenReturn(List.of(awaitingPaymentBid, negotiatingBid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(awaitingPaymentBid.getStatus()).isEqualTo(BidStatus.REJECTED);
            assertThat(awaitingPaymentBid.getRejectionReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);

            assertThat(negotiatingBid.getStatus()).isEqualTo(BidStatus.NEGOTIATION_CLOSED);
            assertThat(negotiatingBid.getRejectionReason()).isNull();

            verify(bidRepository).save(awaitingPaymentBid);
            verify(bidRepository).save(negotiatingBid);
            assertThat(a.getDeletedAt()).isNotNull();

            ArgumentCaptor<com.yadony.api.matching.events.BidRejectedEvent> captor =
                    ArgumentCaptor.forClass(com.yadony.api.matching.events.BidRejectedEvent.class);
            verify(eventPublisher, times(2)).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(com.yadony.api.matching.events.BidRejectedEvent::getBidId)
                    .containsExactlyInAnyOrder(awaitingPaymentBidId, negotiatingBidId);
            // rematchEligible=true ici (voyageur qui supprime son propre trajet), contrairement
            // à removeByAdmin (décision de modération).
            assertThat(captor.getAllValues())
                    .allSatisfy(e -> assertThat(e.isRematchEligible()).isTrue());
        }

        @Test
        @DisplayName("round 2 (arbitrage utilisateur) : bid escrowé liquidé → BidRejectedEvent " +
                "publié, motif ANNOUNCEMENT_DELETED, rematchEligible=true (le voyageur supprime " +
                "lui-même son trajet — pas une décision de modération)")
        void delete_activeWithEscrowedBid_publishesBidRejectedEventWithRematchEligible() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);

            UUID senderId = UUID.randomUUID();
            UUID bidId = UUID.randomUUID();
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(ANNOUNCEMENT_ID);
            bid.setSenderId(senderId);
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);
            setId(bid, bidId);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(false);
            when(bidRepository.findByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID, LIQUIDATABLE_STATUSES))
                    .thenReturn(List.of(bid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            ArgumentCaptor<com.yadony.api.matching.events.BidRejectedEvent> captor =
                    ArgumentCaptor.forClass(com.yadony.api.matching.events.BidRejectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            var published = captor.getValue();
            assertThat(published.getBidId()).isEqualTo(bidId);
            assertThat(published.getSenderId()).isEqualTo(senderId);
            assertThat(published.getReason()).isEqualTo(BidEntity.REJECTION_ANNOUNCEMENT_DELETED);
            assertThat(published.getAnnouncementId()).isEqualTo(ANNOUNCEMENT_ID);
            assertThat(published.isRematchEligible()).isTrue();
        }

        @Test
        @DisplayName("annonce active avec bids ACCEPTED → 409 CONFLICT")
        void delete_activeWithAcceptedBids_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(ANNOUNCEMENT_ID,
                    List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)))
                    .thenReturn(true);

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("annonce CANCELLED → soft-delete des bids + event publié")
        void delete_cancelledAnnouncement_deletesWithBidsAndPublishesEvent() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setStatus(AnnouncementStatus.CANCELLED);

            BidEntity bid = new BidEntity();
            setId(bid, UUID.randomUUID());
            bid.setStatus(BidStatus.CANCELLED);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(List.of(bid));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(bid.getDeletedAt()).isNotNull();
            assertThat(a.getDeletedAt()).isNotNull();
            ArgumentCaptor<AnnouncementDeletedEvent> captor =
                    ArgumentCaptor.forClass(AnnouncementDeletedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().announcementId()).isEqualTo(ANNOUNCEMENT_ID);
        }

        @Test
        @DisplayName("pas propriétaire → 403 FORBIDDEN")
        void delete_notOwner_throwsForbidden() {
            UserEntity otherUser = new UserEntity();
            setId(otherUser, UUID.randomUUID());
            otherUser.setFirebaseUid(FIREBASE_UID);

            AnnouncementEntity a = new AnnouncementEntity();
            a.setTravelerId(UUID.randomUUID());
            a.setStatus(AnnouncementStatus.ACTIVE);
            setId(a, ANNOUNCEMENT_ID);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("annonce COMPLETED (pas ACTIVE ni CANCELLED) → 409 CONFLICT")
        void delete_completedStatus_throwsConflict() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setStatus(AnnouncementStatus.COMPLETED);

            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("brouillon → soft-delete direct + audit (pas de bids possibles sur un DRAFT)")
        void delete_draftAnnouncement_softDeletesWithoutBidHandling() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = draftEntityOwnedBy(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            announcementService.deleteAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(a.getDeletedAt()).isNotNull();
            verify(announcementRepository).save(a);
            verify(auditService).log(eq("ANNOUNCEMENT"), any(), eq("DRAFT_ANNOUNCEMENT_DELETED"), any(), any());
            verifyNoInteractions(bidRepository);
        }
    }

    // ── searchAnnouncements ────────────────────────────────────────────────────

    @Nested
    @DisplayName("searchAnnouncements()")
    class SearchTests {

        /** Helper: stub the batch queries used by searchAnnouncements for a single page. */
        private void stubBatchSearch(UserEntity traveler, long bidCount) {
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
            java.util.List<Object[]> bidCounts = new java.util.ArrayList<>();
            bidCounts.add(new Object[]{ANNOUNCEMENT_ID, bidCount});
            when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(bidCounts);
        }

        @Test
        @DisplayName("sans filtre + tri par date ASC → retourne la page")
        void search_noFilters_sortByDate_returnsPage() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            traveler.setLastName("Diallo");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 3L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("avec tous les filtres + tri par prix DESC")
        void search_allFilters_sortByPriceDesc_returnsPage() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Fatou");
            traveler.setLastName(null);
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 1L);

            Page<?> result = announcementService.searchAnnouncements(
                    "Paris", "Dakar",
                    LocalDate.now(), LocalDate.now().plusDays(30),
                    BigDecimal.valueOf(5), null, null, null, null, null, null, null, null, null, null, null, "price", "desc", PageRequest.of(0, 10), null, null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur sans prénom → displayName = nom de famille")
        void search_travelerLastNameOnly_displayName() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName(null);
            traveler.setLastName("Keita");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "asc", PageRequest.of(0, 10), null, null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur introuvable → profil null")
        void search_travelerNotFound_profileIsNull() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            // Empty result from findAllById simulates missing traveler
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of());
            when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

            Page<?> result = announcementService.searchAnnouncements(
                    "Paris", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "desc", PageRequest.of(0, 10), null, null);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("voyageur sans prénom ni nom → displayName null")
        void search_travelerNeitherFirstNorLastName_displayNameNull() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName(null);
            traveler.setLastName(null);
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            assertThatNoException().isThrownBy(() -> announcementService.searchAnnouncements(
                    null, "Dakar", LocalDate.now(), null, null, null, null, null, null, null, null, null, null, null, null, null, "price", "asc", PageRequest.of(0, 10), null, null));
        }

        @Test
        @DisplayName("voyageur avec avatarUrl → TravelerProfileDto.avatarUrl propagé")
        void search_travelerAvatarUrl_isMappedInProfile() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            traveler.setAvatarUrl("https://cdn.example.com/avatar.jpg");
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            var response = (com.yadony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.traveler()).isNotNull();
            assertThat(response.traveler().avatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
        }

        @Test
        @DisplayName("voyageur sans avatarUrl → TravelerProfileDto.avatarUrl null")
        void search_travelerNoAvatarUrl_profileAvatarUrlNull() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            // avatarUrl not set → null
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            var response = (com.yadony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.traveler()).isNotNull();
            assertThat(response.traveler().avatarUrl()).isNull();
        }

        // ─── isFavorite flag ──────────────────────────────────────────────────────

        @Test
        @DisplayName("trajet en favori du caller → isFavorite=true")
        void search_favoritedTrip_isFavoriteTrue() {
            UserEntity viewer = buildTraveler();
            UUID viewerId = UUID.randomUUID();
            setId(viewer, viewerId);

            UserEntity tripOwner = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(tripOwner);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(userRepository.findByFirebaseUid("viewer-uid")).thenReturn(Optional.of(viewer));
            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(tripOwner, 0L);
            when(favoriteRepository.findTargetIds(viewerId, FavoriteTargetType.TRIP))
                    .thenReturn(List.of(ANNOUNCEMENT_ID));

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), "viewer-uid", null);

            var response = (com.yadony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.isFavorite()).isTrue();
        }

        @Test
        @DisplayName("trajet non en favori du caller → isFavorite=false")
        void search_nonFavoritedTrip_isFavoriteFalse() {
            UserEntity viewer = buildTraveler();
            UUID viewerId = UUID.randomUUID();
            setId(viewer, viewerId);

            UserEntity tripOwner = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(tripOwner);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(userRepository.findByFirebaseUid("viewer-uid")).thenReturn(Optional.of(viewer));
            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(tripOwner, 0L);
            when(favoriteRepository.findTargetIds(viewerId, FavoriteTargetType.TRIP))
                    .thenReturn(List.of()); // no favorites

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), "viewer-uid", null);

            var response = (com.yadony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.isFavorite()).isFalse();
        }

        @Test
        @DisplayName("caller anonyme (viewerFirebaseUid null) → isFavorite=false, pas d'appel FavoriteRepository")
        void search_anonymousCaller_isFavoriteFalse() {
            UserEntity tripOwner = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(tripOwner);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(tripOwner, 0L);
            // viewerFirebaseUid = null → anonymous caller

            Page<?> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            var response = (com.yadony.api.matching.dto.AnnouncementSearchResponse) result.getContent().get(0);
            assertThat(response.isFavorite()).isFalse();
            verify(favoriteRepository, never()).findTargetIds(any(), any());
        }

        // ─── N+1 batch verification ────────────────────────────────────────────────

        @Test
        @DisplayName("recherche N résultats → userRepository.findAllById appelé 1 fois (pas N), findById jamais")
        void search_nResults_onlyOneUserBatchQuery() {
            UserEntity traveler = buildTraveler();
            traveler.setFirstName("Amara");
            // Build two announcements with the same traveler
            AnnouncementEntity ann1 = buildAnnouncement(traveler);
            AnnouncementEntity ann2 = buildAnnouncement(traveler);
            UUID id2 = UUID.randomUUID();
            setId(ann2, id2);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann1, ann2));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
            when(bidRepository.countVisibleByAnnouncementIds(anyCollection()))
                    .thenReturn(List.of(new Object[]{ANNOUNCEMENT_ID, 0L}, new Object[]{id2, 1L}));

            announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            // Batch call made once for both rows
            verify(userRepository, times(1)).findAllById(anyCollection());
            // Per-row call never used
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("recherche N résultats → bidRepository.countVisibleByAnnouncementIds appelé 1 fois, countVisibleByAnnouncementId jamais")
        void search_nResults_onlyOneBidCountBatchQuery() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann1 = buildAnnouncement(traveler);
            AnnouncementEntity ann2 = buildAnnouncement(traveler);
            setId(ann2, UUID.randomUUID());
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann1, ann2));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
            when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

            announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "date", "asc", PageRequest.of(0, 10), null, null);

            verify(bidRepository, times(1)).countVisibleByAnnouncementIds(anyCollection());
            verify(bidRepository, never()).countVisibleByAnnouncementId(any());
        }

        // ─── urgent filter ────────────────────────────────────────────────────────

        @Test
        @DisplayName("urgent=true → restreint departureDate à [today, today+seuil]")
        void searchAnnouncements_urgentTrue_restrictsToNextThresholdDays() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            LocalDate expectedTo = today.plusDays(3); // config.urgency().thresholdDays() == 3 (voir initService())

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                    mockStatic(AnnouncementSpecification.class, CALLS_REAL_METHODS)) {
                announcementService.searchAnnouncements(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), null, true);

                specMock.verify(() -> AnnouncementSpecification.departureDateFrom(today));
                specMock.verify(() -> AnnouncementSpecification.departureDateTo(expectedTo));
            }
        }

        @Test
        @DisplayName("urgent=true + filtres de date explicites → intersection des bornes")
        void searchAnnouncements_urgentTrue_withExplicitDateFilters_appliesIntersection() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(traveler);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            LocalDate explicitFrom = today.plusDays(1); // plus strict que today → conservé tel quel
            LocalDate explicitTo = today.plusDays(30);  // plus large que today+3 → ramené à today+3
            LocalDate expectedTo = today.plusDays(3);

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                    mockStatic(AnnouncementSpecification.class, CALLS_REAL_METHODS)) {
                announcementService.searchAnnouncements(
                        null, null, explicitFrom, explicitTo, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), null, true);

                specMock.verify(() -> AnnouncementSpecification.departureDateFrom(explicitFrom));
                specMock.verify(() -> AnnouncementSpecification.departureDateTo(expectedTo));
            }
        }

        @Test
        @DisplayName("viewer avec prefs CAD → filtre recherche sur CAD")
        void searchAnnouncements_viewerCurrencyFromBusinessPrefs_filtersByCad() {
            UserEntity viewer = buildTraveler();
            UUID viewerId = UUID.randomUUID();
            setId(viewer, viewerId);
            UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
            prefs.setUserId(viewerId);
            prefs.setCurrencyCode("CAD");

            when(userRepository.findByFirebaseUid("viewer-cad")).thenReturn(Optional.of(viewer));
            when(activeCurrencyResolver.resolve(viewerId)).thenReturn(prefs.getCurrencyCode());
            when(favoriteRepository.findTargetIds(viewerId, FavoriteTargetType.TRIP)).thenReturn(List.of());
            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, 10)));

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                         mockStatic(AnnouncementSpecification.class, CALLS_REAL_METHODS)) {
                announcementService.searchAnnouncements(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), "viewer-cad", null);

                specMock.verify(() -> AnnouncementSpecification.hasCurrency("CAD"));
            }
        }

        @Test
        @DisplayName("viewer connu sans prefs → filtre recherche par défaut sur EUR")
        void searchAnnouncements_knownViewerWithoutPrefs_defaultsToEurCurrencyFilter() {
            UserEntity viewer = buildTraveler();
            UUID viewerId = UUID.randomUUID();
            setId(viewer, viewerId);

            when(userRepository.findByFirebaseUid("viewer-no-prefs")).thenReturn(Optional.of(viewer));
            when(activeCurrencyResolver.resolve(viewerId)).thenReturn("EUR");
            when(favoriteRepository.findTargetIds(viewerId, FavoriteTargetType.TRIP)).thenReturn(List.of());
            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, 10)));

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                         mockStatic(AnnouncementSpecification.class, CALLS_REAL_METHODS)) {
                announcementService.searchAnnouncements(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), "viewer-no-prefs", null);

                specMock.verify(() -> AnnouncementSpecification.hasCurrency("EUR"));
            }
        }

        @Test
        @DisplayName("viewer Firebase UID inconnu → spec repository filtrée par défaut sur EUR")
        void searchAnnouncements_unknownViewerUid_defaultsToEurCurrencyFilterInRepositorySpec() {
            when(userRepository.findByFirebaseUid("viewer-unknown")).thenReturn(Optional.empty());
            when(activeCurrencyResolver.resolve(null)).thenReturn("EUR");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Specification<AnnouncementEntity>> specCaptor =
                    ArgumentCaptor.forClass((Class) Specification.class);
            when(announcementRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, 10)));

            Specification<AnnouncementEntity> passThrough = (root, query, cb) -> cb.conjunction();
            java.util.concurrent.atomic.AtomicBoolean eurCurrencySpecIncluded = new java.util.concurrent.atomic.AtomicBoolean(false);
            Specification<AnnouncementEntity> eurCurrencyMarker = (root, query, cb) -> {
                eurCurrencySpecIncluded.set(true);
                return cb.conjunction();
            };

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                         mockStatic(AnnouncementSpecification.class)) {
                specMock.when(() -> AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE))
                        .thenReturn(passThrough);
                specMock.when(AnnouncementSpecification::publicOrOpenSurplus)
                        .thenReturn(passThrough);
                specMock.when(() -> AnnouncementSpecification.hasCurrency("EUR"))
                        .thenReturn(eurCurrencyMarker);

                announcementService.searchAnnouncements(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), "viewer-unknown", null);
            }

            jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder = mock(jakarta.persistence.criteria.CriteriaBuilder.class);
            jakarta.persistence.criteria.Predicate conjunction = mock(jakarta.persistence.criteria.Predicate.class);
            when(criteriaBuilder.conjunction()).thenReturn(conjunction);
            when(criteriaBuilder.and(any(jakarta.persistence.criteria.Predicate.class), any(jakarta.persistence.criteria.Predicate.class)))
                    .thenReturn(conjunction);

            specCaptor.getValue().toPredicate(
                    mock(jakarta.persistence.criteria.Root.class),
                    mock(jakarta.persistence.criteria.CriteriaQuery.class),
                    criteriaBuilder
            );

            assertThat(eurCurrencySpecIncluded).isTrue();
            verify(userRepository).findByFirebaseUid("viewer-unknown");
            // Le repli EUR appartient au résolveur, pas à l'appelant : il est donc
            // bien sollicité, avec un viewer null.
            verify(activeCurrencyResolver).resolve(null);
            verifyNoInteractions(favoriteRepository);
        }

        @Test
        @DisplayName("viewer absent → filtre recherche par défaut sur EUR")
        void searchAnnouncements_missingViewer_defaultsToEurCurrencyFilter() {
            when(activeCurrencyResolver.resolve(null)).thenReturn("EUR");
            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                    .thenReturn(Page.empty(PageRequest.of(0, 10)));

            try (org.mockito.MockedStatic<AnnouncementSpecification> specMock =
                         mockStatic(AnnouncementSpecification.class, CALLS_REAL_METHODS)) {
                announcementService.searchAnnouncements(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        "date", "asc", PageRequest.of(0, 10), null, null);

                specMock.verify(() -> AnnouncementSpecification.hasCurrency("EUR"));
            }

            verify(userRepository, never()).findByFirebaseUid(null);
            verify(activeCurrencyResolver).resolve(null);
        }

        // ─── computeUrgent boundary (DTO field, seuil de test = 3) ─────────────────

        private com.yadony.api.matching.dto.AnnouncementSearchResponse searchSingle(LocalDate departureDate) {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity ann = buildAnnouncement(traveler);
            ann.setDepartureDate(departureDate);
            Page<AnnouncementEntity> page = new PageImpl<>(List.of(ann));

            when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class))).thenReturn(page);
            stubBatchSearch(traveler, 0L);

            Page<com.yadony.api.matching.dto.AnnouncementSearchResponse> result = announcementService.searchAnnouncements(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "date", "asc", PageRequest.of(0, 10), null, null);

            return result.getContent().get(0);
        }

        @Test
        @DisplayName("departureDate = today (UTC) → urgent=true")
        void computeUrgent_departureDateToday_urgentTrue() {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            assertThat(searchSingle(today).urgent()).isTrue();
        }

        @Test
        @DisplayName("departureDate = today+3 (borne exacte du seuil) → urgent=true")
        void computeUrgent_departureDateAtThresholdBoundary_urgentTrue() {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            assertThat(searchSingle(today.plusDays(3)).urgent()).isTrue();
        }

        @Test
        @DisplayName("departureDate = today+4 (juste après le seuil) → urgent=false")
        void computeUrgent_departureDateJustBeyondThreshold_urgentFalse() {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            assertThat(searchSingle(today.plusDays(4)).urgent()).isFalse();
        }

        @Test
        @DisplayName("departureDate = today-1 (passé) → urgent=false")
        void computeUrgent_departureDateInPast_urgentFalse() {
            LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
            assertThat(searchSingle(today.minusDays(1)).urgent()).isFalse();
        }

        @Test
        @DisplayName("departureDate = null → urgent=false")
        void computeUrgent_departureDateNull_urgentFalse() {
            assertThat(searchSingle(null).urgent()).isFalse();
        }
    }

    // ─── capacityUnit + date validation ───────────────────────────────────────

    @Nested
    @DisplayName("createAnnouncement — validation capacityUnit & date")
    class CapacityUnitCreationTest {

        @Test
        @DisplayName("capacityUnit SUITCASE_32KG → persisté sur l'entité sauvegardée")
        void create_withCapacityUnit_suitcase32kg_succeeds() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    LocalDate.now().plusDays(10),
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(32), BigDecimal.valueOf(8),
                    TransportMode.PLANE,
                    null, null, null, null, CapacityUnit.SUITCASE_32KG, null,
                    null, null,
                    LocalDate.now().plusDays(10).atTime(9, 0),
                    null,
                    null
            );

            announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(captor.getValue().getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_32KG);
        }

        @Test
        @DisplayName("capacityUnit null → défaut SUITCASE_23KG")
        void create_withNullCapacityUnit_defaultsToSuitcase23Kg() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(captor.getValue().getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_23KG);
        }

        @Test
        @DisplayName("date de départ dans le passé → 422 invalid-departure-date")
        void create_withPastDepartureDate_throwsUnprocessableEntity() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    LocalDate.now().minusDays(1),
                    null, null,
                    new AddressDto("CDG", 49.009, 2.547),
                    new AddressDto("DSS", 14.693, -17.447),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    LocalDate.now().minusDays(1).atTime(18, 0),
                    null,
                    null
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-departure-date");
                        assertThat(ex.getMessage()).contains("passé");
                    });
        }

        // ─── saveAsDraft (statut DRAFT) ─────────────────────────────────────

        @Test
        @DisplayName("saveAsDraft=true → statut DRAFT, KYC et limite mensuelle ignorés")
        void createAnnouncement_saveAsDraft_setsDraftStatusAndSkipsKycAndMonthlyLimit() {
            UserEntity user = proUser();
            user.setKycStatus(KycStatus.PENDING); // KYC non vérifié : ne doit PAS bloquer un draft
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(0L);
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse resp = announcementService.createAnnouncement(FIREBASE_UID, draftRequest());

            assertThat(resp.status()).isEqualTo("DRAFT");
            verify(announcementRepository, never())
                    .countByTravelerIdAndCreatedAtBetween(any(), any(), any());
        }

        @Test
        @DisplayName("saveAsDraft=true, non-PRO au quota (1) → 403 draft-limit-reached")
        void createAnnouncement_saveAsDraft_nonProAtLimit_throws403DraftLimitReached() {
            UserEntity user = standardUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(1L); // déjà 1 brouillon

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, draftRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "draft-limit-reached");
        }

        @Test
        @DisplayName("saveAsDraft=true, PRO sous le quota (10) → succès")
        void createAnnouncement_saveAsDraft_proUnderProLimit_succeeds() {
            UserEntity user = proUser();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(9L); // 9 < 10
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse resp = announcementService.createAnnouncement(FIREBASE_UID, draftRequest());
            assertThat(resp.status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("saveAsDraft=true, voyageur suspendu de publication → 403 publishing-suspended")
        void createAnnouncement_saveAsDraft_publishingSuspended_throws403() {
            UserEntity user = standardUser();
            user.setPublishingSuspended(true);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, draftRequest()))
                    .isInstanceOf(YadonyBusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "publishing-suspended");
        }

        @Test
        @DisplayName("saveAsDraft=false, limite mensuelle non-PRO → le compte ignore les brouillons")
        void createAnnouncement_publishDirect_monthlyLimitIgnoresDrafts() {
            UserEntity user = standardUser();
            YadonyConfigProperties.Limits limits = new YadonyConfigProperties.Limits(
                    new YadonyConfigProperties.Limits.NonPro(2), null);
            YadonyConfigProperties configWithLimits = new YadonyConfigProperties(null, limits,
                    new YadonyConfigProperties.Urgency(3), null);
            AnnouncementSearchMapper mapperWithLimits = new AnnouncementSearchMapper(
                    userRepository, bidRepository, priceGridService, storageService, configWithLimits);
            AnnouncementService serviceWithLimits = new AnnouncementService(
                    announcementRepository, bidRepository, userRepository,
                    auditService, eventPublisher, configWithLimits, priceGridService, flagService,
                    storageService, favoriteRepository, activeCurrencyResolver, mapperWithLimits, packageRequestRepository,
                    negotiationThreadRepository, notificationDispatcher);

            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            // le nouveau count (hors DRAFT) renvoie 1 => sous la limite (2) => création OK
            when(announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                    eq(user.getId()), any(), any(), eq(AnnouncementStatus.DRAFT))).thenReturn(1L);
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementResponse resp = serviceWithLimits.createAnnouncement(FIREBASE_UID, buildRequest());

            assertThat(resp.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("saveAsDraft=true → aucun event de publication/matching émis (brouillon invisible)")
        void createAnnouncement_saveAsDraft_doesNotPublishMatchingEvents() {
            UserEntity user = proUser();
            user.setKycStatus(KycStatus.PENDING);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(0L);
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, draftRequest());

            verify(eventPublisher, never()).publishEvent(any(com.yadony.api.matching.events.AnnouncementCreatedEvent.class));
            verify(eventPublisher, never()).publishEvent(any(AnnouncementPublishedEvent.class));
        }

        @Test
        @DisplayName("saveAsDraft=false → events de publication/matching émis")
        void createAnnouncement_publishDirect_publishesMatchingEvents() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            announcementService.createAnnouncement(FIREBASE_UID, buildRequest());

            verify(eventPublisher).publishEvent(any(com.yadony.api.matching.events.AnnouncementCreatedEvent.class));
            verify(eventPublisher).publishEvent(any(AnnouncementPublishedEvent.class));
        }
    }

    // ─── HandoverDeadline validation ───────────────────────────────────────────

    @Nested
    @DisplayName("HandoverDeadline — validation createAnnouncement()")
    class HandoverDeadlineTests {

        private AnnouncementRequest buildRequestWithDeadline(LocalDate departure,
                                                             LocalDateTime deadline) {
            return new AnnouncementRequest(
                    "Paris", "Dakar",
                    departure,
                    LocalTime.of(20, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    deadline,
                    null,
                    null
            );
        }

        @Test
        @DisplayName("date limite de dépôt nulle → 422 handover-deadline-required")
        void createAnnouncement_handoverDeadlineNull_throws422() {
            LocalDate departure = LocalDate.now().plusDays(10);
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            AnnouncementRequest req = buildRequestWithDeadline(departure, null);

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("handover-deadline-required");
                    });
        }

        @Test
        @DisplayName("date limite avant le départ → acceptée (plus de borne basse)")
        void createAnnouncement_handoverDeadlineWellBeforeDeparture_ok() {
            LocalDate departure = LocalDate.now().plusDays(10);
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.save(any())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            // Une date limite très en amont du départ était impossible avec
            // l'ancienne fenêtre (début imposé) : elle est désormais valide.
            AnnouncementRequest req = buildRequestWithDeadline(departure, departure.atTime(6, 0));

            assertThatCode(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("date limite après le départ → 422 handover-after-departure")
        void createAnnouncement_handoverDeadlineAfterDeparture_throws422() {
            LocalDate departure = LocalDate.now().plusDays(10);
            LocalDateTime deadline = departure.plusDays(1).atTime(8, 0); // after departure
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));

            // departureTime=null → bound = departure.atTime(LocalTime.MAX)
            AnnouncementRequest req = new AnnouncementRequest(
                    "Paris", "Dakar",
                    departure,
                    null, null,
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    deadline,
                    null,
                    null
            );

            assertThatThrownBy(() -> announcementService.createAnnouncement(FIREBASE_UID, req))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("handover-after-departure");
                    });
        }

        @Test
        @DisplayName("date limite valide → persistée sur l'entité")
        void createAnnouncement_validHandoverDeadline_persistsIt() {
            LocalDate departure = LocalDate.now().plusDays(10);
            LocalDateTime deadline = departure.atTime(18, 0);

            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);

            AnnouncementRequest req = buildRequestWithDeadline(departure, deadline);
            announcementService.createAnnouncement(FIREBASE_UID, req);

            assertThat(captor.getValue().getHandoverDeadline()).isEqualTo(deadline);
        }
    }

    // ─── publishAnnouncement (DRAFT → ACTIVE) ──────────────────────────────────

    @Nested
    @DisplayName("publishAnnouncement()")
    class PublishTests {

        @Test
        @DisplayName("brouillon du propriétaire → ACTIVE + audit ANNOUNCEMENT_PUBLISHED + events de matching émis")
        void publishAnnouncement_draft_becomesActive_andAuditsPublication() {
            UserEntity user = verifiedProUser();
            AnnouncementEntity draft = draftEntityOwnedBy(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            announcementService.publishAnnouncement(draft.getId(), FIREBASE_UID);

            assertThat(draft.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
            verify(auditService).log(eq("USER"), eq(user.getId()),
                    eq("ANNOUNCEMENT_PUBLISHED"), eq(draft.getId()), anyMap());
            // Un brouillon devient réel à la publication : c'est ici, et seulement ici,
            // que les events de matching/notifications doivent partir.
            verify(eventPublisher).publishEvent(any(com.yadony.api.matching.events.AnnouncementCreatedEvent.class));
            verify(eventPublisher).publishEvent(any(AnnouncementPublishedEvent.class));
        }

        @Test
        @DisplayName("annonce déjà active → 422 not-a-draft")
        void publishAnnouncement_notADraft_throws422() {
            UserEntity user = verifiedProUser();
            AnnouncementEntity active = buildAnnouncement(user); // status=ACTIVE par défaut
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(active.getId())).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> announcementService.publishAnnouncement(active.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("not-a-draft");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("pas propriétaire → même pattern que updateAnnouncement (403 FORBIDDEN / forbidden)")
        void publishAnnouncement_notOwner_throws() {
            UserEntity otherUser = new UserEntity();
            otherUser.setFirebaseUid(FIREBASE_UID);
            setId(otherUser, UUID.randomUUID());

            AnnouncementEntity draft = new AnnouncementEntity();
            draft.setTravelerId(UUID.randomUUID()); // différent du user courant
            draft.setStatus(AnnouncementStatus.DRAFT);
            setId(draft, ANNOUNCEMENT_ID);

            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> announcementService.publishAnnouncement(ANNOUNCEMENT_ID, FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("forbidden");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("KYC non vérifié (enforceKyc=true) → 403 kyc-not-verified")
        void publishAnnouncement_kycNotVerified_throws403KycNotVerified() throws Exception {
            UserEntity user = proUser();
            user.setKycStatus(KycStatus.PENDING);
            AnnouncementEntity draft = draftEntityOwnedBy(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

            Field enforceKycField = AnnouncementService.class.getDeclaredField("enforceKyc");
            enforceKycField.setAccessible(true);
            enforceKycField.set(announcementService, true);

            assertThatThrownBy(() -> announcementService.publishAnnouncement(draft.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("kyc-not-verified");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("Task 2 — brouillon déclarant STRIPE, compte Stripe non configuré → 403 à la publication")
        void publishAnnouncement_draftDeclaringStripe_withoutStripeAccount_throws403() throws Exception {
            UserEntity user = verifiedProUser();
            user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
            AnnouncementEntity draft = draftEntityOwnedBy(user);
            // Un brouillon peut déclarer STRIPE sans compte — le check tombe à la publication.
            draft.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

            Field enforceField = AnnouncementService.class.getDeclaredField("enforceStripeOnboarding");
            enforceField.setAccessible(true);
            enforceField.set(announcementService, true);

            assertThatThrownBy(() -> announcementService.publishAnnouncement(draft.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("stripe-onboarding-incomplete");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("voyageur suspendu de publication → 403 publishing-suspended")
        void publishAnnouncement_publishingSuspended_throws403() {
            UserEntity user = verifiedProUser();
            user.setPublishingSuspended(true);
            AnnouncementEntity draft = draftEntityOwnedBy(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> announcementService.publishAnnouncement(draft.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("publishing-suspended");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-PRO, limite mensuelle atteinte → 403 pro-limit-reached")
        void publishAnnouncement_nonProMonthlyLimitReached_throws403ProLimitReached() {
            UserEntity user = standardUser();
            YadonyConfigProperties.Limits limits = new YadonyConfigProperties.Limits(
                    new YadonyConfigProperties.Limits.NonPro(2), null);
            YadonyConfigProperties configWithLimits = new YadonyConfigProperties(null, limits,
                    new YadonyConfigProperties.Urgency(3), null);
            AnnouncementSearchMapper mapperWithLimits = new AnnouncementSearchMapper(
                    userRepository, bidRepository, priceGridService, storageService, configWithLimits);
            AnnouncementService serviceWithLimits = new AnnouncementService(
                    announcementRepository, bidRepository, userRepository,
                    auditService, eventPublisher, configWithLimits, priceGridService, flagService,
                    storageService, favoriteRepository, activeCurrencyResolver, mapperWithLimits, packageRequestRepository,
                    negotiationThreadRepository, notificationDispatcher);

            AnnouncementEntity draft = draftEntityOwnedBy(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
            when(announcementRepository.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                    eq(user.getId()), any(), any(), eq(AnnouncementStatus.DRAFT))).thenReturn(2L);

            assertThatThrownBy(() -> serviceWithLimits.publishAnnouncement(draft.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("pro-limit-reached");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("date de départ passée → 422 departure-date-passed, statut reste DRAFT")
        void publishAnnouncement_departureDatePassed_throws422() {
            UserEntity user = verifiedProUser();
            AnnouncementEntity draft = draftEntityOwnedBy(user);
            draft.setDepartureDate(LocalDate.now().minusDays(1));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> announcementService.publishAnnouncement(draft.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("departure-date-passed");
                    });
            assertThat(draft.getStatus()).isEqualTo(AnnouncementStatus.DRAFT);
            verify(announcementRepository, never()).save(any());
        }
    }

    // ─── unpublishAnnouncement (ACTIVE → DRAFT) ───────────────────────────────

    @Nested
    @DisplayName("unpublishAnnouncement()")
    class UnpublishAnnouncementTests {

        @Test
        @DisplayName("ACTIVE sans demande → DRAFT + audit UNPUBLISHED")
        void unpublish_activeWithoutBids_becomesDraft() {
            UserEntity user = standardUser();
            AnnouncementEntity active = buildAnnouncement(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(announcementRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(0L);
            when(bidRepository.countVisibleByAnnouncementId(active.getId())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(eq(active.getId()), anyList())).thenReturn(0L);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AnnouncementDetailResponse result = announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID);

            assertThat(active.getStatus()).isEqualTo(AnnouncementStatus.DRAFT);
            assertThat(result.status()).isEqualTo("DRAFT");
            verify(announcementRepository).findByIdForUpdate(active.getId());
            verify(auditService).log(eq("ANNOUNCEMENT"), eq(active.getId()),
                    eq("UNPUBLISHED"), eq(user.getId()), anyMap());
        }

        @Test
        @DisplayName("au moins une demande reçue → 409 announcement/has-bids")
        void unpublish_withBids_throws409() {
            UserEntity user = standardUser();
            AnnouncementEntity active = buildAnnouncement(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(bidRepository.countVisibleByAnnouncementId(active.getId())).thenReturn(1L);

            assertThatThrownBy(() -> announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement/has-bids");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("trajet référencé par une négociation active sans bid → 409 announcement/has-negotiations")
        void unpublish_withActiveNegotiationThreadButNoBid_throws409() {
            UserEntity user = standardUser();
            AnnouncementEntity active = buildAnnouncement(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(bidRepository.countVisibleByAnnouncementId(active.getId())).thenReturn(0L);
            when(negotiationThreadRepository.existsActiveByTravelerAnnouncementId(active.getId())).thenReturn(true);

            assertThatThrownBy(() -> announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement/has-negotiations");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("trajet référencé par une négociation REJECTED (morte) → dépublication autorisée")
        void unpublish_withOnlyRejectedNegotiationThread_succeeds() {
            UserEntity user = standardUser();
            AnnouncementEntity active = buildAnnouncement(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(announcementRepository.findById(active.getId())).thenReturn(Optional.of(active));
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(0L);
            when(bidRepository.countVisibleByAnnouncementId(active.getId())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(eq(active.getId()), anyList())).thenReturn(0L);
            when(negotiationThreadRepository.existsActiveByTravelerAnnouncementId(active.getId())).thenReturn(false);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AnnouncementDetailResponse result = announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID);

            assertThat(result.status()).isEqualTo("DRAFT");
            verify(announcementRepository).save(active);
        }

        @Test
        @DisplayName("statut non ACTIVE → 409 announcement/not-unpublishable")
        void unpublish_notActive_throws409() {
            UserEntity user = standardUser();
            AnnouncementEntity completed = buildAnnouncement(user);
            completed.setStatus(AnnouncementStatus.COMPLETED);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(completed.getId())).thenReturn(Optional.of(completed));

            assertThatThrownBy(() -> announcementService.unpublishAnnouncement(completed.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("announcement/not-unpublishable");
                    });
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-propriétaire → 403")
        void unpublish_notOwner_throws403() {
            UserEntity otherUser = standardUser();
            setId(otherUser, UUID.randomUUID());
            AnnouncementEntity active = buildAnnouncement(standardUser());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(otherUser));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));

            assertThatThrownBy(() -> announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("quota de brouillons atteint → 403 draft-limit-reached")
        void unpublish_overDraftQuota_throws403() {
            UserEntity user = standardUser();
            AnnouncementEntity active = buildAnnouncement(user);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(announcementRepository.findByIdForUpdate(active.getId())).thenReturn(Optional.of(active));
            when(bidRepository.countVisibleByAnnouncementId(active.getId())).thenReturn(0L);
            when(announcementRepository.countByTravelerIdAndStatus(user.getId(), AnnouncementStatus.DRAFT))
                    .thenReturn(1L);

            assertThatThrownBy(() -> announcementService.unpublishAnnouncement(active.getId(), FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getErrorCode()).isEqualTo("draft-limit-reached");
                    });
            verify(announcementRepository, never()).save(any());
        }
    }

    // ─── Audit anti-fuite DRAFT (Task 5) ───────────────────────────────────────

    @Nested
    @DisplayName("getTravelerAnnouncements() — verrou anti-fuite DRAFT")
    class GetTravelerAnnouncementsTests {

        @Test
        @DisplayName("ne requête jamais le statut DRAFT — seulement ACTIVE et FULL")
        void getTravelerAnnouncements_neverQueriesDraftStatus() {
            UUID travelerId = UUID.randomUUID();
            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.ACTIVE), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.FULL), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            announcementService.getTravelerAnnouncements(travelerId);

            verify(announcementRepository, never())
                    .findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.DRAFT), any());
            verify(announcementRepository, never()).findByTravelerId(eq(travelerId), any());
        }

        @Test
        @DisplayName("un DRAFT en base ne peut pas apparaître dans le résultat (même en cas de mix ACTIVE/FULL)")
        void getTravelerAnnouncements_resultNeverContainsDraft() {
            UUID travelerId = UUID.randomUUID();
            UserEntity traveler = buildTraveler();
            setId(traveler, travelerId);
            AnnouncementEntity active = buildAnnouncement(traveler);
            active.setStatus(AnnouncementStatus.ACTIVE);

            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.ACTIVE), any()))
                    .thenReturn(new PageImpl<>(List.of(active)));
            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.FULL), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            var result = announcementService.getTravelerAnnouncements(travelerId);

            assertThat(result).hasSize(1);
            assertThat(result).allSatisfy(r -> assertThat(r.status()).isNotEqualTo("DRAFT"));
            assertThat(result.get(0).status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("expose la devise de l'annonce, pas toujours EUR")
        void getTravelerAnnouncements_exposesCurrency() {
            UUID travelerId = UUID.randomUUID();
            UserEntity traveler = buildTraveler();
            setId(traveler, travelerId);
            AnnouncementEntity active = buildAnnouncement(traveler);
            active.setStatus(AnnouncementStatus.ACTIVE);
            active.setCurrency("CAD");

            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.ACTIVE), any()))
                    .thenReturn(new PageImpl<>(List.of(active)));
            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.FULL), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            var result = announcementService.getTravelerAnnouncements(travelerId);

            assertThat(result.get(0).currency()).isEqualTo("CAD");
        }
    }

    // ─── markArrived ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markArrived()")
    class MarkArrivedTests {

        @Test
        @DisplayName("markArrived — transitionne tous les bids IN_TRANSIT vers ARRIVED et publie TripArrivedEvent")
        void markArrived_success() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            BidEntity bidInTransit = buildBid(BidStatus.IN_TRANSIT, announcement.getId());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of(bidInTransit));
            when(bidRepository.countVisibleByAnnouncementId(announcement.getId())).thenReturn(1L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(eq(announcement.getId()), anyList())).thenReturn(0L);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AnnouncementDetailResponse result =
                    announcementService.markArrived(announcement.getId(), FIREBASE_UID, "Métro Châtelet, sortie 3");

            assertThat(bidInTransit.getStatus()).isEqualTo(BidStatus.ARRIVED);
            assertThat(announcement.getArrivalInstructions()).isEqualTo("Métro Châtelet, sortie 3");
            assertThat(result.arrivalInstructions()).isEqualTo("Métro Châtelet, sortie 3");
            verify(bidRepository).saveAll(List.of(bidInTransit));
            ArgumentCaptor<TripArrivedEvent> captor = ArgumentCaptor.forClass(TripArrivedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getTargets()).hasSize(1);
            assertThat(captor.getValue().getTargets().get(0).bidId()).isEqualTo(bidInTransit.getId());
            assertThat(captor.getValue().getTargets().get(0).senderId()).isEqualTo(bidInTransit.getSenderId());
            verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcement.getId()),
                    eq("TRIP_ARRIVED"), eq(traveler.getId()), anyMap());
        }

        @Test
        @DisplayName("markArrived — refuse si un bid actif n'est pas IN_TRANSIT")
        void markArrived_notAllInTransit_throws() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            BidEntity bidHandedOver = buildBid(BidStatus.HANDED_OVER, announcement.getId());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of(bidHandedOver));

            assertYadonyError(
                    () -> announcementService.markArrived(announcement.getId(), FIREBASE_UID, null),
                    "trip/not-all-in-transit");
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("markArrived — refuse si aucun colis actif")
        void markArrived_noActiveParcel_throws() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of());

            assertYadonyError(
                    () -> announcementService.markArrived(announcement.getId(), FIREBASE_UID, null),
                    "trip/no-active-parcel");
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("markArrived — refuse si l'appelant n'est pas le voyageur propriétaire")
        void markArrived_notOwner_throws() {
            UserEntity traveler = buildTraveler();
            UserEntity someoneElse = buildTraveler();
            setId(someoneElse, UUID.randomUUID());
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(someoneElse));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));

            assertYadonyError(
                    () -> announcementService.markArrived(announcement.getId(), FIREBASE_UID, null),
                    "forbidden");
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("markArrived — refuse si le trajet n'existe pas")
        void markArrived_announcementNotFound_throws() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertYadonyError(
                    () -> announcementService.markArrived(ANNOUNCEMENT_ID, FIREBASE_UID, null),
                    "announcement-not-found");
        }

        @Test
        @DisplayName("markArrived — refuse si l'utilisateur n'existe pas")
        void markArrived_userNotFound_throws() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertYadonyError(
                    () -> announcementService.markArrived(ANNOUNCEMENT_ID, FIREBASE_UID, null),
                    "user-not-found");
        }
    }

    // ─── updateArrivalInstructions ─────────────────────────────────────────────

    @Nested
    @DisplayName("updateArrivalInstructions()")
    class UpdateArrivalInstructionsTests {

        @Test
        @DisplayName("updateArrivalInstructions — met à jour le texte tant qu'un colis actif reste")
        void updateArrivalInstructions_success() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            BidEntity bidArrived = buildBid(BidStatus.ARRIVED, announcement.getId());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of(bidArrived));
            when(bidRepository.countVisibleByAnnouncementId(announcement.getId())).thenReturn(1L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(eq(announcement.getId()), anyList())).thenReturn(0L);
            when(bidRepository.existsByAnnouncementIdAndStatusIn(
                    announcement.getId(), List.of(BidStatus.ARRIVED, BidStatus.COMPLETED)))
                    .thenReturn(true);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            announcementService.updateArrivalInstructions(announcement.getId(), FIREBASE_UID, "Nouveau point de RDV");

            assertThat(announcement.getArrivalInstructions()).isEqualTo("Nouveau point de RDV");
        }

        /** Régression I5 : la seule garde était « au moins un colis actif », donc un
         *  voyageur pouvait publier des instructions de retrait à ses expéditeurs alors
         *  que les colis sont encore ACCEPTED/IN_TRANSIT — trajet pas encore arrivé. */
        @Test
        @DisplayName("régression I5 — updateArrivalInstructions refuse si aucun colis n'est encore arrivé")
        void updateArrivalInstructions_notArrivedYet_throws() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            BidEntity bidInTransit = buildBid(BidStatus.IN_TRANSIT, announcement.getId());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of(bidInTransit));
            when(bidRepository.existsByAnnouncementIdAndStatusIn(
                    announcement.getId(), List.of(BidStatus.ARRIVED, BidStatus.COMPLETED)))
                    .thenReturn(false);

            assertYadonyError(
                    () -> announcementService.updateArrivalInstructions(
                            announcement.getId(), FIREBASE_UID, "Devant la gare"),
                    "trip/not-arrived-yet");
            verify(announcementRepository, never()).save(any());
        }

        /** Régression I5 : un trajet partiellement soldé (un colis livré, un autre encore
         *  ARRIVED) reste éditable — COMPLETED compte comme « arrivé ou au-delà ». */
        @Test
        @DisplayName("régression I5 — updateArrivalInstructions accepté si un colis est déjà COMPLETED")
        void updateArrivalInstructions_completedBidCountsAsArrived() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            BidEntity bidArrived = buildBid(BidStatus.ARRIVED, announcement.getId());
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of(bidArrived));
            when(bidRepository.countVisibleByAnnouncementId(announcement.getId())).thenReturn(1L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(eq(announcement.getId()), anyList())).thenReturn(0L);
            when(bidRepository.existsByAnnouncementIdAndStatusIn(
                    announcement.getId(), List.of(BidStatus.ARRIVED, BidStatus.COMPLETED)))
                    .thenReturn(true);
            when(announcementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            announcementService.updateArrivalInstructions(announcement.getId(), FIREBASE_UID, "Hall B");

            assertThat(announcement.getArrivalInstructions()).isEqualTo("Hall B");
        }

        @Test
        @DisplayName("updateArrivalInstructions — refuse si le trajet est totalement livré")
        void updateArrivalInstructions_alreadyDelivered_throws() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));
            when(bidRepository.findByAnnouncementIdAndStatusNotIn(eq(announcement.getId()), anyCollection()))
                    .thenReturn(List.of());

            assertYadonyError(
                    () -> announcementService.updateArrivalInstructions(announcement.getId(), FIREBASE_UID, "x"),
                    "trip/already-delivered");
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateArrivalInstructions — refuse si l'appelant n'est pas le voyageur propriétaire")
        void updateArrivalInstructions_notOwner_throws() {
            UserEntity traveler = buildTraveler();
            UserEntity someoneElse = buildTraveler();
            setId(someoneElse, UUID.randomUUID());
            AnnouncementEntity announcement = buildAnnouncement(traveler);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(someoneElse));
            when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));

            assertYadonyError(
                    () -> announcementService.updateArrivalInstructions(announcement.getId(), FIREBASE_UID, "x"),
                    "forbidden");
            verify(announcementRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateArrivalInstructions — refuse si le trajet n'existe pas")
        void updateArrivalInstructions_announcementNotFound_throws() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            when(announcementRepository.findByIdForUpdate(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

            assertYadonyError(
                    () -> announcementService.updateArrivalInstructions(ANNOUNCEMENT_ID, FIREBASE_UID, "x"),
                    "announcement-not-found");
        }

        @Test
        @DisplayName("updateArrivalInstructions — refuse si l'utilisateur n'existe pas")
        void updateArrivalInstructions_userNotFound_throws() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertYadonyError(
                    () -> announcementService.updateArrivalInstructions(ANNOUNCEMENT_ID, FIREBASE_UID, "x"),
                    "user-not-found");
        }
    }

    /** Régression : jumeau de C2 pour le chemin scheduler/inline (triggerInProgressTransitions).
     *  Avant le fix, applyInProgressTransition interrogeait (ACCEPTED, HANDED_OVER, IN_TRANSIT)
     *  sans ARRIVED : un trajet déjà parti dont tous les colis étaient ARRIVED (retirés du
     *  vol mais pas encore livrés) était forcé à COMPLETED avec l'audit
     *  DEPARTURE_NO_ACCEPTED_BIDS, alors que la livraison n'avait pas eu lieu. */
    @Test
    @DisplayName("régression : trigger inline in-progress — un colis ARRIVED empêche la complétion forcée au départ")
    void triggerInProgressTransitions_arrivedBid_doesNotForceCompletion() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity announcement = buildAnnouncement(traveler);
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setDepartureDate(LocalDate.now().minusDays(1));

        when(announcementRepository.findActiveOrFullDepartingOnOrBefore(any()))
                .thenReturn(List.of(announcement));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(ANNOUNCEMENT_ID),
                argThat(statuses -> statuses != null && statuses.contains(BidStatus.ARRIVED))))
                .thenReturn(true);

        announcementService.triggerInProgressTransitions();

        assertThat(announcement.getStatus()).isNotEqualTo(AnnouncementStatus.COMPLETED);
        verify(announcementRepository, never()).save(argThat(a ->
                a != null && a.getStatus() == AnnouncementStatus.COMPLETED));
        verify(bidRepository).existsByAnnouncementIdAndStatusIn(eq(ANNOUNCEMENT_ID),
                argThat(statuses -> statuses.contains(BidStatus.ARRIVED)));
    }

    // ─── Drapeau « négociable » (Task 8) ────────────────────────────────────

    @Nested
    @DisplayName("drapeau negotiable")
    class Negotiable {

        private AnnouncementRequest requestWithNegotiable(Boolean negotiable) {
            LocalDate departure = LocalDate.now().plusDays(10);
            return new AnnouncementRequest(
                    "Paris", "Dakar",
                    departure,
                    LocalTime.of(10, 0), LocalTime.of(22, 0),
                    new AddressDto("CDG Terminal 2E", 49.009, 2.547),
                    new AddressDto("Aéroport LSS", 14.739, -17.490),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                    TransportMode.PLANE,
                    null, null, null, null, null, null,
                    null, null,
                    departure.atTime(9, 0),
                    null,
                    negotiable
            );
        }

        private ArgumentCaptor<AnnouncementEntity> stubCreate() {
            UserEntity traveler = buildTraveler();
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(traveler));
            ArgumentCaptor<AnnouncementEntity> captor = ArgumentCaptor.forClass(AnnouncementEntity.class);
            when(announcementRepository.save(captor.capture())).thenAnswer(inv -> {
                AnnouncementEntity a = inv.getArgument(0);
                setId(a, ANNOUNCEMENT_ID);
                return a;
            });
            when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);
            when(bidRepository.countByAnnouncementIdAndStatusIn(any(), any())).thenReturn(0L);
            return captor;
        }

        @Test
        @DisplayName("negotiable = true → persisté sur l'annonce et rendu dans la réponse")
        void create_withNegotiableTrue_persistsFlag() {
            ArgumentCaptor<AnnouncementEntity> captor = stubCreate();

            AnnouncementResponse response =
                    announcementService.createAnnouncement(FIREBASE_UID, requestWithNegotiable(true));

            assertThat(captor.getValue().isNegotiable()).isTrue();
            assertThat(response.negotiable()).isTrue();
        }

        @Test
        @DisplayName("champ absent → le trajet reste à prix ferme")
        void create_withoutNegotiable_defaultsToFalse() {
            ArgumentCaptor<AnnouncementEntity> captor = stubCreate();

            AnnouncementResponse response =
                    announcementService.createAnnouncement(FIREBASE_UID, requestWithNegotiable(null));

            assertThat(captor.getValue().isNegotiable()).isFalse();
            assertThat(response.negotiable()).isFalse();
        }

        /**
         * Le drapeau doit voyager jusqu'à CHAQUE surface que l'expéditeur consulte.
         * Exposé sur la seule {@code AnnouncementResponse} (réponse de création et
         * « Mes trajets », deux écrans du VOYAGEUR), il reste invisible de l'expéditeur :
         * le bouton « Proposer un prix » ne s'affiche alors nulle part et la
         * négociation est morte côté produit.
         */
        @Test
        @DisplayName("le détail du trajet porte le drapeau (écran où l'expéditeur propose)")
        void detail_exposesNegotiable() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            a.setNegotiable(true);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result =
                    announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.negotiable()).isTrue();
        }

        @Test
        @DisplayName("le détail d'un trajet à prix ferme rend false")
        void detail_exposesNegotiableFalse() {
            UserEntity traveler = buildTraveler();
            AnnouncementEntity a = buildAnnouncement(traveler);
            when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
            when(bidRepository.countVisibleByAnnouncementId(ANNOUNCEMENT_ID)).thenReturn(0L);

            AnnouncementDetailResponse result =
                    announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, FIREBASE_UID);

            assertThat(result.negotiable()).isFalse();
        }

        @Test
        @DisplayName("la vue compacte du profil voyageur porte le drapeau")
        void travelerAnnouncements_exposeNegotiable() {
            UUID travelerId = UUID.randomUUID();
            UserEntity traveler = buildTraveler();
            setId(traveler, travelerId);
            AnnouncementEntity active = buildAnnouncement(traveler);
            active.setStatus(AnnouncementStatus.ACTIVE);
            active.setNegotiable(true);

            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.ACTIVE), any()))
                    .thenReturn(new PageImpl<>(List.of(active)));
            when(announcementRepository.findByTravelerIdAndStatus(eq(travelerId), eq(AnnouncementStatus.FULL), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            var result = announcementService.getTravelerAnnouncements(travelerId);

            assertThat(result.get(0).negotiable()).isTrue();
        }
    }
}
