package com.yadony.api.assistant.llm;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * Aiguillage vers le bon client selon l'adresse du fournisseur : API Messages native de Claude quand l'hôte est
 * exactement {@code api.anthropic.com} (https, port 443), format OpenAI chat/completions sinon. Pas de colonne
 * dédiée en base : l'adresse saisie dans l'admin suffit à reconnaître Anthropic.
 */
@Component
public class LlmClients {

    static final String ANTHROPIC_HOST = "api.anthropic.com";
    /** Délai maximal d'un appel, quel que soit le client. */
    static final Duration MAX_CALL_TIMEOUT = OpenAiCompatibleClient.TOTAL_TIMEOUT;

    private final OpenAiCompatibleClient openAi;
    private final AnthropicMessagesClient anthropic;

    public LlmClients(OpenAiCompatibleClient openAi, AnthropicMessagesClient anthropic) {
        this.openAi = openAi;
        this.anthropic = anthropic;
    }

    /** Bouton « Tester » de l'admin : délai maximal d'un appel. */
    public String complete(LlmProviderConfig provider, String system, String user) {
        return complete(provider, system, user, MAX_CALL_TIMEOUT);
    }

    String complete(LlmProviderConfig provider, String system, String user, Duration timeout) {
        if (isAnthropic(provider.baseUrl())) {
            return anthropic.complete(provider, system, user, timeout);
        }
        return openAi.complete(provider, system, user, timeout);
    }

    /**
     * Vrai si et seulement si l'adresse est en https et son hôte est exactement {@value #ANTHROPIC_HOST}
     * (port absent ou 443, sans identifiants). Le chemin est ignoré : {@code https://api.anthropic.com} et
     * {@code https://api.anthropic.com/v1} désignent le même fournisseur, le SDK fixe lui-même l'adresse.
     */
    public static boolean isAnthropic(String baseUrl) {
        if (baseUrl == null) return false;
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && ANTHROPIC_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null;
    }
}
