package com.yadony.api.matching.reception;

/** Types de notification ({@code data["type"]}) du suivi par le destinataire. */
public final class ReceptionNotifications {

    private ReceptionNotifications() {}

    public static final String INCOMING = "RECIPIENT_PARCEL_INCOMING";
    public static final String DEPARTED = "RECIPIENT_PARCEL_DEPARTED";
    public static final String ARRIVED = "RECIPIENT_PARCEL_ARRIVED";
    public static final String DELIVERED = "RECIPIENT_PARCEL_DELIVERED";
    public static final String CANCELLED = "RECIPIENT_PARCEL_CANCELLED";
    public static final String RESCHEDULED = "RECIPIENT_PARCEL_RESCHEDULED";
    public static final String CONFIRMED = "RECIPIENT_CONFIRMED";
    public static final String DECLINED = "RECIPIENT_DECLINED";
}
