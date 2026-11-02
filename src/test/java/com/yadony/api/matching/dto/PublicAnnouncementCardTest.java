package com.yadony.api.matching.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.PricingMode;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class PublicAnnouncementCardTest {

    /** Même réglage que application.yml : default-property-inclusion NON_NULL, dates en ISO. */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAVELER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static AnnouncementSearchResponse source(BigDecimal pricePerKgDisplay) {
        TravelerProfileDto traveler = new TravelerProfileDto(
                TRAVELER_ID, "Awa D.", 4.8, 12, false, true, true, true,
                "https://cdn.example.com/avatar.jpg", true);
        return new AnnouncementSearchResponse(
                ID,
                TRAVELER_ID,
                "Paris",
                "Dakar",
                LocalDate.of(2026, 12, 20),
                LocalTime.of(10, 30),
                LocalTime.of(18, 0),
                LocalDate.of(2026, 12, 21),
                new AddressDto("12 rue de Rivoli, Paris", 48.85, 2.35),
                new AddressDto("Point relais Dakar", 14.69, -17.44),
                new BigDecimal("15.0"),
                new BigDecimal("23.0"),
                new BigDecimal("8.00"),
                pricePerKgDisplay,
                TransportMode.PLANE,
                1,
                "ACTIVE",
                3L,
                traveler,
                "Appelez-moi au +33612345678",
                List.of("DOCUMENTS", "CLOTHES"),
                List.of("LIQUIDS"),
                List.of("CASH"),
                CapacityUnit.KG_EXACT,
                LocalDateTime.of(2026, 11, 1, 9, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0),
                PricingMode.KG,
                List.of(),
                LocalDateTime.of(2026, 12, 19, 18, 0),
                true,
                true,
                "EUR",
                true,
                Set.of(PaymentMethod.CASH),
                new BigDecimal("5250.00"),
                "XOF",
                new BigDecimal("6500.00"),
                42L,
                "Europe/Paris");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void exposeUneListeBlancheDeChamps() throws Exception {
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(new BigDecimal("9.00")));

        String raw = json.writeValueAsString(card);
        JsonNode root = json.readTree(raw);

        assertThat(fieldNames(root)).containsExactlyInAnyOrder(
                "id", "departureCity", "arrivalCity", "departureDate", "availableKg",
                "pricePerKgDisplay", "currency", "transportMode", "urgent",
                "acceptedContentTypes", "traveler");
        assertThat(fieldNames(root.get("traveler"))).containsExactlyInAnyOrder(
                "displayName", "averageRating", "totalTrips", "identityVerified");

        assertThat(root.get("id").asText()).isEqualTo(ID.toString());
        assertThat(root.get("departureCity").asText()).isEqualTo("Paris");
        assertThat(root.get("arrivalCity").asText()).isEqualTo("Dakar");
        assertThat(root.get("departureDate").asText()).isEqualTo("2026-12-20");
        assertThat(root.get("currency").asText()).isEqualTo("EUR");
        assertThat(root.get("transportMode").asText()).isEqualTo("PLANE");
        assertThat(root.get("urgent").asBoolean()).isTrue();
        assertThat(root.get("acceptedContentTypes")).hasSize(2);
        assertThat(root.get("traveler").get("displayName").asText()).isEqualTo("Awa D.");
        assertThat(root.get("traveler").get("averageRating").asDouble()).isEqualTo(4.8);
        assertThat(root.get("traveler").get("totalTrips").asInt()).isEqualTo(12);
        assertThat(root.get("traveler").get("identityVerified").asBoolean()).isTrue();

        // Aucune donnée privée ne doit fuiter, même sous forme de valeur.
        assertThat(raw)
                .doesNotContain("+33612345678")
                .doesNotContain("avatar")
                .doesNotContain(TRAVELER_ID.toString())
                .doesNotContain("Rivoli")
                .doesNotContain("48.85")
                .doesNotContain("5250")
                .doesNotContain("6500");
    }

    @Test
    void neSerialisePasPricePerKgDisplayQuandAbsent() throws Exception {
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(null));

        JsonNode root = json.readTree(json.writeValueAsString(card));

        assertThat(root.has("pricePerKgDisplay")).isFalse();
        assertThat(root.has("currency")).isTrue();
    }

    @Test
    void neSerialisePasPricePerKgDisplayQuandAbsentMemeAvecUnMapperNu() throws Exception {
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(null));

        ObjectMapper bare = new ObjectMapper().findAndRegisterModules();
        JsonNode root = bare.readTree(bare.writeValueAsString(card));

        assertThat(root.has("pricePerKgDisplay")).isFalse();
    }

    @Test
    void reprendLePrixAffichePasLeNet() {
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(new BigDecimal("9.00")));

        assertThat(card.pricePerKgDisplay()).isEqualByComparingTo("9.00");
        assertThat(card.pricePerKgDisplay()).isNotEqualByComparingTo("8.00");
        assertThat(card.currency()).isEqualTo("EUR");
    }

    @Test
    void mappeLeProfilVoyageurSansIdentifiantNiPhoto() {
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(new BigDecimal("9.00")));

        assertThat(card.traveler()).isEqualTo(new PublicTravelerCard("Awa D.", 4.8, 12, true));
    }

    @Test
    void toleraUnVoyageurAbsent() {
        AnnouncementSearchResponse r = source(new BigDecimal("9.00"));
        AnnouncementSearchResponse sansVoyageur = new AnnouncementSearchResponse(
                r.id(), r.travelerId(), r.departureCity(), r.arrivalCity(), r.departureDate(),
                r.departureTime(), r.arrivalTime(), r.arrivalDate(), r.pickupAddress(),
                r.deliveryAddress(), r.availableKg(), r.totalKg(), r.pricePerKg(),
                r.pricePerKgDisplay(), r.transportMode(), r.stopsCount(), r.status(), r.bidsCount(),
                null, r.description(), r.acceptedContentTypes(), r.refusedTypes(),
                r.acceptedPaymentMethods(), r.capacityUnit(), r.createdAt(), r.updatedAt(),
                r.pricingMode(), r.priceGridItems(), r.handoverDeadline(), r.isFavorite(),
                r.urgent(), r.currency(), r.negotiable(), r.availablePaymentMethods(),
                r.convertedPricePerKg(), r.convertedCurrency(), r.pricePerKgDisplayConverted(),
                r.uniqueViewerCount(), r.departureTimezone());

        assertThat(PublicAnnouncementCard.from(sansVoyageur).traveler()).isNull();
    }
}
