package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminBidTimelineResponse(
    UUID bidId,
    List<Entry> entries
) {
    /**
     * Une étape de la vie du colis. {@code label} est un code ({@code DEPART}, {@code BID_ACCEPTED},
     * {@code PAYMENT_CAPTURED}…) que le back-office traduit ; {@code kind} vaut {@code SCAN},
     * {@code PAYMENT} ou {@code EVENT}.
     *
     * @param source     {@code TRACKING}, {@code AUDIT}, {@code PAYMENT} ou {@code BID} (date portée par une entité)
     * @param actorKind  {@code ADMIN}, {@code USER} ou {@code null} (système, inconnu)
     * @param actorLabel e-mail de l'admin ou nom de l'utilisateur
     */
    public record Entry(
        LocalDateTime at,
        String kind,
        String label,
        String detail,
        String photoUrl,
        BigDecimal gpsLat,
        BigDecimal gpsLon,
        String source,
        String actorKind,
        String actorLabel
    ) {
        public Entry(LocalDateTime at, String kind, String label, String detail, String photoUrl,
                     BigDecimal gpsLat, BigDecimal gpsLon) {
            this(at, kind, label, detail, photoUrl, gpsLat, gpsLon, null, null, null);
        }
    }
}
