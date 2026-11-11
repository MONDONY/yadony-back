package com.yadony.api.dac7.events;

import java.util.UUID;

/**
 * Demande (ou rappel) du numéro fiscal à un voyageur « à solliciter », déjà inscrite au registre des diligences et
 * comptée dans {@code users.tax_reminder_count}. Publiée dans la transaction de réservation ; le paquet
 * {@code notifications} l'envoie après validation (notification {@code TAX_INFO_REQUEST}, route
 * {@code /profile/tax-info}). Ne porte aucune donnée fiscale.
 *
 * @param action {@code REQUEST}, {@code REMINDER_1} ou {@code REMINDER_2}
 */
public record Dac7TaxInfoRequestedEvent(UUID userId, String action) {
}
