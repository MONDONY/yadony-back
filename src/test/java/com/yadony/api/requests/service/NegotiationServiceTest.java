package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.NegotiationStartRequest;
import com.yadony.api.requests.dto.NegotiationThreadResponse;
import com.yadony.api.requests.entity.*;
import com.yadony.api.requests.event.NegotiationStartedEvent;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.requests.event.NegotiationAwaitingTripEvent;
import com.yadony.api.requests.repository.*;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationServiceTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private UserBusinessPrefsRepository userBusinessPrefsRepository;
    @Mock private RequestsConfig config;
    @Mock private CommissionProperties commissionProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Spy private CurrencyMatchGuard currencyMatchGuard = new CurrencyMatchGuard();

    @InjectMocks private NegotiationService service;

    private final UUID SENDER_ID = UUID.randomUUID();
    private final UUID TRAVELER_ID = UUID.randomUUID();
    private final UUID REQUEST_ID = UUID.randomUUID();

    private PackageRequestEntity request;
    private UserEntity traveler;

    @BeforeEach
    void setup() {
        traveler = new UserEntity();
        traveler.setKycStatus(KycStatus.VERIFIED);
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        // Set id via reflection (BaseEntity has no public setId)
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(traveler, TRAVELER_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        request = new PackageRequestEntity();
        request.setSenderId(SENDER_ID);
        request.setStatus(PackageRequestStatus.OPEN);
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, REQUEST_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Default commission rate used whenever toResponse() is called.
        // Lenient to avoid UnnecessaryStubbingException in error-path tests that never reach toResponse().
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        // Pass-through for presigned avatar URLs
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userBusinessPrefsRepository.findById(any())).thenReturn(Optional.empty());
    }

    private UserBusinessPrefsEntity prefsWithCurrency(UUID userId, String code) {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(userId);
        prefs.setCurrencyCode(code);
        return prefs;
    }

    @Nested
    @DisplayName("start() — happy path")
    class StartHappyPath {
        @Test
        @DisplayName("traveler ouvre thread → status OPEN, rounds=1, message PROPOSAL, event")
        void start_valid_createsThreadWithProposalMessage() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
            when(threadRepo.save(any())).thenAnswer(inv -> {
                NegotiationThreadEntity t = inv.getArgument(0);
                try {
                    var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(t, UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return t;
            });

            var req = new NegotiationStartRequest(
                REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"),
                null, "Pas de problème"
            );
            var response = service.start(TRAVELER_ID, req);

            assertThat(response.status()).isEqualTo(NegotiationThreadStatus.OPEN);
            assertThat(response.roundsCount()).isEqualTo(1);
            assertThat(response.currentPriceEur()).isEqualByComparingTo("30");
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.PROPOSAL));
            verify(eventPublisher).publishEvent(any(NegotiationStartedEvent.class));
        }

        @Test
        @DisplayName("demande avec promoCode → copié sur le thread créé (auto-application au paiement)")
        void start_requestHasPromoCode_copiedOntoThread() {
            request.setPromoCode("WELCOME6");

            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
            when(threadRepo.save(any())).thenAnswer(inv -> {
                NegotiationThreadEntity t = inv.getArgument(0);
                try {
                    var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(t, UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return t;
            });

            var req = new NegotiationStartRequest(
                REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"),
                null, "Pas de problème"
            );
            service.start(TRAVELER_ID, req);

            ArgumentCaptor<NegotiationThreadEntity> captor =
                ArgumentCaptor.forClass(NegotiationThreadEntity.class);
            verify(threadRepo).save(captor.capture());
            assertThat(captor.getValue().getPromoCode()).isEqualTo("WELCOME6");
        }

        @Test
        @DisplayName("devise voyageur absente → fallback EUR, la négociation démarre et le thread copie EUR")
        void start_missingTravelerPrefsFallsBackToEurAndCopiesRequestCurrency() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.start(TRAVELER_ID, validStartReq());

            ArgumentCaptor<NegotiationThreadEntity> captor =
                ArgumentCaptor.forClass(NegotiationThreadEntity.class);
            verify(userBusinessPrefsRepository).findById(TRAVELER_ID);
            verify(threadRepo).save(captor.capture());
            assertThat(response).isNotNull();
            assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("quand la devise matche, le thread copie exactement la devise de la demande")
        void start_matchingCurrencyCopiesExactRequestCurrency() {
            request.setCurrency("cad");

            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
            when(userBusinessPrefsRepository.findById(TRAVELER_ID))
                .thenReturn(Optional.of(prefsWithCurrency(TRAVELER_ID, "CAD")));
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.start(TRAVELER_ID, validStartReq());

            ArgumentCaptor<NegotiationThreadEntity> captor =
                ArgumentCaptor.forClass(NegotiationThreadEntity.class);
            verify(threadRepo).save(captor.capture());
            assertThat(captor.getValue().getCurrency()).isEqualTo("cad");
        }
    }

    @Nested
    @DisplayName("start() — validation errors")
    class StartValidationErrors {
        @Test
        @DisplayName("traveler bid sa propre request → 403")
        void start_ownRequest_throws403() {
            request.setSenderId(TRAVELER_ID);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot-bid-own-request");
        }

        @Test
        @DisplayName("KYC non vérifié → 403")
        void start_kycNotVerified_throws403() {
            traveler.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kyc/not-verified");
        }

        @Test
        @DisplayName("thread existant → 409 duplicate")
        void start_duplicate_throws409() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.of(new NegotiationThreadEntity()));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("duplicate-thread");
        }

        @Test
        @DisplayName("request status EXPIRED → 410")
        void start_requestExpired_throws410() {
            request.setStatus(PackageRequestStatus.EXPIRED);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("request/expired");
        }

        @Test
        @DisplayName("rate limit dépassé → 429")
        void start_rateLimitExceeded_throws429() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(0L);
            when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(2L);

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate-limit");
        }

        @Test
        @DisplayName("max-open-threads atteint → 409")
        void start_atMaxOpenThreads_throws409() {
            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
                .thenReturn(5L);

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-open-reached");
        }

        @Test
        @DisplayName("voyageur sans Stripe peut négocier une demande card-only → blocage différé au trip-linking")
        void start_allowsTravelerWithoutStripeOnCardOnlyRequest_blockDeferredToTripLinking() {
            // Request only accepts STRIPE
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            // Traveler is NOT onboarded on Stripe (default stripeAccountStatus = NOT_CREATED)
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);

            when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
            when(config.threadsPerMinuteRateLimit()).thenReturn(1);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findActiveByPackageRequestIdAndTravelerId(any(), any()))
                .thenReturn(Optional.empty());
            when(threadRepo.countByTravelerIdAndStatus(any(), any())).thenReturn(0L);
            when(threadRepo.countCreatedBy(any(), any())).thenReturn(0L);
            when(threadRepo.save(any())).thenAnswer(inv -> {
                NegotiationThreadEntity t = inv.getArgument(0);
                try {
                    var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(t, UUID.randomUUID());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return t;
            });

            var req = new NegotiationStartRequest(REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"), null, "x");

            var response = service.start(TRAVELER_ID, req);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(NegotiationThreadStatus.OPEN);
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.PROPOSAL));
        }

        @Test
        @DisplayName("mismatch de devise → 422 avant tout save, event, audit ou mutation irréversible")
        void start_currencyMismatch_throws422BeforeAnyIrreversibleEffect() {
            request.setCurrency("USD");
            traveler.getRoles().clear();
            PackageRequestStatus statusBefore = request.getStatus();

            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.start(TRAVELER_ID, validStartReq()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException business = (YadonyBusinessException) ex;
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(business.getErrorCode()).isEqualTo("currency-mismatch");
                });

            verify(userBusinessPrefsRepository).findById(TRAVELER_ID);
            verify(threadRepo, never()).save(any(NegotiationThreadEntity.class));
            verify(messageRepo, never()).save(any(NegotiationMessageEntity.class));
            verify(requestRepo, never()).save(any(PackageRequestEntity.class));
            verify(userRepository, never()).save(any(UserEntity.class));
            verifyNoInteractions(auditService, eventPublisher);
            assertThat(request.getStatus()).isEqualTo(statusBefore);
            assertThat(traveler.getRoles()).isEmpty();
        }
    }

    private NegotiationStartRequest validStartReq() {
        return new NegotiationStartRequest(
            REQUEST_ID, new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            null, null
        );
    }

    @Nested
    @DisplayName("counter() — alternance + rounds")
    class CounterTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("sender counter après PROPOSAL traveler → OK, rounds++, message COUNTER")
        void counter_validAlternance_savesAndIncrementsRounds() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));

            // Last message was PROPOSAL by traveler
            var lastMsg = NegotiationMessageEntity.create(THREAD_ID, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            var req = new com.yadony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), "Mon contre");
            var response = service.counter(SENDER_ID, THREAD_ID, req);

            assertThat(response.roundsCount()).isEqualTo(2);
            assertThat(response.currentPriceEur()).isEqualByComparingTo("25");
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.COUNTER
                && m.getFromUserId().equals(SENDER_ID)));
            verify(eventPublisher).publishEvent(any(com.yadony.api.requests.event.NegotiationCounterPostedEvent.class));
        }

        @Test
        @DisplayName("même partie 2 fois d'affilée → 409 not-your-turn")
        void counter_sameSideTwice_throws409() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var lastMsg = NegotiationMessageEntity.create(THREAD_ID, SENDER_ID,
                NegotiationMessageKind.COUNTER, new BigDecimal("28"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            var req = new com.yadony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("27"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-your-turn");
        }

        @Test
        @DisplayName("rounds_count >= max → 409 max-rounds-reached")
        void counter_atMaxRounds_throws409() {
            when(config.maxNegotiationRounds()).thenReturn(5);
            thread.setRoundsCount((short) 5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.yadony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max-rounds-reached");
        }

        @Test
        @DisplayName("thread status REJECTED → 410 expired")
        void counter_threadNotOpen_throws410() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));

            var req = new com.yadony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("thread/expired");
        }

        @Test
        @DisplayName("non-participant → 403")
        void counter_outsider_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.yadony.api.requests.dto.NegotiationCounterRequest(
                new BigDecimal("25"), null);

            assertThatThrownBy(() -> service.counter(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }
    }

    @Nested
    @DisplayName("reject() — manual rejection")
    class RejectTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("traveler reject → thread REJECTED, message REJECT")
        void reject_byTraveler_marksRejected() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.yadony.api.requests.dto.NegotiationRejectRequest("Trop cher pour moi");
            service.reject(TRAVELER_ID, THREAD_ID, req);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.REJECTED);
            verify(messageRepo).save(argThat(m -> m.getKind() == NegotiationMessageKind.REJECT
                && "Trop cher pour moi".equals(m.getBody())));
            verify(threadRepo).save(thread);
        }

        @Test
        @DisplayName("dernière négociation rejetée → demande de nouveau OPEN")
        void reject_lastActiveThread_reopensRequest() {
            request.setStatus(PackageRequestStatus.NEGOTIATING);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread));

            service.reject(TRAVELER_ID, THREAD_ID,
                new com.yadony.api.requests.dto.NegotiationRejectRequest("Terminé"));

            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            verify(requestRepo).save(request);
        }

        @Test
        @DisplayName("non-participant → 403")
        void reject_outsider_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.yadony.api.requests.dto.NegotiationRejectRequest("nope");

            assertThatThrownBy(() -> service.reject(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("thread status REJECTED → 409 already-finalized")
        void reject_alreadyRejected_throws409() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));

            var req = new com.yadony.api.requests.dto.NegotiationRejectRequest("dup");
            assertThatThrownBy(() -> service.reject(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already-finalized");
        }
    }

    @Nested
    @DisplayName("cancelNegotiation() — end negotiation before payment")
    class CancelNegotiationTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("non-participant → 403 negotiation/not-thread-participant")
        void cancel_nonParticipant_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            UUID outsider = UUID.randomUUID();
            assertThatThrownBy(() -> service.cancelNegotiation(outsider, THREAD_ID, "nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("status ACCEPTED → 409 negotiation/not-cancellable")
        void cancel_statusAccepted_throws409NotCancellable() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.cancelNegotiation(SENDER_ID, THREAD_ID, "nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-cancellable");
        }

        @Test
        @DisplayName("status OPEN, caller = sender → CANCELLED, event vers traveler, audit, escrowPort jamais appelé")
        void cancel_open_setsCancelled_publishesEventToOtherParty_audits() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.cancelNegotiation(SENDER_ID, THREAD_ID, "Je change d'avis");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.CANCELLED);
            verify(threadRepo).save(thread);
            ArgumentCaptor<com.yadony.api.requests.event.NegotiationCancelledEvent> eventCaptor =
                ArgumentCaptor.forClass(com.yadony.api.requests.event.NegotiationCancelledEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            var publishedEvent = eventCaptor.getValue();
            assertThat(publishedEvent.threadId()).isEqualTo(THREAD_ID);
            assertThat(publishedEvent.packageRequestId()).isEqualTo(REQUEST_ID);
            assertThat(publishedEvent.byUserId()).isEqualTo(SENDER_ID);
            assertThat(publishedEvent.toUserId()).isEqualTo(TRAVELER_ID);
            assertThat(publishedEvent.releaseEscrow()).isFalse();
            verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(THREAD_ID), eq("CANCELLED"), eq(SENDER_ID), any());
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
        }

        @Test
        @DisplayName("une autre négociation active demeure → demande reste NEGOTIATING")
        void cancel_withAnotherActiveThread_keepsRequestNegotiating() {
            request.setStatus(PackageRequestStatus.NEGOTIATING);
            NegotiationThreadEntity other = new NegotiationThreadEntity();
            other.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread, other));

            service.cancelNegotiation(SENDER_ID, THREAD_ID, null);

            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.NEGOTIATING);
            verify(requestRepo, never()).save(request);
        }

        @Test
        @DisplayName("status AWAITING_PAYMENT → escrow NON libéré inline (releaseEscrow=true) + trajet dédié soft-deleted + CANCELLED")
        void cancel_awaitingPayment_defersEscrow_softDeletesDedicatedTrip_setsCancelled() {
            UUID announcementId = UUID.randomUUID();
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(announcementId);

            com.yadony.api.matching.AnnouncementEntity dedicatedAnn = new com.yadony.api.matching.AnnouncementEntity();
            dedicatedAnn.setLinkedPackageRequestId(REQUEST_ID);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(dedicatedAnn, announcementId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(dedicatedAnn));

            service.cancelNegotiation(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.CANCELLED);
            // Le hold Stripe n'est PLUS annulé inline : cela se fait dans un listener
            // paiements AFTER_COMMIT (règle #18). Le service ne touche jamais escrowPort ici.
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
            assertThat(dedicatedAnn.getDeletedAt()).isNotNull();
            verify(announcementRepo).save(dedicatedAnn);
            ArgumentCaptor<com.yadony.api.requests.event.NegotiationCancelledEvent> eventCaptor =
                ArgumentCaptor.forClass(com.yadony.api.requests.event.NegotiationCancelledEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().toUserId()).isEqualTo(SENDER_ID);
            assertThat(eventCaptor.getValue().byUserId()).isEqualTo(TRAVELER_ID);
            // L'event porte le drapeau qui déclenchera la libération de l'escrow après commit.
            assertThat(eventCaptor.getValue().releaseEscrow()).isTrue();
        }

        @Test
        @DisplayName("status AWAITING_TRIP, caller = traveler → CANCELLED, event vers sender")
        void cancel_awaitingTrip_setsCancelled() {
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            service.cancelNegotiation(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.CANCELLED);
            ArgumentCaptor<com.yadony.api.requests.event.NegotiationCancelledEvent> eventCaptor =
                ArgumentCaptor.forClass(com.yadony.api.requests.event.NegotiationCancelledEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().toUserId()).isEqualTo(SENDER_ID);
            assertThat(eventCaptor.getValue().byUserId()).isEqualTo(TRAVELER_ID);
            assertThat(eventCaptor.getValue().releaseEscrow()).isFalse();
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
        }
    }

    @Nested
    @DisplayName("accept() — atomic acceptance")
    class AcceptTests {
        private NegotiationThreadEntity thread;
        private final UUID THREAD_ID = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new java.math.BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("sender accept → thread AWAITING_TRIP, request still NEGOTIATING, competing threads untouched, event")
        void accept_byOwner_movesToAwaitingTrip() {
            UUID OTHER_THREAD_ID = UUID.randomUUID();
            var otherThread = new NegotiationThreadEntity();
            otherThread.setPackageRequestId(REQUEST_ID);
            otherThread.setTravelerId(UUID.randomUUID());
            otherThread.setStatus(NegotiationThreadStatus.OPEN);
            otherThread.setCurrentPriceEur(new java.math.BigDecimal("32"));
            otherThread.setRoundsCount((short) 1);
            otherThread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(otherThread, OTHER_THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            // Last message from the traveler — sender can now accept it (bilateral contract)
            var travelerProposal = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(travelerProposal));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);

            var req = new com.yadony.api.requests.dto.NegotiationAcceptRequest("Deal!");
            var response = service.accept(SENDER_ID, THREAD_ID, req);

            assertThat(response.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(response.paymentIntentClientSecret()).isNull();
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            // Request unchanged at accept time — only finalizes to ACCEPTED after payment
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
            // Competing threads untouched at accept time — auto-reject only at finalize
            assertThat(otherThread.getStatus()).isEqualTo(NegotiationThreadStatus.OPEN);
            verify(eventPublisher).publishEvent(any(com.yadony.api.requests.event.NegotiationAwaitingTripEvent.class));
            verify(messageRepo, atLeastOnce()).save(argThat(m -> m.getKind() == NegotiationMessageKind.ACCEPT));
            // Mapper: thread sans bid matérialisé → DTO.materializedBidId == null
            assertThat(response.materializedBidId()).isNull();
        }

        @Test
        @DisplayName("toResponse propage materializedBidId du thread vers le DTO")
        void accept_mapsMaterializedBidId() {
            UUID materializedBidId = UUID.randomUUID();
            thread.setMaterializedBidId(materializedBidId);

            var travelerProposal = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(travelerProposal));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);

            var req = new com.yadony.api.requests.dto.NegotiationAcceptRequest("Deal!");
            var response = service.accept(SENDER_ID, THREAD_ID, req);

            assertThat(response.materializedBidId()).isEqualTo(materializedBidId);
        }

        @Test
        @DisplayName("non-sender → 403")
        void accept_nonSender_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            var req = new com.yadony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(OUTSIDER, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("voyageur accepte sans messages → 409 inconsistent-thread (contrat bilatéral)")
        void traveler_accepts_noMessages_throwsInconsistent() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of());

            var req = new com.yadony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("inconsistent-thread");
        }

        @Test
        @DisplayName("thread déjà ACCEPTED → 409 already-finalized")
        void accept_alreadyAccepted_throws409() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            var req = new com.yadony.api.requests.dto.NegotiationAcceptRequest(null);

            assertThatThrownBy(() -> service.accept(SENDER_ID, THREAD_ID, req))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("already-finalized");
        }

        @Test
        @DisplayName("expéditeur accepte le dernier message du voyageur → AWAITING_TRIP")
        void sender_accepts_travelerMessage_setsAwaitingTrip() {
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, TRAVELER_ID, NegotiationMessageKind.PROPOSAL,
                new java.math.BigDecimal("30"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            var result = service.accept(SENDER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(result.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
        }

        @Test
        @DisplayName("voyageur accepte le dernier message du sender sans trajet lié → AWAITING_TRIP")
        void traveler_accepts_senderMessage_noTrip_setsAwaitingTrip() {
            thread.setTravelerAnnouncementId(null);
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            service.accept(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
        }

        @Test
        @DisplayName("voyageur accepte avec trajet déjà lié → AWAITING_PAYMENT direct")
        void traveler_accepts_withLinkedTrip_setsAwaitingPayment() {
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));
            when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(threadRepo.save(any())).thenReturn(thread);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);

            service.accept(TRAVELER_ID, THREAD_ID, null);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
        }

        @Test
        @DisplayName("accepter son propre message → 409 not-your-turn")
        void accept_ownMessage_throwsNotYourTurn() {
            NegotiationMessageEntity lastMsg = NegotiationMessageEntity.create(
                THREAD_ID, SENDER_ID, NegotiationMessageKind.COUNTER,
                new java.math.BigDecimal("28"), null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID))
                .thenReturn(java.util.List.of(lastMsg));

            assertThatThrownBy(() -> service.accept(SENDER_ID, THREAD_ID, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-your-turn");
        }

        @Test
        @DisplayName("tiers non participant → 403 not-thread-participant")
        void accept_thirdParty_throwsForbidden() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID stranger = UUID.randomUUID();
            assertThatThrownBy(() -> service.accept(stranger, THREAD_ID, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }
    }

    @Nested
    @DisplayName("getById() / listMine()")
    class GetByIdAndListTests {
        @Test
        @DisplayName("getById — participant → response avec messages")
        void getById_participant_returnsThreadWithMessages() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setPromoCode("WELCOME6");
            thread.setCommissionRate(new BigDecimal("0.06"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());

            var resp = service.getById(TRAVELER_ID, THREAD_ID);
            assertThat(resp.id()).isEqualTo(THREAD_ID);
            assertThat(resp.promoCode()).isEqualTo("WELCOME6");
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(resp)
                    .path("commissionRate").decimalValue()).isEqualByComparingTo("0.06");
            assertThat(thread.getTravelerLastReadAt()).isNotNull();
            assertThat(resp.hasUnread()).isFalse();
            verify(threadRepo).save(thread);
        }

        @Test
        @DisplayName("getById — non-participant → 403")
        void getById_outsider_throws403() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));

            UUID OUTSIDER = UUID.randomUUID();
            assertThatThrownBy(() -> service.getById(OUTSIDER, THREAD_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        private NegotiationThreadEntity threadFor(UUID threadId) {
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            return thread;
        }

        @Test
        @DisplayName("cashCommissionAvailable = true — solde wallet suffisant")
        void getById_cashCommissionAvailable_trueWhenWalletSufficient() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = threadFor(THREAD_ID);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(true);

            var resp = service.getById(TRAVELER_ID, THREAD_ID);

            assertThat(resp.cashCommissionAvailable()).isTrue();
        }

        @Test
        @DisplayName("cashCommissionAvailable = false — pas de solde ni de carte")
        void getById_cashCommissionAvailable_falseWhenNoWalletAndNoCard() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = threadFor(THREAD_ID);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(false);
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(false);

            var resp = service.getById(TRAVELER_ID, THREAD_ID);

            assertThat(resp.cashCommissionAvailable()).isFalse();
        }

        @Test
        @DisplayName("cashCommissionAvailable = true — solde insuffisant mais carte enregistrée")
        void getById_cashCommissionAvailable_trueWhenCardEvenIfWalletInsufficient() {
            UUID THREAD_ID = UUID.randomUUID();
            var thread = threadFor(THREAD_ID);

            when(threadRepo.findById(THREAD_ID)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(false);
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(true);

            var resp = service.getById(TRAVELER_ID, THREAD_ID);

            assertThat(resp.cashCommissionAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("quote() — devis transparent expéditeur (breakdown + promo)")
    class QuoteTests {
        private NegotiationThreadEntity threadFor(UUID threadId, BigDecimal net) {
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(net);
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            return thread;
        }

        @Test
        @DisplayName("sans promo — commission au taux de base")
        void quote_noPromo_baseRate() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, new BigDecimal("40.00"));
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.05"));

            var quote = service.quote(SENDER_ID, threadId, null);

            assertThat(quote.netEur()).isEqualByComparingTo("40.00");
            assertThat(quote.rate()).isEqualByComparingTo("0.05");
            assertThat(quote.commissionEur()).isEqualByComparingTo("2.00");
            assertThat(quote.totalEur()).isEqualByComparingTo("42.00");
            assertThat(quote.promoApplied()).isFalse();
        }

        @Test
        @DisplayName("promo valide — remise réelle en points, base toujours affichée")
        void quote_validPromo_discountsTotal() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, new BigDecimal("40.00"));
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "WELCOME6"))
                    .thenReturn(new BigDecimal("0.06"));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

            var quote = service.quote(SENDER_ID, threadId, "WELCOME6");

            assertThat(quote.rate()).isEqualByComparingTo("0.12");       // base, jamais affecté
            assertThat(quote.commissionEur()).isEqualByComparingTo("4.80");
            assertThat(quote.totalEur()).isEqualByComparingTo("42.40"); // 40 + 40*0.06
            assertThat(quote.promoApplied()).isTrue();
            assertThat(quote.promoLabel()).contains("6 % de réduction");
        }

        @Test
        @DisplayName("promo invalide — propage l'exception avant tout calcul")
        void quote_invalidPromo_propagates() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, new BigDecimal("40.00"));
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "BADCODE"))
                    .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                            HttpStatus.NOT_FOUND, "promo-not-found", "Promo Not Found", "Introuvable"));

            assertThatThrownBy(() -> service.quote(SENDER_ID, threadId, "BADCODE"))
                    .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
        }

        @Test
        @DisplayName("traveler (non-payeur) — 403")
        void quote_travelerCaller_throws403() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, new BigDecimal("40.00"));
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.quote(TRAVELER_ID, threadId, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("aucun param explicite → applique le code déjà porté par le thread")
        void quote_noExplicitPromo_usesThreadStoredCode() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, new BigDecimal("40.00"));
            thread.setPromoCode("AUTOCODE");
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID, "AUTOCODE"))
                    .thenReturn(new BigDecimal("0.06"));
            when(commissionRateResolver.resolve(TRAVELER_ID, SENDER_ID)).thenReturn(new BigDecimal("0.12"));

            var quote = service.quote(SENDER_ID, threadId, null);

            assertThat(quote.promoApplied()).isTrue();
        }
    }

    @Nested
    @DisplayName("recordAppliedPromo() — persistance promo/rate sur le thread")
    class RecordAppliedPromoTests {
        @Test
        @DisplayName("thread existant — promoCode normalisé (upper/strip) et rate stampés")
        void recordAppliedPromo_persistsNormalizedCode() {
            UUID threadId = UUID.randomUUID();
            var thread = new NegotiationThreadEntity();
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));

            service.recordAppliedPromo(threadId, " welcome6 ", new BigDecimal("0.06"));

            assertThat(thread.getPromoCode()).isEqualTo("WELCOME6");
            assertThat(thread.getCommissionRate()).isEqualByComparingTo("0.06");
            verify(threadRepo).save(thread);
        }

        @Test
        @DisplayName("thread introuvable — no-op silencieux")
        void recordAppliedPromo_threadNotFound_noop() {
            UUID threadId = UUID.randomUUID();
            when(threadRepo.findById(threadId)).thenReturn(Optional.empty());

            service.recordAppliedPromo(threadId, "WELCOME6", new BigDecimal("0.06"));

            verify(threadRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("canNudge — flag calculé sur la réponse du thread")
    class CanNudgeTests {

        /**
         * @param status thread status
         * @param lastActivityAt when the thread last saw activity
         * @param lastNudgeAt when the viewer last nudged (null if never)
         */
        private NegotiationThreadEntity threadFor(UUID threadId, NegotiationThreadStatus status,
                                                   java.time.LocalDateTime lastActivityAt,
                                                   java.time.LocalDateTime lastNudgeAt) {
            var thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(status);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(lastActivityAt);
            thread.setLastNudgeAt(lastNudgeAt);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            return thread;
        }

        private void stubCommonLookups(UUID threadId, NegotiationThreadEntity thread) {
            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        }

        @Test
        @DisplayName("true — statut OPEN, en attente >1h, pas de relance récente")
        void canNudge_trueWhenWaitingOverAnHour_noRecentNudge() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.OPEN,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            stubCommonLookups(threadId, thread);

            // Dernier message posté par le caller (TRAVELER_ID) lui-même : il attend la réponse
            // de l'autre partie → isMyTurn = false pour ce viewer.
            var lastMsg = NegotiationMessageEntity.create(threadId, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(lastMsg));

            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isTrue();
        }

        @Test
        @DisplayName("false — moins d'1h depuis la dernière activité")
        void canNudge_falseWhenLessThanAnHour() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.OPEN,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(30), null);
            stubCommonLookups(threadId, thread);

            var lastMsg = NegotiationMessageEntity.create(threadId, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(lastMsg));

            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isFalse();
        }

        @Test
        @DisplayName("false — rate-limité, relance déjà envoyée il y a moins d'1h")
        void canNudge_falseWhenRateLimited() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.OPEN,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20));
            stubCommonLookups(threadId, thread);

            var lastMsg = NegotiationMessageEntity.create(threadId, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(lastMsg));

            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isFalse();
        }

        @Test
        @DisplayName("false — c'est le tour du viewer d'agir")
        void canNudge_falseWhenMyTurn() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.OPEN,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            stubCommonLookups(threadId, thread);

            // Dernier message posté par l'autre partie (SENDER_ID) : c'est au caller
            // (TRAVELER_ID) d'agir → isMyTurn = true, donc pas de nudge (il doit répondre).
            var lastMsg = NegotiationMessageEntity.create(threadId, SENDER_ID,
                NegotiationMessageKind.COUNTER, new BigDecimal("28"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(lastMsg));

            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isFalse();
        }

        @Test
        @DisplayName("false — statut ni OPEN ni AWAITING_TRIP")
        void canNudge_falseWhenStatusNotOpenOrAwaitingTrip() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.AWAITING_PAYMENT,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            stubCommonLookups(threadId, thread);

            var lastMsg = NegotiationMessageEntity.create(threadId, TRAVELER_ID,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("30"), null);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(lastMsg));

            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isFalse();
        }

        @Test
        @DisplayName("true — AWAITING_TRIP, viewer = expéditeur (partie qui attend le voyageur)")
        void canNudge_trueWhenAwaitingTrip_viewerIsSender() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.AWAITING_TRIP,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            stubCommonLookups(threadId, thread);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());

            // En AWAITING_TRIP le voyageur doit lier un trajet -> l'expéditeur attend.
            var resp = service.getById(SENDER_ID, threadId);

            assertThat(resp.canNudge()).isTrue();
        }

        @Test
        @DisplayName("false — AWAITING_TRIP, viewer = voyageur (partie qui doit agir, jamais relancée)")
        void canNudge_falseWhenAwaitingTrip_viewerIsTraveler() {
            UUID threadId = UUID.randomUUID();
            var thread = threadFor(threadId, NegotiationThreadStatus.AWAITING_TRIP,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            stubCommonLookups(threadId, thread);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());

            // Le voyageur est celui qui doit agir (lier un trajet) : jamais canNudge=true
            // pour lui, sinon il verrait un bouton "Relancer" que /nudge rejetterait en 409.
            var resp = service.getById(TRAVELER_ID, threadId);

            assertThat(resp.canNudge()).isFalse();
        }
    }

    @Nested
    class RefuseTripTests {

        @Test
        void refuseTrip_asSender_movesToAwaitingTrip() {
            UUID threadId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(announcementId);
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            // Trajet lié via submitTrip() (existant, pas dédié) — pas de
            // linkedPackageRequestId, donc ne doit PAS être soft-delete.
            com.yadony.api.matching.AnnouncementEntity existingAnn = new com.yadony.api.matching.AnnouncementEntity();

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(existingAnn));

            NegotiationThreadResponse resp = service.refuseTrip(SENDER_ID, threadId, "Trajet non adapté");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(thread.getTravelerAnnouncementId()).isNull();
            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_TRIP);
            assertThat(resp.linkedTrip()).isNull();
            verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("TRIP_REFUSED"), eq(SENDER_ID), any());
            verify(eventPublisher).publishEvent(any(NegotiationAwaitingTripEvent.class));
            verify(threadRepo).save(thread);
            assertThat(existingAnn.getDeletedAt()).isNull();
            verify(announcementRepo, never()).save(any());
        }

        @Test
        void refuseTrip_dedicatedTrip_softDeletesOrphanedAnnouncement() {
            UUID threadId = UUID.randomUUID();
            UUID announcementId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(announcementId);
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            // Trajet DÉDIÉ créé exclusivement pour cette demande (createDedicatedTrip) —
            // n'a plus aucune utilité une fois détaché, doit être soft-delete.
            com.yadony.api.matching.AnnouncementEntity dedicatedAnn = new com.yadony.api.matching.AnnouncementEntity();
            dedicatedAnn.setLinkedPackageRequestId(REQUEST_ID);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(dedicatedAnn, announcementId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(config.maxNegotiationRounds()).thenReturn(5);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(announcementRepo.findById(announcementId)).thenReturn(Optional.of(dedicatedAnn));

            service.refuseTrip(SENDER_ID, threadId, "Trajet non adapté");

            assertThat(dedicatedAnn.getDeletedAt()).isNotNull();
            verify(announcementRepo).save(dedicatedAnn);
            verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcementId),
                eq("DEDICATED_TRIP_ORPHANED_ON_REFUSAL"), eq(SENDER_ID), any());
        }

        @Test
        void refuseTrip_asTraveler_throws403() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(TRAVELER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        }

        @Test
        void refuseTrip_noTripLinked_throws409() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setTravelerAnnouncementId(null);
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(SENDER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        }

        @Test
        void refuseTrip_wrongStatus_throws409() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setTravelerAnnouncementId(UUID.randomUUID());
            thread.setTravelerTravelDate(java.time.LocalDate.now());
            thread.setTravelerAvailableKg(new BigDecimal("5"));
            thread.setCurrentPriceEur(new BigDecimal("45"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());

            when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.refuseTrip(SENDER_ID, threadId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        }
    }

    @Nested
    @DisplayName("createDedicatedTrip()")
    class CreateDedicatedTripTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));
            request.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.CASH));

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        private com.yadony.api.requests.dto.NegotiationCreateDedicatedTripRequest buildRequest(LocalDate date) {
            return new com.yadony.api.requests.dto.NegotiationCreateDedicatedTripRequest(
                date,
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(14, 30),
                new com.yadony.api.matching.dto.AddressDto("CDG T2E", 49.0097, 2.5479),
                new com.yadony.api.matching.dto.AddressDto("DSS Diass", 14.6708, -17.0734),
                "Bagage en soute",
                java.util.List.of("vetements", "documents"),
                java.util.List.of("liquides"),
                com.yadony.api.payments.cash.PaymentMethod.CASH
            );
        }

        @Test
        @DisplayName("happy path — date dans la fenêtre → annonce dédiée créée + thread → AWAITING_PAYMENT + event")
        void createDedicatedTrip_valid_createsAnnouncementAndTransitionsThread() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(true);
            UUID newAnnId = UUID.randomUUID();
            when(announcementRepo.save(any())).thenAnswer(inv -> {
                com.yadony.api.matching.AnnouncementEntity a = inv.getArgument(0);
                try {
                    var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(a, newAnnId);
                } catch (Exception e) { throw new RuntimeException(e); }
                return a;
            });
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());

            var resp = service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())); // exact desired date — within tolerance

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(resp.travelerAnnouncementId()).isEqualTo(newAnnId);

            ArgumentCaptor<com.yadony.api.matching.AnnouncementEntity> annCaptor =
                ArgumentCaptor.forClass(com.yadony.api.matching.AnnouncementEntity.class);
            verify(announcementRepo).save(annCaptor.capture());
            com.yadony.api.matching.AnnouncementEntity savedAnn = annCaptor.getValue();
            // Locked fields derived server-side
            assertThat(savedAnn.getDepartureCity()).isEqualTo("Paris");
            assertThat(savedAnn.getArrivalCity()).isEqualTo("Dakar");
            // Avant ouverture du surplus : aucune capacité dispo pour des tiers,
            // tout est réservé au sender → availableKg = 0 (carte « 5/5 réservés »).
            assertThat(savedAnn.getAvailableKg()).isEqualByComparingTo("0");
            assertThat(savedAnn.getTotalKg()).isEqualByComparingTo("5");
            assertThat(savedAnn.getTransportMode()).isEqualTo(com.yadony.api.matching.TransportMode.PLANE);
            assertThat(savedAnn.getLinkedPackageRequestId()).isEqualTo(REQUEST_ID);
            // Surplus capacity: reservedKg = request weight, surplus locked at creation
            assertThat(savedAnn.getReservedKg()).isEqualByComparingTo("5");
            assertThat(savedAnn.isSurplusEligible()).isFalse();
            assertThat(savedAnn.isSurplusPublished()).isFalse();
            // Sender réservé mémorisé → il ne pourra pas re-bidder sur le surplus.
            assertThat(savedAnn.getReservedSenderId()).isEqualTo(SENDER_ID);
            // Price-per-kg derived from agreed total (80 / 5 = 16)
            assertThat(savedAnn.getPricePerKg()).isEqualByComparingTo("16.00");
            assertThat(savedAnn.getStatus()).isEqualTo(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
            // Fenêtre de remise dérivée du jour de départ (héritée par le bid dédié).
            assertThat(savedAnn.getHandoverWindowStart()).isNotNull();
            assertThat(savedAnn.getHandoverWindowEnd()).isNotNull();
            assertThat(savedAnn.getHandoverWindowStart().toLocalDate())
                .isEqualTo(savedAnn.getDepartureDate());
            assertThat(savedAnn.getHandoverWindowEnd().toLocalDate())
                .isEqualTo(savedAnn.getDepartureDate());

            verify(eventPublisher).publishEvent(any(com.yadony.api.requests.event.NegotiationAwaitingPaymentEvent.class));
        }

        @Test
        @DisplayName("types acceptés/refusés legacy normalisés vers le vocabulaire canonique, texte libre préservé")
        void createDedicatedTrip_normalisesLegacyContentTypes() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(true);
            when(announcementRepo.save(any())).thenAnswer(inv -> {
                com.yadony.api.matching.AnnouncementEntity a = inv.getArgument(0);
                try {
                    var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(a, UUID.randomUUID());
                } catch (Exception e) { throw new RuntimeException(e); }
                return a;
            });
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(java.util.List.of());

            var req = new com.yadony.api.requests.dto.NegotiationCreateDedicatedTripRequest(
                request.getDesiredDate(),
                java.time.LocalTime.of(8, 0),
                java.time.LocalTime.of(14, 30),
                new com.yadony.api.matching.dto.AddressDto("CDG T2E", 49.0097, 2.5479),
                new com.yadony.api.matching.dto.AddressDto("DSS Diass", 14.6708, -17.0734),
                "Bagage en soute",
                java.util.List.of("Hi-fi", "Vêtements"),
                java.util.List.of("Nourriture", "Poissons"),
                com.yadony.api.payments.cash.PaymentMethod.CASH
            );

            service.createDedicatedTrip(TRAVELER_ID, THREAD_ID, req);

            ArgumentCaptor<com.yadony.api.matching.AnnouncementEntity> annCaptor =
                ArgumentCaptor.forClass(com.yadony.api.matching.AnnouncementEntity.class);
            verify(announcementRepo).save(annCaptor.capture());
            assertThat(annCaptor.getValue().getAcceptedContentTypes())
                .containsExactly("Téléphone & électronique", "Vêtements & tissus");
            assertThat(annCaptor.getValue().getRefusedTypes())
                .containsExactly("Alimentation sèche", "Poissons");
        }

        @Test
        @DisplayName("date avant la fenêtre de tolérance → 422 date-mismatch")
        void createDedicatedTrip_dateBeforeWindow_throws422() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(true);

            var req = buildRequest(request.getDesiredDate().minusDays(3));
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-mismatch");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("date après la fenêtre de tolérance → 422 date-mismatch")
        void createDedicatedTrip_dateAfterWindow_throws422() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(true);

            var req = buildRequest(request.getDesiredDate().plusDays(3));
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("date-mismatch");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("caller n'est pas le traveler → 403 not-traveler")
        void createDedicatedTrip_notTraveler_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));

            UUID OUTSIDER = UUID.randomUUID();
            assertThatThrownBy(() -> service.createDedicatedTrip(OUTSIDER, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-traveler");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("thread status != AWAITING_TRIP → 409 not-awaiting-trip")
        void createDedicatedTrip_wrongStatus_throws409() {
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));

            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-trip");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("thread introuvable → 404 thread/not-found")
        void createDedicatedTrip_threadMissing_throws404() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(LocalDate.now().plusDays(10))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("thread/not-found");
        }

        @Test
        @DisplayName("voyageur choisit CASH avec fonds insuffisants → 422 discriminant cash-funds-required")
        void createDedicatedTrip_rejectsCashWhenInsufficientFunds() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(false);

            // buildRequest uses CASH as payment method (now ignored — request only accepts CASH,
            // and the wallet has insufficient funds with no card consent → SET is empty).
            assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, THREAD_ID,
                buildRequest(request.getDesiredDate())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payment-method/cash-funds-required");

            verifyNoInteractions(announcementRepo);
        }
    }

    @Nested
    @DisplayName("submitTrip() — payment method validation")
    class SubmitTripPaymentMethodTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));
            // Request only accepts STRIPE
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("req.paymentMethod() est ignoré pour la décision → SET calculé depuis accepted ∩ capacité voyageur")
        void submitTrip_ignoresPaymentMethodField_usesRequestAcceptedAndCapability() {
            UUID annId = UUID.randomUUID();

            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5"));

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());

            // Request only accepts STRIPE, traveler is Stripe-capable (fixture default);
            // req.paymentMethod() = CASH is ignored — the SET is still computed correctly.
            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.CASH);
            var resp = service.submitTrip(TRAVELER_ID, THREAD_ID, req);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(thread.getAvailablePaymentMethods()).isEqualTo(java.util.EnumSet.of(PaymentMethod.STRIPE));
            assertThat(thread.getPaymentMethod()).isNull();
        }

        @Test
        @DisplayName("voyageur CASH avec fonds insuffisants et sans consentement carte → 422 discriminant cash-funds-required")
        void submitTrip_rejectsCashWhenInsufficientFunds() {
            // Request accepts CASH — SET computation fails (no funds, no card consent) before
            // the announcement is ever looked up.
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new java.math.BigDecimal("0.12"));
            when(cashGatePort.hasSufficientFunds(eq(TRAVELER_ID), any(), any())).thenReturn(false);

            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.CASH);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payment-method/cash-funds-required");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("CASH + consentement carte + carte enregistrée → liaison OK même wallet vide")
        void submitTrip_cashWithCardConsent_linksEvenIfWalletShort() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5")); // request weight is 5 → linkable

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(true);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());

            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(
                annId, PaymentMethod.CASH, true);
            var resp = service.submitTrip(TRAVELER_ID, THREAD_ID, req);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(thread.getAvailablePaymentMethods()).isEqualTo(java.util.EnumSet.of(PaymentMethod.CASH));
            assertThat(thread.getPaymentMethod()).isNull();
        }

        @Test
        @DisplayName("CASH + consentement carte mais aucune carte enregistrée (et wallet vide) → 422 discriminant cash-funds-required")
        void submitTrip_cashWithCardConsentButNoCard_throws422() {
            // SET computation fails (no funds, card consent but no card registered) before
            // the announcement is ever looked up.
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            UUID annId = UUID.randomUUID();

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(cashGatePort.hasCommissionCard(eq(TRAVELER_ID))).thenReturn(false);

            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(
                annId, PaymentMethod.CASH, true);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payment-method/cash-funds-required");
            verifyNoInteractions(announcementRepo);
        }

        @Test
        @DisplayName("annonce non ACTIVE (ex. COMPLETED) → 422 announcement/not-active")
        void submitTrip_rejectsWhenAnnouncementNotActive() {
            UUID annId = UUID.randomUUID();
            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5"));
            // Trip already finished: linking it would leave it stuck out of "À venir".
            ann.setStatus(com.yadony.api.matching.AnnouncementStatus.COMPLETED);

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/not-active");
        }

        @Test
        @DisplayName("annonce ACTIVE mais capacité insuffisante → 422 announcement/insufficient-capacity")
        void submitTrip_rejectsWhenInsufficientCapacity() {
            UUID annId = UUID.randomUUID();
            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
            ann.setAvailableKg(new BigDecimal("2")); // request weight is 5 → too small

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            var req = new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE);

            assertThatThrownBy(() -> service.submitTrip(TRAVELER_ID, THREAD_ID, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/insufficient-capacity");
        }

        /** BaseEntity.id has no public setter — mirrors the reflection helper used throughout this file. */
        private void setEntityId(com.yadony.api.common.BaseEntity entity, UUID id) {
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entity, id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("colis card-only, voyageur non onboardé Stripe → 422 discriminant card-capability-required")
        void submitTrip_cardOnlyRequest_travelerNoStripe_throws422CardCapability() {
            UUID threadId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID annId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setEntityId(thread, threadId);
            thread.setTravelerId(travelerId);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("100.00"));

            PackageRequestEntity request = new PackageRequestEntity();
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));

            UserEntity traveler = new UserEntity();
            setEntityId(traveler, travelerId);
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED); // pas onboardé (tout statut ≠ ONBOARDING_COMPLETE)

            when(threadRepo.findById(threadId)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(any())).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(travelerId)).thenReturn(java.util.Optional.of(traveler));

            com.yadony.api.requests.dto.NegotiationSubmitTripRequest req =
                new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE, false);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitTrip(travelerId, threadId, req));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
            assertEquals("payment-method/card-capability-required", ex.getReason());
        }

        @Test
        @DisplayName("colis cash-only, voyageur sans fonds ni consentement carte → 422 discriminant cash-funds-required")
        void submitTrip_cashOnlyRequest_noFundsNoConsent_throws422CashFunds() {
            UUID threadId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID annId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setEntityId(thread, threadId);
            thread.setTravelerId(travelerId);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("100.00"));

            PackageRequestEntity request = new PackageRequestEntity();
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));

            UserEntity traveler = new UserEntity();
            setEntityId(traveler, travelerId);
            traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);

            when(threadRepo.findById(threadId)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(any())).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(travelerId)).thenReturn(java.util.Optional.of(traveler));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.05"));
            when(cashGatePort.hasSufficientFunds(eq(travelerId), any(), any())).thenReturn(false);

            com.yadony.api.requests.dto.NegotiationSubmitTripRequest req =
                new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.CASH, false); // pas de consentement

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitTrip(travelerId, threadId, req));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
            assertEquals("payment-method/cash-funds-required", ex.getReason());
        }

        @Test
        @DisplayName("colis STRIPE+CASH, voyageur STRIPE seulement → SET={STRIPE} persisté, champ paymentMethod requête ignoré")
        void submitTrip_bothAccepted_travelerStripeOnly_persistsStripeSet() {
            UUID threadId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID annId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setEntityId(thread, threadId);
            thread.setTravelerId(travelerId);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("100.00"));
            thread.setRoundsCount((short) 1);

            PackageRequestEntity request = new PackageRequestEntity();
            request.setSenderId(SENDER_ID);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));

            UserEntity traveler = new UserEntity();
            setEntityId(traveler, travelerId);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(travelerId);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5"));

            when(threadRepo.findById(threadId)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(any())).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(travelerId)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(java.util.Optional.of(ann));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.05"));
            when(cashGatePort.hasSufficientFunds(eq(travelerId), any(), any())).thenReturn(false);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());

            service.submitTrip(travelerId, threadId,
                new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE, false));

            assertEquals(java.util.EnumSet.of(PaymentMethod.STRIPE), thread.getAvailablePaymentMethods());
            assertNull(thread.getPaymentMethod());
        }

        @Test
        @DisplayName("colis STRIPE+CASH, voyageur STRIPE seulement → SET exposé dans la réponse")
        void submitTrip_response_exposesAvailableSet() {
            UUID threadId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID annId = UUID.randomUUID();

            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            setEntityId(thread, threadId);
            thread.setTravelerId(travelerId);
            thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
            thread.setCurrentPriceEur(new BigDecimal("100.00"));
            thread.setRoundsCount((short) 1);

            PackageRequestEntity request = new PackageRequestEntity();
            request.setSenderId(SENDER_ID);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setDesiredDate(LocalDate.now().plusDays(10));
            request.setDateToleranceDays((short) 2);
            request.setWeightKg(new BigDecimal("5"));

            UserEntity traveler = new UserEntity();
            setEntityId(traveler, travelerId);
            traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

            com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(travelerId);
            ann.setDepartureCity("Paris");
            ann.setArrivalCity("Dakar");
            ann.setDepartureDate(request.getDesiredDate());
            ann.setAvailableKg(new BigDecimal("5"));

            when(threadRepo.findById(threadId)).thenReturn(java.util.Optional.of(thread));
            when(requestRepo.findById(any())).thenReturn(java.util.Optional.of(request));
            when(userRepository.findById(travelerId)).thenReturn(java.util.Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(java.util.Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(java.util.Optional.of(ann));
            when(commissionProperties.rate()).thenReturn(new BigDecimal("0.05"));
            when(cashGatePort.hasSufficientFunds(eq(travelerId), any(), any())).thenReturn(false);
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());

            NegotiationThreadResponse res = service.submitTrip(travelerId, threadId,
                new com.yadony.api.requests.dto.NegotiationSubmitTripRequest(annId, PaymentMethod.STRIPE, false));

            assertEquals(java.util.EnumSet.of(PaymentMethod.STRIPE), res.availablePaymentMethods());
        }
    }

    @Nested
    @DisplayName("Firm-price (negotiable=false)")
    class FirmPriceTests {

        @Test
        @DisplayName("start() avec prix ferme et prix proposé ≠ targetPrice → 422 firm-price")
        void start_firmRequest_priceMustEqualTarget() {
            request.setNegotiable(false);
            request.setTargetPriceEur(new BigDecimal("35"));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

            var bad = new NegotiationStartRequest(REQUEST_ID, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"), null, "x");
            assertThatThrownBy(() -> service.start(TRAVELER_ID, bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("firm-price");
        }

        @Test
        @DisplayName("counter() sur request avec prix ferme → 409 counter-not-allowed-firm-price")
        void counter_firmRequest_forbidden() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setRoundsCount((short) 1);
            request.setNegotiable(false);
            when(threadRepo.findById(any())).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.counter(SENDER_ID, UUID.randomUUID(),
                    new com.yadony.api.requests.dto.NegotiationCounterRequest(new BigDecimal("33"), "non")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("counter-not-allowed-firm-price");
        }
    }

    @Nested
    @DisplayName("finalizeAfterPayment() — details completeness check")
    class FinalizeAfterPaymentTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test
        @DisplayName("détails incomplets (recipientName null) → 422 details-incomplete")
        void finalize_requiresCompleteDetails() {
            request.setRecipientName(null); // détails incomplets intentionnellement
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("details-incomplete");
        }

        @Test
        @DisplayName("détails incomplets (recipientPhone null) → 422 details-incomplete")
        void finalize_requiresRecipientPhone() {
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone(null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("details-incomplete");
        }

        @Test
        @DisplayName("name + phone seuls (sans adresse/declaredValue) → finalize OK")
        void finalize_onlyNameAndPhone_succeeds() {
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            // address, declaredValue, disclaimer left null on purpose
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_789");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
        }

        @Test
        @DisplayName("thread CASH — commission prélevée (port=true) → finalize OK + ACCEPTED")
        void finalize_cashThread_commissionCharged_succeeds() {
            thread.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            // Mode final CASH → on tente de libérer tout escrow carte en vol (ici aucun : no-op → true).
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            // Simule le comportement réel de CashCommissionService.chargeNegotiationCommission :
            // stamper commissionChargedVia sur le thread en même temps que le charge réussi.
            when(cashGatePort.chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any()))
                .thenAnswer(inv -> {
                    thread.setCommissionChargedVia("WALLET");
                    return true;
                });
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_cash");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
            verify(cashGatePort).chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID),
                eq(thread.getCurrentPriceEur()));
            org.mockito.ArgumentCaptor<PackageRequestAcceptedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PackageRequestAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().paymentMethod())
                .isEqualTo(com.yadony.api.payments.cash.PaymentMethod.CASH);
            // Sans cette propagation, le bid matérialisé (ThreadAcceptedBidListener) ne
            // saurait pas comment rembourser la commission si annulé avant remise.
            assertThat(captor.getValue().commissionChargedVia()).isEqualTo("WALLET");
        }

        @Test
        @DisplayName("thread CASH — commission échoue (port=false) → 422 et thread reste AWAITING_PAYMENT")
        void finalize_cashThread_commissionFails_throws422AndNotFinalized() {
            thread.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.CASH);
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            when(cashGatePort.chargeNegotiationCashCommission(eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any()))
                .thenReturn(false);

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negotiation/commission-charge-failed");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("idempotent : thread déjà ACCEPTED avec le même PaymentIntent → succès sans 409 ni event ré-publié")
        void finalize_alreadyAcceptedSamePaymentIntent_isIdempotent() {
            // Race du double finalize (/checkout synchrone + webhook Stripe) : le premier a
            // déjà accepté ce thread avec CE PaymentIntent. Le second ne doit PAS lever 409 ni
            // re-publier l'event (sinon bid/QR/tracking en double) → retour idempotent ACCEPTED.
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setPaymentIntentId("pi_already");
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_already");

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
            verify(threadRepo, never()).save(any()); // pas de re-finalize
        }

        @Test
        @DisplayName("vrai conflit : thread REJECTED → 409 not-awaiting-payment (pas idempotent)")
        void finalize_rejectedThread_throws409() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }

        @Test
        @DisplayName("trajet dédié finalisé → l'annonce liée devient surplusEligible")
        void finalize_dedicatedTrip_marksAnnouncementSurplusEligible() {
            UUID annId = UUID.randomUUID();
            thread.setTravelerAnnouncementId(annId);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.yadony.api.matching.AnnouncementEntity dedicatedAnn = new com.yadony.api.matching.AnnouncementEntity();
            dedicatedAnn.setLinkedPackageRequestId(REQUEST_ID); // dédié
            dedicatedAnn.setReservedKg(new BigDecimal("5"));

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(dedicatedAnn));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_dedicated");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(dedicatedAnn.isSurplusEligible()).isTrue();
            verify(announcementRepo).save(dedicatedAnn);
        }

        @Test
        @DisplayName("trajet existant (non dédié) finalisé → l'annonce n'est PAS marquée éligible")
        void finalize_nonDedicatedTrip_doesNotMarkSurplusEligible() {
            UUID annId = UUID.randomUUID();
            thread.setTravelerAnnouncementId(annId);
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.yadony.api.matching.AnnouncementEntity publicAnn = new com.yadony.api.matching.AnnouncementEntity();
            publicAnn.setLinkedPackageRequestId(null); // trajet public/existant

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(announcementRepo.findById(annId)).thenReturn(Optional.of(publicAnn));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_public");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(publicAnn.isSurplusEligible()).isFalse();
            // not a dedicated trip → no surplus-eligibility save on the announcement
            verify(announcementRepo, never()).save(publicAnn);
        }
    }

    @Nested
    @DisplayName("checkout() — idempotence vs finalize webhook concurrent")
    class CheckoutIdempotencyTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
            request.setRecipientName("Fatou Diop");
            request.setRecipientPhone("+221771234567");
        }

        @Test
        @DisplayName("webhook gagne la course du @Version (optimistic lock) → succès ACCEPTED, pas de 409")
        void checkout_optimisticLockLoser_returnsAcceptedSuccess() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.verifyNegotiationEscrow(eq(THREAD_ID), any())).thenReturn(true);
            // Le webhook concurrent a déjà commité → notre save perd la course du @Version.
            when(threadRepo.save(any())).thenThrow(
                new org.springframework.orm.ObjectOptimisticLockingFailureException(
                    NegotiationThreadEntity.class, THREAD_ID));
            // Relecture (getById) : finalizeInternal a déjà passé le thread à ACCEPTED en
            // mémoire avant le save qui échoue — comme le webhook gagnant l'a fait en base.
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.checkout(SENDER_ID, THREAD_ID, "pi_real", null);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
        }

        @Test
        @DisplayName("thread déjà ACCEPTED (webhook a finalisé avant la lecture) → succès idempotent")
        void checkout_threadAlreadyAccepted_returnsSuccess() {
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            var resp = service.checkout(SENDER_ID, THREAD_ID, "pi_real", null);

            assertThat(resp.status()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            // pas de re-finalize → aucun event ré-publié (pas de bid/QR/tracking en double)
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("vrai conflit (thread REJECTED, pas une course) → 409 propagé au caller")
        void checkout_genuineConflict_rethrows() {
            thread.setStatus(NegotiationThreadStatus.REJECTED);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            assertThatThrownBy(() -> service.checkout(SENDER_ID, THREAD_ID, "pi_real", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }
    }

    @Nested
    @DisplayName("openSurplus()")
    class OpenSurplusTests {

        private final UUID ANN_ID = UUID.randomUUID();
        private com.yadony.api.matching.AnnouncementEntity ann;
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupAnnAndThread() {
            ann = new com.yadony.api.matching.AnnouncementEntity();
            ann.setTravelerId(TRAVELER_ID);
            ann.setLinkedPackageRequestId(REQUEST_ID); // dédié
            ann.setReservedKg(new BigDecimal("5"));
            ann.setAvailableKg(new BigDecimal("5"));
            ann.setTotalKg(new BigDecimal("5"));
            ann.setPricePerKg(new BigDecimal("16.00"));
            ann.setSurplusEligible(true);
            ann.setSurplusPublished(false);

            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.ACCEPTED);
            thread.setCurrentPriceEur(new BigDecimal("80"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
        }

        @Test
        @DisplayName("succès → availableKg=surplus, totalKg=reserved+surplus, pricePerKg=surplusPrice, surplusPublished")
        void openSurplus_success() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.of(thread));
            when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.openSurplus(TRAVELER_ID, ANN_ID, new BigDecimal("8"), new BigDecimal("7"));

            assertThat(ann.getAvailableKg()).isEqualByComparingTo("8");
            assertThat(ann.getTotalKg()).isEqualByComparingTo("13"); // reserved 5 + surplus 8
            assertThat(ann.getPricePerKg()).isEqualByComparingTo("7");
            assertThat(ann.isSurplusPublished()).isTrue();
            verify(announcementRepo).save(ann);
            verify(auditService).log(eq("ANNOUNCEMENT"), eq(ANN_ID), eq("SURPLUS_OPENED"), eq(TRAVELER_ID), anyMap());
        }

        @Test
        @DisplayName("annonce introuvable → 404")
        void openSurplus_notFound_throws404() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("announcement/not-found");
        }

        @Test
        @DisplayName("caller ≠ voyageur → 403 negotiation/not-traveler")
        void openSurplus_notTraveler_throws403() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(UUID.randomUUID(), ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negotiation/not-traveler");
            verify(announcementRepo, never()).save(any());
        }

        @Test
        @DisplayName("trajet non dédié (linkedPackageRequestId null) → 422 surplus/not-dedicated")
        void openSurplus_notDedicated_throws422() {
            ann.setLinkedPackageRequestId(null);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/not-dedicated");
        }

        @Test
        @DisplayName("déjà publié → 409 surplus/already-open")
        void openSurplus_alreadyOpen_throws409() {
            ann.setSurplusPublished(true);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/already-open");
        }

        @Test
        @DisplayName("surplusKg < 1 → 422 surplus/invalid-kg")
        void openSurplus_invalidKg_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("0.5"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-kg");
        }

        @Test
        @DisplayName("surplusKg null → 422 surplus/invalid-kg")
        void openSurplus_nullKg_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                null, new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-kg");
        }

        @Test
        @DisplayName("pricePerKg <= 0 → 422 surplus/invalid-price")
        void openSurplus_invalidPrice_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), BigDecimal.ZERO))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-price");
        }

        @Test
        @DisplayName("pricePerKg null → 422 surplus/invalid-price")
        void openSurplus_nullPrice_throws422() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/invalid-price");
        }

        @Test
        @DisplayName("aucun thread pour le trajet → 409 surplus/negotiation-not-accepted")
        void openSurplus_noThread_throws409() {
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/negotiation-not-accepted");
            verify(announcementRepo, never()).save(any());
        }

        @Test
        @DisplayName("thread non ACCEPTED → 409 surplus/negotiation-not-accepted")
        void openSurplus_threadNotAccepted_throws409() {
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            when(announcementRepo.findById(ANN_ID)).thenReturn(Optional.of(ann));
            when(threadRepo.findByTravelerAnnouncementId(ANN_ID)).thenReturn(Optional.of(thread));
            assertThatThrownBy(() -> service.openSurplus(TRAVELER_ID, ANN_ID,
                new BigDecimal("8"), new BigDecimal("7")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("surplus/negotiation-not-accepted");
            verify(announcementRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("toResponse() — Modèle B champs calculés")
    class ToResponseModelBTests {

        @Test
        @DisplayName("grossPriceEur exposé = net * (1 + rate) — modèle B")
        void toResponse_exposesGrossPrice_modelB() {
            // Préparer un thread OPEN avec currentPriceEur (net) = 35
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrency("CAD");
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            // commission rate = 0.12 (already stubbed in @BeforeEach)

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            // Appeler toResponse directement
            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            // Asserter: grossPriceEur ≈ 39.20 (35 * 1.12)
            assertThat(response.grossPriceEur())
                .isNotNull()
                .isEqualByComparingTo("39.20");

            // paymentMethod is null (not set on entity)
            assertThat(response.paymentMethod()).isNull();
            assertThat(response.currency()).isEqualTo("CAD");
        }

        @Test
        @DisplayName("currentPriceEur null → grossPriceEur null (pas de NPE)")
        void toResponse_nullCurrentPrice_returnsNullGross() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(null); // prix non encore fixé
            thread.setRoundsCount((short) 0);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            // Ne doit pas lancer de NullPointerException
            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.grossPriceEur()).isNull();
            assertThat(response.currentPriceEur()).isNull();
        }

        @Test
        @DisplayName("paymentMethod exposé depuis l'entité thread")
        void toResponse_exposesPaymentMethod() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("40"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            thread.setPaymentMethod(com.yadony.api.payments.cash.PaymentMethod.WAVE);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.paymentMethod()).isEqualTo(com.yadony.api.payments.cash.PaymentMethod.WAVE);
        }

        @Test
        @DisplayName("linkedAnn KG_FREE → capacityUnit exposé dans linkedTrip ET travelerCapacityUnit")
        void toResponse_kgFree_exposesCapacityUnit() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            com.yadony.api.matching.AnnouncementEntity linkedAnn =
                new com.yadony.api.matching.AnnouncementEntity();
            linkedAnn.setDepartureCity("Paris");
            linkedAnn.setArrivalCity("Dakar");
            linkedAnn.setAvailableKg(new BigDecimal("1"));
            linkedAnn.setCapacityUnit(com.yadony.api.matching.CapacityUnit.KG_FREE);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(linkedAnn, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", linkedAnn);

            assertThat(response.linkedTrip()).isNotNull();
            assertThat(response.linkedTrip().capacityUnit()).isEqualTo("KG_FREE");
            assertThat(response.travelerCapacityUnit()).isEqualTo("KG_FREE");
        }

        @Test
        @DisplayName("linkedAnn null → travelerCapacityUnit null (fallback kg côté front)")
        void toResponse_nullLinkedAnn_nullCapacityUnit() {
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            NegotiationThreadResponse response = service.toResponse(
                thread, List.of(), null, traveler, request, TRAVELER_ID, "Expéditeur", null);

            assertThat(response.linkedTrip()).isNull();
            assertThat(response.travelerCapacityUnit()).isNull();
        }
    }

    // ─── Task 13 — tests supplémentaires pour couvrir listMine, listForRequest,
    //              finalizeAfterPayment happy-path, et NegotiationPaymentAuthorizedEvent ─────────

    @Nested
    @DisplayName("listMine() — threads du participant")
    class ListMineTests {

        @Test
        @DisplayName("listMine() retourne les threads de l'utilisateur avec toResponse mappé")
        void listMine_returnsThreadsForUser() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("30"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));

            when(threadRepo.findByParticipant(TRAVELER_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler)); // reuse as sender

            List<com.yadony.api.requests.dto.NegotiationThreadResponse> result = service.listMine(TRAVELER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).currentPriceEur()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("listMine() ignore les threads avec request ou traveler soft-deleted (retourne vide)")
        void listMine_skipsOrphanedThreads() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("25"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findByParticipant(TRAVELER_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.empty()); // orphaned

            List<com.yadony.api.requests.dto.NegotiationThreadResponse> result = service.listMine(TRAVELER_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("listForRequest() — threads d'une demande")
    class ListForRequestTests {

        @Test
        @DisplayName("listForRequest() retourne les threads quand le caller est le sender")
        void listForRequest_returnThreadsForSender() {
            UUID threadId = UUID.randomUUID();
            NegotiationThreadEntity thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.OPEN);
            thread.setCurrentPriceEur(new BigDecimal("28"));
            thread.setRoundsCount((short) 1);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, threadId);
            } catch (Exception e) { throw new RuntimeException(e); }

            request.setDepartureCity("Lyon");
            request.setArrivalCity("Abidjan");
            request.setWeightKg(new BigDecimal("3"));

            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread));
            when(announcementRepo.findAllById(any())).thenReturn(List.of());
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            List<com.yadony.api.requests.dto.NegotiationThreadResponse> result =
                service.listForRequest(SENDER_ID, REQUEST_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("listForRequest() throws 403 si le caller n'est pas le sender")
        void listForRequest_throwsForbiddenForNonSender() {
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.listForRequest(TRAVELER_ID, REQUEST_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("forbidden");
        }

        @Test
        @DisplayName("listForRequest() throws 404 si la request n'existe pas")
        void listForRequest_throwsNotFoundForMissingRequest() {
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listForRequest(SENDER_ID, REQUEST_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-found");
        }
    }

    @Nested
    @DisplayName("finalizeAfterPayment() — chemin nominal")
    class FinalizeAfterPaymentHappyPathTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        @BeforeEach
        void setupThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(thread, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }

            // Complet request details
            request.setRecipientName("Mamadou Diallo");
            request.setRecipientPhone("+221771234567");
            request.setPickupAddressLabel("10 rue de la Paix, Paris");
            request.setDeliveryAddressLabel("Plateau, Dakar");
            request.setDisclaimerSignedAt(java.time.LocalDateTime.now());
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
        }

        @Test
        @DisplayName("finalize avec détails complets → thread ACCEPTED, request ACCEPTED, event publié")
        void finalize_completeDetails_acceptsThread() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of()); // no competing
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // thread.getTravelerAnnouncementId() is null → announcementRepo not called

            com.yadony.api.requests.dto.NegotiationThreadResponse result =
                service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_123");

            assertThat(result).isNotNull();
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);

            org.mockito.ArgumentCaptor<PackageRequestAcceptedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PackageRequestAcceptedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            PackageRequestAcceptedEvent published = captor.getValue();
            assertThat(published.recipientName()).isEqualTo("Mamadou Diallo");
            assertThat(published.recipientPhone()).isEqualTo("+221771234567");
            assertThat(published.disclaimerSignedAt()).isEqualTo(request.getDisclaimerSignedAt());
        }

        @Test
        @DisplayName("finalize auto-rejette les threads concurrents OPEN/AWAITING_TRIP/AWAITING_PAYMENT")
        void finalize_autoRejectsCompetingThreads() {
            NegotiationThreadEntity competing = new NegotiationThreadEntity();
            competing.setPackageRequestId(REQUEST_ID);
            competing.setTravelerId(UUID.randomUUID());
            competing.setStatus(NegotiationThreadStatus.OPEN);
            competing.setCurrentPriceEur(new BigDecimal("40"));
            competing.setRoundsCount((short) 1);
            competing.setLastActivityAt(java.time.LocalDateTime.now());
            UUID competingId = UUID.randomUUID();
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(competing, competingId);
            } catch (Exception e) { throw new RuntimeException(e); }

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of(thread, competing));
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // thread.getTravelerAnnouncementId() is null → announcementRepo not called

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_456");

            assertThat(competing.getStatus()).isEqualTo(NegotiationThreadStatus.AUTO_REJECTED);
        }

        @Test
        @DisplayName("finalize throws 409 si le thread n'est pas en AWAITING_PAYMENT")
        void finalize_wrongStatus_throws409() {
            thread.setStatus(NegotiationThreadStatus.OPEN);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-awaiting-payment");
        }

        @Test
        @DisplayName("finalize throws 403 si le caller n'est pas le sender")
        void finalize_nonSender_throws403() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() -> service.finalizeAfterPayment(TRAVELER_ID, THREAD_ID, "pi_x"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("not-thread-participant");
        }

        @Test
        @DisplayName("finalize avec un mode de paiement non autorisé par la demande → 422")
        void finalize_chosenMethodNotAccepted_throws422() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x", PaymentMethod.CASH))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("payment-method/not-accepted");
        }

        @Test
        @DisplayName("finalize avec un mode hors du SET fournissable par le voyageur (availablePaymentMethods) → 422")
        void finalize_chosenMethodNotInAvailableSet_throws422() {
            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            // Voyageur non onboardé Stripe : seul CASH est réellement fournissable,
            // bien que STRIPE reste accepté par la demande.
            thread.setAvailablePaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            thread.setPaymentMethod(null);

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            org.springframework.web.server.ResponseStatusException ex = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x", PaymentMethod.STRIPE));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
            assertEquals("payment-method/not-in-available-set", ex.getReason());
        }

        @Test
        @DisplayName("finalize STRIPE ne libère JAMAIS l'escrow carte (choisir STRIPE n'est pas une bascule)")
        void finalize_chosenMethodStripe_neverReleasesEscrow() {
            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            thread.setPaymentMethod(PaymentMethod.CASH);

            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            // Mode final STRIPE → /checkout vérifie l'escrow online (jamais de libération).
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "pi_real_override")).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_override", PaymentMethod.STRIPE);

            // Le mode retenu est STRIPE ; la branche cash (commission) n'est PAS déclenchée
            // et surtout l'escrow carte de l'expéditeur n'est JAMAIS annulé (sécurité escrow).
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
            org.mockito.Mockito.verifyNoInteractions(cashGatePort);
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
        }
    }

    @Nested
    @DisplayName("checkout() — vérification de l'escrow online (anti-bypass paiement)")
    class CheckoutEscrowVerificationTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        private void setId(Object entity, UUID id) {
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entity, id);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @BeforeEach
        void setupAwaitingPaymentThread() {
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setPaymentMethod(PaymentMethod.STRIPE);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            setId(thread, THREAD_ID);

            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            request.setRecipientName("Mamadou Diallo");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
        }

        @Test
        @DisplayName("PaymentIntent non vérifié par Stripe → 422, thread reste AWAITING_PAYMENT, aucun event")
        void checkout_stripeUnverifiedEscrow_throws422_doesNotFinalize() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "x")).thenReturn(false);

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "x", PaymentMethod.STRIPE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("escrow-not-verified");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            assertThat(request.getStatus()).isNotEqualTo(PackageRequestStatus.ACCEPTED);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("PaymentIntent vérifié (requires_capture) → thread ACCEPTED + event")
        void checkout_stripeVerifiedEscrow_finalizes() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "pi_real_ok")).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_real_ok", PaymentMethod.STRIPE);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(eventPublisher).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("switch vers CASH → libère l'escrow Stripe puis finalise en CASH")
        void checkout_switchToCash_releasesEscrowThenFinalizes() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            when(cashGatePort.chargeNegotiationCashCommission(
                    eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any())).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH);

            verify(escrowPort).releaseEscrowForMethodSwitch(THREAD_ID);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verify(cashGatePort).chargeNegotiationCashCommission(
                eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any());
        }

        @Test
        @DisplayName("switch vers CASH mais escrow Stripe impossible à libérer → 409, pas de bascule ni de finalize")
        void checkout_switchToCash_releaseFails_throws409() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(false);

            assertThatThrownBy(() ->
                    service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("escrow-release-failed");

            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE); // pas de bascule
            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.AWAITING_PAYMENT);
            verifyNoInteractions(cashGatePort);
            verify(eventPublisher, never()).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("webhook (3-arg) finalise un thread STRIPE sans appeler escrowPort (déjà vérifié par Stripe)")
        void webhookFinalize_stripe_doesNotCallEscrowPort() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_webhook");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            verifyNoInteractions(escrowPort);
        }
    }

    @Nested
    @DisplayName("finalizeInternal() — modèle SET (thread.paymentMethod null post-linking)")
    class SetModelFinalizeTests {

        private final UUID THREAD_ID = UUID.randomUUID();
        private NegotiationThreadEntity thread;

        private void setId(Object entity, UUID id) {
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entity, id);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @BeforeEach
        void setupPostLinkingThread() {
            // Forme RÉELLE post-trip-linking : le voyageur ne fixe plus de mode → paymentMethod
            // null, et le SET fournissable est persisté sur le thread.
            thread = new NegotiationThreadEntity();
            thread.setPackageRequestId(REQUEST_ID);
            thread.setTravelerId(TRAVELER_ID);
            thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
            thread.setCurrentPriceEur(new BigDecimal("35"));
            thread.setRoundsCount((short) 2);
            thread.setPaymentMethod(null);
            thread.setLastActivityAt(java.time.LocalDateTime.now());
            setId(thread, THREAD_ID);

            request.setRecipientName("Mamadou Diallo");
            request.setRecipientPhone("+221771234567");
            request.setDepartureCity("Paris");
            request.setArrivalCity("Dakar");
            request.setWeightKg(new BigDecimal("5"));
        }

        private void stubFinalizeCollaborators() {
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(threadRepo.findByPackageRequestId(REQUEST_ID)).thenReturn(List.of());
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
        }

        @Test
        @DisplayName("1. webhook STRIPE (chosenMethod null, paymentMethod null, SET={STRIPE}) → ACCEPTED en STRIPE, pas de release")
        void webhookStripe_postLinking_finalizesToStripe() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            thread.setAvailablePaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            stubFinalizeCollaborators();

            // Chemin webhook (3-arg) : le sender a déjà payé (carte autorisée), Stripe a émis
            // amount_capturable_updated. Le thread DOIT finaliser malgré paymentMethod null.
            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_webhook_set");

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.ACCEPTED);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
            verify(eventPublisher).publishEvent(any(PackageRequestAcceptedEvent.class));
        }

        @Test
        @DisplayName("2. checkout STRIPE (chosenMethod STRIPE, paymentMethod null, SET={STRIPE}) → ACCEPTED, escrow vérifié, jamais libéré")
        void checkoutStripe_postLinking_verifiesEscrowNeverReleases() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            thread.setAvailablePaymentMethods(java.util.EnumSet.of(PaymentMethod.STRIPE));
            stubFinalizeCollaborators();
            when(escrowPort.verifyNegotiationEscrow(THREAD_ID, "pi_sync_set")).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_sync_set", PaymentMethod.STRIPE);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.STRIPE);
            // L'escrow live du sender NE DOIT JAMAIS être annulé sur un checkout STRIPE.
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
            verify(escrowPort).verifyNegotiationEscrow(THREAD_ID, "pi_sync_set");
        }

        @Test
        @DisplayName("3. checkout CASH (chosenMethod CASH, paymentMethod null, SET={CASH}) → ACCEPTED en CASH, commission prélevée")
        void checkoutCash_postLinking_chargesCommission() {
            request.setAcceptedPaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            thread.setAvailablePaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            stubFinalizeCollaborators();
            // Pas d'escrow carte en vol → release no-op (true).
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            when(cashGatePort.chargeNegotiationCashCommission(
                    eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any())).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            verify(cashGatePort).chargeNegotiationCashCommission(
                eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any());
        }

        @Test
        @DisplayName("4. bascule STRIPE→CASH (chosenMethod CASH, SET={STRIPE,CASH}) → CASH, release appelé UNE fois")
        void switchStripeToCash_postLinking_releasesEscrowOnce() {
            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            thread.setAvailablePaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            stubFinalizeCollaborators();
            // Le sender avait initié un escrow carte puis choisi cash → hold carte annulé.
            when(escrowPort.releaseEscrowForMethodSwitch(THREAD_ID)).thenReturn(true);
            when(cashGatePort.chargeNegotiationCashCommission(
                    eq(TRAVELER_ID), eq(SENDER_ID), eq(THREAD_ID), any())).thenReturn(true);

            service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "CASH", PaymentMethod.CASH);

            assertThat(thread.getStatus()).isEqualTo(NegotiationThreadStatus.ACCEPTED);
            assertThat(thread.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
            verify(escrowPort, times(1)).releaseEscrowForMethodSwitch(THREAD_ID);
        }

        @Test
        @DisplayName("5. Task 5 préservée : SET={CASH}, chosenMethod STRIPE → 422 not-in-available-set")
        void chosenMethodNotInSet_postLinking_throws422() {
            request.setAcceptedPaymentMethods(
                java.util.EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
            thread.setAvailablePaymentMethods(java.util.EnumSet.of(PaymentMethod.CASH));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.finalizeAfterPayment(SENDER_ID, THREAD_ID, "pi_x", PaymentMethod.STRIPE));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
            assertEquals("payment-method/not-in-available-set", ex.getReason());
            verify(escrowPort, never()).releaseEscrowForMethodSwitch(any());
        }
    }

    // ========== AvatarUrl in NegotiationThreadResponse ==========

    @Nested
    @DisplayName("toResponse() — photo URLs")
    class PhotoUrlTests {

        private final UUID THREAD_ID = UUID.randomUUID();

        private NegotiationThreadEntity buildThread() {
            NegotiationThreadEntity t = new NegotiationThreadEntity();
            t.setPackageRequestId(REQUEST_ID);
            t.setTravelerId(TRAVELER_ID);
            t.setStatus(NegotiationThreadStatus.OPEN);
            t.setCurrentPriceEur(new BigDecimal("30"));
            t.setRoundsCount((short) 1);
            t.setLastActivityAt(java.time.LocalDateTime.now());
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(t, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
            return t;
        }

        @Test
        @DisplayName("travelerPhotoUrl mappé depuis UserEntity du voyageur")
        void toResponse_travelerPhotoUrl_isMapped() {
            traveler.setAvatarUrl("https://cdn.example.com/traveler.jpg");
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler)); // sender returns same user

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.travelerPhotoUrl()).isEqualTo("https://cdn.example.com/traveler.jpg");
        }

        @Test
        @DisplayName("senderPhotoUrl mappé depuis UserEntity de l'expéditeur")
        void toResponse_senderPhotoUrl_isMapped() {
            UserEntity sender = new UserEntity();
            sender.setAvatarUrl("https://cdn.example.com/sender.jpg");
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.senderPhotoUrl()).isEqualTo("https://cdn.example.com/sender.jpg");
        }

        @Test
        @DisplayName("sender introuvable → senderPhotoUrl null")
        void toResponse_senderNotFound_senderPhotoUrlNull() {
            request.setNegotiable(true);
            NegotiationThreadEntity thread = buildThread();

            when(config.maxNegotiationRounds()).thenReturn(5);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            var response = service.getById(SENDER_ID, THREAD_ID);

            assertThat(response.senderPhotoUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("nudge()")
    class NudgeTests {

        private final UUID THREAD_ID = UUID.randomUUID();

        private NegotiationThreadEntity buildThread(NegotiationThreadStatus status,
                                                     java.time.LocalDateTime lastActivityAt,
                                                     java.time.LocalDateTime lastNudgeAt) {
            NegotiationThreadEntity t = new NegotiationThreadEntity();
            t.setPackageRequestId(REQUEST_ID);
            t.setTravelerId(TRAVELER_ID);
            t.setStatus(status);
            t.setCurrentPriceEur(new BigDecimal("30"));
            t.setRoundsCount((short) 1);
            t.setLastActivityAt(lastActivityAt);
            t.setLastNudgeAt(lastNudgeAt);
            try {
                var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(t, THREAD_ID);
            } catch (Exception e) { throw new RuntimeException(e); }
            return t;
        }

        @Test
        @DisplayName("caller ni sender ni traveler => 403")
        void nudge_nonParticipant_throws403() {
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.OPEN,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            UUID stranger = UUID.randomUUID();
            var ex = assertThrows(ResponseStatusException.class, () -> service.nudge(stranger, THREAD_ID));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            assertThat(ex.getReason()).isEqualTo("negotiation/not-thread-participant");
        }

        @Test
        @DisplayName("status AWAITING_PAYMENT => 409 nudge/not-active")
        void nudge_statusNotOpenOrAwaitingTrip_throws409NotActive() {
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.AWAITING_PAYMENT,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            var ex = assertThrows(ResponseStatusException.class, () -> service.nudge(SENDER_ID, THREAD_ID));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertThat(ex.getReason()).isEqualTo("nudge/not-active");
        }

        @Test
        @DisplayName("caller est celui qui doit agir (voyageur en AWAITING_TRIP) => 409 nudge/not-your-wait")
        void nudge_callerIsTheOneWhoMustAct_throws409NotYourWait() {
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.AWAITING_TRIP,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            // Le voyageur doit agir en AWAITING_TRIP -> il ne peut pas relancer.
            var ex = assertThrows(ResponseStatusException.class, () -> service.nudge(TRAVELER_ID, THREAD_ID));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertThat(ex.getReason()).isEqualTo("nudge/not-your-wait");
        }

        @Test
        @DisplayName("lastActivityAt il y a 20 min => 409 nudge/too-early")
        void nudge_beforeOneHour_throws409TooEarly() {
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.AWAITING_TRIP,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20), null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            // Le sender attend (le voyageur doit agir) mais l'activité est trop récente.
            var ex = assertThrows(ResponseStatusException.class, () -> service.nudge(SENDER_ID, THREAD_ID));

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            assertThat(ex.getReason()).isEqualTo("nudge/too-early");
        }

        @Test
        @DisplayName("lastNudgeAt il y a 20 min => 429 nudge/rate-limited")
        void nudge_rateLimited_throws429() {
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.AWAITING_TRIP,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20));
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));

            var ex = assertThrows(ResponseStatusException.class, () -> service.nudge(SENDER_ID, THREAD_ID));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
            assertThat(ex.getReason()).isEqualTo("nudge/rate-limited");
        }

        @Test
        @DisplayName("succès : notifie la partie qui attend, set lastNudgeAt, garde lastActivityAt, canNudge=false")
        void nudge_success_notifiesOtherParty_setsLastNudgeAt_keepsLastActivityAt() {
            java.time.LocalDateTime oldActivity =
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2);
            NegotiationThreadEntity thread = buildThread(NegotiationThreadStatus.AWAITING_TRIP, oldActivity, null);
            when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
            when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
            when(messageRepo.findByThreadIdOrderByCreatedAtAsc(THREAD_ID)).thenReturn(List.of());
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(threadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(config.maxNegotiationRounds()).thenReturn(5);

            var response = service.nudge(SENDER_ID, THREAD_ID);

            ArgumentCaptor<com.yadony.api.requests.event.NegotiationNudgeSentEvent> eventCaptor =
                ArgumentCaptor.forClass(com.yadony.api.requests.event.NegotiationNudgeSentEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            var publishedEvent = eventCaptor.getValue();
            assertThat(publishedEvent.toUserId()).isEqualTo(TRAVELER_ID);
            assertThat(publishedEvent.fromUserId()).isEqualTo(SENDER_ID);
            assertThat(publishedEvent.threadId()).isEqualTo(THREAD_ID);
            verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(THREAD_ID), eq("NUDGE_SENT"), eq(SENDER_ID), any());
            assertThat(thread.getLastNudgeAt()).isNotNull();
            assertThat(thread.getLastActivityAt()).isEqualTo(oldActivity);
            assertThat(response.canNudge()).isFalse();
        }
    }
}
