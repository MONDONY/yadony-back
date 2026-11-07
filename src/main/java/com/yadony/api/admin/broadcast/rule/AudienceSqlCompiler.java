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
            where += " AND " + render(root, sink);
        }
        return new CompiledRule(where, params);
    }

    private static String render(AudienceRule rule, ParamSink sink) {
        if (rule instanceof AudienceRule.Group g) {
            String joiner = g.op() == AudienceRule.Op.AND ? " AND " : " OR ";
            return g.children().stream().map(c -> render(c, sink)).collect(Collectors.joining(joiner, "(", ")"));
        }
        AudienceRule.Condition c = (AudienceRule.Condition) rule;
        AudienceField field = AudienceField.fromKey(c.field());
        AudienceOperator op = operator(c.operator());
        return "(" + field.toSql(op, c.value(), sink) + ")";
    }

    private static AudienceOperator operator(String raw) {
        try {
            return AudienceOperator.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "broadcast-rule-invalid", "Unprocessable Entity", "Operateur inconnu");
        }
    }
}
