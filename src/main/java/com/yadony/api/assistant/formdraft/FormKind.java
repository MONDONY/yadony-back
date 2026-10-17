package com.yadony.api.assistant.formdraft;

public enum FormKind {
    TRIP("trip"), PACKAGE_REQUEST("package_request");

    private final String tag;

    FormKind(String tag) {
        this.tag = tag;
    }

    /** Étiquette des métriques et des journaux (jamais de donnée utilisateur). */
    public String tag() {
        return tag;
    }
}
