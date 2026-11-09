package com.yadony.api.admin.broadcast.preset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.broadcast.rule.CompiledRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AudiencePresets : audiences prêtes et modèles de message")
class AudiencePresetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bornes de {@code BroadcastRequest}. */
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 500;

    @Test
    @DisplayName("le catalogue n'est pas vide et ses clés sont uniques")
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        AudiencePresets.all().forEach(p -> assertThat(keys.add(p.key())).as(p.key()).isTrue());
        assertThat(keys).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("chaque règle est valide et se compile en SQL")
    void everyRuleCompiles() {
        for (AudiencePreset preset : AudiencePresets.all()) {
            AudienceRule.Group rule = AudienceRule.parse(preset.rule());
            CompiledRule compiled = AudienceSqlCompiler.compile(rule);
            assertThat(compiled.where()).as(preset.key()).isNotBlank();
        }
    }

    @Test
    @DisplayName("la racine est un ET : l'écran peut y ajouter la condition de langue sans dépasser la profondeur")
    void languageConditionCanBeAppended() {
        for (AudiencePreset preset : AudiencePresets.all()) {
            ObjectNode root = (ObjectNode) preset.rule().deepCopy();
            assertThat(root.get("op").asText()).as(preset.key()).isEqualTo("AND");
            ObjectNode language = MAPPER.createObjectNode();
            language.put("field", "language");
            language.put("operator", "IN");
            ArrayNode value = language.putArray("value");
            value.add("fr");
            ((ArrayNode) root.get("children")).add(language);

            CompiledRule compiled = AudienceSqlCompiler.compile(AudienceRule.parse(root));
            assertThat(compiled.where()).as(preset.key()).contains("preferred_language");
        }
    }

    @Test
    @DisplayName("chaque audience a un modèle français et anglais dans les bornes de l'envoi")
    void messagesAreComplete() {
        for (AudiencePreset preset : AudiencePresets.all()) {
            assertThat(preset.label()).as(preset.key()).isNotBlank();
            assertThat(preset.description()).as(preset.key()).isNotBlank();
            assertThat(preset.messages().keySet()).as(preset.key()).containsExactlyInAnyOrder("fr", "en");
            preset.messages().forEach((lang, m) -> {
                String where = preset.key() + "/" + lang;
                assertThat(m.title()).as(where).isNotBlank().hasSizeLessThanOrEqualTo(MAX_TITLE);
                assertThat(m.body()).as(where).isNotBlank().hasSizeLessThanOrEqualTo(MAX_BODY);
                // Règle du projet : pas de tiret cadratin dans un texte affiché à l'utilisateur.
                assertThat(m.title() + m.body()).as(where).doesNotContain("—").doesNotContain("–");
            });
        }
    }

    @Test
    @DisplayName("les audiences demandées par le propriétaire sont toutes présentes")
    void requestedAudiencesArePresent() {
        Set<String> keys = new HashSet<>();
        AudiencePresets.all().forEach(p -> keys.add(p.key()));
        assertThat(keys).contains("activation", "identite_abandonnee", "identite_refusee",
                "verifies_sans_trajet", "trajet_bientot", "versement_a_configurer", "solde_a_utiliser",
                "promo_non_utilise", "reactivation_30j", "inactifs_3j");
    }

    @Test
    @DisplayName("la règle « identité abandonnée » cible le champ dédié")
    void abandonedKycUsesDedicatedField() {
        JsonNode rule = AudiencePresets.all().stream()
                .filter(p -> p.key().equals("identite_abandonnee")).findFirst().orElseThrow().rule();
        assertThat(rule.toString()).contains("kyc_abandoned");
    }
}
