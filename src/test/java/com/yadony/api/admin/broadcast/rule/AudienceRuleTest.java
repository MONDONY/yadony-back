package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceRuleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertInvalid(JsonNode node) {
        assertThatThrownBy(() -> AudienceRule.parse(node))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("broadcast-rule-invalid");
                });
    }

    private static String cond() {
        return "{\"field\":\"kilo_pro\",\"operator\":\"IS\",\"value\":true}";
    }

    @Test
    void parse_refuseProfondeur4() {
        assertInvalid(json("{\"op\":\"AND\",\"children\":[{\"op\":\"OR\",\"children\":["
                + "{\"op\":\"AND\",\"children\":[{\"op\":\"OR\",\"children\":[" + cond() + "]}]}]}]}"));
    }

    @Test
    void parse_accepteProfondeur3() {
        AudienceRule.Group g = AudienceRule.parse(json("{\"op\":\"AND\",\"children\":[{\"op\":\"OR\",\"children\":["
                + "{\"op\":\"AND\",\"children\":[" + cond() + "]}]}]}"));
        assertThat(g.conditionCount()).isEqualTo(1);
    }

    @Test
    void parse_refuse31Conditions() {
        StringBuilder sb = new StringBuilder("{\"op\":\"AND\",\"children\":[");
        for (int i = 0; i < 31; i++) {
            sb.append(i > 0 ? "," : "").append(cond());
        }
        sb.append("]}");
        assertInvalid(json(sb.toString()));
    }

    @Test
    void parse_accepte30Conditions() {
        StringBuilder sb = new StringBuilder("{\"op\":\"OR\",\"children\":[");
        for (int i = 0; i < 30; i++) {
            sb.append(i > 0 ? "," : "").append(cond());
        }
        sb.append("]}");
        assertThat(AudienceRule.parse(json(sb.toString())).conditionCount()).isEqualTo(30);
    }

    @Test
    void parse_refuseSousGroupeVide() {
        assertInvalid(json("{\"op\":\"AND\",\"children\":[{\"op\":\"OR\",\"children\":[]}]}"));
    }

    @Test
    void parse_refuseRacineCondition() {
        assertInvalid(json(cond()));
    }

    @Test
    void parse_refuseFormesInvalides() {
        assertInvalid(null);
        assertInvalid(json("[]"));
        assertInvalid(json("{\"op\":\"XOR\",\"children\":[]}"));
        assertInvalid(json("{\"op\":\"AND\"}"));
        assertInvalid(json("{\"op\":\"AND\",\"children\":{}}"));
        assertInvalid(json("{\"op\":\"AND\",\"children\":[42]}"));
        assertInvalid(json("{\"op\":\"AND\",\"children\":[{\"operator\":\"IS\"}]}"));
        assertInvalid(json("{\"op\":\"AND\",\"children\":[{\"field\":\"kilo_pro\"}]}"));
        assertInvalid(json("{\"op\":\"AND\",\"children\":[{\"field\":1,\"operator\":\"IS\"}]}"));
    }

    @Test
    void parse_accepteRacineVide() {
        AudienceRule.Group g = AudienceRule.parse(json("{\"op\":\"AND\",\"children\":[]}"));
        assertThat(g.children()).isEmpty();
        assertThat(g.conditionCount()).isZero();
    }

    @Test
    void parse_conditionSansValeur() {
        AudienceRule.Group g = AudienceRule.parse(json(
                "{\"op\":\"AND\",\"children\":[{\"field\":\"x\",\"operator\":\"EVER\"}]}"));
        AudienceRule.Condition c = (AudienceRule.Condition) g.children().get(0);
        assertThat(c.value()).isNull();
        assertThat(g.canonicalJson()).contains("\"value\":null");
    }

    @Test
    void hash_stableQuelQueSoitOrdreDesCles() {
        AudienceRule.Group a = AudienceRule.parse(json("{\"op\":\"AND\",\"children\":["
                + "{\"field\":\"kilo_pro\",\"operator\":\"IS\",\"value\":true}]}"));
        AudienceRule.Group b = AudienceRule.parse(json("{\"children\":["
                + "{\"value\":true,\"operator\":\"IS\",\"field\":\"kilo_pro\"}],\"op\":\"AND\"}"));
        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson()).doesNotContain(" ");
    }

    @Test
    void hash_differeSiLaRegleDiffere() {
        AudienceRule.Group a = AudienceRule.parse(json("{\"op\":\"AND\",\"children\":[" + cond() + "]}"));
        AudienceRule.Group b = AudienceRule.parse(json("{\"op\":\"OR\",\"children\":[" + cond() + "]}"));
        assertThat(a.hash()).isNotEqualTo(b.hash());
    }
}
