package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Publié par {@code TrackingService} au scan DEPART qui génère le code de retrait :
 * le colis est remis au voyageur. Le destinataire qui suit le colis dans l'app
 * ({@code matching/reception}) est prévenu que son code est disponible.
 */
public record ParcelDepartedEvent(UUID bidId) {}
