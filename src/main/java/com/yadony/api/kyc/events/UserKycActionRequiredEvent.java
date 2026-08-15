package com.yadony.api.kyc.events;

import java.util.UUID;

/** Stripe Identity demande une nouvelle action ou une nouvelle pièce. */
public record UserKycActionRequiredEvent(UUID userId, String reasonCode) {
}
