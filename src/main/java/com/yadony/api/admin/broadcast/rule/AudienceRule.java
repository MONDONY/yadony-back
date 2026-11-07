package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regle d'audience : arbre de groupes ET/OU dont les feuilles sont des conditions.
 *
 * <p>{@link #parse} ne valide que la FORME (racine groupe, profondeur, nombre de conditions,
 * sous-groupes vides). Le sens de chaque condition (champ, operateur, valeur) est controle par
 * {@link AudienceSqlCompiler}, qui reste le seul chemin vers le SQL.
 */
public sealed interface AudienceRule {

    int MAX_DEPTH = 3;
    int MAX_CONDITIONS = 30;

    enum Op { AND, OR }

    record Group(Op op, List<AudienceRule> children) implements AudienceRule {
        public Group {
            children = List.copyOf(children);
        }
    }

    record Condition(String field, String operator, JsonNode value) implements AudienceRule {}

    /** Parse et valide la forme ; la racine est toujours un groupe (eventuellement vide). */
    static Group parse(JsonNode json) {
        if (json == null || !json.isObject() || !json.has("children")) {
            throw invalid("La racine de la règle doit être un groupe");
        }
        int[] count = {0};
        Group root = parseGroup(json, 1, count);
        return root;
    }

    private static Group parseGroup(JsonNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH) {
            throw invalid("Une règle ne peut pas dépasser " + MAX_DEPTH + " niveaux de groupes");
        }
        Op op;
        try {
            op = Op.valueOf(node.path("op").asText(""));
        } catch (IllegalArgumentException e) {
            throw invalid("L'opérateur de groupe doit être AND ou OR");
        }
        JsonNode kids = node.get("children");
        if (kids == null || !kids.isArray()) {
            throw invalid("Un groupe doit contenir une liste 'children'");
        }
        List<AudienceRule> children = new ArrayList<>();
        for (JsonNode kid : kids) {
            if (!kid.isObject()) {
                throw invalid("Chaque élément d'un groupe doit être un objet");
            }
            if (kid.has("children")) {
                Group sub = parseGroup(kid, depth + 1, count);
                if (sub.children().isEmpty()) {
                    throw invalid("Un sous-groupe ne peut pas être vide");
                }
                children.add(sub);
            } else {
                if (!kid.path("field").isTextual() || !kid.path("operator").isTextual()) {
                    throw invalid("Une condition exige un champ et un opérateur");
                }
                if (++count[0] > MAX_CONDITIONS) {
                    throw invalid("Une règle ne peut pas dépasser " + MAX_CONDITIONS + " conditions");
                }
                JsonNode value = kid.get("value");
                children.add(new Condition(kid.get("field").asText(), kid.get("operator").asText(),
                        value == null || value.isNull() ? null : value));
            }
        }
        return new Group(op, children);
    }

    default int conditionCount() {
        if (this instanceof Group g) {
            return g.children().stream().mapToInt(AudienceRule::conditionCount).sum();
        }
        return 1;
    }

    /** JSON canonique (cles triees, sans espaces) : base du {@link #hash()}. */
    default String canonicalJson() {
        try {
            return CanonicalSupport.MAPPER.writeValueAsString(toCanonicalMap(this));
        } catch (Exception e) {
            throw new IllegalStateException("Serialisation canonique impossible", e);
        }
    }

    /** SHA-256 hexadecimal du JSON canonique : empreinte journalisee a la place de la regle. */
    default String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> toCanonicalMap(AudienceRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rule instanceof Group g) {
            m.put("op", g.op().name());
            m.put("children", g.children().stream().map(AudienceRule::toCanonicalMap).toList());
        } else if (rule instanceof Condition c) {
            m.put("field", c.field());
            m.put("operator", c.operator());
            m.put("value", c.value() == null ? null : withoutNulls(
                    CanonicalSupport.MAPPER.convertValue(c.value(), Object.class)));
        }
        return m;
    }

    /** Une clé à null équivaut à une clé absente : les deux donnent la même empreinte. */
    private static Object withoutNulls(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, x) -> {
                if (x != null) {
                    out.put(k, withoutNulls(x));
                }
            });
            return out;
        }
        if (v instanceof List<?> list) {
            return list.stream().map(AudienceRule::withoutNulls).toList();
        }
        return v;
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "broadcast-rule-invalid", "Unprocessable Entity", detail);
    }

    /** Mapper partage : interface scellee, les constantes ne peuvent pas etre privees. */
    final class CanonicalSupport {
        static final ObjectMapper MAPPER = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();

        private CanonicalSupport() {}
    }
}
