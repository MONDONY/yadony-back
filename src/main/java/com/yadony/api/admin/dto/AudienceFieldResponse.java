package com.yadony.api.admin.dto;

import com.yadony.api.admin.broadcast.rule.AudienceField;
import com.yadony.api.admin.broadcast.rule.AudienceOperator;
import com.yadony.api.admin.broadcast.rule.Family;
import com.yadony.api.admin.broadcast.rule.ValueOption;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Un champ du constructeur de règles : ses opérateurs (avec type de valeur) et ses valeurs proposées. */
public record AudienceFieldResponse(String key, String family, String label,
                                    List<OperatorDto> operators, List<ValueOption> values) {

    public record OperatorDto(String key, String label, String valueType) {}

    /** Triée par famille puis dans l'ordre de l'enum. */
    public static List<AudienceFieldResponse> catalogue() {
        return Arrays.stream(AudienceField.values())
                .sorted(Comparator.comparing((AudienceField f) -> f.family().ordinal()))
                .map(AudienceFieldResponse::of)
                .toList();
    }

    static AudienceFieldResponse of(AudienceField field) {
        List<OperatorDto> operators = field.operators().stream()
                .sorted()
                .map(op -> new OperatorDto(op.name(), label(op), field.valueType(op).name()))
                .toList();
        Family family = field.family();
        return new AudienceFieldResponse(field.key(), family.name(), field.label(), operators, field.options());
    }

    static String label(AudienceOperator op) {
        return switch (op) {
            case IS -> "est";
            case IN -> "parmi";
            case EVER -> "au moins une fois";
            case NEVER -> "jamais";
            case WITHIN_DAYS -> "dans les N derniers jours";
            case OLDER_THAN_DAYS -> "il y a plus de N jours";
            case GTE -> "au moins";
            case LTE -> "au plus";
            case MATCHES -> "correspond à";
        };
    }
}
