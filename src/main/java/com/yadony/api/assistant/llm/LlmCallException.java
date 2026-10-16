package com.yadony.api.assistant.llm;

/**
 * Échec d'un appel : statut HTTP, ou NETWORK (réseau, délai), INVALID_OUTPUT (réponse inexploitable)
 * ou BLOCKED_ADDRESS (hôte du fournisseur résolu vers une adresse non publique, appel jamais émis).
 */
public class LlmCallException extends RuntimeException {

    public static final int NETWORK = 0;
    public static final int INVALID_OUTPUT = -1;
    public static final int BLOCKED_ADDRESS = -2;

    private final int status;

    public LlmCallException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
