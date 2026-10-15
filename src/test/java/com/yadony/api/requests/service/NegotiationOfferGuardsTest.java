package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.NegotiationCounterRequest;
import com.yadony.api.requests.dto.NegotiationStartRequest;
import com.yadony.api.requests.entity.NegotiationMessageEntity;
import com.yadony.api.requests.entity.NegotiationMessageKind;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.settings.MinPricePerKgGuard;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offres sur la demande d'un expéditeur (recette du 2026-10-11) : « profils vérifiés
 * uniquement » de l'expéditeur appliqué au voyageur (403 {@code contact-kyc-required}),
 * et prix minimum par kilo du voyageur appliqué aux contre-offres de l'expéditeur
 * (422 {@code bid-below-min-price}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NegotiationService — gardes d'offre dans les deux sens")
class NegotiationOfferGuardsTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private com.yadony.api.requests.NegotiationProperties negotiationProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private com.yadony.api.requests.NegotiationMobileMoneyPort mobileMoneyPort;
    @Mock private UserBusinessPrefsRepository prefsRepository;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();

    private NegotiationService service;
    private UserEntity traveler;
    private UserEntity sender;
    private PackageRequestEntity request;
    private NegotiationThreadEntity thread;

    private static void setId(Object entity, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        traveler = new UserEntity();
        traveler.setKycStatus(KycStatus.VERIFIED);
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        setId(traveler, travelerId);
        sender = new UserEntity();
        sender.setKycStatus(KycStatus.VERIFIED);
        setId(sender, senderId);

        request = new PackageRequestEntity();
        request.setSenderId(senderId);
        request.setStatus(PackageRequestStatus.OPEN);
        request.setWeightKg(new BigDecimal("5.00"));
        setId(request, requestId);

        thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(requestId);
        thread.setTravelerId(travelerId);
        thread.setStatus(NegotiationThreadStatus.OPEN);
        thread.setCurrency("EUR");
        thread.setCurrentPriceEur(new BigDecimal("40"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now());
        setId(thread, threadId);

        lenient().when(commissionRateResolver.globalRate()).thenReturn(new BigDecimal("0.12"));
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(exchangeRateService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new NegotiationService(requestRepo, threadRepo, messageRepo, userRepository,
                announcementRepo, eventPublisher, auditService, config, negotiationProperties,
                cashGatePort, escrowPort, storageService, photoService,
                commissionRateResolver, exchangeRateService, mobileMoneyPort, TestMessages.resolver());
        service.setMinPriceGuard(new MinPricePerKgGuard(prefsRepository, exchangeRateService));
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    private NegotiationStartRequest startRequest() {
        return new NegotiationStartRequest(requestId, new BigDecimal("30"),
                LocalDate.now().plusDays(5), new BigDecimal("10"), null, null);
    }

    private void travelerMinimum(int min) {
        UserBusinessPrefsEntity p = new UserBusinessPrefsEntity();
        p.setUserId(travelerId);
        p.setCurrencyCode("EUR");
        p.setMinBidPriceEur(min);
        when(prefsRepository.findById(travelerId)).thenReturn(Optional.of(p));
    }

    /** Fil ouvert, dernier message de {@code lastAuthor} : c'est au tour de l'autre. */
    private void openThreadAwaiting(UUID lastAuthor) {
        when(config.maxNegotiationRounds()).thenReturn(5);
        when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        var last = NegotiationMessageEntity.create(threadId, lastAuthor,
                NegotiationMessageKind.PROPOSAL, new BigDecimal("40"), null);
        lenient().when(messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(last));
    }

    @Nested
    @DisplayName("Profils vérifiés uniquement (option de l'expéditeur)")
    class VerifiedOnly {

        @Test
        @DisplayName("offre d'un voyageur non vérifié, option active : 403 contact-kyc-required")
        void start_unverifiedTraveler_optionOn_isRefused() {
            traveler.setKycStatus(KycStatus.PENDING);
            sender.setContactKycOnly(true);
            when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
            when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.start(travelerId, startRequest()))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getErrorCode()).isEqualTo("contact-kyc-required");
                    });
        }

        @Test
        @DisplayName("option désactivée : l'exigence propre aux offres sur demande reste kyc/not-verified")
        void start_unverifiedTraveler_optionOff_keepsGenericKycError() {
            traveler.setKycStatus(KycStatus.PENDING);
            sender.setContactKycOnly(false);
            when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
            when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

            assertThatThrownBy(() -> service.start(travelerId, startRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("kyc/not-verified");
        }

        @Test
        @DisplayName("demande introuvable : kyc/not-verified, sans lever d'autre erreur")
        void start_unverifiedTraveler_unknownRequest_keepsGenericKycError() {
            traveler.setKycStatus(KycStatus.PENDING);
            when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
            when(requestRepo.findById(requestId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start(travelerId, startRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("kyc/not-verified");
        }

        @Test
        @DisplayName("contre-offre d'un voyageur devenu non vérifié, option active : 403")
        void counter_unverifiedTraveler_optionOn_isRefused() {
            openThreadAwaiting(senderId);
            traveler.setKycStatus(KycStatus.PENDING);
            sender.setContactKycOnly(true);

            assertThatThrownBy(() -> service.counter(travelerId, threadId,
                    new NegotiationCounterRequest(new BigDecimal("45"), null)))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("contact-kyc-required"));
            verify(messageRepo, never()).save(any());
        }

        @Test
        @DisplayName("contre-offre d'un voyageur non vérifié, option désactivée : acceptée")
        void counter_unverifiedTraveler_optionOff_isAccepted() {
            openThreadAwaiting(senderId);
            traveler.setKycStatus(KycStatus.PENDING);
            sender.setContactKycOnly(false);

            service.counter(travelerId, threadId, new NegotiationCounterRequest(new BigDecimal("45"), null));

            verify(messageRepo).save(argThat(m -> m.getFromUserId().equals(travelerId)));
        }
    }

    @Nested
    @DisplayName("Prix minimum par kg (réglage du voyageur)")
    class MinPrice {

        @Test
        @DisplayName("contre-offre de l'expéditeur sous le minimum : 422 bid-below-min-price")
        void senderCounter_belowMinimum_isRefused() {
            openThreadAwaiting(travelerId);
            travelerMinimum(6);

            // 25 € net pour 5 kg = 5 €/kg < 6 €/kg
            assertThatThrownBy(() -> service.counter(senderId, threadId,
                    new NegotiationCounterRequest(new BigDecimal("25"), null)))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getErrorCode()).isEqualTo("bid-below-min-price");
                        assertThat(e.getProperties()).containsEntry("currency", "EUR");
                        // Minimum exprimé en brut à l'expéditeur : 6 × 1,12
                        assertThat((BigDecimal) e.getProperties().get("minPricePerKg"))
                                .isEqualByComparingTo("6.72");
                    });
            verify(messageRepo, never()).save(any());
        }

        @Test
        @DisplayName("contre-offre de l'expéditeur au-dessus du minimum : acceptée")
        void senderCounter_aboveMinimum_isAccepted() {
            openThreadAwaiting(travelerId);
            travelerMinimum(6);

            service.counter(senderId, threadId, new NegotiationCounterRequest(new BigDecimal("35"), null));

            verify(messageRepo).save(argThat(m -> m.getFromUserId().equals(senderId)));
        }

        @Test
        @DisplayName("minimum à 0 : aucune restriction")
        void senderCounter_zeroMinimum_isAccepted() {
            openThreadAwaiting(travelerId);
            travelerMinimum(0);

            service.counter(senderId, threadId, new NegotiationCounterRequest(new BigDecimal("1"), null));

            verify(messageRepo).save(argThat(m -> m.getFromUserId().equals(senderId)));
        }

        @Test
        @DisplayName("contre-offre du voyageur : jamais soumise à son propre minimum")
        void travelerCounter_isNeverCheckedAgainstMinimum() {
            openThreadAwaiting(senderId);

            service.counter(travelerId, threadId, new NegotiationCounterRequest(new BigDecimal("1"), null));

            verify(prefsRepository, never()).findById(any());
        }
    }
}
