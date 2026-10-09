package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRecurrenceRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripRecurrenceServiceTest {

    @Mock TripRecurrenceRepository repository;
    @Mock AnnouncementService announcementService;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    @InjectMocks TripRecurrenceService service;

    private final UUID userId = UUID.randomUUID();

    private TripRecurrenceEntity entity(String weekdays, int horizon, LocalDate lastGen) {
        TripRecurrenceEntity e = new TripRecurrenceEntity();
        e.setUserId(userId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setTransportMode("PLANE");
        e.setCapacityUnit("SUITCASE_23KG");
        e.setAvailableKg(23.0);
        e.setPricePerKg(8.0);
        e.setAcceptedCategories("Vêtements,Documents");
        e.setPickupLabel("12 rue de la Paix");
        e.setPickupLat(48.86);
        e.setPickupLng(2.33);
        e.setDeliveryLabel("Aéroport CDG");
        e.setDeliveryLat(49.01);
        e.setDeliveryLng(2.55);
        e.setDepartureTime(LocalTime.of(14, 0));
        e.setWeekdays(weekdays);
        e.setHorizonDays(horizon);
        e.setStartDate(LocalDate.now());
        e.setActive(true);
        e.setLastGeneratedDate(lastGen);
        return e;
    }

    private void mockUser() {
        mockUser(true);
    }

    private void mockUser(boolean stripeConnectActive) {
        mockUser(stripeConnectActive, false);
    }

    private void mockUser(boolean stripeConnectActive, boolean mobileMoneyActive) {
        UserEntity user = mock(UserEntity.class);
        when(user.getFirebaseUid()).thenReturn("firebase-uid");
        lenient().when(user.hasActiveStripeConnect()).thenReturn(stripeConnectActive);
        lenient().when(user.hasActiveMobileMoney()).thenReturn(mobileMoneyActive);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    // STRIPE était imposé à chaque occurrence : en zone CFA ou sans compte Connect, chaque
    // génération échouait en silence et la récurrence ne publiait jamais rien.
    @Test
    void generate_cfaRecurrence_neverAsksForTheCard() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCurrency("XOF");
        rec.setCashAccepted(true);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods()).containsExactly(PaymentMethod.CASH);
    }

    // FLUTTER-4E : une récurrence de vol de nuit publie chaque occurrence avec sa
    // date d'arrivée au lendemain ; le même jour, la date reste absente.
    @Test
    void generate_overnightRecurrence_setsArrivalDateOnEachOccurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setArrivalDayOffset(1);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().arrivalDate()).isEqualTo(cap.getValue().departureDate().plusDays(1));
    }

    @Test
    void generate_sameDayRecurrence_leavesArrivalDateEmpty() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().arrivalDate()).isNull();
    }

    @Test
    void generate_travelerWithoutConnect_fallsBackToCash() {
        mockUser(false);
        TripRecurrenceEntity rec = entity("1111111", 0, null);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods()).containsExactly(PaymentMethod.CASH);
    }

    // La récurrence stockait négociable, devise, note, refusés, mode et délai de remise
    // depuis le lot mobile money, mais buildRequest publiait des constantes : chaque
    // trajet généré sortait Stripe seul, non négociable, sans note, en devise nulle.
    @Test
    void generate_usesTheRecurrenceOwnConditions() {
        mockUser(true);
        // Départ dans 3 jours (pas aujourd'hui) : avec un délai de remise de 2 jours la
        // limite reste dans le futur, donc non affectée par le repli du fix B (départ
        // trop proche) et l'assertion peut vérifier que le délai est bien appliqué.
        LocalDate departureInThreeDays = LocalDate.now().plusDays(3);
        StringBuilder weekdays = new StringBuilder("0000000");
        weekdays.setCharAt(departureInThreeDays.getDayOfWeek().getValue() - 1, '1');
        TripRecurrenceEntity rec = entity(weekdays.toString(), 3, null);
        rec.setCurrency("EUR");
        rec.setNegotiable(true);
        rec.setDescription("Fragile bienvenu");
        rec.setRefusedCategories("Téléphone & électronique");
        rec.setPricingMode(PricingMode.MIXED);
        rec.setHandoverLeadDays(2);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        AnnouncementRequest req = cap.getValue();
        assertThat(req.currency()).isEqualTo("EUR");
        assertThat(req.negotiable()).isTrue();
        assertThat(req.description()).isEqualTo("Fragile bienvenu");
        assertThat(req.refusedTypes()).containsExactly("Téléphone & électronique");
        assertThat(req.pricingMode()).isEqualTo(PricingMode.MIXED);
        assertThat(req.handoverDeadline()).isEqualTo(req.departureDate().atTime(LocalTime.of(14, 0)).minusDays(2));
    }

    @Test
    void generate_cfaRecurrenceWithMobileMoneyAccount_offersMobileMoney() {
        mockUser(false, true);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCurrency("XOF");
        rec.setCashAccepted(true);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods())
                .containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
    }

    @Test
    void generate_noHandoverLead_keepsDepartureAsDeadline() {
        mockUser(true);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setHandoverLeadDays(0);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().handoverDeadline())
                .isEqualTo(cap.getValue().departureDate().atTime(LocalTime.of(14, 0)));
    }

    // B : un délai de remise long combiné à un départ proche produisait une limite déjà
    // expirée à la publication, ouvrant le signalement de no-show dès l'acceptation.
    @Test
    void generate_handoverLeadBeyondHorizon_fallsBackToDeparture() {
        mockUser(true);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        StringBuilder weekdays = new StringBuilder("0000000");
        weekdays.setCharAt(tomorrow.getDayOfWeek().getValue() - 1, '1');
        TripRecurrenceEntity rec = entity(weekdays.toString(), 1, null);
        rec.setHandoverLeadDays(3);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().departureDate()).isEqualTo(tomorrow);
        assertThat(cap.getValue().handoverDeadline())
                .isEqualTo(cap.getValue().departureDate().atTime(LocalTime.of(14, 0)));
    }

    @Test
    void generate_createsForEveryMatchingDayWithinHorizon() {
        mockUser();
        // tous les jours cochés, horizon 6 → 7 jours (today..today+6) → 7 trajets
        TripRecurrenceEntity rec = entity("1111111", 6, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isEqualTo(7);
        verify(announcementService, times(7)).createRecurringAnnouncement(eq("firebase-uid"), any(), eq(rec.getId()));
        assertThat(rec.getLastGeneratedDate()).isEqualTo(LocalDate.now().plusDays(6));
    }

    @Test
    void generate_includesCashAndArrivalTimeFromRecurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCashAccepted(true);
        rec.setArrivalTime(LocalTime.of(18, 30));

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods())
                .contains(PaymentMethod.STRIPE, PaymentMethod.CASH);
        assertThat(cap.getValue().arrivalTime()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void generate_skipsNonMatchingWeekdays() {
        mockUser();
        // aucun jour coché → aucun trajet
        TripRecurrenceEntity rec = entity("0000000", 6, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verify(announcementService, never()).createRecurringAnnouncement(anyString(), any(), any());
    }

    @Test
    void generate_respectsLastGeneratedDate() {
        // déjà généré jusqu'à l'horizon (today) → rien de neuf
        TripRecurrenceEntity rec = entity("1111111", 0, LocalDate.now());

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verifyNoInteractions(announcementService);
    }

    @Test
    void generate_isolatesCreationFailures() {
        mockUser();
        when(announcementService.createRecurringAnnouncement(anyString(), any(), any()))
                .thenThrow(new RuntimeException("limite PRO atteinte"));
        TripRecurrenceEntity rec = entity("1111111", 1, null); // 2 jours

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero(); // aucune réussie
        verify(announcementService, times(2)).createRecurringAnnouncement(anyString(), any(), any()); // mais 2 tentées
        assertThat(rec.getLastGeneratedDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    void generate_missingUser_skips() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        TripRecurrenceEntity rec = entity("1111111", 3, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verifyNoInteractions(announcementService);
    }

    @Test
    void generate_skipsOccurrenceAlreadyLinkedToRecurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        when(announcementRepository.existsBySourceRecurrenceIdAndDepartureDate(
                rec.getId(), LocalDate.now())).thenReturn(true);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verifyNoInteractions(announcementService);
    }

    @Test
    void create_savesAndSchedulesGenerationAfterCommitWhenActive() {
        var req = request("1111111", 0, true);

        service.create(userId, req);

        verify(repository, atLeastOnce()).save(any(TripRecurrenceEntity.class));
        // La génération ne tourne plus dans la transaction de create : une occurrence en
        // échec la marquait rollback-only (500). Elle part après le commit.
        verify(eventPublisher).publishEvent(any(TripRecurrenceSavedEvent.class));
        verifyNoInteractions(announcementService);
        verify(auditService).log(eq("TRIP_RECURRENCE"), any(), eq("TRIP_RECURRENCE_CREATED"), eq(userId), anyMap());
    }

    @Test
    void update_active_schedulesGenerationAfterCommit() {
        UUID id = UUID.randomUUID();
        TripRecurrenceEntity existing = entity("1111111", 0, null);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(existing));

        service.update(userId, id, request("1111111", 0, true));

        verify(eventPublisher).publishEvent(any(TripRecurrenceSavedEvent.class));
        verifyNoInteractions(announcementService);
    }

    @Test
    void generateForRecurrenceId_generatesForActiveRecurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(rec));

        assertThat(service.generateForRecurrenceId(id)).isEqualTo(1);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), any(), any());
    }

    @Test
    void generateForRecurrenceId_skipsInactiveOrMissingRecurrence() {
        TripRecurrenceEntity inactive = entity("1111111", 0, null);
        inactive.setActive(false);
        UUID inactiveId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(repository.findById(inactiveId)).thenReturn(Optional.of(inactive));
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThat(service.generateForRecurrenceId(inactiveId)).isZero();
        assertThat(service.generateForRecurrenceId(missingId)).isZero();
        verifyNoInteractions(announcementService);
    }

    // C2 : normalisation à l'écriture — un client pas à jour envoie des libellés/codes
    // legacy, la récurrence doit être persistée avec les libellés canoniques.
    @Test
    void create_legacyAcceptedCategories_areNormalizedOnWrite() {
        var req = new TripRecurrenceRequest(
                null, "Paris", "Dakar", "PLANE", "SUITCASE_23KG",
                23.0, 8.0, List.of("Hi-fi", "Téléphone", "Vêtements"),
                new AddressDto("12 rue de la Paix", 48.86, 2.33),
                new AddressDto("Aéroport CDG", 49.01, 2.55),
                LocalTime.of(14, 0), LocalTime.of(18, 30), false, "1111111", 0, false);

        ArgumentCaptor<TripRecurrenceEntity> captor = ArgumentCaptor.forClass(TripRecurrenceEntity.class);
        var dto = service.create(userId, req);

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCategories()).isEqualTo("Téléphone & électronique,Vêtements & tissus");
        assertThat(dto.acceptedCategories()).containsExactly("Téléphone & électronique", "Vêtements & tissus");
    }

    @Test
    void create_storesArrivalDayOffset() {
        var base = request("1111111", 0, false);
        var req = new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), base.pricePerKg(),
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                base.currency(), false, 2);

        var dto = service.create(userId, req);

        assertThat(dto.arrivalDayOffset()).isEqualTo(2);
        assertThat(service.create(userId, base).arrivalDayOffset()).isZero();
    }

    // FLUTTER-GE : les escales saisies sur la récurrence suivent chaque occurrence.
    @Test
    void generate_copiesStopsCountOnEachOccurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setStopsCount(1);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().stopsCount()).isEqualTo(1);
    }

    @Test
    void create_storesStopsCount_onlyForPlane() {
        var base = request("1111111", 0, false);
        var req = new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                "PLANE", base.capacityUnit(), base.availableKg(), base.pricePerKg(),
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                base.currency(), false, null, 0);
        var car = new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                "CAR", base.capacityUnit(), base.availableKg(), base.pricePerKg(),
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                base.currency(), false, null, 1);

        assertThat(service.create(userId, req).stopsCount()).isZero();
        assertThat(service.create(userId, car).stopsCount()).isNull();
        assertThat(service.create(userId, base).stopsCount()).isNull();
    }

    // FLUTTER-FT : carte décochée sur la récurrence, aucune occurrence ne la propose.
    @Test
    void generate_cardDeclined_neverOffersTheCard() {
        mockUser(true);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCashAccepted(true);
        rec.setCardDeclined(true);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods()).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void generate_cardAccepted_keepsTheCard() {
        mockUser(true);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCashAccepted(true);

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createRecurringAnnouncement(eq("firebase-uid"), cap.capture(), eq(rec.getId()));
        assertThat(cap.getValue().acceptedPaymentMethods())
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);
        verify(announcementRepository, never()).findById(any());
    }

    // Générée pendant une restriction du compte Stripe, l'occurrence garde le refus de la
    // récurrence : la réouverture après onboarding (FLUTTER-DH) ne lui rend pas la carte.
    @Test
    void generate_cardDeclined_marksEachOccurrenceEvenWithoutConnect() {
        mockUser(false);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCashAccepted(true);
        rec.setCardDeclined(true);
        UUID occurrenceId = UUID.randomUUID();
        AnnouncementResponse response = mock(AnnouncementResponse.class);
        when(response.id()).thenReturn(occurrenceId);
        when(announcementService.createRecurringAnnouncement(anyString(), any(), eq(rec.getId())))
                .thenReturn(response);
        AnnouncementEntity occurrence = new AnnouncementEntity();
        when(announcementRepository.findById(occurrenceId)).thenReturn(Optional.of(occurrence));

        service.generateForRecurrence(rec);

        assertThat(occurrence.isCardDeclined()).isTrue();
        verify(announcementRepository).save(occurrence);
    }

    // En zone CFA la carte n'existe pas : rien à refuser, l'occurrence n'est pas marquée.
    @Test
    void generate_cardDeclinedInCfa_leavesOccurrenceUntouched() {
        mockUser(false);
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCurrency("XOF");
        rec.setCashAccepted(true);
        rec.setCardDeclined(true);
        AnnouncementResponse response = mock(AnnouncementResponse.class);
        lenient().when(response.id()).thenReturn(UUID.randomUUID());
        when(announcementService.createRecurringAnnouncement(anyString(), any(), eq(rec.getId())))
                .thenReturn(response);

        service.generateForRecurrence(rec);

        verify(announcementRepository, never()).findById(any());
    }

    @Test
    void create_storesCardChoice_andLegacyClientKeepsTheCard() {
        var base = request("1111111", 0, false);
        var declined = withCard(base, true, false);
        var legacy = withCard(base, true, null);

        ArgumentCaptor<TripRecurrenceEntity> captor = ArgumentCaptor.forClass(TripRecurrenceEntity.class);
        assertThat(service.create(userId, declined).cardAccepted()).isFalse();
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isCardDeclined()).isTrue();
        assertThat(service.create(userId, legacy).cardAccepted()).isTrue();
        assertThat(service.create(userId, withCard(base, false, true)).cardAccepted()).isTrue();
    }

    // Au moins un moyen de paiement : ni carte ni espèces, la récurrence est refusée.
    @Test
    void create_cardDeclinedWithoutCash_isRejected() {
        var req = withCard(request("1111111", 0, false), false, false);

        assertThatThrownBy(() -> service.create(userId, req))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("payment-method-required"));
        verify(repository, never()).save(any());
    }

    @Test
    void update_cardDeclined_isStoredOnTheRecurrence() {
        UUID id = UUID.randomUUID();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(rec));

        var dto = service.update(userId, id, withCard(request("1111111", 0, false), true, false));

        assertThat(rec.isCardDeclined()).isTrue();
        assertThat(dto.cardAccepted()).isFalse();
    }

    private static TripRecurrenceRequest withCard(TripRecurrenceRequest base, boolean cash, Boolean card) {
        return new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), base.pricePerKg(),
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), cash,
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                base.currency(), base.active(), base.arrivalDayOffset(), base.stopsCount(), card);
    }

    // Le bug : sans devise envoyée, un modèle XOF/XAF était enregistré en euros.
    @Test
    void create_withoutCurrency_fallsBackToTheTravelersActiveCurrency() {
        when(activeCurrencyResolver.resolve(userId)).thenReturn("XAF");

        var dto = service.create(userId, cfaPrice(request("1111111", 0, false)));

        assertThat(dto.currency()).isEqualTo("XAF");
    }

    @Test
    void create_withCurrency_keepsItAndPublishesOccurrencesInIt() {
        var req = withCurrency(cfaPrice(request("1111111", 0, false)), "xof");

        var dto = service.create(userId, req);

        assertThat(dto.currency()).isEqualTo("XOF");
        verifyNoInteractions(activeCurrencyResolver);
    }

    @Test
    void update_withoutCurrency_keepsTheRecurrenceCurrency() {
        UUID id = UUID.randomUUID();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCurrency("XOF");
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(rec));

        service.update(userId, id, cfaPrice(request("1111111", 0, false)));

        assertThat(rec.getCurrency()).isEqualTo("XOF");
    }

    @Test
    void create_unsupportedCurrency_isRejected() {
        assertThatThrownBy(() -> service.create(userId, withCurrency(request("1111111", 0, false), "JPY")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("currency-unsupported"));
        verify(repository, never()).save(any());
    }

    @Test
    void create_priceAboveTheCapOfItsCurrency_isRejected() {
        var base = request("1111111", 0, false);
        var eur = new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), 5000.0,
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                "EUR", base.active(), base.arrivalDayOffset(), base.stopsCount(), base.cardAccepted());

        assertThatThrownBy(() -> service.create(userId, eur))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("price-out-of-bounds"));
        // 5 000 F CFA le kilo reste sous le plafond du franc CFA.
        assertThat(service.create(userId, withCurrency(eur, "XOF")).currency()).isEqualTo("XOF");
    }

    @Test
    void create_priceBelowTheFloorOfItsCurrency_isRejected() {
        var base = request("1111111", 0, false);
        var cheap = new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), 0.99,
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                "EUR", base.active(), base.arrivalDayOffset(), base.stopsCount(), base.cardAccepted());

        assertThatThrownBy(() -> service.create(userId, cheap))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("price-out-of-bounds");
                    assertThat(e.getProperties()).containsEntry("reason", "too-low");
                });
        verify(repository, never()).save(any());
    }

    /** Prix réaliste en franc CFA : 8/kg passerait sous le plancher de 656 (FLUTTER-GK). */
    private static TripRecurrenceRequest cfaPrice(TripRecurrenceRequest base) {
        return new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), 5000.0,
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                base.currency(), base.active(), base.arrivalDayOffset(), base.stopsCount(), base.cardAccepted());
    }

    private static TripRecurrenceRequest withCurrency(TripRecurrenceRequest base, String currency) {
        return new TripRecurrenceRequest(base.sourceTemplateId(), base.departureCity(), base.arrivalCity(),
                base.transportMode(), base.capacityUnit(), base.availableKg(), base.pricePerKg(),
                base.acceptedCategories(), base.refusedCategories(), base.description(), base.pickupAddress(),
                base.deliveryAddress(), base.departureTime(), base.arrivalTime(), base.cashAccepted(),
                base.weekdays(), base.horizonDays(), base.startDate(), base.endDate(), base.weekInterval(),
                base.publicationLeadDays(), base.handoverLeadDays(), base.pricingMode(), base.negotiable(),
                currency, base.active(), base.arrivalDayOffset(), base.stopsCount(), base.cardAccepted());
    }

    @Test
    void create_inactive_doesNotGenerate() {
        var req = request("1111111", 0, false);

        service.create(userId, req);

        verifyNoInteractions(announcementService);
    }

    @Test
    void generateDueTrips_iteratesActiveRecurrences() {
        mockUser();
        when(repository.findByActiveTrue()).thenReturn(List.of(entity("1111111", 0, null)));

        int total = service.generateDueTrips();

        assertThat(total).isEqualTo(1);
    }

    @Test
    void update_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(userId, id, request("1111111", 0, true)))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void delete_softDeletes() {
        UUID id = UUID.randomUUID();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(rec));

        service.delete(userId, id);

        assertThat(rec.getDeletedAt()).isNotNull();
        verify(auditService).log(eq("TRIP_RECURRENCE"), any(), eq("TRIP_RECURRENCE_DELETED"), eq(userId), anyMap());
    }

    @Test
    void toDto_derivesLifecycleStatusAndNextOccurrence() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        TripRecurrenceEntity upcoming = entity("0001000", 14, null);
        upcoming.setStartDate(LocalDate.of(2026, 9, 10));
        upcoming.setPublicationLeadDays(7);

        var upcomingDto = service.toDto(upcoming, today);

        assertThat(upcomingDto.status()).isEqualTo(TripRecurrenceStatus.UPCOMING);
        assertThat(upcomingDto.nextDepartureDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(upcomingDto.nextPublicationDate()).isEqualTo(LocalDate.of(2026, 9, 3));

        upcoming.setActive(false);
        assertThat(service.toDto(upcoming, today).status()).isEqualTo(TripRecurrenceStatus.PAUSED);

        upcoming.setEndDate(today.minusDays(1));
        upcoming.setLastPublicationErrorCode("kyc-required");
        assertThat(service.toDto(upcoming, today).status()).isEqualTo(TripRecurrenceStatus.TERMINATED);
    }

    private TripRecurrenceRequest request(String weekdays, int horizon, boolean active) {
        return new TripRecurrenceRequest(
                null, "Paris", "Dakar", "PLANE", "SUITCASE_23KG",
                23.0, 8.0, List.of("Vêtements", "Documents"),
                new AddressDto("12 rue de la Paix", 48.86, 2.33),
                new AddressDto("Aéroport CDG", 49.01, 2.55),
                LocalTime.of(14, 0), LocalTime.of(18, 30), false, weekdays, horizon, active);
    }
}
