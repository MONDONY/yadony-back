package com.yadony.api.search;

/**
 * {@code CITY_UNASSIGNED} : une ville a bien été reconnue, mais aucun champ n'était
 * libre pour l'accueillir (départ et arrivée déjà fixés par ailleurs, ou trop de
 * villes libres dans la phrase). Elle ne doit jamais être perdue en silence.
 */
public enum UnresolvedKind { PRICE_VAGUE, DATE_VAGUE, CITY_UNKNOWN, CITY_AMBIGUOUS, CITY_UNASSIGNED }
