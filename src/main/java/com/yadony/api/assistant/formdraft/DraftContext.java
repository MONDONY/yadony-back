package com.yadony.api.assistant.formdraft;

import java.time.LocalDate;
import java.time.ZoneId;

/** Contexte d'un tour : date du jour (celle du client si elle est plausible), fuseau, langue, devise par défaut. */
public record DraftContext(LocalDate today, ZoneId zone, String locale, String defaultCurrency) {
}
