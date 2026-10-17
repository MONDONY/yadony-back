package com.yadony.api.assistant.parcel;

import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;

import java.util.Map;

/**
 * Textes écrits par le back, sans modèle : ce qui touche aux colis d'un utilisateur ne doit
 * jamais dépendre d'une reformulation (« numéro incorrect », « aucun colis », étape du colis).
 */
final class ParcelTexts {

    private ParcelTexts() {
    }

    static final Map<String, String> INCORRECT = Map.of(
            "fr", "Ce numéro de suivi est incorrect, ou ce colis n'est pas lié à votre compte. Vérifiez-le : "
                    + "il commence par DON- suivi de 8 caractères.",
            "en", "This tracking number is incorrect, or this parcel is not linked to your account. Check it: "
                    + "it starts with DON- followed by 8 characters.");

    static final Map<String, String> NO_PARCEL = Map.of(
            "fr", "Vous n'avez aucun colis en cours.",
            "en", "You have no parcel in progress.");

    static final Map<String, String> CHOOSE = Map.of(
            "fr", "Choisissez le colis concerné, ou écrivez son numéro de suivi (DON-…).",
            "en", "Choose the parcel concerned, or type its tracking number (DON-…).");

    static final Map<String, String> RECENT_ONLY = Map.of(
            "fr", "Vous n'avez aucun colis en cours. Voici vos colis récents : choisissez celui qui vous concerne.",
            "en", "You have no parcel in progress. Here are your recent parcels: choose the one concerned.");

    private static final Map<String, Map<String, String>> STEPS = Map.ofEntries(
            Map.entry("AWAITING_PAYMENT", Map.of("fr", "en attente de paiement", "en", "waiting for payment")),
            Map.entry("PENDING", Map.of("fr", "demande envoyée, en attente de la réponse du voyageur",
                    "en", "request sent, waiting for the traveler's answer")),
            Map.entry("PAYMENT_ESCROWED", Map.of("fr", "payé, en attente de la réponse du voyageur",
                    "en", "paid, waiting for the traveler's answer")),
            Map.entry("ACCEPTED", Map.of("fr", "accepté par le voyageur, pas encore remis",
                    "en", "accepted by the traveler, not handed over yet")),
            Map.entry("HANDED_OVER", Map.of("fr", "remis au voyageur", "en", "handed over to the traveler")),
            Map.entry("IN_TRANSIT", Map.of("fr", "en route avec le voyageur", "en", "on its way with the traveler")),
            Map.entry("ARRIVED", Map.of("fr", "arrivé à destination, en attente de retrait",
                    "en", "arrived at destination, waiting for pickup")),
            Map.entry("COMPLETED", Map.of("fr", "livré", "en", "delivered")),
            Map.entry("CANCELLED", Map.of("fr", "annulé", "en", "cancelled")),
            Map.entry("REJECTED", Map.of("fr", "refusé par le voyageur", "en", "declined by the traveler")),
            Map.entry("EXPIRED", Map.of("fr", "expiré", "en", "expired")),
            Map.entry("NO_SHOW", Map.of("fr", "clos après une absence", "en", "closed after a no-show")),
            Map.entry("PARCEL_REFUSED", Map.of("fr", "refusé par le voyageur à la remise",
                    "en", "refused by the traveler at handover")));

    /** « Colis DON-7K3M9QXA (Paris → Dakar) : en route avec le voyageur. Ouvrez sa fiche pour le détail. » */
    static String status(ParcelRef parcel, String locale) {
        boolean en = "en".equals(locale);
        StringBuilder sb = new StringBuilder(en ? "Parcel" : "Colis");
        if (parcel.trackingNumber() != null) sb.append(' ').append(parcel.trackingNumber());
        if (parcel.departureCity() != null && parcel.arrivalCity() != null) {
            sb.append(" (").append(parcel.departureCity()).append(" → ").append(parcel.arrivalCity()).append(')');
        }
        String step = STEPS.getOrDefault(parcel.status(), Map.of()).get(locale);
        if (step != null) sb.append(en ? ": " : " : ").append(step);
        sb.append(en ? ". Open it for the details." : ". Ouvrez sa fiche pour le détail.");
        return sb.toString();
    }

    static String openLabel(ParcelRef parcel, String locale) {
        boolean recipient = parcel.role() == com.yadony.api.common.assistant.ParcelRole.RECIPIENT;
        if ("en".equals(locale)) return recipient ? "Open the reception" : "Open the parcel";
        return recipient ? "Voir la réception" : "Voir le colis";
    }

    static String linkLabel(ParcelLink link, String locale) {
        boolean en = "en".equals(locale);
        return switch (link) {
            case RENEW_PICKUP_CODE -> en ? "Regenerate the code" : "Régénérer le code";
            case DISPUTES -> en ? "My disputes" : "Mes litiges";
            case SUPPORT -> en ? "Contact support" : "Contacter le support";
        };
    }
}
