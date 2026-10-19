package com.yadony.api.auth.events;

import java.util.UUID;

/**
 * Un numéro vérifié par code SMS vient d'être rattaché au compte ({@code POST /auth/sms-otp/attach}).
 * Écouté par les réceptions pour retrouver les colis envoyés à ce numéro avant l'inscription.
 * Ne porte jamais le numéro : l'écouteur relit celui de Firebase, seul vérifié.
 */
public record UserPhoneAttachedEvent(UUID userId) {}
