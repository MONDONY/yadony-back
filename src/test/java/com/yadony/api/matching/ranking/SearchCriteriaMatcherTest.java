package com.yadony.api.matching.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SearchCriteriaMatcherTest {

    private static final LocalDate D12 = LocalDate.of(2026, 11, 12); // jeudi
    private static final SearchCriteria NONE = new SearchCriteria(null, null, null, null, null, null, null,
            null, false, false, null, false);

    private AnnouncementEntity trip() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setDepartureDate(D12);
        a.setAvailableKg(new BigDecimal("10"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setTransportMode(TransportMode.PLANE);
        a.setAcceptedContentTypes(List.of("CLOTHES", "FOOD"));
        return a;
    }

    private List<String> codes(List<UnmetCriterion> l) {
        return l.stream().map(UnmetCriterion::code).toList();
    }

    @Test
    void exactAnnouncement_returnsEmpty() {
        SearchCriteria c = new SearchCriteria(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15),
                new BigDecimal("5"), new BigDecimal("20"), "plane", new BigDecimal("4"), "FOOD",
                null, false, false, null, true);
        assertThat(SearchCriteriaMatcher.unmet(trip(), new BigDecimal("4.5"), true, c)).isEmpty();
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, true, NONE)).isEmpty();
    }

    @Test
    void date_beforeFrom() {
        SearchCriteria c = new SearchCriteria(LocalDate.of(2026, 11, 13), null, null, null, null, null, null,
                null, false, false, null, false);
        List<UnmetCriterion> r = SearchCriteriaMatcher.unmet(trip(), null, false, c);
        assertThat(r).containsExactly(new UnmetCriterion("DATE", "2026-11-13", "2026-11-12"));
    }

    @Test
    void date_afterTo() {
        SearchCriteria c = new SearchCriteria(null, LocalDate.of(2026, 11, 10), null, null, null, null, null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, false, c))
                .containsExactly(new UnmetCriterion("DATE", "2026-11-10", "2026-11-12"));
    }

    @Test
    void availableKg_min() {
        AnnouncementEntity a = trip();
        a.setAvailableKg(new BigDecimal("5"));
        SearchCriteria c = new SearchCriteria(null, null, new BigDecimal("8"), null, null, null, null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(a, null, false, c))
                .containsExactly(new UnmetCriterion("AVAILABLE_KG", "8", "5"));
    }

    @Test
    void availableKg_kgFreePassesMin_butNotMax() {
        AnnouncementEntity a = trip();
        a.setCapacityUnit(CapacityUnit.KG_FREE);
        a.setAvailableKg(BigDecimal.ONE);
        SearchCriteria min = new SearchCriteria(null, null, new BigDecimal("23"), null, null, null, null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(a, null, false, min)).isEmpty();
    }

    @Test
    void availableKg_max() {
        SearchCriteria c = new SearchCriteria(null, null, null, new BigDecimal("6"), null, null, null,
                null, false, false, null, false);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, c))).containsExactly("AVAILABLE_KG");
    }

    @Test
    void transportMode() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, "CAR", null, null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, false, c))
                .containsExactly(new UnmetCriterion("TRANSPORT_MODE", "CAR", "PLANE"));
    }

    @Test
    void transportMode_invalidValueIgnored() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, "ROCKET", null, null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, false, c)).isEmpty();
    }

    @Test
    void minRating() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, new BigDecimal("4.5"), null,
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), new BigDecimal("4.0"), false, c))
                .containsExactly(new UnmetCriterion("MIN_RATING", "4.5", "4"));
    }

    @Test
    void nullTravelerRating_withMinRating_isUnmet() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, new BigDecimal("3"), null,
                null, false, false, null, false);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, c))).containsExactly("MIN_RATING");
    }

    @Test
    void contentType() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, "ELECTRONICS",
                null, false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, false, c))
                .containsExactly(new UnmetCriterion("CONTENT_TYPE", "ELECTRONICS", "CLOTHES, FOOD"));
    }

    @Test
    void radius() {
        SearchCriteria out = new SearchCriteria(null, null, null, null, null, null, null,
                Set.of(UUID.randomUUID()), false, false, null, false);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, out))).containsExactly("RADIUS");
        AnnouncementEntity a = trip();
        SearchCriteria in = new SearchCriteria(null, null, null, null, null, null, null,
                Set.of(a.getId()), false, false, null, false);
        assertThat(SearchCriteriaMatcher.unmet(a, null, false, in)).isEmpty();
    }

    @Test
    void weekendOnly() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, null,
                null, true, false, null, false);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, c))).containsExactly("WEEKEND_ONLY");
        AnnouncementEntity sat = trip();
        sat.setDepartureDate(LocalDate.of(2026, 11, 14));
        AnnouncementEntity sun = trip();
        sun.setDepartureDate(LocalDate.of(2026, 11, 15));
        assertThat(SearchCriteriaMatcher.unmet(sat, null, false, c)).isEmpty();
        assertThat(SearchCriteriaMatcher.unmet(sun, null, false, c)).isEmpty();
    }

    @Test
    void urgent() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, null,
                null, false, true, LocalDate.of(2026, 11, 5), false);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, false, c))
                .containsExactly(new UnmetCriterion("URGENT", "2026-11-05", "2026-11-12"));
    }

    @Test
    void kiloProOnly() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, null,
                null, false, false, null, true);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, c))).containsExactly("KILO_PRO_ONLY");
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, true, c)).isEmpty();
    }

    @Test
    void kiloProOnly_proTraveler_isNotUnmet() {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, null,
                null, false, false, null, true);
        assertThat(SearchCriteriaMatcher.unmet(trip(), null, true, c)).isEmpty();
    }

    @Test
    void multipleMisses_returnsAll() {
        SearchCriteria c = new SearchCriteria(LocalDate.of(2026, 11, 13), null, new BigDecimal("50"), null,
                "CAR", null, null, null, true, false, null, false);
        assertThat(codes(SearchCriteriaMatcher.unmet(trip(), null, false, c)))
                .containsExactly("DATE", "AVAILABLE_KG", "TRANSPORT_MODE", "WEEKEND_ONLY");
    }
}
