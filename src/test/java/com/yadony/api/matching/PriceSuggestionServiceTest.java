package com.yadony.api.matching;

import com.yadony.api.matching.dto.PriceSuggestionResponse;
import com.yadony.api.matching.dto.PriceSuggestionResponse.ItemPriceSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PriceSuggestionService} exécuté contre la base : quels trajets comptent dans la
 * médiane (route, devise, statut, fenêtre) et comment les prix par article sont regroupés.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({PriceSuggestionService.class, PriceSuggestionServiceTest.FixedClock.class})
class PriceSuggestionServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementPriceGridItemRepository gridItems;
    @Autowired private PriceSuggestionService service;

    @BeforeEach
    void clean() {
        gridItems.deleteAll();
        announcements.deleteAll();
    }

    @Test
    void medianOfOddSample_isTheMiddlePrice() {
        trip("Paris", "Abidjan", "7");
        trip("Paris", "Abidjan", "8");
        trip("Paris", "Abidjan", "12");

        PriceSuggestionResponse r = service.suggest("Paris", "Abidjan", "EUR");

        assertThat(r.pricePerKg()).isEqualByComparingTo("8");
        assertThat(r.sampleSize()).isEqualTo(3);
        assertThat(r.currency()).isEqualTo("EUR");
    }

    @Test
    void medianOfEvenSample_isRoundedAverageOfTheTwoMiddles() {
        trip("Paris", "Abidjan", "7");
        trip("Paris", "Abidjan", "8");
        trip("Paris", "Abidjan", "9");
        trip("Paris", "Abidjan", "20");

        assertThat(service.suggest("Paris", "Abidjan", "EUR").pricePerKg()).isEqualByComparingTo("9");
    }

    @Test
    void tooFewTrips_giveNoKgPrice_butReportTheSampleSize() {
        trip("Paris", "Abidjan", "7");
        trip("Paris", "Abidjan", "8");

        PriceSuggestionResponse r = service.suggest("Paris", "Abidjan", "EUR");

        assertThat(r.pricePerKg()).isNull();
        assertThat(r.sampleSize()).isEqualTo(2);
        assertThat(r.items()).isEmpty();
    }

    @Test
    void cityMatch_ignoresCaseAndSpaces_andCurrencyIsNormalized() {
        trip("Paris", "Abidjan", "8");
        trip(" paris ", "ABIDJAN", "8");
        trip("PARIS", "abidjan ", "8");

        PriceSuggestionResponse r = service.suggest("  Paris", "abidjan", "eur");

        assertThat(r.sampleSize()).isEqualTo(3);
        assertThat(r.currency()).isEqualTo("EUR");
    }

    @Test
    void onlyTheSameRouteCurrencyLiveStatusesAndRecentTripsCount() {
        trip("Paris", "Abidjan", "8");
        save(trip("Paris", "Abidjan", "8"), AnnouncementStatus.FULL);
        save(trip("Paris", "Abidjan", "8"), AnnouncementStatus.COMPLETED);
        // Exclus : autre route, autre sens, autre devise, brouillon, annulé, trop ancien, sans prix au kilo.
        trip("Paris", "Dakar", "50");
        trip("Abidjan", "Paris", "50");
        save(withCurrency(trip("Paris", "Abidjan", "5000"), "XOF"), AnnouncementStatus.ACTIVE);
        save(trip("Paris", "Abidjan", "50"), AnnouncementStatus.DRAFT);
        save(trip("Paris", "Abidjan", "50"), AnnouncementStatus.CANCELLED);
        AnnouncementEntity old = trip("Paris", "Abidjan", "50");
        old.setDepartureDate(TODAY.minusDays(PriceSuggestionService.WINDOW_DAYS + 1));
        announcements.saveAndFlush(old);
        AnnouncementEntity gridOnly = trip("Paris", "Abidjan", "1");
        gridOnly.setPricePerKg(null);
        announcements.saveAndFlush(gridOnly);

        PriceSuggestionResponse r = service.suggest("Paris", "Abidjan", "EUR");

        assertThat(r.sampleSize()).isEqualTo(3);
        assertThat(r.pricePerKg()).isEqualByComparingTo("8");
    }

    @Test
    void otherCurrency_usesItsOwnTrips() {
        for (String p : List.of("4000", "5000", "6000")) {
            announcements.saveAndFlush(withCurrency(trip("Paris", "Abidjan", p), "XOF"));
        }
        trip("Paris", "Abidjan", "8");

        PriceSuggestionResponse r = service.suggest("Paris", "Abidjan", "XOF");

        assertThat(r.pricePerKg()).isEqualByComparingTo("5000");
        assertThat(r.sampleSize()).isEqualTo(3);
    }

    @Test
    void itemPrices_areGroupedByLabel_mostFrequentFirst_withAMinimumOfTwoTrips() {
        AnnouncementEntity a = trip("Paris", "Abidjan", "8");
        AnnouncementEntity b = trip("Paris", "Abidjan", "8");
        AnnouncementEntity c = trip("Paris", "Abidjan", "8");
        item(a, "Téléphone", "15");
        item(b, "téléphone ", "20");
        item(c, "Téléphone", "10");
        item(a, "Ordinateur", "30");
        item(b, "ordinateur", "40");
        item(c, "Vélo", "50"); // un seul trajet : pas proposé

        List<ItemPriceSuggestion> items = service.suggest("Paris", "Abidjan", "EUR").items();

        assertThat(items).extracting(ItemPriceSuggestion::label).containsExactly("Téléphone", "Ordinateur");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("15");
        assertThat(items.get(0).sampleSize()).isEqualTo(3);
        assertThat(items.get(1).unitPrice()).isEqualByComparingTo("35");
        assertThat(items.get(1).sampleSize()).isEqualTo(2);
    }

    @Test
    void itemPrices_ignoreTripsOutsideTheRouteOrStatus() {
        AnnouncementEntity a = trip("Paris", "Abidjan", "8");
        AnnouncementEntity draft = save(trip("Paris", "Abidjan", "8"), AnnouncementStatus.DRAFT);
        AnnouncementEntity other = trip("Paris", "Dakar", "8");
        item(a, "Téléphone", "15");
        item(draft, "Téléphone", "99");
        item(other, "Téléphone", "99");

        assertThat(service.suggest("Paris", "Abidjan", "EUR").items()).isEmpty();
    }

    @Test
    void itemSuggestions_countEachTripOnce_skipBlankLabels_andKeepAtMostFour() {
        List<Object[]> rows = new ArrayList<>();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        rows.add(new Object[]{"Téléphone", new BigDecimal("10"), t1});
        rows.add(new Object[]{"Téléphone", new BigDecimal("90"), t1}); // même trajet : ignoré
        rows.add(new Object[]{"Téléphone", new BigDecimal("20"), t2});
        rows.add(new Object[]{"  ", new BigDecimal("5"), t1});
        rows.add(new Object[]{null, new BigDecimal("5"), t2});
        rows.add(new Object[]{"Sac", null, t1});
        for (String label : List.of("A", "B", "C", "D")) {
            rows.add(new Object[]{label, new BigDecimal("5"), t1});
            rows.add(new Object[]{label, new BigDecimal("7"), t2});
        }

        List<ItemPriceSuggestion> items = PriceSuggestionService.itemSuggestions(rows);

        assertThat(items).hasSize(PriceSuggestionService.MAX_ITEMS);
        assertThat(items).extracting(ItemPriceSuggestion::label).containsExactly("A", "B", "C", "D");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("6");
    }

    @Test
    void itemSuggestions_keepTheMostFrequentSpelling() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{"ordinateur", new BigDecimal("30"), t1},
                new Object[]{"Ordinateur", new BigDecimal("30"), t2},
                new Object[]{"Ordinateur", new BigDecimal("30"), t3});

        assertThat(PriceSuggestionService.itemSuggestions(rows).get(0).label()).isEqualTo("Ordinateur");
    }

    @Test
    void helpers_handleNullCityAndRounding() {
        assertThat(PriceSuggestionService.normalize(null)).isEmpty();
        assertThat(PriceSuggestionService.roundToUnit(new BigDecimal("8.5"))).isEqualByComparingTo("9");
        assertThat(PriceSuggestionService.median(List.of(new BigDecimal("3")))).isEqualByComparingTo("3");
    }

    private AnnouncementEntity trip(String from, String to, String pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity(from);
        a.setArrivalCity(to);
        a.setDepartureDate(TODAY.plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord");
        a.setPickupLat(new BigDecimal("48.880000"));
        a.setPickupLng(new BigDecimal("2.355000"));
        a.setDeliveryAddressLabel("Cocody");
        a.setDeliveryLat(new BigDecimal("5.359000"));
        a.setDeliveryLng(new BigDecimal("-3.987000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("23"));
        a.setTotalKg(new BigDecimal("23"));
        a.setPricePerKg(new BigDecimal(pricePerKg));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcements.saveAndFlush(a);
    }

    private AnnouncementEntity save(AnnouncementEntity a, AnnouncementStatus status) {
        a.setStatus(status);
        return announcements.saveAndFlush(a);
    }

    private static AnnouncementEntity withCurrency(AnnouncementEntity a, String currency) {
        a.setCurrency(currency);
        return a;
    }

    private void item(AnnouncementEntity a, String label, String price) {
        AnnouncementPriceGridItemEntity g = new AnnouncementPriceGridItemEntity();
        g.setAnnouncementId(a.getId());
        g.setLabel(label);
        g.setUnitPriceNet(new BigDecimal(price));
        g.setPosition(0);
        gridItems.saveAndFlush(g);
    }
}
