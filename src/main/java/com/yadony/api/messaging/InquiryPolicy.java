package com.yadony.api.messaging;

/** Règles des conversations {@link ConversationKind#INQUIRY} (messagerie avant réservation). */
public final class InquiryPolicy {

    /** Nombre maximal de messages d'une conversation INQUIRY tant que le quota n'est pas levé. */
    public static final int MESSAGE_LIMIT = 10;

    /** Jours d'inactivité après lesquels une conversation INQUIRY est fermée. */
    public static final int INACTIVITY_DAYS = 30;

    private InquiryPolicy() {}
}
