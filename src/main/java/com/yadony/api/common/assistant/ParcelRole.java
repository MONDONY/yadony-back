package com.yadony.api.common.assistant;

/** Place du demandeur de l'assistant sur un colis : elle borne ce qu'il peut en apprendre. */
public enum ParcelRole {
    SENDER,
    TRAVELER,
    /** Destinataire dont le compte est rattaché au colis et qui l'a confirmé. */
    RECIPIENT
}
