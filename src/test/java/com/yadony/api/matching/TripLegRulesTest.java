package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripLegRulesTest {

    private static final LocalDate D = LocalDate.now().plusDays(10);

    static AnnouncementRequest leg(String from, String to, LocalDate date, LocalTime depTime,
                                   LocalDate arrivalDate, LocalTime arrTime, boolean draft) {
        return new AnnouncementRequest(from, to, date, depTime, arrTime,
                new AddressDto(from, 1.0, 1.0), new AddressDto(to, 2.0, 2.0),
                new BigDecimal("10"), new BigDecimal("5"), TransportMode.PLANE, null,
                null, null, null, null, null, null, null,
                date.atTime(6, 0), draft, null, null, arrivalDate);
    }

    static AnnouncementRequest leg(String from, String to, LocalDate date) {
        return leg(from, to, date, LocalTime.of(10, 0), null, null, false);
    }

    private static YadonyBusinessException refusal(List<AnnouncementRequest> legs) {
        try {
            TripLegRules.validate(legs);
        } catch (YadonyBusinessException e) {
            return e;
        }
        throw new AssertionError("refus attendu");
    }

    @Test
    void acceptsChainedLegs_evenTheSameDayAfterArrival() {
        assertThatCode(() -> TripLegRules.validate(List.of(
                leg("Paris", "Abidjan", D, LocalTime.of(8, 0), null, LocalTime.of(14, 0), false),
                leg("Abidjan", "Douala", D, LocalTime.of(18, 0), null, null, false),
                leg("Douala", "Paris", D.plusDays(5)))))
                .doesNotThrowAnyException();
    }

    @Test
    void cityMatchingIgnoresCaseSpacesAndAccents() {
        assertThatCode(() -> TripLegRules.validate(List.of(
                leg("Paris", "  Yaoundé ", D),
                leg("yaounde", "Douala", D.plusDays(4)))))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTooFewOrTooManyLegs() {
        assertThat(refusal(List.of(leg("Paris", "Abidjan", D))).getErrorCode()).isEqualTo("trip-legs-count");
        assertThat(refusal(Collections.nCopies(6, leg("Paris", "Paris", D))).getErrorCode())
                .isEqualTo("trip-legs-count");
        assertThatThrownBy(() -> TripLegRules.validate(null))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void refusesALegNotStartingWhereThePreviousArrives() {
        YadonyBusinessException e = refusal(List.of(
                leg("Paris", "Abidjan", D),
                leg("Dakar", "Douala", D.plusDays(4))));
        assertThat(e.getErrorCode()).isEqualTo("trip-leg-city-mismatch");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getProperties()).containsEntry("legIndex", 2);
    }

    @Test
    void refusesALegDepartingBeforeThePreviousArrivalDay() {
        YadonyBusinessException e = refusal(List.of(
                leg("Paris", "Abidjan", D, LocalTime.of(22, 0), D.plusDays(1), LocalTime.of(6, 0), false),
                leg("Abidjan", "Douala", D)));
        assertThat(e.getErrorCode()).isEqualTo("trip-leg-date-before-previous");
        assertThat(e.getProperties()).containsEntry("legIndex", 2);
    }

    @Test
    void refusesALegDepartingTheSameDayBeforeThePreviousArrivalTime() {
        YadonyBusinessException e = refusal(List.of(
                leg("Paris", "Abidjan", D, LocalTime.of(8, 0), null, LocalTime.of(14, 0), false),
                leg("Abidjan", "Douala", D, LocalTime.of(12, 0), null, null, false)));
        assertThat(e.getErrorCode()).isEqualTo("trip-leg-date-before-previous");
    }

    @Test
    void reportsTheThirdLegWhenItIsTheFaultyOne() {
        YadonyBusinessException e = refusal(List.of(
                leg("Paris", "Abidjan", D),
                leg("Abidjan", "Douala", D.plusDays(4)),
                leg("Lomé", "Paris", D.plusDays(9))));
        assertThat(e.getProperties()).containsEntry("legIndex", 3);
    }

    @Test
    void refusesMixingDraftAndPublishedLegs() {
        YadonyBusinessException e = refusal(List.of(
                leg("Paris", "Abidjan", D, LocalTime.of(10, 0), null, null, true),
                leg("Abidjan", "Douala", D.plusDays(4), LocalTime.of(10, 0), null, null, false)));
        assertThat(e.getErrorCode()).isEqualTo("trip-legs-draft-mismatch");
    }

    @Test
    void ignoresDateChainingWhenADateIsMissing() {
        AnnouncementRequest first = leg("Paris", "Abidjan", D);
        AnnouncementRequest noDate = new AnnouncementRequest("Abidjan", "Douala", null, LocalTime.of(10, 0), null,
                new AddressDto("A", 1.0, 1.0), new AddressDto("B", 2.0, 2.0),
                new BigDecimal("10"), new BigDecimal("5"), TransportMode.PLANE, null,
                null, null, null, null, null, null, null, null, false, null, null, null);
        assertThatCode(() -> TripLegRules.validate(List.of(first, noDate))).doesNotThrowAnyException();
    }

    @Test
    void withLegIndexKeepsTheOriginalRefusal() {
        YadonyBusinessException original = new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid-price", "Prix invalide", "Le prix par kg est obligatoire", Map.of("field", "price"));
        YadonyBusinessException e = TripLegRules.withLegIndex(original, 2);
        assertThat(e.getErrorCode()).isEqualTo("invalid-price");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getTitle()).isEqualTo("Prix invalide");
        assertThat(e.getMessage()).isEqualTo("Le prix par kg est obligatoire");
        assertThat(e.getProperties()).containsEntry("legIndex", 2).containsEntry("field", "price");
    }

    @Test
    void sameCityIsFalseForNulls() {
        assertThat(TripLegRules.sameCity(null, "Paris")).isFalse();
        assertThat(TripLegRules.sameCity("Paris", null)).isFalse();
    }
}
