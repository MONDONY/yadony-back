package com.yadony.api.triptemplate;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.triptemplate.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripTemplateServiceTest {

    @Mock TripTemplateRepository repository;
    @Mock AuditService auditService;
    @Mock com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    @Mock com.yadony.api.matching.TransportModeGuard transportModeGuard;
    @InjectMocks TripTemplateService service;

    // Mockito renvoie null pour un retour String non stubé, et non Optional.empty() :
    // sans ce stub le plafond serait calculé sur une devise nulle.
    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        lenient().when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }

    private final UUID userId = UUID.randomUUID();

    private CreateTripTemplateRequest createRequest(List<String> categories) {
        return new CreateTripTemplateRequest(
                "Mon Paris->Dakar", "🇸🇳",
                "Paris", 48.85, 2.35,
                "Dakar", 14.71, -17.46,
                "PLANE", "SUITCASE_23KG", 23, 8.0, categories, false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Requête « formulaire complet » en XOF, tous les nouveaux champs renseignés. */
    private CreateTripTemplateRequest fullXofRequest(Set<PaymentMethod> methods, Double pricePerKg,
                                                     String pricingMode, AddressDto pickup) {
        return new CreateTripTemplateRequest(
                "Abidjan->Paris", null,
                "Abidjan", null, null,
                "Paris", null, null,
                "PLANE", "SUITCASE_23KG", 23, pricePerKg, List.of("Vêtements"), false, LocalTime.of(6, 30),
                "XOF", pricingMode, methods, true, List.of("Hi-fi"), "Pas de liquide",
                pickup, new AddressDto("Gare de Lyon", 48.84, 2.37),
                LocalTime.of(22, 0), 2, "CI", "FR");
    }

    private CreateTripTemplateRequest requestWithMode(String mode) {
        return new CreateTripTemplateRequest(
                "Mon Paris->Dakar", "🇸🇳",
                "Paris", 48.85, 2.35,
                "Dakar", 14.71, -17.46,
                mode, "SUITCASE_23KG", 23, 8.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static YadonyBusinessException transportModeDisabled() {
        return new YadonyBusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "transport-mode-disabled", "Mode de transport indisponible",
                "Le transport par bateau n'est pas encore ouvert.");
    }

    @Test
    void create_boatTemplate_whenGuardRefuses_is422_andNothingIsSaved() {
        doThrow(transportModeDisabled()).when(transportModeGuard)
                .assertTripModeAllowed(eq(com.yadony.api.matching.TransportMode.BOAT), any());

        assertThatThrownBy(() -> service.create(userId, requestWithMode("BOAT")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("transport-mode-disabled");
                });
        verify(repository, never()).save(any(TripTemplateEntity.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void update_existingBoatTemplate_guardSeesBoatAsPrevious() {
        TripTemplateEntity existing = new TripTemplateEntity();
        existing.setUserId(userId);
        existing.setTransportMode("BOAT");
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(existing));
        var request = new UpdateTripTemplateRequest(
                "Mon Paris->Dakar", "🇸🇳", "Paris", 48.85, 2.35, "Dakar", 14.71, -17.46,
                "BOAT", "SUITCASE_23KG", 23, 8.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        service.update(userId, id, request);

        verify(transportModeGuard).assertTripModeAllowed(
                com.yadony.api.matching.TransportMode.BOAT, com.yadony.api.matching.TransportMode.BOAT);
        verify(repository).save(existing);
    }

    @Test
    void create_lowercaseMode_isStoredNormalized() {
        var dto = service.create(userId, requestWithMode(" plane "));

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTransportMode()).isEqualTo("PLANE");
        assertThat(dto.transportMode()).isEqualTo("PLANE");
        verify(transportModeGuard).assertTripModeAllowed(eq(com.yadony.api.matching.TransportMode.PLANE), any());
    }

    @Test
    void create_unknownMode_is422InvalidTransportMode_andNothingIsSaved() {
        assertThatThrownBy(() -> service.create(userId, requestWithMode("SHIP")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("invalid-transport-mode");
                });
        verify(repository, never()).save(any(TripTemplateEntity.class));
    }

    @Test
    void create_mapsFieldsAndJoinsCategories() {
        // C2 : normalisation à l'écriture — "Vêtements"/"Documents" sont des libellés
        // legacy (cf. ContentCategoryNormalizer), persistés canoniques désormais.
        var dto = service.create(userId, createRequest(List.of("Vêtements", "Documents")));

        assertThat(dto.label()).isEqualTo("Mon Paris->Dakar");
        assertThat(dto.departureCity()).isEqualTo("Paris");
        assertThat(dto.arrivalCity()).isEqualTo("Dakar");
        assertThat(dto.pricePerKg()).isEqualTo(8.0);
        assertThat(dto.acceptedCategories()).containsExactly("Vêtements & tissus", "Documents & administratif");

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getAcceptedCategories()).isEqualTo("Vêtements & tissus,Documents & administratif");
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_CREATED"), eq(userId), anyMap());
    }

    // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
    // legacy, le modèle doit être persisté avec le libellé canonique.
    // FLUTTER-4E : un modèle de vol de nuit mémorise son jour d'arrivée, un client
    // ancien qui n'envoie rien garde le comportement « même jour ».
    @Test
    void create_storesArrivalDayOffset_andLegacyClientDefaultsToSameDay() {
        var base = createRequest(null);
        var overnight = new CreateTripTemplateRequest(base.label(), base.emoji(), base.departureCity(),
                base.departureLat(), base.departureLng(), base.arrivalCity(), base.arrivalLat(),
                base.arrivalLng(), base.transportMode(), base.capacityUnit(), base.availableKg(),
                base.pricePerKg(), base.acceptedCategories(), false, LocalTime.of(6, 30), null, null,
                null, null, null, null, null, null, LocalTime.of(22, 0), null, null, null, 1);

        assertThat(service.create(userId, overnight).arrivalDayOffset()).isEqualTo(1);
        assertThat(service.create(userId, base).arrivalDayOffset()).isZero();
    }

    @Test
    void create_legacyCategoryCode_isNormalizedOnWrite() {
        var dto = service.create(userId, createRequest(List.of("Hi-fi", "Téléphone")));

        assertThat(dto.acceptedCategories()).containsExactly("Téléphone & électronique");

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCategories()).isEqualTo("Téléphone & électronique");
    }

    @Test
    void create_nullCategories_storesNullAndReturnsEmptyList() {
        var dto = service.create(userId, createRequest(null));
        assertThat(dto.acceptedCategories()).isEmpty();

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCategories()).isNull();
    }

    @Test
    void findAll_returnsMappedDtos() {
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        e.setLabel("T1");
        e.setDepartureCity("Lyon");
        e.setArrivalCity("Abidjan");
        e.setTransportMode("PLANE");
        e.setCapacityUnit("SUITCASE_23KG");
        e.setAvailableKg(23);
        e.setPricePerKg(8.0);
        e.setAcceptedCategories("Vêtements,Cosmétiques");
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(e));

        var result = service.findAll(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isEqualTo("T1");
        assertThat(result.get(0).acceptedCategories()).containsExactly("Vêtements", "Cosmétiques");
    }

    // F : un jeton de moyen de paiement devenu inconnu en base (valeur legacy retirée)
    // ne doit pas faire planter findAll avec un 500, mais être ignoré.
    @Test
    void findAll_unknownPaymentMethodTokenIsIgnored() {
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        e.setLabel("T2");
        e.setDepartureCity("Lyon");
        e.setArrivalCity("Abidjan");
        e.setTransportMode("PLANE");
        e.setCapacityUnit("SUITCASE_23KG");
        e.setAvailableKg(23);
        e.setPricePerKg(8.0);
        e.setAcceptedPaymentMethods("CASH,BITCOIN");
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(e));

        var result = service.findAll(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).acceptedPaymentMethods()).containsExactly(PaymentMethod.CASH);
    }

    @Test
    void update_existing_updatesFields() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        e.setLabel("old");
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(e));

        var req = new UpdateTripTemplateRequest(
                "new label", null, "Marseille", null, null, "Dakar", null, null,
                "BOAT", "KG_FREE", 30, 9.0, List.of("Documents"), true, java.time.LocalTime.of(18, 30),
                null, null, null, null, null, null, null, null, null, null, null, null);
        var dto = service.update(userId, id, req);

        assertThat(dto.label()).isEqualTo("new label");
        assertThat(dto.transportMode()).isEqualTo("BOAT");
        // C2 : "Documents" (legacy) → persisté canonique "Documents & administratif".
        assertThat(dto.acceptedCategories()).containsExactly("Documents & administratif");
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_UPDATED"), eq(userId), anyMap());
    }

    @Test
    void update_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(userId, id,
                new UpdateTripTemplateRequest("x", null, "A", null, null, "B", null, null,
                        "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                        null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(YadonyNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void delete_existing_softDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(e));

        service.delete(userId, id);

        assertThat(e.getDeletedAt()).isNotNull();
        verify(repository).save(e);
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_DELETED"), eq(userId), anyMap());
    }

    @Test
    void delete_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(userId, id))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    // ── Formulaire complet, devise, moyens de paiement, validation ──────────────

    @Test
    void create_fullForm_mapsEveryNewFieldAndMirrorsCash() {
        var dto = service.create(userId, fullXofRequest(
                Set.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), 2000.0, "KG",
                new AddressDto("Cocody", 5.35, -3.99)));

        assertThat(dto.currency()).isEqualTo("XOF");
        assertThat(dto.pricingMode()).isEqualTo("KG");
        assertThat(dto.acceptedPaymentMethods()).containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        assertThat(dto.cashAccepted()).isTrue();
        assertThat(dto.negotiable()).isTrue();
        assertThat(dto.refusedTypes()).containsExactly("Téléphone & électronique");
        assertThat(dto.description()).isEqualTo("Pas de liquide");
        assertThat(dto.pickupAddress()).isEqualTo(new AddressDto("Cocody", 5.35, -3.99));
        assertThat(dto.deliveryAddress().label()).isEqualTo("Gare de Lyon");
        assertThat(dto.departureTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(dto.handoverLeadDays()).isEqualTo(2);
        assertThat(dto.departureCountryCode()).isEqualTo("CI");
        assertThat(dto.arrivalCountryCode()).isEqualTo("FR");

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedPaymentMethods()).isEqualTo("CASH,MOBILE_MONEY");
        assertThat(captor.getValue().isCashAccepted()).isTrue();
    }

    // Un client antérieur au formulaire complet n'envoie que cashAccepted.
    @Test
    void create_legacyClient_derivesPaymentMethodsFromCashAccepted() {
        var withCash = new CreateTripTemplateRequest(
                "Legacy", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, true, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        var dto = service.create(userId, withCash);

        assertThat(dto.acceptedPaymentMethods()).containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);
        assertThat(dto.cashAccepted()).isTrue();
        assertThat(dto.pricingMode()).isEqualTo("KG");
        assertThat(dto.negotiable()).isFalse();
        assertThat(dto.currency()).isNull();
    }

    // A : le chemin « client ancien » (acceptedPaymentMethods absent) échappait au filtre
    // de devise. Un modèle en XOF avec cashAccepted=false dérivait STRIPE, un rail que la
    // devise CFA n'autorise pas : filtré en silence par restrictToCurrency, repli CASH.
    @Test
    void create_legacyClientInCfaCurrency_dropsCardKeepsCash() {
        var request = new CreateTripTemplateRequest(
                "Legacy XOF", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 5000.0, null, false, null,
                "XOF", null, null, null, null, null,
                null, null, null, null, null, null);

        var dto = service.create(userId, request);

        assertThat(dto.acceptedPaymentMethods()).containsExactly(PaymentMethod.CASH);
        assertThat(dto.cashAccepted()).isTrue();
    }

    // Symétrique du test précédent : cashAccepted=false doit produire STRIPE seul,
    // pas seulement l'absence de CASH (la branche opposée du ternaire de resolvePaymentMethods).
    @Test
    void create_legacyClientWithoutCash_derivesStripeOnly() {
        var withoutCash = new CreateTripTemplateRequest(
                "Legacy sans cash", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        var dto = service.create(userId, withoutCash);

        assertThat(dto.acceptedPaymentMethods()).containsExactly(PaymentMethod.STRIPE);
        assertThat(dto.cashAccepted()).isFalse();
    }

    // Un ensemble explicitement vide (pas null) doit retomber sur STRIPE : c'est le
    // chemin qui, sans la garde isEmpty(), ferait planter EnumSet.copyOf(Set.of()).
    @Test
    void create_emptyPaymentMethods_fallsBackToStripe() {
        var request = new CreateTripTemplateRequest(
                "Ensemble vide", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                null, null, Set.of(), null, null, null,
                null, null, null, null, null, null);

        var dto = service.create(userId, request);

        assertThat(dto.acceptedPaymentMethods()).containsExactly(PaymentMethod.STRIPE);
    }

    @Test
    void create_unsupportedCurrency_throws422() {
        var request = new CreateTripTemplateRequest(
                "Devise", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                "ZZZ", null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("currency-unsupported"));
    }

    // Le plafond suit la devise DU MODÈLE, plus la devise active du profil (EUR ici).
    @Test
    void create_priceBoundedInTemplateCurrencyNotActiveOne() {
        var dto = service.create(userId, fullXofRequest(Set.of(PaymentMethod.CASH), 2000.0, "KG", null));

        assertThat(dto.pricePerKg()).isEqualTo(2000.0);
    }

    @Test
    void create_kgModeWithoutPrice_throws422() {
        assertThatThrownBy(() -> service.create(userId,
                fullXofRequest(Set.of(PaymentMethod.CASH), null, "KG", null)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("trip-template/price-required"));
    }

    @Test
    void create_mixedModeWithoutPrice_isAccepted() {
        var dto = service.create(userId, fullXofRequest(Set.of(PaymentMethod.CASH), null, "MIXED", null));

        assertThat(dto.pricingMode()).isEqualTo("MIXED");
        assertThat(dto.pricePerKg()).isNull();
    }

    // D : en mode MIXED un pricePerKg à 0.0 (distinct de l'absence de prix) était stocké
    // et ressortait tel quel, alors qu'il doit se comporter comme une absence de prix.
    @Test
    void create_mixedModeWithZeroPrice_storesNull() {
        var dto = service.create(userId, fullXofRequest(Set.of(PaymentMethod.CASH), 0.0, "MIXED", null));

        assertThat(dto.pricingMode()).isEqualTo("MIXED");
        assertThat(dto.pricePerKg()).isNull();

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPricePerKg()).isNull();
    }

    @Test
    void create_mobileMoneyOutsideCfa_throws422() {
        var request = new CreateTripTemplateRequest(
                "Euro", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                "EUR", "KG", Set.of(PaymentMethod.MOBILE_MONEY), null, null, null,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("trip-template/payment-method-not-available"));
    }

    @Test
    void create_legacyPaymentMethod_throws422() {
        var request = new CreateTripTemplateRequest(
                "Legacy rail", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                "XOF", "KG", Set.of(PaymentMethod.WAVE), null, null, null,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("trip-template/payment-method-invalid"));
    }

    @Test
    void create_incompleteAddress_throws422() {
        assertThatThrownBy(() -> service.create(userId,
                fullXofRequest(Set.of(PaymentMethod.CASH), 2000.0, "KG", new AddressDto("Cocody", null, null))))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("trip-template/address-incomplete"));
    }

    @Test
    void create_absentAddresses_areAccepted() {
        var request = new CreateTripTemplateRequest(
                "Sans adresse", null, "Paris", null, null, "Dakar", null, null,
                "PLANE", "SUITCASE_23KG", 23, 8.0, null, false, null,
                "EUR", "KG", Set.of(PaymentMethod.STRIPE), null, null, null,
                null, null, null, null, null, null);

        var dto = service.create(userId, request);

        assertThat(dto.pickupAddress()).isNull();
        assertThat(dto.deliveryAddress()).isNull();
    }

    // ── Bornes de prix par devise ────────────────────────────────────────────

    @Test
    void create_rejectsPricePerKgAboveTheCurrencyCeiling() {
        var request = new CreateTripTemplateRequest(
                "Trop cher", "🇸🇳", "Paris", 48.85, 2.35, "Dakar", 14.71, -17.46,
                "PLANE", "SUITCASE_23KG", 23, 900.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("trip-template/price-out-of-bounds"));
        verify(repository, never()).save(any());
    }

    @Test
    void create_rejectsPricePerKgBelowTheCurrencyFloor() {
        // FLUTTER-GK : 8 F CFA/kg, saisis en croyant taper des euros.
        when(activeCurrencyResolver.resolve(userId)).thenReturn("XOF");
        var request = new CreateTripTemplateRequest(
                "Trop bas", "🇸🇳", "Paris", 48.85, 2.35, "Dakar", 14.71, -17.46,
                "PLANE", "SUITCASE_23KG", 23, 8.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("trip-template/price-out-of-bounds");
                    assertThat(e.getProperties()).containsEntry("reason", "too-low");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void create_allowsALargePriceWhenTheCurrencyHasNoMinorUnit() {
        // 5 000 XOF/kg vaut environ 7,60 €/kg : un tarif banal, que l'ancien
        // plafond fixe à 500 rejetait alors qu'il est parfaitement légitime.
        when(activeCurrencyResolver.resolve(userId)).thenReturn("XOF");
        var request = new CreateTripTemplateRequest(
                "Paris Dakar", "🇸🇳", "Paris", 48.85, 2.35, "Dakar", 14.71, -17.46,
                "PLANE", "SUITCASE_23KG", 23, 5000.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        var dto = service.create(userId, request);

        assertThat(dto.pricePerKg()).isEqualTo(5000.0);
    }


    // --- FLUTTER-8F : changement de devise active ---

    @Test
    void pinLegacyTemplatesCurrency_pinsOnlyTemplatesWithoutCurrency() {
        TripTemplateEntity legacy = new TripTemplateEntity();
        TripTemplateEntity xof = new TripTemplateEntity();
        xof.setCurrency("XOF");
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(legacy, xof));

        service.pinLegacyTemplatesCurrency(userId, "eur");

        assertThat(legacy.getCurrency()).isEqualTo("EUR");
        assertThat(xof.getCurrency()).isEqualTo("XOF");
        verify(repository).saveAll(List.of(legacy));
        verify(auditService).log(org.mockito.ArgumentMatchers.eq("USER"),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq("TRIP_TEMPLATES_CURRENCY_PINNED"),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pinLegacyTemplatesCurrency_nothingToPin_noWrite() {
        TripTemplateEntity xof = new TripTemplateEntity();
        xof.setCurrency("XOF");
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(xof));

        service.pinLegacyTemplatesCurrency(userId, "EUR");

        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(auditService);
    }

    @Test
    void pinLegacyTemplatesCurrency_blankPrevious_isIgnored() {
        service.pinLegacyTemplatesCurrency(userId, null);
        service.pinLegacyTemplatesCurrency(userId, " ");

        verifyNoInteractions(repository, auditService);
    }

    @Test
    void tripTemplateCurrencyListener_delegatesWithPreviousCurrency() {
        TripTemplateService mocked = mock(TripTemplateService.class);

        new TripTemplateCurrencyListener(mocked).onActiveCurrencyChanged(
                new com.yadony.api.settings.ActiveCurrencyChangedEvent(userId, "EUR", "XOF"));

        verify(mocked).pinLegacyTemplatesCurrency(userId, "EUR");
    }

    // ── Lot 2 route : durée, poids, taille et poids max par mode ────────────────────────────

    /** Modèle complet : seuls le mode, la capacité, le décalage d'arrivée et les limites varient. */
    private CreateTripTemplateRequest modelWith(String mode, int availableKg, Integer arrivalDayOffset,
                                                String maxItemSize, Double maxParcelWeightKg) {
        return new CreateTripTemplateRequest(
                "Abidjan->Ouaga", null, "Abidjan", null, null, "Ouagadougou", null, null,
                mode, "KG_EXACT", availableKg, 8.0, List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                arrivalDayOffset, maxItemSize, maxParcelWeightKg);
    }

    private static void assertBusiness(Throwable thrown, String code, String messagePart) {
        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    @Test
    void template_road_offset14_accepted_15_refused() {
        assertThat(service.create(userId, modelWith("CAR", 100, 14, null, null)).arrivalDayOffset())
                .isEqualTo(14);
        assertThat(service.create(userId, modelWith("BUS", 100, 14, null, null)).arrivalDayOffset())
                .isEqualTo(14);

        assertThatThrownBy(() -> service.create(userId, modelWith("CAR", 100, 15, null, null)))
                .satisfies(e -> assertBusiness(e, "arrival-too-far", "14"));
        verify(repository, times(2)).save(any(TripTemplateEntity.class));
    }

    @Test
    void template_boat_offset120_accepted_121_refused() {
        assertThat(service.create(userId, modelWith("BOAT", 100, 120, null, null)).arrivalDayOffset())
                .isEqualTo(120);

        assertThatThrownBy(() -> service.create(userId, modelWith("BOAT", 100, 121, null, null)))
                .satisfies(e -> assertBusiness(e, "arrival-too-far", "120"));
    }

    // Review Focus 3 : l'avion garde sa limite de 3 jours, la limite de la route ne déborde pas.
    @Test
    void template_plane_offset3_accepted_4_refused() {
        assertThat(service.create(userId, modelWith("PLANE", 23, 3, null, null)).arrivalDayOffset())
                .isEqualTo(3);

        assertThatThrownBy(() -> service.create(userId, modelWith("PLANE", 23, 4, null, null)))
                .satisfies(e -> assertBusiness(e, "arrival-too-far", "3"));
        // Mêmes règles à la modification.
        UUID id = UUID.randomUUID();
        TripTemplateEntity existing = new TripTemplateEntity();
        existing.setUserId(userId);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(existing));
        var tooFar = new UpdateTripTemplateRequest(
                "x", null, "Paris", null, null, "Dakar", null, null, "PLANE", "SUITCASE_23KG", 23, 8.0,
                List.of(), false, null, null, null, null, null, null, null, null, null, null, null, null,
                null, 4, null, null);
        assertThatThrownBy(() -> service.update(userId, id, tooFar))
                .satisfies(e -> assertBusiness(e, "arrival-too-far", "3"));
    }

    @Test
    void template_refusedOffset_isNeverSaved() {
        assertThatThrownBy(() -> service.create(userId, modelWith("PLANE", 23, 4, null, null)));

        verify(repository, never()).save(any(TripTemplateEntity.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void template_road_2000kg_accepted_plane_1000kg_refused() {
        assertThat(service.create(userId, modelWith("CAR", 2000, null, null, null)).availableKg())
                .isEqualTo(2000);
        assertThat(service.create(userId, modelWith("BOAT", 20000, null, null, null)).availableKg())
                .isEqualTo(20000);
        // Plafond de l'avion : 999,99 kg accepté (989 en entier), 1 000 refusé.
        assertThat(service.create(userId, modelWith("PLANE", 999, null, null, null)).availableKg())
                .isEqualTo(999);

        assertThatThrownBy(() -> service.create(userId, modelWith("PLANE", 1000, null, null, null)))
                .satisfies(e -> assertBusiness(e, "trip-capacity-too-high", "999.99"));
        assertThatThrownBy(() -> service.create(userId, modelWith("TRAIN", 1000, null, null, null)))
                .satisfies(e -> assertBusiness(e, "trip-capacity-too-high", "999.99"));
    }

    @Test
    void template_road_keepsSizeAndMaxWeight_roundTrippedInDto() {
        var dto = service.create(userId, modelWith("CAR", 100, null, "L", 20.5));

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMaxItemSize()).isEqualTo("L");
        assertThat(captor.getValue().getMaxParcelWeightKg()).isEqualTo(20.5);
        assertThat(dto.maxItemSize()).isEqualTo("L");
        assertThat(dto.maxParcelWeightKg()).isEqualTo(20.5);
    }

    @Test
    void template_lowercaseSize_isNormalized() {
        assertThat(service.create(userId, modelWith("BUS", 100, null, "xl", null)).maxItemSize())
                .isEqualTo("XL");
    }

    @Test
    void template_boat_dropsSizeButKeepsMaxWeight() {
        var dto = service.create(userId, modelWith("BOAT", 5000, null, "M", 1500.0));

        assertThat(dto.maxItemSize()).isNull();
        assertThat(dto.maxParcelWeightKg()).isEqualTo(1500.0);
    }

    @Test
    void template_planeDropsSize() {
        var dto = service.create(userId, modelWith("PLANE", 23, null, "M", 12.0));

        assertThat(dto.maxItemSize()).isNull();
        assertThat(dto.maxParcelWeightKg()).isNull();
    }

    @Test
    void template_withoutLimits_hasNoLimit() {
        var dto = service.create(userId, modelWith("CAR", 100, null, null, null));

        assertThat(dto.maxItemSize()).isNull();
        assertThat(dto.maxParcelWeightKg()).isNull();
    }

    @Test
    void template_update_absentLimits_areKept_noneAndZeroClear() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity existing = new TripTemplateEntity();
        existing.setUserId(userId);
        existing.setTransportMode("CAR");
        existing.setMaxItemSize("L");
        existing.setMaxParcelWeightKg(30.0);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(existing));

        // Client du lot 1 : ne sait rien de la taille ni du poids max, ne doit pas les effacer.
        var kept = service.update(userId, id, asUpdate(modelWith("CAR", 100, null, null, null)));
        assertThat(kept.maxItemSize()).isEqualTo("L");
        assertThat(kept.maxParcelWeightKg()).isEqualTo(30.0);

        var changed = service.update(userId, id, asUpdate(modelWith("CAR", 100, null, "S", 12.0)));
        assertThat(changed.maxItemSize()).isEqualTo("S");
        assertThat(changed.maxParcelWeightKg()).isEqualTo(12.0);

        var cleared = service.update(userId, id, asUpdate(modelWith("CAR", 100, null, "NONE", 0.0)));
        assertThat(cleared.maxItemSize()).isNull();
        assertThat(cleared.maxParcelWeightKg()).isNull();
    }

    @Test
    void template_update_toPlane_clearsLimits() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity existing = new TripTemplateEntity();
        existing.setUserId(userId);
        existing.setTransportMode("CAR");
        existing.setMaxItemSize("L");
        existing.setMaxParcelWeightKg(30.0);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(existing));

        var dto = service.update(userId, id, asUpdate(modelWith("PLANE", 23, null, null, null)));

        assertThat(dto.maxItemSize()).isNull();
        assertThat(dto.maxParcelWeightKg()).isNull();
    }

    private static UpdateTripTemplateRequest asUpdate(CreateTripTemplateRequest c) {
        return new UpdateTripTemplateRequest(c.label(), c.emoji(), c.departureCity(), c.departureLat(),
                c.departureLng(), c.arrivalCity(), c.arrivalLat(), c.arrivalLng(), c.transportMode(),
                c.capacityUnit(), c.availableKg(), c.pricePerKg(), c.acceptedCategories(), c.cashAccepted(),
                c.arrivalTime(), c.currency(), c.pricingMode(), c.acceptedPaymentMethods(), c.negotiable(),
                c.refusedTypes(), c.description(), c.pickupAddress(), c.deliveryAddress(), c.departureTime(),
                c.handoverLeadDays(), c.departureCountryCode(), c.arrivalCountryCode(), c.arrivalDayOffset(),
                c.maxItemSize(), c.maxParcelWeightKg());
    }
}
