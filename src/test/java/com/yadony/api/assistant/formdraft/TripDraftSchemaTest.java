package com.yadony.api.assistant.formdraft;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripDraftSchemaTest {

    private final TripDraftSchema schema = new TripDraftSchema();
    private final DraftContext ctx = new DraftContext(LocalDate.parse("2026-10-17"), ZoneOffset.UTC, "fr", "EUR");

    private static Map<String, Object> complet() {
        Map<String, Object> m = new HashMap<>();
        m.put("departureCity", "Paris");
        m.put("departureCountryCode", "fr");
        m.put("arrivalCity", "Abidjan");
        m.put("arrivalCountryCode", "CI");
        m.put("departureDate", "2026-11-15");
        m.put("departureTime", "15:00");
        m.put("transportMode", "PLANE");
        m.put("direct", true);
        m.put("availableKg", 23);
        m.put("pricingMode", "MIXED");
        m.put("pricePerKg", 10);
        m.put("currency", "EUR");
        m.put("cardEnabled", true);
        m.put("cashEnabled", true);
        return m;
    }

    @Test
    void phraseDExempleDonneUnBrouillonComplet() {
        Map<String, Object> d = schema.sanitize(complet(), ctx);
        assertThat(d).containsEntry("departureCountryCode", "FR")
                .containsEntry("capacityUnit", "SUITCASE_23KG")
                .containsEntry("pricingMode", "MIXED");
        assertThat((BigDecimal) d.get("pricePerKg")).isEqualByComparingTo("10");
        assertThat(schema.missing(d)).isEmpty();
        assertThat(schema.understood(d))
                .containsExactly("route", "date", "time", "transport", "capacity", "price", "grid", "payment", "direct");
    }

    @Test
    void champsManquantsDansLOrdreSansLeTransport() {
        Map<String, Object> d = schema.sanitize(Map.of("departureCity", "Paris"), ctx);
        assertThat(schema.missing(d)).containsExactly("arrivalCity", "departureDate", "departureTime",
                "availableKg", "price", "paymentMethod");
    }

    @Test
    void grilleSeuleSuffitPourLePrix() {
        Map<String, Object> raw = complet();
        raw.remove("pricePerKg");
        assertThat(schema.missing(schema.sanitize(raw, ctx))).isEmpty();
        raw.put("pricingMode", "KG");
        assertThat(schema.missing(schema.sanitize(raw, ctx))).containsExactly("price");
    }

    @Test
    void paiementTousFauxEstManquant() {
        Map<String, Object> raw = complet();
        raw.put("cardEnabled", false);
        raw.put("cashEnabled", false);
        assertThat(schema.missing(schema.sanitize(raw, ctx))).containsExactly("paymentMethod");
    }

    @Test
    void valeursInvalidesRetirees() {
        Map<String, Object> raw = complet();
        raw.put("availableKg", 5000);
        raw.put("departureDate", "2026-10-01");
        raw.put("transportMode", "ROCKET");
        raw.put("isAdmin", true);
        raw.put("description", "x".repeat(501));
        Map<String, Object> d = schema.sanitize(raw, ctx);
        assertThat(d).doesNotContainKeys("availableKg", "departureDate", "transportMode", "isAdmin", "description");
    }

    @Test
    void villesIdentiquesRetirentLArrivee() {
        Map<String, Object> raw = complet();
        raw.put("arrivalCity", "paris");
        Map<String, Object> d = schema.sanitize(raw, ctx);
        assertThat(d).containsEntry("departureCity", "Paris").doesNotContainKeys("arrivalCity", "arrivalCountryCode");
    }

    @Test
    void uniteDeCapaciteCoherenteAvecLesKilos() {
        assertThat(unit(32, null)).isEqualTo("SUITCASE_32KG");
        assertThat(unit(20, null)).isEqualTo("KG_EXACT");
        assertThat(unit(20, "SUITCASE_23KG")).isEqualTo("KG_EXACT");
        assertThat(unit(20, "KG_FREE")).isEqualTo("KG_FREE");
        assertThat(schema.sanitize(Map.of("capacityUnit", "SUITCASE_32KG"), ctx)).containsEntry("capacityUnit", "SUITCASE_32KG");
    }

    @Test
    void sanitizeEstIdempotent() {
        Map<String, Object> once = schema.sanitize(complet(), ctx);
        assertThat(schema.sanitize(once, ctx)).isEqualTo(once);
    }

    @Test
    void questionsDeRepliEnDeuxLanguesAuVouvoiement() {
        assertThat(schema.fallbackQuestion("departureDate", "fr")).isEqualTo("Quel jour partez-vous ?");
        assertThat(schema.fallbackQuestion("departureDate", "en")).isEqualTo("What day do you leave?");
        for (String field : schema.missing(Map.of())) {
            assertThat(schema.fallbackQuestion(field, "fr")).isNotBlank().doesNotContain(" tu ", "-tu", "ton ", "ta ");
            assertThat(schema.fallbackQuestion(field, "en")).isNotBlank();
        }
    }

    @Test
    void promptContientLaDateEtLesChamps() {
        String p = schema.systemPrompt(ctx);
        assertThat(p).contains("2026-10-17", "SUITCASE_23KG", "MIXED", "needsConfirmation", "PLANE", "\"vous\"");
        assertThat(schema.keys()).hasSize(18).contains("pricePerKg", "direct");
        assertThat(schema.kind()).isEqualTo(FormKind.TRIP);
    }

    private String unit(int kg, String unit) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("availableKg", kg);
        if (unit != null) raw.put("capacityUnit", unit);
        return (String) schema.sanitize(raw, ctx).get("capacityUnit");
    }
}
