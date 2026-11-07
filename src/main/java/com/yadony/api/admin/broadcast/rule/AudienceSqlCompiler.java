package com.yadony.api.admin.broadcast.rule;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.stream.Collectors;

/**
 * Compile une regle validee en clause WHERE parametree (alias {@code u}). Chaque groupe et
 * chaque condition sont parentheses, pour que {@code A ET (B OU C)} ne devienne jamais
 * {@code A ET B OU C}.
 */
public final class AudienceSqlCompiler {

    static final String ACTIVE_PREFIX = "u.deleted_at IS NULL AND u.status = 'ACTIVE'";

    private AudienceSqlCompiler() {}

    public static CompiledRule compile(AudienceRule.Group root) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ParamSink sink = value -> {
            String name = "p" + params.getParameterNames().length;
            params.addValue(name, value);
            return ":" + name;
        };
        String where = ACTIVE_PREFIX;
        if (!root.children().isEmpty()) {
            where += " AND " + render(root, sink, true);
        }
        return new CompiledRule(where, params);
    }

    private static String render(AudienceRule rule, ParamSink sink, boolean isRoot) {
        if (rule instanceof AudienceRule.Group g) {
            // Arbre reconstruit hors parse (ex. relu en base) : on ne fait confiance à rien.
            if (g.op() == null) {
                throw invalid("L'opérateur de groupe doit être AND ou OR");
            }
            if (!isRoot && g.children().isEmpty()) {
                throw invalid("Un sous-groupe ne peut pas être vide");
            }
            String joiner = g.op() == AudienceRule.Op.AND ? " AND " : " OR ";
            return g.children().stream().map(c -> render(c, sink, false)).collect(Collectors.joining(joiner, "(", ")"));
        }
        AudienceRule.Condition c = (AudienceRule.Condition) rule;
        if (c.field() == null || c.operator() == null) {
            throw invalid("Une condition exige un champ et un opérateur");
        }
        AudienceField field = AudienceField.fromKey(c.field());
        AudienceOperator op = operator(c.operator());
        return "(" + field.toSql(op, c.value(), sink) + ")";
    }

    private static AudienceOperator operator(String raw) {
        try {
            return AudienceOperator.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("Opérateur inconnu");
        }
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "broadcast-rule-invalid", "Unprocessable Entity", detail);
    }
}
