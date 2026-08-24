package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PackageRequestCompleteDetailsRequest;
import com.yadony.api.requests.dto.PackageRequestCreateRequest;
import com.yadony.api.requests.entity.*;
import com.yadony.api.requests.event.NegotiationCancelledEvent;
import com.yadony.api.requests.event.PackageRequestCreatedEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageRequestServiceTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.yadony.api.city.CityRepository cityRepository;
    @Mock private com.yadony.api.payments.cash.CommissionProperties commissionProperties;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        org.mockito.Mockito.lenient()
                .when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }
    @Mock private com.yadony.api.matching.MatchingService matchingService;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    /** Real record (not mocked) — threshold-days=3 mirrors application-test.yml (yadony.urgency.threshold-days). */
    private final YadonyConfigProperties yadonyConfig =
            new YadonyConfigProperties(null, null, new YadonyConfigProperties.Urgency(3), null);
    private PackageRequestService service;

    private UserEntity sender;
    private final UUID SENDER_ID = UUID.randomUUID();

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

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        sender.setKycStatus(KycStatus.VERIFIED);
        // Default commission rate = 12% — lenient because only create-related tests use it
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        // Pass-through for presigned avatar URLs
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        // Aucune photo par défaut (les mappers appellent activePhotos ou activePhotosBatch)
        lenient().when(photoService.activePhotos(any())).thenReturn(List.of());
        lenient().when(photoService.activePhotosBatch(any())).thenReturn(java.util.Map.of());
        // Default batch stubs for search (batch API used by search/searchNearMe)
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(sender));
        lenient().when(cityRepository.findByNamesIgnoreCaseBatch(any())).thenReturn(java.util.Map.of());
        // Real mapper wired to the same mocks so SearchTests assertions remain valid
        PackageRequestSearchMapper realMapper = new PackageRequestSearchMapper(
                userRepository, cityRepository, storageService, photoService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                commissionProperties);
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, commissionProperties,
                storageService, photoService, favoriteRepository, activeCurrencyResolver, realMapper, matchingService,
                yadonyConfig, announcementRepository, commissionRateResolver);
    }

    // ========== Task 12: create() tests ==========

    @Nested @DisplayName("create() — happy path")
    class CreateHappyPath {
        @Test @DisplayName("création valide → persist + event PackageRequestCreatedEvent + audit log")
        void create_valid_persistsAndPublishesEvent() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });

            PackageRequestCreateRequest req = validRequest();
            var response = service.create(SENDER_ID, req);

            assertThat(response.status()).isEqualTo(PackageRequestStatus.OPEN);
            assertThat(response.departureCity()).isEqualTo("Paris");
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), any(UUID.class), eq("CREATED"), eq(SENDER_ID), anyMap());
        }

        @Test @DisplayName("création → attache les photoKeys via replacePhotos(reqId, sender, keys)")
        void create_attachesPhotoKeys() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            UUID reqId = UUID.randomUUID();
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, reqId);
                return e;
            });

            List<String> keys = List.of("package_requests/" + SENDER_ID + "/1.jpg",
                                        "package_requests/" + SENDER_ID + "/2.jpg");
            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("5"), "vetements", null, new BigDecimal("28.00"),
                null, null, null, true, EnumSet.of(PaymentMethod.STRIPE), keys, null);

            service.create(SENDER_ID, req);

            verify(photoService).replacePhotos(reqId, SENDER_ID, keys);
        }

        @Test @DisplayName("promoCode fourni → persisté normalisé (trim + upper), validation différée au paiement")
        void create_withPromoCode_persistsNormalized() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("5"), "vetements", null, new BigDecimal("28.00"),
                null, null, null, true, EnumSet.of(PaymentMethod.STRIPE), List.of(),
                null, " welcome6 ");

            service.create(SENDER_ID, req);

            ArgumentCaptor<PackageRequestEntity> captor = ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getPromoCode()).isEqualTo("WELCOME6");
        }

        @Test @DisplayName("promoCode absent → null persisté (pas de code promo requis)")
        void create_withoutPromoCode_persistsNull() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });

            service.create(SENDER_ID, validRequest());

            ArgumentCaptor<PackageRequestEntity> captor = ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getPromoCode()).isNull();
        }
    }

    @Nested @DisplayName("quote() — devis budget transparent (avant tout voyageur)")
    class QuoteTests {
        @Test @DisplayName("sans promo — commission au taux de base, net dérivé du budget")
        void quote_noPromo_baseRate() {
            when(commissionRateResolver.resolve(isNull(), eq(SENDER_ID))).thenReturn(new BigDecimal("0.05"));

            var quote = service.quote(SENDER_ID, new BigDecimal("40.00"), null);

            assertThat(quote.totalEur()).isEqualByComparingTo("40.00");
            assertThat(quote.rate()).isEqualByComparingTo("0.05");
            assertThat(quote.commissionEur()).isEqualByComparingTo("1.90");
            assertThat(quote.netEur()).isEqualByComparingTo("38.10");
            assertThat(quote.promoApplied()).isFalse();
        }

        @Test @DisplayName("promo valide — commission toujours au taux de base, net du voyageur augmente")
        void quote_validPromo_increasesNet() {
            when(commissionRateResolver.resolve(isNull(), eq(SENDER_ID))).thenReturn(new BigDecimal("0.12"));
            when(commissionRateResolver.resolve(isNull(), eq(SENDER_ID), eq("WELCOME6")))
                .thenReturn(new BigDecimal("0.06"));

            var quote = service.quote(SENDER_ID, new BigDecimal("40.00"), "WELCOME6");

            assertThat(quote.rate()).isEqualByComparingTo("0.12"); // base, jamais affecté
            assertThat(quote.commissionEur()).isEqualByComparingTo("4.29"); // 40 - 40/1.12
            assertThat(quote.totalEur()).isEqualByComparingTo("40.00"); // budget fixe
            assertThat(quote.netEur()).isEqualByComparingTo("37.74"); // 40/1.06 > 40/1.12
            assertThat(quote.promoApplied()).isTrue();
            assertThat(quote.promoLabel()).contains("6 % de réduction");
        }

        @Test @DisplayName("promo invalide — propage l'exception")
        void quote_invalidPromo_propagates() {
            when(commissionRateResolver.resolve(isNull(), eq(SENDER_ID), eq("BADCODE")))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                    HttpStatus.NOT_FOUND, "promo-not-found", "Promo Not Found", "Introuvable"));

            assertThatThrownBy(() -> service.quote(SENDER_ID, new BigDecimal("40.00"), "BADCODE"))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
        }
    }

    @Nested @DisplayName("create() — validation errors")
    class CreateValidationErrors {
        @Test @DisplayName("KYC non vérifié → 403")
        void create_kycNotVerified_throws403() {
            sender.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.create(SENDER_ID, validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kyc/not-verified");
        }

        @Test @DisplayName("departure == arrival → 422")
        void create_sameCorridor_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Paris",
                LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE)
            , List.of(), null);

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid-corridor");
        }

        @Test @DisplayName("limite atteinte (10 requests OPEN) → 409")
        void create_atOpenLimit_throws409() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(10L);

            assertThatThrownBy(() -> service.create(SENDER_ID, validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-open-reached");
        }

        @Test @DisplayName("acceptedPaymentMethods contient WAVE → 422 mobile money retiré")
        void create_waveAccepted_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Dakar",
                LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.WAVE)
            , List.of(), null);

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mobile-money-payment-retired");
        }

        @Test @DisplayName("acceptedPaymentMethods contient ORANGE_MONEY → 422 mobile money retiré")
        void create_orangeMoneyAccepted_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Dakar",
                LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.ORANGE_MONEY)
            , List.of(), null);

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mobile-money-payment-retired");
        }

        @Test @DisplayName("desired_date > 90j → 422")
        void create_desiredDateTooFar_throws422() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Dakar",
                LocalDate.now().plusDays(95), 2,
                new BigDecimal("5"), "vetements",
                null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE)
            , List.of(), null);

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-too-far");
        }

        @Test @DisplayName("photoUrl legacy = URL absolue → 422 (anti-injection contenu externe)")
        void create_photoUrlAbsolute_throws422() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            // photoUrl (arg 9) = URL externe arbitraire : doit être rejetée avant
            // persistance (sinon pixel de tracking / phishing affiché dans le feed).
            PackageRequestCreateRequest req = new PackageRequestCreateRequest(
                "Paris", "Dakar",
                LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements",
                "desc", new BigDecimal("30.00"), "https://evil.example/pixel.gif",
                "10e arr", "Plateau",
                true, EnumSet.of(PaymentMethod.STRIPE)
            , List.of(), null);

            assertThatThrownBy(() -> service.create(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid-photo-url");
            verify(repository, never()).save(any(PackageRequestEntity.class));
        }
    }

    // ========== Task 5: createAndReturnEntity() — derived size, forced PLANE, gross→net, negotiable ==========

    @Nested @DisplayName("createAndReturnEntity() — avion forcé, taille dérivée, gross→net, négociable")
    class CreateAndReturnEntityTests {

        @Test @DisplayName("23kg → LARGE, transport=PLANE, net=gross/1.12, negotiable propagé")
        void create_derivesSize_forcesAvion_storesNetFromGross() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("23"), "Médicaments", "desc",
                new BigDecimal("39.20"), null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of(), null);

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, req);

            assertThat(saved.getParcelSize()).isEqualTo(ParcelSize.LARGE);   // 23 kg → LARGE
            assertThat(saved.getTransportMode()).isEqualTo(TransportMode.PLANE);
            assertThat(saved.getTargetPriceEur()).isEqualByComparingTo("35.00"); // 39.20 / 1.12
            assertThat(saved.isNegotiable()).isTrue();
        }

        @Test @DisplayName("devise business CAD de l'expéditeur → assignée à la demande")
        void create_assignsCurrencyFromSenderBusinessPrefs() {
            UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
            prefs.setUserId(SENDER_ID);
            prefs.setCurrencyCode("CAD");
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(activeCurrencyResolver.resolve(SENDER_ID)).thenReturn(prefs.getCurrencyCode());
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, validRequest());

            assertThat(saved.getCurrency()).isEqualTo("CAD");
            verify(activeCurrencyResolver).resolve(SENDER_ID);
        }

        @Test @DisplayName("sans business prefs → fallback EUR après lookup repository")
        void create_defaultsToEurWhenSenderHasNoBusinessPrefs() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(activeCurrencyResolver.resolve(SENDER_ID)).thenReturn("EUR");
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, validRequest());

            assertThat(saved.getCurrency()).isEqualTo("EUR");
            verify(activeCurrencyResolver).resolve(SENDER_ID);
        }

        @Test @DisplayName("devise choisie explicitement (USD) → prévaut sur celle du portefeuille")
        void create_explicitCurrency_overridesResolvedCurrency() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, requestWithCurrency("USD"));

            assertThat(saved.getCurrency()).isEqualTo("USD");
            verify(activeCurrencyResolver, never()).resolve(any());
        }

        @Test @DisplayName("devise absente de la requête → repli sur ActiveCurrencyResolver (comportement historique)")
        void create_noCurrencyInRequest_fallsBackToResolver() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(activeCurrencyResolver.resolve(SENDER_ID)).thenReturn("CAD");
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, requestWithCurrency(null));

            assertThat(saved.getCurrency()).isEqualTo("CAD");
            verify(activeCurrencyResolver).resolve(SENDER_ID);
        }

        @Test @DisplayName("devise hors catalogue → 422 currency-unsupported")
        void create_unsupportedCurrency_throws422() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);

            assertThatThrownBy(() -> service.createAndReturnEntity(SENDER_ID, requestWithCurrency("JPY")))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((com.yadony.api.common.YadonyBusinessException) ex).getErrorCode())
                    .isEqualTo("currency-unsupported"));

            verify(repository, never()).save(any());
        }

        // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
        // legacy, la demande doit être persistée avec le libellé canonique.
        @Test @DisplayName("contentCategory legacy ('Hi-fi') → persisté normalisé ('Téléphone & électronique')")
        void create_legacyContentCategory_isNormalizedOnWrite() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("5"), "Hi-fi, Téléphone", "desc",
                new BigDecimal("28.00"), null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            PackageRequestEntity saved = service.createAndReturnEntity(SENDER_ID, req);

            assertThat(saved.getContentCategory()).isEqualTo("Téléphone & électronique");
        }

        @Test @DisplayName("budget null + non négociable → 422 target-price-required")
        void create_firmPrice_requiresBudget() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("6"), "Médicaments", null,
                null /* pas de budget */, null, null, null,
                false /* non négociable */, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            assertThatThrownBy(() -> service.createAndReturnEntity(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required");
        }

        @Test @DisplayName("budget null + négociable → 422 target-price-required")
        void create_negotiablePrice_requiresBudget() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(5), 2,
                new BigDecimal("6"), "Médicaments", null,
                null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            assertThatThrownBy(() -> service.createAndReturnEntity(SENDER_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required");
        }
    }

    // ========== Task 13: getById() / findMine() / cancel() / completeDetails() tests ==========

    @Nested @DisplayName("getById() — ownership")
    class GetByIdTests {

        @Test @DisplayName("sender owner → OK")
        void getById_owner_returnsResponse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);

            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(SENDER_ID, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("photos non vides → photos[] présignées + photoUrl = 1ère")
        void getById_withPhotos_exposesPhotosAndDerivesPhotoUrl() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(photoService.activePhotos(entity.getId())).thenReturn(List.of(
                new com.yadony.api.requests.dto.PackageRequestPhotoResponse(UUID.randomUUID(), "package_requests/s/1.jpg", "https://signed/1"),
                new com.yadony.api.requests.dto.PackageRequestPhotoResponse(UUID.randomUUID(), "package_requests/s/2.jpg", "https://signed/2")));

            var resp = service.getById(SENDER_ID, entity.getId());

            assertThat(resp.photos()).hasSize(2);
            assertThat(resp.photoUrl()).isEqualTo("https://signed/1");
        }

        @Test @DisplayName("voyageur avec offre active → viewerThreadId + statut exposés")
        void getById_travelerWithActiveThread_exposesViewerThread() {
            UUID traveler = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setId(thread, threadId);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepository.findActiveByPackageRequestIdAndTravelerId(entity.getId(), traveler))
                .thenReturn(Optional.of(thread));

            var resp = service.getById(traveler, entity.getId());

            assertThat(resp.viewerThreadId()).isEqualTo(threadId);
            assertThat(resp.viewerThreadStatus()).isEqualTo("OPEN");
        }

        @Test @DisplayName("propriétaire → pas de viewerThreadId")
        void getById_owner_noViewerThread() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(SENDER_ID, entity.getId());

            assertThat(resp.viewerThreadId()).isNull();
        }

        /**
         * Contrepartie du masquage de {@code promoCode} : il ne doit toucher QUE les tiers.
         *
         * <p>Sans ce test, quelqu'un qui durcirait le masquage demain casserait l'écran
         * d'édition du propriétaire, dont le formulaire se pré-remplit avec ce code, sans que
         * rien ne l'en avertisse. Les tests de non-fuite ne vérifient que l'absence.
         *
         * <p>Écrit ici et non dans {@code PackageRequestControllerIT} : ce dernier pose un
         * {@code @MockBean PackageRequestService}, donc {@code toResponse} n'y tourne jamais et
         * un test y aurait vérifié un mock. Ici le vrai service s'exécute.
         */
        @Test @DisplayName("propriétaire → reçoit toujours son propre promoCode")
        void getById_owner_stillReceivesOwnPromoCode() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setPromoCode("BIENVENUE10");
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(SENDER_ID, entity.getId());

            assertThat(resp.promoCode())
                .as("le code pré-remplit l'édition de son propriétaire, il doit lui rester servi")
                .isEqualTo("BIENVENUE10");
        }

        /** Le pendant négatif, au même endroit : un tiers ne l'obtient jamais. */
        @Test @DisplayName("voyageur tiers → promoCode masqué")
        void getById_thirdParty_promoCodeHidden() {
            UUID autre = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setPromoCode("BIENVENUE10");
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var resp = service.getById(autre, entity.getId());

            assertThat(resp.promoCode())
                .as("un code à usages comptés ne suit pas la consultation d'une demande")
                .isNull();
        }

        @Test @DisplayName("non-participant, demande OPEN → OK (consultable publiquement)")
        void getById_nonParticipant_openRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("non-participant, demande NEGOTIATING → OK (consultable publiquement)")
        void getById_nonParticipant_negotiatingRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.NEGOTIATING);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }

        @Test @DisplayName("non-participant, demande ACCEPTED (non listée) → 403")
        void getById_nonParticipant_acceptedRequest_throws403() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(false);

            assertThatThrownBy(() -> service.getById(OTHER, entity.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test @DisplayName("participant d'un thread, demande ACCEPTED → OK")
        void getById_threadParticipant_acceptedRequest_returnsResponse() {
            UUID OTHER = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(entity.getId(), OTHER))
                .thenReturn(true);

            var resp = service.getById(OTHER, entity.getId());
            assertThat(resp.id()).isEqualTo(entity.getId());
        }
    }

    @Nested @DisplayName("getById() — brouillon")
    class GetByIdDraft {

        private PackageRequestEntity draftOwnedBySender(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.DRAFT);
            return e;
        }

        @Test @DisplayName("le propriétaire voit son brouillon")
        void getById_owner_seesDraft() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draftOwnedBySender(id)));

            assertThatCode(() -> service.getById(SENDER_ID, id)).doesNotThrowAnyException();
        }

        @Test @DisplayName("un tiers reçoit 404, pas 403")
        void getById_stranger_throws404() {
            UUID id = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(draftOwnedBySender(id)));
            when(threadRepository.existsByPackageRequestIdAndTravelerId(id, stranger))
                .thenReturn(false);

            // 403 révélerait qu'une demande existe derrière cet id.
            assertThatThrownBy(() -> service.getById(stranger, id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/not-found");
        }
    }

    @Nested @DisplayName("update() — édition tant qu'aucun accord")
    class UpdateTests {

        @Test @DisplayName("OPEN → met à jour les champs, reste OPEN, audit UPDATED")
        void update_open_updatesAndStaysOpen() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(entity.getId())).thenReturn(List.of());
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Lyon", "Bamako", LocalDate.now().plusDays(10), 3,
                new BigDecimal("8"), "electronique", "desc",
                new BigDecimal("56.00"), null, "7e", "ACI 2000",
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of(), null);

            var resp = service.update(SENDER_ID, entity.getId(), req);

            assertThat(entity.getDepartureCity()).isEqualTo("Lyon");
            assertThat(entity.getArrivalCity()).isEqualTo("Bamako");
            assertThat(entity.getWeightKg()).isEqualByComparingTo("8");
            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            // net = 56 / 1.12 = 50.00
            assertThat(entity.getTargetPriceEur()).isEqualByComparingTo("50.00");
            assertThat(resp.id()).isEqualTo(entity.getId());
            verify(repository).save(entity);
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(entity.getId()),
                eq("UPDATED"), eq(SENDER_ID), any());
        }

        // C2 : normalisation à l'écriture — s'applique aussi à update().
        @Test @DisplayName("update() : contentCategory legacy ('Hi-fi') → persisté normalisé")
        void update_legacyContentCategory_isNormalizedOnWrite() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(entity.getId())).thenReturn(List.of());
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCreateRequest(
                "Lyon", "Bamako", LocalDate.now().plusDays(10), 3,
                new BigDecimal("8"), "Hi-fi, Téléphone", "desc",
                new BigDecimal("56.00"), null, "7e", "ACI 2000",
                true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of(), null);

            service.update(SENDER_ID, entity.getId(), req);

            assertThat(entity.getContentCategory()).isEqualTo("Téléphone & électronique");
        }

        @Test @DisplayName("NEGOTIATING → rejette les offres OPEN et repasse OPEN")
        void update_negotiating_rejectsOpenThreads() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.NEGOTIATING);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepository.findByPackageRequestId(entity.getId()))
                .thenReturn(List.of(thread));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            service.update(SENDER_ID, entity.getId(), validRequest());

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            verify(threadRepository).save(thread);
        }

        @Test @DisplayName("ACCEPTED → 409 not-editable")
        void update_accepted_throws409() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.ACCEPTED);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-editable");
        }

        @Test @DisplayName("autre que le propriétaire → 403")
        void update_notOwner_throws403() {
            UUID other = UUID.randomUUID();
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.update(other, entity.getId(), validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test @DisplayName("prix ferme sans budget → 422")
        void update_firmWithoutBudget_throws422() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", null, null, null, null, null,
                false, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required");
        }

        @Test @DisplayName("prix négociable sans budget → 422")
        void update_negotiableWithoutBudget_throws422() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var req = new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", null, null, null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-price-required");
        }

        @Test @DisplayName("corridor invalide (mêmes villes) → 422")
        void update_sameCorridor_throws422() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

            var req = new PackageRequestCreateRequest(
                "Paris", "Paris", LocalDate.now().plusDays(7), 2,
                new BigDecimal("5"), "vetements", null, new BigDecimal("28.00"),
                null, null, null, true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null);

            assertThatThrownBy(() -> service.update(SENDER_ID, entity.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid-corridor");
        }
    }

    @Nested @DisplayName("cancel() — soft delete + cascade threads")
    class CancelTests {
        @Test @DisplayName("status OPEN → soft delete + threads auto-rejected")
        void cancel_open_softDeletesAndCancelsThreads() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.OPEN);
            when(repository.findByIdForUpdate(reqId)).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(reqId)).thenReturn(List.of());

            service.cancel(SENDER_ID, reqId);

            assertThat(entity.getStatus()).isEqualTo(PackageRequestStatus.CANCELLED);
            verify(repository).save(entity);
        }

        @Test @DisplayName("status ACCEPTED → 409")
        void cancel_accepted_throws409() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findByIdForUpdate(reqId)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.cancel(SENDER_ID, reqId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already-accepted");
        }

        /**
         * La demande est soft-deletée donc introuvable ensuite : tout fil laissé
         * actif est perdu avec elle. Un voyageur en AWAITING_COMMISSION peut déjà
         * avoir payé sa commission — sans ce traitement il restait débité pour une
         * demande évaporée, sans notification, son trajet dédié bloqué en
         * « Mes trajets ».
         */
        @Test @DisplayName("fil en attente de commission → auto-rejeté, remboursement demandé, trajet dédié libéré")
        void cancel_awaitingCommissionThread_refundsAndCleansUp() {
            UUID reqId = UUID.randomUUID();
            UUID threadId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();

            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.NEGOTIATING);

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setId(thread, threadId);
            thread.setPackageRequestId(reqId);
            thread.setTravelerId(travelerId);
            thread.setTravelerAnnouncementId(announcementId);
            thread.setStatus(NegotiationThreadStatus.AWAITING_COMMISSION);

            AnnouncementEntity dedicated = new AnnouncementEntity();
            setId(dedicated, announcementId);
            dedicated.setLinkedPackageRequestId(reqId);

            when(repository.findByIdForUpdate(reqId)).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(reqId)).thenReturn(List.of(thread));
            when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(dedicated));

            service.cancel(SENDER_ID, reqId);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
            assertThat(dedicated.getDeletedAt()).isNotNull();

            ArgumentCaptor<NegotiationCancelledEvent> captor =
                ArgumentCaptor.forClass(NegotiationCancelledEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
            NegotiationCancelledEvent published = captor.getAllValues().stream()
                .filter(e -> e.threadId().equals(threadId))
                .findFirst()
                .orElseThrow();
            assertThat(published.refundCommission()).isTrue();
            assertThat(published.releaseEscrow()).isFalse();
            assertThat(published.toUserId()).isEqualTo(travelerId);
        }

        /**
         * Un fil déjà terminal ne doit pas être ressuscité en AUTO_REJECTED, ni
         * déclencher un remboursement pour une commission qui a déjà été soldée.
         */
        @Test @DisplayName("fil déjà terminal → laissé intact, aucun événement")
        void cancel_terminalThread_leftUntouched() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.NEGOTIATING);

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setId(thread, UUID.randomUUID());
            thread.setPackageRequestId(reqId);
            thread.setTravelerId(UUID.randomUUID());
            thread.setStatus(NegotiationThreadStatus.REJECTED);

            when(repository.findByIdForUpdate(reqId)).thenReturn(Optional.of(entity));
            when(threadRepository.findByPackageRequestId(reqId)).thenReturn(List.of(thread));

            service.cancel(SENDER_ID, reqId);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.REJECTED);
            verify(eventPublisher, never()).publishEvent(any(NegotiationCancelledEvent.class));
        }
    }

    @Nested @DisplayName("completeDetails() — post-acceptation")
    class CompleteDetailsTests {
        @Test @DisplayName("status ACCEPTED → renseigne recipient (name + phone + city)")
        void completeDetails_accepted_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCompleteDetailsRequest(
                "Marie", "+221771234567", "Dakar"
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.5");

            assertThat(entity.getRecipientName()).isEqualTo("Marie");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(entity.getRecipientCity()).isEqualTo("Dakar");
            // The entity had no disclaimerSignedAt (bare entity), so the defensive
            // branch signs it now using the client IP.
            assertThat(entity.getDisclaimerSignedAt()).isNotNull();
            assertThat(entity.getDisclaimerSignedIp()).isEqualTo("203.0.113.5");
        }

        @Test @DisplayName("status ACCEPTED + city null → succès, disclaimer déjà signé conservé")
        void completeDetails_accepted_nullCity_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.ACCEPTED);
            var signedAt = java.time.LocalDateTime.now().minusDays(1);
            entity.setDisclaimerSignedAt(signedAt); // signed at creation
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            var req = new PackageRequestCompleteDetailsRequest(
                "Fatou Diop", "+221771234567", null
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.5");

            assertThat(entity.getRecipientName()).isEqualTo("Fatou Diop");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221771234567");
            assertThat(entity.getRecipientCity()).isNull();
            // disclaimer was already signed at creation → not overwritten, no IP set
            assertThat(entity.getDisclaimerSignedAt()).isEqualTo(signedAt);
            assertThat(entity.getDisclaimerSignedIp()).isNull();
        }

        @Test @DisplayName("status OPEN → 409 not-yet-accepted")
        void completeDetails_open_throws409() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.OPEN);
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));

            var req = new PackageRequestCompleteDetailsRequest(
                "Z", "+221771234567", "Dakar"
            );

            assertThatThrownBy(() -> service.completeDetails(SENDER_ID, reqId, req, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-yet-accepted");
        }

        @Test @DisplayName("thread AWAITING_PAYMENT (avant paiement) → succès même si request pas ACCEPTED")
        void completeDetails_awaitingPaymentThread_persists() {
            UUID reqId = UUID.randomUUID();
            PackageRequestEntity entity = new PackageRequestEntity();
            setId(entity, reqId);
            entity.setSenderId(SENDER_ID);
            entity.setStatus(PackageRequestStatus.NEGOTIATING); // pas encore ACCEPTED
            when(repository.findById(reqId)).thenReturn(Optional.of(entity));
            when(repository.save(any(PackageRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            when(threadRepository.findByPackageRequestId(reqId))
                .thenReturn(List.of(thread));

            var req = new PackageRequestCompleteDetailsRequest(
                "Awa", "+221770000000", "Abobo"
            );

            service.completeDetails(SENDER_ID, reqId, req, "203.0.113.9");

            assertThat(entity.getRecipientName()).isEqualTo("Awa");
            assertThat(entity.getRecipientPhone()).isEqualTo("+221770000000");
            assertThat(entity.getRecipientCity()).isEqualTo("Abobo");
        }
    }

    @Nested @DisplayName("searchNearMe() — geo proximity filter")
    class SearchNearMeTests {

        @Test @DisplayName("filtre + tri par distance asc, exclut hors radius et coords inconnues")
        void searchNearMe_filtersAndSorts() {
            // 3 demandes : Paris (ref), Lyon, Marseille — viewer à Paris, radius 600 km → garde Paris (0km) + Lyon (~390km), exclut Marseille (~660km)
            PackageRequestEntity paris = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            paris.setDepartureCity("Paris");
            PackageRequestEntity lyon = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            lyon.setDepartureCity("Lyon");
            PackageRequestEntity marseille = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            marseille.setDepartureCity("Marseille");

            var page = new org.springframework.data.domain.PageImpl<>(List.of(marseille, lyon, paris));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

            com.yadony.api.city.CityEntity cityParis = cityWith(new BigDecimal("48.8566"), new BigDecimal("2.3522"));
            cityParis.setName("Paris");
            com.yadony.api.city.CityEntity cityLyon = cityWith(new BigDecimal("45.7640"), new BigDecimal("4.8357"));
            cityLyon.setName("Lyon");
            com.yadony.api.city.CityEntity cityMarseille = cityWith(new BigDecimal("43.2965"), new BigDecimal("5.3698"));
            cityMarseille.setName("Marseille");
            // Batch version used by searchNearMe now
            when(cityRepository.findByNamesIgnoreCaseBatch(any())).thenReturn(
                    java.util.Map.of("paris", cityParis, "lyon", cityLyon, "marseille", cityMarseille));

            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.searchNearMe(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                600.0,
                UUID.randomUUID()
            );

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).departureCity()).isEqualTo("Paris");
            assertThat(result.getContent().get(1).departureCity()).isEqualTo("Lyon");
        }

        @Test @DisplayName("toutes hors radius → page vide")
        void searchNearMe_allOutOfRadius_returnsEmpty() {
            PackageRequestEntity lyon = buildEntity(UUID.randomUUID(), PackageRequestStatus.OPEN);
            lyon.setDepartureCity("Lyon");
            var page = new org.springframework.data.domain.PageImpl<>(List.of(lyon));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);
            com.yadony.api.city.CityEntity cityLyon = cityWith(new BigDecimal("45.7640"), new BigDecimal("4.8357"));
            cityLyon.setName("Lyon");
            when(cityRepository.findByNamesIgnoreCaseBatch(any()))
                .thenReturn(java.util.Map.of("lyon", cityLyon));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.searchNearMe(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                10.0,
                UUID.randomUUID()
            );
            assertThat(result.getContent()).isEmpty();
        }

        private com.yadony.api.city.CityEntity cityWith(BigDecimal lat, BigDecimal lng) {
            com.yadony.api.city.CityEntity c = new com.yadony.api.city.CityEntity();
            c.setLatitude(lat);
            c.setLongitude(lng);
            return c;
        }
    }

    @Nested @DisplayName("search() — toSearchResponse propagation")
    class SearchTests {
        @Test @DisplayName("sender.ratingCount est propagé dans sender.totalRatings du SearchResponse")
        void search_propagatesSenderRatingCount() {
            sender.setRatingCount(7);
            sender.setAverageRating(new java.math.BigDecimal("4.30"));
            // Batch API used by search()
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            var sp = result.getContent().get(0).sender();
            assertThat(sp.totalRatings()).isEqualTo(7);
            assertThat(sp.averageRating()).isEqualTo(4.30);
            assertThat(sp.kycVerified()).isTrue();
            // negotiable is propagated from the entity (default true)
            assertThat(result.getContent().get(0).negotiable()).isTrue();
        }

        @Test @DisplayName("negotiable=false (demande à prix ferme) est propagé dans le SearchResponse")
        void search_propagatesFirmPriceNegotiableFalse() {
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setNegotiable(false);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).negotiable()).isFalse();
        }

        @Test @DisplayName("acceptedPaymentMethods est propagé dans le SearchResponse")
        void search_propagatesAcceptedPaymentMethods() {
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));

            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setAcceptedPaymentMethods(java.util.Set.of(
                com.yadony.api.payments.cash.PaymentMethod.STRIPE,
                com.yadony.api.payments.cash.PaymentMethod.CASH));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).acceptedPaymentMethods())
                .containsExactlyInAnyOrder(
                    com.yadony.api.payments.cash.PaymentMethod.STRIPE,
                    com.yadony.api.payments.cash.PaymentMethod.CASH);
        }

        /**
         * Tâche 10 : le fil « demandes » n'est plus cloisonné par devise, comme celui des
         * annonces. Un lecteur résolu en EUR reçoit désormais une demande publiée en XOF,
         * sans qu'aucun filtre de devise n'intervienne (la méthode {@code hasCurrency} a
         * d'ailleurs été supprimée de {@code PackageRequestSpecifications}, faute
         * d'appelant, en même temps que ce filtre).
         */
        @Test @DisplayName("lecteur en EUR → voit aussi les demandes publiées en XOF (plus de filtre devise)")
        void search_readerInEur_seesRequestInXof() {
            UUID callerId = UUID.randomUUID();

            PackageRequestEntity xofRequest = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            xofRequest.setCurrency("XOF");
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(xofRequest)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                callerId
            );

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).currency()).isEqualTo("XOF");
        }

        @Test @DisplayName("N résultats → userRepository.findAllById appelé 1 fois, findById jamais")
        void search_nResults_onlyOneBatchUserQuery() {
            // Note: findAllById lenient default stub set in @BeforeEach
            PackageRequestEntity e1 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            PackageRequestEntity e2 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(e1, e2)));

            service.search(org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20), null);

            verify(userRepository, times(1)).findAllById(anyCollection());
            verify(userRepository, never()).findById(any());
        }

        @Test @DisplayName("N résultats → cityRepository.findByNamesIgnoreCaseBatch appelé 1 fois")
        void search_nResults_onlyOneBatchCityQuery() {
            // Note: findByNamesIgnoreCaseBatch lenient default stub set in @BeforeEach
            PackageRequestEntity e1 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            PackageRequestEntity e2 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(e1, e2)));

            service.search(org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20), null);

            verify(cityRepository, times(1)).findByNamesIgnoreCaseBatch(anyCollection());
            verify(cityRepository, never()).findFirstByNameIgnoreCase(anyString());
        }

        @Test @DisplayName("N résultats → photoService.activePhotosBatch appelé 1 fois, activePhotos jamais")
        void search_nResults_onlyOneBatchPhotoQuery() {
            // Note: activePhotosBatch lenient default stub set in @BeforeEach
            PackageRequestEntity e1 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            PackageRequestEntity e2 = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(e1, e2)));

            service.search(org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20), null);

            verify(photoService, times(1)).activePhotosBatch(anyCollection());
            verify(photoService, never()).activePhotos(any());
        }

        // ─── urgent field (Task 3) ──────────────────────────────────────────────

        @Test @DisplayName("desiredDate dans [today, today+seuil] → urgent=true")
        void search_desiredDateWithinThreshold_urgentTrue() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2)); // seuil de test = 3
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isTrue();
        }

        @Test @DisplayName("desiredDate au-delà de today+seuil → urgent=false")
        void search_desiredDateBeyondThreshold_urgentFalse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(9)); // seuil de test = 3
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isFalse();
        }

        @Test @DisplayName("desiredDate = today (UTC) → urgent=true")
        void search_desiredDateToday_urgentTrue() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isTrue();
        }

        @Test @DisplayName("desiredDate = today+3 (borne exacte du seuil) → urgent=true")
        void search_desiredDateAtThresholdBoundary_urgentTrue() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isTrue();
        }

        @Test @DisplayName("desiredDate = today+4 (juste après le seuil) → urgent=false")
        void search_desiredDateJustBeyondThreshold_urgentFalse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(4));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isFalse();
        }

        @Test @DisplayName("desiredDate = today-1 (passé) → urgent=false")
        void search_desiredDateInPast_urgentFalse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1));
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isFalse();
        }

        @Test @DisplayName("desiredDate = null → urgent=false")
        void search_desiredDateNull_urgentFalse() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            entity.setDesiredDate(null);
            when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                                    any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var result = service.search(
                org.springframework.data.jpa.domain.Specification.where(null),
                org.springframework.data.domain.PageRequest.of(0, 20),
                SENDER_ID
            );

            assertThat(result.getContent().get(0).urgent()).isFalse();
        }
    }

    @Nested @DisplayName("search() — isFavorite flag")
    class SearchIsFavoriteTests {

        private PackageRequestEntity prepareEntity() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);
            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            // Batch API used by search()
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));
            return entity;
        }

        @Test @DisplayName("voyageur authentifié — demande en favori → isFavorite=true")
        void search_travelerWithFavorite_returnsTrueFlag() {
            PackageRequestEntity entity = prepareEntity();
            UUID travelerId = UUID.randomUUID();
            when(favoriteRepository.findTargetIds(travelerId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(entity.getId()));

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), travelerId);

            assertThat(result.getContent().get(0).isFavorite()).isTrue();
        }

        @Test @DisplayName("voyageur authentifié — demande non mise en favori → isFavorite=false")
        void search_travelerWithoutFavorite_returnsFalseFlag() {
            prepareEntity();
            UUID travelerId = UUID.randomUUID();
            when(favoriteRepository.findTargetIds(travelerId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of());

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), travelerId);

            assertThat(result.getContent().get(0).isFavorite()).isFalse();
        }

        @Test @DisplayName("appelant anonyme (callerId=null) → isFavorite=false, findTargetIds jamais appelé")
        void search_anonymousCaller_returnsFalseFlagWithoutDbCall() {
            prepareEntity();

            var result = service.search(null,
                org.springframework.data.domain.PageRequest.of(0, 20), null);

            assertThat(result.getContent().get(0).isFavorite()).isFalse();
            verify(favoriteRepository, never()).findTargetIds(any(), any());
        }
    }

    @Nested @DisplayName("findMine() — own requests pagination")
    class FindMineTests {
        @Test @DisplayName("returns paginated responses for sender")
        void findMine_returnsPage() {
            PackageRequestEntity entity = buildEntity(SENDER_ID, PackageRequestStatus.OPEN);

            var page = new org.springframework.data.domain.PageImpl<>(List.of(entity));
            when(repository.findBySenderIdOrderByCreatedAtDesc(eq(SENDER_ID), any()))
                .thenReturn(page);

            var result = service.findMine(SENDER_ID, org.springframework.data.domain.PageRequest.of(0, 20));
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).senderId()).isEqualTo(SENDER_ID);
        }

        @Test @DisplayName("lecture seule : ne répare plus une NEGOTIATING stale (délégué au scheduler)")
        void findMine_staleNegotiatingRequest_staysUntouched() {
            PackageRequestEntity entity = buildEntity(
                SENDER_ID, PackageRequestStatus.NEGOTIATING);
            when(repository.findBySenderIdOrderByCreatedAtDesc(eq(SENDER_ID), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));

            var result = service.findMine(
                SENDER_ID, org.springframework.data.domain.PageRequest.of(0, 20));

            assertThat(result.getContent().get(0).status())
                .isEqualTo(PackageRequestStatus.NEGOTIATING);
            verify(repository, never()).save(any());
            verifyNoInteractions(threadRepository);
        }

    }

    // ─── Shared helpers ─────────────────────────────────────────────────────────

    private PackageRequestEntity buildEntity(UUID senderId, PackageRequestStatus status) {
        PackageRequestEntity entity = new PackageRequestEntity();
        setId(entity, UUID.randomUUID());
        entity.setSenderId(senderId);
        entity.setDepartureCity("Paris");
        entity.setArrivalCity("Dakar");
        entity.setDesiredDate(LocalDate.now().plusDays(7));
        entity.setDateToleranceDays((short) 2);
        entity.setWeightKg(new BigDecimal("5"));
        entity.setParcelSize(ParcelSize.SMALL);
        entity.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        entity.setContentCategory("vetements");
        entity.setStatus(status);
        return entity;
    }

    private PackageRequestCreateRequest validRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau pour ma mère", new BigDecimal("28.00"), null,
            "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE)
        , List.of(), null);
    }

    private PackageRequestCreateRequest requestWithCurrency(String currency) {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau pour ma mère", new BigDecimal("28.00"), null,
            "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE), List.of(), null, null, currency);
    }

    // ========== AvatarUrl in SenderPublicProfile ==========

    @Nested @DisplayName("search() — SenderPublicProfile.avatarUrl")
    class SenderPublicProfileAvatarTests {

        private PackageRequestEntity buildEntity() {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, UUID.randomUUID());
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(5));
            e.setWeightKg(new BigDecimal("5"));
            e.setStatus(PackageRequestStatus.OPEN);
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            return e;
        }

        @Test @DisplayName("sender avec avatarUrl → SenderPublicProfile.avatarUrl propagé")
        void search_senderAvatarUrl_isMapped() {
            sender.setAvatarUrl("https://cdn.example.com/sender.jpg");
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isEqualTo("https://cdn.example.com/sender.jpg");
        }

        @Test @DisplayName("sender sans avatarUrl → SenderPublicProfile.avatarUrl null")
        void search_senderNoAvatarUrl_null() {
            // sender.avatarUrl is null by default
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            when(userRepository.findAllById(any())).thenReturn(List.of(sender));
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isNull();
        }

        @Test @DisplayName("sender introuvable → SenderPublicProfile.avatarUrl null")
        void search_senderNotFound_avatarUrlNull() {
            PackageRequestEntity entity = buildEntity();

            when(repository.findAll(
                    org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<PackageRequestEntity>>any(),
                    org.mockito.ArgumentMatchers.<org.springframework.data.domain.Pageable>any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entity)));
            // Batch returns empty — sender not found
            when(userRepository.findAllById(any())).thenReturn(List.of());
            when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

            var page = service.search(null, org.springframework.data.domain.PageRequest.of(0, 10), SENDER_ID);

            var result = page.getContent().get(0);
            assertThat(result.sender().avatarUrl()).isNull();
        }
    }

    // ========== Brouillons ==========

    /** Construit une requête de création valide ; saveAsDraft piloté par l'appelant. */
    private PackageRequestCreateRequest draftRequest(Boolean saveAsDraft) {
        return new PackageRequestCreateRequest(
                "Paris", "Dakar", LocalDate.now().plusDays(10), 2,
                new BigDecimal("3.0"), "Documents", null, new BigDecimal("30.00"), null, null, null,
                true, EnumSet.of(PaymentMethod.STRIPE), null, saveAsDraft);
    }

    @Nested @DisplayName("create() — brouillon")
    class CreateDraft {

        @BeforeEach
        void stubSave() {
            // lenient : create_asDraft_overLimit_throws403 lève 403 avant d'atteindre
            // repository.save(), ce qui rendrait ce stub "inutile" en strict-stubs.
            lenient().when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
                PackageRequestEntity e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });
        }

        @Test @DisplayName("saveAsDraft=true → statut DRAFT, aucun event, aucun disclaimer")
        void create_asDraft_doesNotPublish() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            service.create(SENDER_ID, draftRequest(true));

            ArgumentCaptor<PackageRequestEntity> captor =
                    ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            PackageRequestEntity saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
            // Le disclaimer douanier se signe à la publication, pas à la rédaction.
            assertThat(saved.getDisclaimerSignedAt()).isNull();
            // L'event déclenche les alertes corridor : le publier notifierait une
            // demande que personne ne peut voir.
            verify(eventPublisher, never()).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), any(), eq("DRAFT_CREATED"),
                    eq(SENDER_ID), any());
        }

        @Test @DisplayName("brouillon : KYC non vérifié accepté")
        void create_asDraft_doesNotRequireKyc() {
            sender.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            assertThatCode(() -> service.create(SENDER_ID, draftRequest(true)))
                    .doesNotThrowAnyException();
        }

        @Test @DisplayName("brouillon : ne compte pas dans maxOpenRequestsPerSender")
        void create_asDraft_ignoresOpenQuota() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(0L);

            service.create(SENDER_ID, draftRequest(true));

            verify(repository, never()).countBySenderIdAndStatusIn(any(), any());
        }

        @Test @DisplayName("limite de brouillons atteinte → 403 draft-limit-reached")
        void create_asDraft_overLimit_throws403() {
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            // maxDrafts() vaut 1 par défaut quand yadony.limits.drafts n'est pas configuré.
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                    .thenReturn(1L);

            assertThatThrownBy(() -> service.create(SENDER_ID, draftRequest(true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("draft-limit-reached");
        }

        @Test @DisplayName("saveAsDraft=null → publication directe (comportement historique)")
        void create_nullFlag_publishesDirectly() {
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);

            service.create(SENDER_ID, draftRequest(null));

            ArgumentCaptor<PackageRequestEntity> captor =
                    ArgumentCaptor.forClass(PackageRequestEntity.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
        }
    }

    @Nested @DisplayName("publish()")
    class Publish {

        private PackageRequestEntity draft(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setTargetPriceEur(new BigDecimal("30.00"));
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.DRAFT);
            return e;
        }

        @Test @DisplayName("DRAFT → OPEN + event + disclaimer signé + audit PUBLISHED")
        void publish_draft_becomesOpen() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(config.maxOpenRequestsPerSender()).thenReturn(10);
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.publish(SENDER_ID, id);

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            assertThat(e.getDisclaimerSignedAt()).isNotNull();
            verify(eventPublisher).publishEvent(any(PackageRequestCreatedEvent.class));
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(id), eq("PUBLISHED"),
                    eq(SENDER_ID), any());
        }

        @Test @DisplayName("non-propriétaire → 404 (ne révèle pas l'existence)")
        void publish_notOwner_throws404() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(draft(id)));

            assertThatThrownBy(() -> service.publish(UUID.randomUUID(), id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-found");
        }

        @Test @DisplayName("déjà publiée → 409 request/not-draft")
        void publish_alreadyOpen_throws409() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setStatus(PackageRequestStatus.OPEN);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/not-draft");
        }

        @Test @DisplayName("KYC non vérifié → 403 kyc/not-verified")
        void publish_kycNotVerified_throws403() {
            UUID id = UUID.randomUUID();
            sender.setKycStatus(KycStatus.PENDING);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(draft(id)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("kyc/not-verified");
        }

        @Test @DisplayName("date devenue trop lointaine → 422 request/date-too-far")
        void publish_dateTooFar_throws422() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setDesiredDate(LocalDate.now().plusDays(120));
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/date-too-far");
        }

        @Test @DisplayName("quota de demandes ouvertes atteint → 409 max-open-reached")
        void publish_overOpenQuota_throws409() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(draft(id)));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(config.maxOpenRequestsPerSender()).thenReturn(1);
            when(repository.countBySenderIdAndStatusIn(eq(SENDER_ID), any())).thenReturn(1L);

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/max-open-reached");
        }

        @Test @DisplayName("date déjà passée → 422 request/desired-date-in-past")
        void publish_desiredDateInPast_throws422() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setDesiredDate(LocalDate.now().minusDays(5));  // Passée de 5 jours
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/desired-date-in-past");
        }

        @Test @DisplayName("budget absent → 422 request/target-price-required")
        void publish_withoutBudget_throws422() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = draft(id);
            e.setTargetPriceEur(null);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.publish(SENDER_ID, id))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("request/target-price-required");
        }
    }

    @Nested @DisplayName("unpublish()")
    class Unpublish {

        private PackageRequestEntity openRequest(UUID id) {
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDesiredDate(LocalDate.now().plusDays(10));
            e.setDateToleranceDays((short) 2);
            e.setWeightKg(new BigDecimal("3.0"));
            e.setContentCategory("Documents");
            e.setNegotiable(true);
            e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            e.setStatus(PackageRequestStatus.OPEN);
            return e;
        }

        @Test @DisplayName("OPEN sans offre → DRAFT + audit UNPUBLISHED")
        void unpublish_openWithoutOffers_becomesDraft() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = openRequest(id);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                .thenReturn(0L);
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.unpublish(SENDER_ID, id);

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
            verify(auditService).log(eq("PACKAGE_REQUEST"), eq(id), eq("UNPUBLISHED"),
                eq(SENDER_ID), any());
        }

        @Test @DisplayName("au moins une offre → 409 request/has-offers")
        void unpublish_withOffers_throws409() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(openRequest(id)));
            when(threadRepository.findByPackageRequestId(id))
                .thenReturn(List.of(new NegotiationThreadEntity()));

            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/has-offers");
        }

        @Test @DisplayName("statut non OPEN → 409 request/not-unpublishable")
        void unpublish_notOpen_throws409() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = openRequest(id);
            e.setStatus(PackageRequestStatus.ACCEPTED);
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/not-unpublishable");
        }

        @Test @DisplayName("non-propriétaire → 403 request/forbidden")
        void unpublish_notOwner_throws403() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(openRequest(id)));

            assertThatThrownBy(() -> service.unpublish(UUID.randomUUID(), id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/forbidden");
        }

        @Test @DisplayName("quota de brouillons atteint → 403 draft-limit-reached")
        void unpublish_overDraftQuota_throws403() {
            UUID id = UUID.randomUUID();
            when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(openRequest(id)));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
            when(repository.countBySenderIdAndStatus(SENDER_ID, PackageRequestStatus.DRAFT))
                .thenReturn(1L);

            assertThatThrownBy(() -> service.unpublish(SENDER_ID, id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("draft-limit-reached");
        }
    }

    @Nested @DisplayName("update() — brouillon")
    class UpdateDraft {

        @Test @DisplayName("éditer un brouillon le laisse DRAFT")
        void update_draft_staysDraft() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setStatus(PackageRequestStatus.DRAFT);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.update(SENDER_ID, id, draftRequest(null));

            // Sans garde, update() posait OPEN en dur et publiait le brouillon en
            // silence — la demande devenait visible de tous à la première édition.
            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
        }

        @Test @DisplayName("éditer une demande en négociation la repasse OPEN")
        void update_negotiating_returnsToOpen() {
            UUID id = UUID.randomUUID();
            PackageRequestEntity e = new PackageRequestEntity();
            setId(e, id);
            e.setSenderId(SENDER_ID);
            e.setStatus(PackageRequestStatus.NEGOTIATING);
            when(repository.findById(id)).thenReturn(Optional.of(e));
            when(threadRepository.findByPackageRequestId(id)).thenReturn(List.of());
            when(repository.save(any(PackageRequestEntity.class))).thenAnswer(i -> i.getArgument(0));

            service.update(SENDER_ID, id, draftRequest(null));

            assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
        }
    }
}
