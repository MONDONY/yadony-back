package com.yadony.api.videomodal;

/**
 * Rôle de la personne sur le colis qu'elle ouvre, visé par une campagne d'étape ({@code parcelRoles},
 * vide = tous). Le back ne connaît pas le colis au moment de l'éligibilité : c'est l'app qui calcule le
 * rôle et le compare (une personne expéditrice et destinataire a les deux rôles). Noms identiques dans le
 * back, l'admin et l'app.
 */
public enum VideoModalParcelRole {
    SENDER, TRAVELER, RECIPIENT
}
