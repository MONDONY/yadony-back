package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceSqlCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "u.deleted_at IS NULL AND u.status = 'ACTIVE'";

    private static CompiledRule compile(String json) {
        try {
            return AudienceSqlCompiler.compile(AudienceRule.parse(MAPPER.readTree(json)));
        } catch (YadonyBusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String one(String field, String op, String value) {
        return "{\"op\":\"AND\",\"children\":[{\"field\":\"" + field + "\",\"operator\":\"" + op
                + "\",\"value\":" + value + "}]}";
    }

    private static void assert422(String json) {
        assertThatThrownBy(() -> compile(json))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid");
                });
    }

    @Test
    void racineVide_donneSeulementLePrefixeActif() {
        CompiledRule r = compile("{\"op\":\"AND\",\"children\":[]}");
        assertThat(r.where()).isEqualTo(PREFIX);
        assertThat(r.params().getParameterNames()).isEmpty();
    }

    @Test
    void ouImbrique_parenthese() {
        CompiledRule r = compile("{\"op\":\"AND\",\"children\":["
                + "{\"field\":\"kilo_pro\",\"operator\":\"IS\",\"value\":true},"
                + "{\"op\":\"OR\",\"children\":["
                + "{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]},"
                + "{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"en\"]}]}]}");
        assertThat(r.where()).startsWith(PREFIX).contains(
                "AND ((u.kilo_pro = :p0) AND ((u.preferred_language IN (:p1)) OR (u.preferred_language IN (:p2))))");
        assertThat(r.params().getValue("p0")).isEqualTo(true);
        assertThat(r.params().getValue("p1")).isEqualTo(java.util.List.of("fr"));
        assertThat(r.params().getValue("p2")).isEqualTo(java.util.List.of("en"));
    }

    @Test
    void champInconnu_422() {
        assert422(one("inconnu", "IS", "true"));
    }

    @Test
    void operateurInconnu_422() {
        assert422(one("kilo_pro", "BOUM", "true"));
    }

    @Test
    void operateurNonPermis_422() {
        assert422(one("kilo_pro", "GTE", "1"));
    }

    @Test
    void valeurDeMauvaisType_422() {
        assert422(one("kilo_pro", "IS", "\"oui\""));
        assert422(one("kilo_pro", "IS", "null"));
    }

    @Test
    void enumInconnu_422() {
        assert422(one("language", "IN", "[\"de\"]"));
        assert422(one("language", "IN", "[]"));
        assert422(one("language", "IN", "\"fr\""));
        assert422(one("language", "IN", "[1]"));
    }

    @Test
    void valeurJamaisDansLeSql() {
        String evil = "x' OR 1=1 --";
        assert422(one("language", "IN", "[\"" + evil + "\"]"));
        // Meme en echec, rien ne doit fuiter ; et un cas valide ne contient aucune valeur litterale.
        CompiledRule r = compile(one("language", "IN", "[\"fr\",\"en\"]"));
        assertThat(r.where()).doesNotContain("'fr'").doesNotContain("OR 1=1");
    }

    @Test
    void valeurMalveillanteAbsenteDuMessageEtDuSql() {
        String evil = "x' OR 1=1 --";
        assertThatThrownBy(() -> compile(one("language", "IN", "[\"" + evil + "\"]")))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("1=1"));
    }

    @Test
    void libellesAvecAccents() {
        assertThat(AudienceField.LANGUAGE.options())
                .extracting(ValueOption::label).contains("Français");
        assertThatThrownBy(() -> compile(one("kilo_pro", "GTE", "1")))
                .hasMessageContaining("opérateur").hasMessageContaining("« Kilo Pro »");
    }

    @Test
    void arbreSansParse_sousGroupeVide_422() {
        AudienceRule.Group root = new AudienceRule.Group(AudienceRule.Op.AND,
                java.util.List.of(new AudienceRule.Group(AudienceRule.Op.OR, java.util.List.of())));
        assertThatThrownBy(() -> AudienceSqlCompiler.compile(root))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid"));
    }

    @Test
    void arbreSansParse_opNull_422() {
        AudienceRule.Group root = new AudienceRule.Group(null, java.util.List.of(
                new AudienceRule.Condition("kilo_pro", "IS", MAPPER.getNodeFactory().booleanNode(true))));
        assertThatThrownBy(() -> AudienceSqlCompiler.compile(root))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void arbreSansParse_champOuOperateurNull_422() {
        for (AudienceRule.Condition c : new AudienceRule.Condition[] {
                new AudienceRule.Condition(null, "IS", null), new AudienceRule.Condition("kilo_pro", null, null)}) {
            AudienceRule.Group root = new AudienceRule.Group(AudienceRule.Op.AND, java.util.List.of(c));
            assertThatThrownBy(() -> AudienceSqlCompiler.compile(root))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid"));
        }
    }

    @Test
    void typeNone_refuseToutEtAccepteAbsent() {
        var f = AudienceField.KILO_PRO;
        var yes = MAPPER.getNodeFactory().booleanNode(true);
        assertThatThrownBy(() -> f.validate(AudienceOperator.IS, ValueType.NONE, yes))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        f.validate(AudienceOperator.IS, ValueType.NONE, null);
        f.validate(AudienceOperator.IS, ValueType.NONE, MAPPER.getNodeFactory().nullNode());
    }
}
