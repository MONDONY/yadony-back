package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Règles d'un trajet bateau (lot 3, BA3) : fourchette d'arrivée, un seul prix (m³ ou kilo), volume
 * obligatoire au m³, bornes du DTO et capacité en kilos facultative pour le seul bateau au m³.
 */
@DisplayName("Trajet bateau : validation (BA3)")
class AnnouncementBoatValidationTest {

    private static final LocalDate DEP = LocalDate.now().plusDays(10);
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** Requête modifiable champ par champ ; par défaut un bateau au m³ valide. */
    private static final class Req {
        TransportMode mode = TransportMode.BOAT;
        BigDecimal availableKg;
        BigDecimal pricePerKg;
        PricingMode pricingMode;
        LocalDate arrivalDate = DEP.plusDays(20);
        LocalDate arrivalDateMax = DEP.plusDays(30);
        BigDecimal volume = new BigDecimal("2.5");
        BigDecimal pricePerM3 = new BigDecimal("600");
        CapacityUnit capacityUnit;

        Req mode(TransportMode m) { mode = m; return this; }
        Req kg(String kg, String price) {
            availableKg = kg == null ? null : new BigDecimal(kg);
            pricePerKg = price == null ? null : new BigDecimal(price);
            return this;
        }
        Req pricingMode(PricingMode p) { pricingMode = p; return this; }
        Req arrival(LocalDate min, LocalDate max) { arrivalDate = min; arrivalDateMax = max; return this; }
        Req volume(String v) { volume = v == null ? null : new BigDecimal(v); return this; }
        Req unit(CapacityUnit u) { capacityUnit = u; return this; }
        Req pricePerM3(String p) { pricePerM3 = p == null ? null : new BigDecimal(p); return this; }

        AnnouncementRequest build() {
            return new AnnouncementRequest("Marseille", "Dakar", DEP, LocalTime.of(10, 0), null,
                    new AddressDto("Port", 43.3, 5.37), new AddressDto("Port", 14.69, -17.44),
                    availableKg, pricePerKg, mode, null, null, null, null, capacityUnit, pricingMode,
                    null, null, DEP.atTime(7, 30), false, false, null, arrivalDate, null, null, null,
                    arrivalDateMax, volume, pricePerM3);
        }
    }

    private static Req boat() {
        return new Req();
    }

    private static void assertProblem(Req req, String code) {
        assertThatThrownBy(() -> AnnouncementService.validateBoatFields(req.build(), req.mode))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo(code);
                });
    }

    private static void assertValid(Req req) {
        assertThatCode(() -> AnnouncementService.validateBoatFields(req.build(), req.mode))
                .doesNotThrowAnyException();
    }

    // ─── validateBoatFields ──────────────────────────────────────────────────────

    @Test
    @DisplayName("bateau au m³ complet : accepté, prix au m³ sans borne haute")
    void boatPerM3_valid() {
        assertValid(boat());
        assertValid(boat().pricePerM3("25000"));
    }

    @Test
    @DisplayName("bateau au kilo, volume facultatif : accepté")
    void boatPerKg_valid() {
        assertValid(boat().pricePerM3(null).volume(null).kg("500", "4"));
        assertValid(boat().pricePerM3(null).volume("3").kg("500", "4"));
    }

    @Test
    @DisplayName("fourchette manquante ou à l'envers : 422 arrival-range-invalid")
    void boatWithoutArrivalMax_422() {
        assertProblem(boat().arrival(DEP.plusDays(20), null), "arrival-range-invalid");
        assertProblem(boat().arrival(null, DEP.plusDays(30)), "arrival-range-invalid");
        assertProblem(boat().arrival(DEP.plusDays(20), DEP.plusDays(19)), "arrival-range-invalid");
    }

    @Test
    @DisplayName("fin de fourchette au-delà de départ + 120 j : 422 arrival-too-far")
    void boatArrivalMaxBeyond120_422() {
        assertProblem(boat().arrival(DEP.plusDays(100), DEP.plusDays(121)), "arrival-too-far");
    }

    @Test
    @DisplayName("au m³ sans volume : 422 boat-volume-required")
    void boatPerM3WithoutVolume_422() {
        assertProblem(boat().volume(null), "boat-volume-required");
    }

    @Test
    @DisplayName("au m³ en kilo libre : 422 boat-volume-unit-invalid ; autres unités et bateau au kilo acceptés")
    void boatPerM3WithFreeKiloUnit_422() {
        assertProblem(boat().unit(CapacityUnit.KG_FREE), "boat-volume-unit-invalid");
        assertValid(boat().unit(CapacityUnit.KG_EXACT));
        assertValid(boat());
        assertValid(boat().pricePerM3(null).volume(null).kg("500", "4").unit(CapacityUnit.KG_FREE));
    }

    @Test
    @DisplayName("les deux prix, aucun prix, ou une grille : 422 boat-price-required")
    void boatPrices_422() {
        assertProblem(boat().kg("100", "8"), "boat-price-required");
        assertProblem(boat().pricePerM3(null).kg("100", null), "boat-price-required");
        assertProblem(boat().pricePerM3(null).kg("100", "0"), "boat-price-required");
        assertProblem(boat().pricePerM3(null).kg("100", "0").pricingMode(PricingMode.MIXED), "boat-price-required");
        assertProblem(boat().pricingMode(PricingMode.MIXED), "boat-price-required");
    }

    @Test
    @DisplayName("prix au kilo à 0 avec un prix au m³ : le kilo n'est pas un prix, accepté au m³")
    void zeroKgPriceWithPerM3_valid() {
        assertValid(boat().kg(null, "0"));
    }

    @Test
    @DisplayName("hors bateau : aucune règle bateau (champs ignorés)")
    void planeIgnoresBoatRules() {
        assertValid(boat().mode(TransportMode.PLANE).arrival(null, null).volume(null).kg("20", "9"));
        assertValid(boat().mode(TransportMode.CAR).kg("20", "9").pricePerM3("100"));
    }

    // ─── Bean Validation de la requête ───────────────────────────────────────────

    private static Set<String> violatedFields(AnnouncementRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("bateau au m³ sans kilos : aucune violation")
    void boatPerM3WithoutKg_noViolation() {
        assertThat(violatedFields(boat().build())).isEmpty();
    }

    @Test
    @DisplayName("hors bateau au m³, capacité en kilos manquante : violation sur availableKg")
    void missingKg_violationOnAvailableKg() {
        assertThat(violatedFields(boat().mode(TransportMode.PLANE).kg(null, "9").build()))
                .containsExactly("availableKg");
        assertThat(violatedFields(boat().pricePerM3(null).kg(null, "9").build()))
                .containsExactly("availableKg");
        assertThat(validator.validate(boat().mode(TransportMode.PLANE).kg(null, "9").build()))
                .extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly("{validation.trip.available-kg.required}");
    }

    @Test
    @DisplayName("volume hors de 0,1 à 9 999 m³, prix au m³ nul ou démesuré : violations")
    void boatBounds_violations() {
        assertThat(violatedFields(boat().volume("0.09").build())).containsExactly("volumeCapacityM3");
        assertThat(violatedFields(boat().volume("9999.001").build())).containsExactly("volumeCapacityM3");
        assertThat(violatedFields(boat().volume("0.1").build())).isEmpty();
        assertThat(violatedFields(boat().volume("9999").build())).isEmpty();
        assertThat(violatedFields(boat().pricePerM3("0").build())).containsExactly("pricePerM3");
        assertThat(violatedFields(boat().pricePerM3("100000000").build())).containsExactly("pricePerM3");
    }

    @Test
    @DisplayName("ancienne arité de la requête : champs bateau nuls")
    void legacyConstructor_boatFieldsNull() {
        AnnouncementRequest legacy = new AnnouncementRequest("Paris", "Dakar", DEP, LocalTime.of(10, 0), null,
                new AddressDto("A", 1.0, 1.0), new AddressDto("B", 2.0, 2.0), BigDecimal.TEN, BigDecimal.ONE,
                TransportMode.PLANE, null, null, null, null, null, null, null, null, DEP.atTime(7, 30),
                false, false, null, null, null, null, null);

        assertThat(legacy.arrivalDateMax()).isNull();
        assertThat(legacy.volumeCapacityM3()).isNull();
        assertThat(legacy.pricePerM3()).isNull();
    }
}
