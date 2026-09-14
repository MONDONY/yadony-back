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

}
