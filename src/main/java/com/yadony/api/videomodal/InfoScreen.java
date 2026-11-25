package com.yadony.api.videomodal;

/**
 * Écrans de l'app proposés à l'action SCREEN : liste fermée, jamais un chemin saisi à la main.
 * Doit rester identique à l'enum de l'app et de l'admin. Nouvelles valeurs toujours ajoutées à la fin.
 *
 * <p>Les écrans {@code PARCEL_*} agissent sur le colis ouvert par la personne : ils n'ont de sens que pour
 * une campagne déclenchée par une étape du colis, et l'app masque celui qui ne s'applique pas à son rôle
 * (par exemple {@code PARCEL_QR} pour le destinataire).
 */
public enum InfoScreen {
    SEARCH, ACTIVITIES, TRACKING, MESSAGES, PROFILE, PUBLISH_TRIP, SEND_PARCEL,
    SETTINGS, MY_MONEY, REFERRAL, TRIP_ALERTS, HELP,
    /** Détail du colis. */
    PARCEL_DETAIL,
    /** QR du colis (expéditeur et voyageur). */
    PARCEL_QR,
    /** « Prévenir » en mode code de retrait (expéditeur, une fois le code créé). */
    PARCEL_SEND_CODE,
    /** Suivi du colis. */
    PARCEL_TRACKING;

    /** Vrai pour les écrans liés au colis ({@code PARCEL_*}). */
    public boolean isParcelScreen() {
        return switch (this) {
            case PARCEL_DETAIL, PARCEL_QR, PARCEL_SEND_CODE, PARCEL_TRACKING -> true;
            default -> false;
        };
    }
}
