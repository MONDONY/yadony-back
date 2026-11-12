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
        // Annonces ouvertes et à venir : un dateFrom passé est ramené à aujourd'hui.
        LocalDate passe = TODAY.minusDays(10);
        PublicAnnouncementQuery q = query(null, null, passe, null, null, null, null, null);
        assertThat(q.dateFrom()).isEqualTo(TODAY);
        assertThat(q.dateTo()).isEqualTo(TODAY.plusDays(90));
        assertThat(q).isEqualTo(query(null, null, null, null, null, null, null, null));
    }

    @Test
    void laPlageDe90JoursSeMesureDepuisLeDateFromRamene() {
        // 95 jours depuis le dateFrom saisi, 85 depuis aujourd'hui : accepté.
        PublicAnnouncementQuery q = query(null, null, TODAY.minusDays(10), TODAY.plusDays(85),
                null, null, null, null);
        assertThat(q.dateFrom()).isEqualTo(TODAY);
        assertThat(q.dateTo()).isEqualTo(TODAY.plusDays(85));
        // Une fin déjà passée reste incohérente avec « à venir » : refusée.
        assertInvalid(() -> query(null, null, TODAY.minusDays(10), TODAY.minusDays(1),
                null, null, null, null));
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

    /** Un des trois paramètres numériques posé à {@code value}, les deux autres absents. */
    private static PublicAnnouncementQuery numeric(int which, BigDecimal value) {
        return switch (which) {
            case 0 -> query(null, null, null, null, value, null, null, null);
            case 1 -> query(null, null, null, null, null, value, null, null);
            default -> query(null, null, null, null, null, null, value, null);
        };
    }

    @Test
    void refuseLesExposantsGeantsSurLesTroisParametresNumeriques() {
        // 1E+20000000 tient en 12 octets mais setScale(4) le matérialisait en 10^20000004 (9,4 s).
        for (String raw : new String[] {"1E+999999", "1E-999999", "1E+2147483647"}) {
            BigDecimal value = new BigDecimal(raw);
            for (int which = 0; which < 3; which++) {
                int field = which;
                assertInvalid(() -> numeric(field, value));
            }
        }
    }

    @Test
    void refusePlusDeQuatreDecimales() {
        for (int which = 0; which < 3; which++) {
            int field = which;
            assertInvalid(() -> numeric(field, new BigDecimal("1.00001")));
        }
        assertThat(numeric(1, new BigDecimal("1.0001")).maxPricePerKg()).isEqualByComparingTo("1.0001");
    }

    @Test
    void unVirguleZeroEtUnDonnentDesCriteresEgaux() {
        for (int which = 0; which < 3; which++) {
            assertThat(numeric(which, new BigDecimal("1.0")))
                    .isEqualTo(numeric(which, new BigDecimal("1")))
                    .isEqualTo(numeric(which, new BigDecimal("1.0000")));
        }
        assertThat(numeric(0, new BigDecimal("0.00")).minAvailableKg()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void borneHauteDesKilosEtDuPrix() {
        assertThat(numeric(0, new BigDecimal("1000")).minAvailableKg()).isEqualByComparingTo("1000");
        assertInvalid(() -> numeric(0, new BigDecimal("1000.0001")));
        assertInvalid(() -> numeric(0, new BigDecimal("1001")));
        assertThat(numeric(1, new BigDecimal("100000")).maxPricePerKg()).isEqualByComparingTo("100000");
        assertInvalid(() -> numeric(1, new BigDecimal("100000.0001")));
        assertInvalid(() -> numeric(1, new BigDecimal("100001")));
        assertThat(PublicAnnouncementQuery.MAX_MIN_AVAILABLE_KG).isEqualByComparingTo("1000");
        assertThat(PublicAnnouncementQuery.MAX_PRICE_PER_KG).isEqualByComparingTo("100000");
    }

    @Test
    void normaliseModeDeTransportEtTypeDeContenu() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                true, " plane ", " DOCUMENTS ", 0, TODAY);
        assertThat(q.transportMode()).isEqualTo("PLANE");
        assertThat(q.contentType()).isEqualTo("DOCUMENTS");
        assertThat(q.urgent()).isTrue();

        PublicAnnouncementQuery vide = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, "  ", "  ", 0, TODAY);
        assertThat(vide.transportMode()).isNull();
        assertThat(vide.contentType()).isNull();
    }

    @Test
    void normaliseTailleMinimaleEtVilleDePassage() {
        PublicAnnouncementQuery q = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, null, " xl ", "  Tanger  ", 0, TODAY);
        assertThat(q.minItemSize()).isEqualTo(ItemSize.XL);
        assertThat(q.viaCity()).isEqualTo("tanger");

        PublicAnnouncementQuery vide = PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, null, "  ", "  ", 0, TODAY);
        assertThat(vide.minItemSize()).isNull();
        assertThat(vide.viaCity()).isNull();
        // Sans les deux : même requête qu'avec l'ancienne signature (clé de cache identique).
        assertThat(PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, null, 0, TODAY))
                .isEqualTo(PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                        null, null, null, null, null, 0, TODAY));
    }

    @Test
    void refuseUneTailleMinimaleInconnueOuUneVilleDePassageTropLongue() {
        assertInvalid(() -> PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, null, "XXL", null, 0, TODAY));
        assertInvalid(() -> PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, null, null, "x".repeat(PublicAnnouncementQuery.MAX_CITY_LENGTH + 1), 0, TODAY));
    }

    @Test
    void refuseUnModeDeTransportInconnu() {
        assertInvalid(() -> PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, "teleporter", null, 0, TODAY));
        for (TransportMode mode : TransportMode.values()) {
            assertThat(PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                    null, mode.name().toLowerCase(java.util.Locale.ROOT), null, 0, TODAY).transportMode())
                    .isEqualTo(mode.name());
        }
    }

    @Test
    void borneLeTypeDeContenuA40Caracteres() {
        assertInvalid(() -> PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, "a".repeat(41), 0, TODAY));
        assertThat(PublicAnnouncementQuery.of(null, null, null, null, null, null, null,
                null, null, " " + "a".repeat(40) + " ", 0, TODAY).contentType()).hasSize(40);
        assertThat(PublicAnnouncementQuery.MAX_CONTENT_TYPE_LENGTH).isEqualTo(40);
    }
}
