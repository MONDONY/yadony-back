package com.yadony.api.admin.broadcast.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalogue ferme des champs ciblables. Tout ce qui finit dans le SQL vient d'ici : un champ
 * inconnu, un opérateur non permis ou une valeur de mauvais type donnent un 422 avant toute
 * generation. Les valeurs passent toujours par {@link ParamSink#bind}.
 *
 * <p>Le ciblage est comportemental : jamais de filtre sur {@code user_roles} (depuis V193,
 * tout le monde porte SENDER + TRAVELER).
 */
public enum AudienceField {

    KILO_PRO("kilo_pro", Family.STATUS, "Kilo Pro",
            Map.of(AudienceOperator.IS, ValueType.BOOLEAN), List.of(),
            (op, value, p) -> "u.kilo_pro = " + p.bind(value.asBoolean())),

    LANGUAGE("language", Family.STATUS, "Langue",
            Map.of(AudienceOperator.IN, ValueType.ENUM_MULTI),
            List.of(new ValueOption("fr", "Français"), new ValueOption("en", "Anglais")),
            (op, value, p) -> "u.preferred_language IN (" + p.bind(texts(value)) + ")");

    /** Generateur du fragment booleen, appele apres validation. */
    @FunctionalInterface
    interface SqlBuilder {
        String build(AudienceOperator op, JsonNode value, ParamSink params);
    }

    private static final int MAX_DAYS = 3650;
    private static final int MAX_COUNT = 10_000;
    private static final int MAX_TEXT = 100;

    private final String key;
    private final Family family;
    private final String label;
    private final Map<AudienceOperator, ValueType> operators;
    private final List<ValueOption> values;
    private final SqlBuilder sql;

    AudienceField(String key, Family family, String label, Map<AudienceOperator, ValueType> operators,
                  List<ValueOption> values, SqlBuilder sql) {
        this.key = key;
        this.family = family;
        this.label = label;
        this.operators = new EnumMap<>(operators);
        this.values = List.copyOf(values);
        this.sql = sql;
    }

    public String key() { return key; }

    public Family family() { return family; }

    public String label() { return label; }

    public Set<AudienceOperator> operators() { return operators.keySet(); }

    /** Type de valeur attendu pour cet operateur (R1 : porte par le couple champ + operateur). */
    public ValueType valueType(AudienceOperator op) { return operators.get(op); }

    /** Valeurs proposees (renomme {@code options} : {@code values()} est deja la methode statique de l'enum). */
    public List<ValueOption> options() { return values; }

    /** Valide l'operateur et la valeur, puis renvoie le fragment booleen sur l'alias {@code u}. */
    public String toSql(AudienceOperator op, JsonNode value, ParamSink params) {
        ValueType type = operators.get(op);
        if (type == null) {
            throw invalid("L'opérateur " + op + " n'est pas permis pour le champ « " + label + " »");
        }
        validate(op, type, value);
        return sql.build(op, value, params);
    }

    public static AudienceField fromKey(String key) {
        for (AudienceField f : AudienceField.values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        throw invalid("Champ d'audience inconnu");
    }

    /** Visible du package pour tester les types sans champ du catalogue (NONE). */
    void validate(AudienceOperator op, ValueType type, JsonNode value) {
        switch (type) {
            case NONE -> {
                if (value != null && !value.isNull()) {
                    throw invalid("L'opérateur " + op + " du champ « " + label + " » n'accepte aucune valeur");
                }
            }
            case BOOLEAN -> {
                if (value == null || !value.isBoolean()) {
                    throw bad("un booléen");
                }
            }
            case ENUM -> {
                if (value == null || !value.isTextual() || !knownEnum(value.asText())) {
                    throw bad("une valeur connue");
                }
            }
            case ENUM_MULTI -> {
                if (value == null || !value.isArray() || value.isEmpty()) {
                    throw bad("une liste non vide de valeurs connues");
                }
                for (JsonNode v : value) {
                    if (!v.isTextual() || !knownEnum(v.asText())) {
                        throw bad("une liste de valeurs connues");
                    }
                }
            }
            case INT -> {
                int max = op == AudienceOperator.WITHIN_DAYS || op == AudienceOperator.OLDER_THAN_DAYS
                        ? MAX_DAYS : MAX_COUNT;
                if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                        || value.asInt() < 0 || value.asInt() > max) {
                    throw bad("un entier entre 0 et " + max);
                }
            }
            case TEXT -> {
                if (!validText(value)) {
                    throw bad("un texte non vide de " + MAX_TEXT + " caractères au plus");
                }
            }
            case CORRIDOR -> {
                if (value == null || !value.isObject() || !validText(value.get("origin"))
                        || !validText(value.get("destination"))) {
                    throw bad("un corridor avec origin et destination");
                }
            }
        }
    }

    private boolean knownEnum(String v) {
        return values.stream().anyMatch(o -> o.key().equals(v));
    }

    private static boolean validText(JsonNode v) {
        return v != null && v.isTextual() && !v.asText().isBlank() && v.asText().length() <= MAX_TEXT;
    }

    private YadonyBusinessException bad(String expected) {
        return invalid("La valeur du champ « " + label + " » doit être " + expected);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "broadcast-rule-invalid", "Unprocessable Entity", detail);
    }
}
