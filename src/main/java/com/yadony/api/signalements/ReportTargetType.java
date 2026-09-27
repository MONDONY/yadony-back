package com.yadony.api.signalements;

public enum ReportTargetType {
    USER, ANNOUNCEMENT, BID, MESSAGE, RATING, APP,
    /** Demande d'envoi ({@code package_requests}), signalée depuis sa fiche publique. */
    PACKAGE_REQUEST
}
