package com.yadony.api.cancellation.dto;

/**
 * Corps du signalement « destinataire absent » (FLUTTER-E2). {@code contactConfirmed} : le
 * voyageur confirme avoir attendu sur place et tenté de joindre le destinataire (y compris hors
 * de l'app, ce que le serveur ne peut pas vérifier). Obligatoire : un corps absent (ancienne
 * version de l'app) vaut {@code false} et le signalement est refusé en 422 explicite.
 */
public record ReportDeliveryNoShowRequest(Boolean contactConfirmed) {

    public boolean confirmed() {
        return Boolean.TRUE.equals(contactConfirmed);
    }
}
