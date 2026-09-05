package com.yadony.api.payments.events;

import java.util.UUID;

/** Délai de paiement mobile money dépassé : bid annulé, capacité rendue. */
public record MobileMoneyPaymentExpiredEvent(UUID bidId, UUID senderId, UUID travelerId) {}
