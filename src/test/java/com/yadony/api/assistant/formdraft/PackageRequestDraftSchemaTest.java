package com.yadony.api.assistant.formdraft;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackageRequestDraftSchemaTest {

    private final PackageRequestDraftSchema schema = new PackageRequestDraftSchema();
    private final DraftContext ctx = new DraftContext(LocalDate.parse("2026-10-17"), ZoneOffset.UTC, "fr", "EUR");

    private static Map<String, Object> exemple() {
        Map<String, Object> m = new HashMap<>();
        m.put("departureCity", "Lyon");
        m.put("arrivalCity", "Dakar");
        m.put("desiredDate", "2026-11-20");
        m.put("dateToleranceDays", 3);
        m.put("weightKg", 5);
        m.put("categories", List.of("Vêtements"));
        m.put("acceptedPaymentMethods", List.of("STRIPE"));
        m.put("totalBudget", 60);
        m.put("currency", "EUR");
        return m;
    }

    @Test
    void phraseDExempleDonneUnBrouillonComplet() {
        Map<String, Object> d = schema.sanitize(exemple(), ctx);
        assertThat(d).containsEntry("parcelSize", "SMALL").containsEntry("dateToleranceDays", 3);
        assertThat(schema.missing(d)).isEmpty();
        assertThat(schema.understood(d))
                .containsExactly("route", "date", "flexibility", "weight", "content", "budget", "payment");
    }

    @Test
    void tailleCalculeeParLeServeurJamaisPriseDuModele() {
        Map<String, Object> raw = exemple();
        raw.put("weightKg", 20);
        raw.put("parcelSize", "SMALL");
        assertThat(schema.sanitize(raw, ctx)).containsEntry("parcelSize", "LARGE");
        raw.remove("weightKg");
        assertThat(schema.sanitize(raw, ctx)).doesNotContainKey("parcelSize");
    }

    @Test
    void bornesDuFormulaire() {
        Map<String, Object> raw = exemple();
        raw.put("weightKg", 40);
        raw.put("dateToleranceDays", 9);
        raw.put("totalBudget", 2_000_000);
        raw.put("acceptedPaymentMethods", List.of("CARD", "WAVE"));
        raw.put("pickupNeighborhood", "x".repeat(101));
        Map<String, Object> d = schema.sanitize(raw, ctx);
        assertThat(d).doesNotContainKeys("weightKg", "dateToleranceDays", "totalBudget", "acceptedPaymentMethods",
                "pickupNeighborhood");
    }

    @Test
    void categoriesDedoublonneesEtBornees() {
        Map<String, Object> raw = exemple();
        raw.put("categories", List.of("a".repeat(40), "b".repeat(40), "c".repeat(40), "d".repeat(40), "e".repeat(40)));
        assertThat(schema.sanitize(raw, ctx)).containsKey("categories");
        raw.put("categories", List.of("Vêtements", "Vêtements", "Médicaments"));
        assertThat(schema.sanitize(raw, ctx).get("categories")).isEqualTo(List.of("Vêtements", "Médicaments"));
    }

    @Test
    void budgetDemandeApresLesRequisPuisAccepteJeNeSaisPas() {
        Map<String, Object> raw = exemple();
        raw.remove("totalBudget");
        raw.remove("categories");
        assertThat(schema.missing(schema.sanitize(raw, ctx))).containsExactly("categories", "budget");
        raw.put("categories", List.of("Vêtements"));
        raw.put("budgetDeclined", true);
        assertThat(schema.missing(schema.sanitize(raw, ctx))).isEmpty();
    }

    @Test
    void villesIdentiquesRetirentLArrivee() {
        Map<String, Object> raw = exemple();
        raw.put("arrivalCity", "LYON");
        assertThat(schema.sanitize(raw, ctx)).doesNotContainKey("arrivalCity");
    }

    @Test
    void sanitizeEstIdempotent() {
        Map<String, Object> once = schema.sanitize(exemple(), ctx);
        assertThat(schema.sanitize(once, ctx)).isEqualTo(once);
    }

    @Test
    void questionsDeRepliAuVouvoiement() {
        assertThat(schema.fallbackQuestion("weightKg", "fr")).contains("0,5", "32", "votre");
        assertThat(schema.fallbackQuestion("budget", "en")).contains("don't know");
        assertThat(schema.fallbackQuestion("inconnu", "fr")).isEqualTo("Est-ce bien cela ?");
        for (String field : schema.missing(Map.of())) {
            assertThat(schema.fallbackQuestion(field, "fr")).isNotEqualTo("Est-ce bien cela ?")
                    .doesNotContain("-tu", "ton ", " tu ");
        }
    }

    @Test
    void promptEtCles() {
        assertThat(schema.systemPrompt(ctx)).contains("dateToleranceDays", "STRIPE", "budgetDeclined", "2026-10-17");
        assertThat(schema.keys()).hasSize(16).contains("parcelSize", "budgetDeclined");
        assertThat(schema.kind()).isEqualTo(FormKind.PACKAGE_REQUEST);
    }
}
