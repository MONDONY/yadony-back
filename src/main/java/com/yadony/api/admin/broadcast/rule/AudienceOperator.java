package com.yadony.api.admin.broadcast.rule;

/**
 * Operateurs d'une condition. Le type de valeur attendu n'est pas porte ici mais par le
 * couple (champ, operateur), voir {@link AudienceField}.
 */
public enum AudienceOperator { IS, IN, EVER, NEVER, WITHIN_DAYS, OLDER_THAN_DAYS, GTE, LTE, MATCHES }
