package com.yadony.api.admin.broadcast.rule;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Clause WHERE (alias {@code u}) et ses parametres nommes. */
public record CompiledRule(String where, MapSqlParameterSource params) {}
