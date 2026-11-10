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
                "capacityUnit", "pricePerKgDisplay", "currency", "pricingMode", "transportMode",
                "urgent", "acceptedContentTypes", "traveler");
        assertThat(fieldNames(root.get("traveler"))).containsExactlyInAnyOrder(
                "displayName", "averageRating", "totalTrips", "identityVerified");

        assertThat(root.get("id").asText()).isEqualTo(ID.toString());
        assertThat(root.get("departureCity").asText()).isEqualTo("Paris");
        assertThat(root.get("arrivalCity").asText()).isEqualTo("Dakar");
        assertThat(root.get("departureDate").asText()).isEqualTo("2026-12-20");
        assertThat(root.get("currency").asText()).isEqualTo("EUR");
        assertThat(root.get("transportMode").asText()).isEqualTo("PLANE");
        assertThat(root.get("capacityUnit").asText()).isEqualTo("KG_EXACT");
        assertThat(root.get("pricingMode").asText()).isEqualTo("KG");
        assertThat(root.get("availableKg").decimalValue()).isEqualByComparingTo("15.0");
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
    void traiteUnPrixNulCommeAbsent() throws Exception {
        // Trajet MIXED à grille seule : 0 au kilo en base, jamais « 0 €/kg » côté public.
        PublicAnnouncementCard card = PublicAnnouncementCard.from(source(BigDecimal.ZERO));

        assertThat(card.pricePerKgDisplay()).isNull();
        assertThat(json.readTree(json.writeValueAsString(card)).has("pricePerKgDisplay")).isFalse();
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

    /** Copie de {@code r} avec un autre profil voyageur, unité, mode de prix et types acceptés. */
    private static AnnouncementSearchResponse with(AnnouncementSearchResponse r, TravelerProfileDto traveler,
                                                   CapacityUnit unit, PricingMode pricing,
                                                   List<String> accepted) {
        return new AnnouncementSearchResponse(
                r.id(), r.travelerId(), r.departureCity(), r.arrivalCity(), r.departureDate(),
                r.departureTime(), r.arrivalTime(), r.arrivalDate(), r.pickupAddress(),
                r.deliveryAddress(), r.availableKg(), r.totalKg(), r.pricePerKg(),
                r.pricePerKgDisplay(), r.transportMode(), r.stopsCount(), r.status(), r.bidsCount(),
                traveler, r.description(), accepted, r.refusedTypes(),
                r.acceptedPaymentMethods(), unit, r.createdAt(), r.updatedAt(),
                pricing, r.priceGridItems(), r.handoverDeadline(), r.isFavorite(),
                r.urgent(), r.currency(), r.negotiable(), r.availablePaymentMethods(),
                r.convertedPricePerKg(), r.convertedCurrency(), r.pricePerKgDisplayConverted(),
                r.uniqueViewerCount(), r.departureTimezone());
    }

    @Test
    void identiteVerifieeReprendKycVerifiedEtPasUnBooleenVoisin() {
        AnnouncementSearchResponse r = source(new BigDecimal("9.00"));
        // kycVerified seul à false, tous les booléens voisins à true : un décalage de position
        // (gpPro, isProAccount, acceptsUnverified) donnerait true.
        TravelerProfileDto nonVerifie = new TravelerProfileDto(
                TRAVELER_ID, "Awa D.", 4.8, 12, true, true, true, false, null, true);
        assertThat(PublicAnnouncementCard.from(with(r, nonVerifie, r.capacityUnit(), r.pricingMode(),
                r.acceptedContentTypes())).traveler().identityVerified()).isFalse();

        // Et l'inverse : kycVerified seul à true.
        TravelerProfileDto verifie = new TravelerProfileDto(
                TRAVELER_ID, "Awa D.", 4.8, 12, false, false, false, true, null, false);
        assertThat(PublicAnnouncementCard.from(with(r, verifie, r.capacityUnit(), r.pricingMode(),
                r.acceptedContentTypes())).traveler().identityVerified()).isTrue();
    }

    @Test
    void carteAnonymeNeLaissePasFuirLeStatutNiLeNumeroDEntreprise() throws Exception {
        AnnouncementSearchResponse r = source(new BigDecimal("9.00"));
        TravelerProfileDto pro = new TravelerProfileDto(
                TRAVELER_ID, "Awa D.", 4.8, 12, false, true, true, true, null, true,
                "PROFESSIONAL", "Acme Express", "SIREN 123 456 789", "FR");

        PublicAnnouncementCard card = PublicAnnouncementCard.from(with(r, pro, r.capacityUnit(), r.pricingMode(),
                r.acceptedContentTypes()));

        String out = json.writeValueAsString(card);
        assertThat(out).doesNotContain("SIREN").doesNotContain("123 456 789").doesNotContain("Acme")
                .doesNotContain("registration").doesNotContain("businessName");
    }

    @Test
    void exposeLUniteDeCapaciteEtLeModeDePrix() {
        AnnouncementSearchResponse r = source(BigDecimal.ZERO);
        // Kilo libre : availableKg n'est qu'une valeur technique, le client lit capacityUnit.
        // Mixte à grille seule : pas de prix au kilo, pricingMode dit pourquoi.
        PublicAnnouncementCard card = PublicAnnouncementCard.from(
                with(r, r.traveler(), CapacityUnit.KG_FREE, PricingMode.MIXED, r.acceptedContentTypes()));

        assertThat(card.capacityUnit()).isEqualTo(CapacityUnit.KG_FREE);
        assertThat(card.pricingMode()).isEqualTo(PricingMode.MIXED);
        assertThat(card.pricePerKgDisplay()).isNull();
    }

    @Test
    void copieLesTypesAcceptesSansGarderLaListeSource() {
        AnnouncementSearchResponse r = source(new BigDecimal("9.00"));
        List<String> mutable = new java.util.ArrayList<>(List.of("DOCUMENTS"));
        PublicAnnouncementCard card = PublicAnnouncementCard.from(
                with(r, r.traveler(), r.capacityUnit(), r.pricingMode(), mutable));
        mutable.add("CLOTHES");

        assertThat(card.acceptedContentTypes()).containsExactly("DOCUMENTS");
        // Liste source absente : tolérée, rien n'est sérialisé.
        assertThat(PublicAnnouncementCard.from(
                with(r, r.traveler(), r.capacityUnit(), r.pricingMode(), null)).acceptedContentTypes()).isNull();
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
