package com.yadony.api.payments.amendment.events;

import java.util.UUID;

/** Rappel au voyageur : une modification attend sa réponse et son échéance approche (une fois par modification). */
public record BidAmendmentReminderDueEvent(UUID amendmentId, UUID bidId, UUID travelerId) {
}
