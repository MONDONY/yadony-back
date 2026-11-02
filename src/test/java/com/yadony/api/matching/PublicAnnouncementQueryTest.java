package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAnnouncementQueryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static PublicAnnouncementQuery query(String dep, String arr, LocalDate from, LocalDate to,
                                                 BigDecimal kg, BigDecimal price, BigDecimal rating,
                                                 Integer page) {
        return PublicAnnouncementQuery.of(dep, arr, from, to, kg, price, rating, null, null, null, page, TODAY);
    }

    private static PublicAnnouncementQuery cities(String dep, String arr) {
        return query(dep, arr, null, null, null, null, null, null);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("invalid-search");
                    assertThat(e.getTitle()).isEqualTo("Invalid Search");
                });
    }

    @Test
    void normaliseLesVilles() {
        PublicAnnouncementQuery a = cities(" Paris ", "Dakar");
        PublicAnnouncementQuery b = cities("PARIS", "Dakar");
        PublicAnnouncementQuery c = cities("paris", "Dakar");
        assertThat(a).isEqualTo(b).isEqualTo(c);
        assertThat(a.departureCity()).isEqualTo("paris");
        assertThat(cities("Saint   Denis", null).departureCity()).isEqualTo("saint denis");
    }

    @Test
    void conserveLesAccents() {
        PublicAnnouncementQuery accent = cities(null, "Yaoundé");
        assertThat(accent.arrivalCity()).isEqualTo("yaoundé");
        assertThat(accent).isNotEqualTo(cities(null, "yaounde"));
    }

    @Test
    void villeVideOuAbsenteDevientNull() {
        assertThat(cities("   ", null).departureCity()).isNull();
        assertThat(cities(null, "").arrivalCity()).isNull();
    }

    @Test
    void refuseUneVilleDePlusDe80Caracteres() {
        assertInvalid(() -> cities("a".repeat(81), null));
        assertInvalid(() -> cities(null, "a".repeat(81)));
        assertThat(cities("a".repeat(80), null).departureCity()).hasSize(80);
    }

    @Test
    void refuseLaPage5EtAccepteLaPage4() {
        assertInvalid(() -> query(null, null, null, null, null, null, null, 5));
        assertThat(query(null, null, null, null, null, null, null, 4).page()).isEqualTo(4);
    }

    @Test
    void pageAbsenteVautZero() {
        assertThat(query(null, null, null, null, null, null, null, null).page()).isZero();
    }

    @Test
    void refusePageNegative() {
        assertInvalid(() -> query(null, null, null, null, null, null, null, -1));
    }

    @Test
    void refuseDateFromApresDateTo() {
        assertInvalid(() -> query(null, null, TODAY.plusDays(5), TODAY.plusDays(4),
                null, null, null, null));
    }

    @Test
    void refuseUnePlageDe91JoursEtAccepte90() {
        assertInvalid(() -> query(null, null, TODAY, TODAY.plusDays(91), null, null, null, null));
        PublicAnnouncementQuery ok = query(null, null, TODAY, TODAY.plusDays(90), null, null, null, null);
        assertThat(ok.dateTo()).isEqualTo(TODAY.plusDays(90));
    }

    @Test
    void dateFromParDefautVautAujourdhui() {
        assertThat(query(null, null, null, null, null, null, null, null).dateFrom()).isEqualTo(TODAY);
    }

    @Test
    void dateToParDefautVautDateFromPlus90Jours() {
        PublicAnnouncementQuery q = query(null, null, null, null, null, null, null, null);
        assertThat(q.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(q.dateTo()).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void dateToAbsentEtDateToExplicitePlus90DonnentDesCriteresEgaux() {
        LocalDate d = TODAY.plusDays(3);
        PublicAnnouncementQuery sansDateTo = query(null, null, d, null, null, null, null, null);
        PublicAnnouncementQuery avecDateTo = query(null, null, d, d.plusDays(90), null, null, null, null);
        assertThat(sansDateTo).isEqualTo(avecDateTo);
    }

    @Test
    void dateToAbsentAvecDateFromExpliciteDansLePasse() {
        LocalDate passe = TODAY.minusDays(10);
        PublicAnnouncementQuery q = query(null, null, passe, null, null, null, null, null);
        assertThat(q.dateFrom()).isEqualTo(passe);
        assertThat(q.dateTo()).isEqualTo(passe.plusDays(90));
    }

    @Test
    void refuseUneValeurNegative() {
        assertInvalid(() -> query(null, null, null, null, new BigDecimal("-1"), null, null, null));
        assertInvalid(() -> query(null, null, null, null, null, new BigDecimal("-0.01"), null, null));
        assertInvalid(() -> query(null, null, null, null, null, null, new BigDecimal("-0.1"), null));
        assertInvalid(() -> query(null, null, null, null, null, null, new BigDecimal("5.1"), null));
        PublicAnnouncementQuery ok = query(null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("5"), null);
        assertThat(ok.minRating()).isEqualByComparingTo("5");
    }

    @Test
    void normaliseModeDeTransportEtTypeDeContenu() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                true, " plane ", " DOCUMENTS ", 0, TODAY);
        assertThat(q.transportMode()).isEqualTo("PLANE");
        assertThat(q.contentType()).isEqualTo("DOCUMENTS");
        assertThat(q.urgent()).isTrue();

        PublicAnnouncementQuery inconnu = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, "teleporter", "  ", 0, TODAY);
        assertThat(inconnu.transportMode()).isEqualTo("TELEPORTER");
        assertThat(inconnu.contentType()).isNull();
    }
}
