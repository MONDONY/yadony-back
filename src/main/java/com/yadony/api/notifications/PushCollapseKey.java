package com.yadony.api.notifications;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Clé de remplacement d'un push sur le téléphone : deux push de même clé ne s'empilent pas,
 * le second remplace le premier ({@code tag} Android, {@code apns-collapse-id} iOS) et iOS
 * les range dans le même fil ({@code thread-id}).
 *
 * <p>Réservée aux flux qui se répètent : une conversation, un ticket support, un fil de
 * négociation, les demandes d'un même trajet, une alerte, un voyageur suivi. Les étapes d'un
 * colis n'en ont pas : remplacer « code de retrait prêt » par l'étape suivante ferait
 * disparaître une information que la personne n'a peut-être pas encore lue.
 */
public final class PushCollapseKey {

    /** Limite d'Apple pour {@code apns-collapse-id}, en octets. */
    static final int MAX_BYTES = 64;

    /**
     * Identifiants de conversation Firestore ({@code conv_<bidId>}, 41 caractères, et ceux du
     * destinataire, plus longs) : même règle que la Cloud Function (dony-functions,
     * CONVERSATION_ID). La limite de 64 octets s'applique ensuite à la clé entière.
     */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private PushCollapseKey() {}

    public static Optional<String> of(String type, Map<String, String> data) {
        if (type == null || data == null) return Optional.empty();
        Optional<String> key = switch (type) {
            case "NEW_MESSAGE" -> safeId(data, "conversationId").map(id -> "chat:" + id);
            case "SUPPORT_MESSAGE" -> NotificationGroupKey.uuid(data, "ticketId").map(id -> "support:" + id);
            default -> NotificationGroupKey.of(type, data);
        };
        return key.filter(k -> k.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }

    private static Optional<String> safeId(Map<String, String> data, String key) {
        String v = data.get(key);
        return v != null && SAFE_ID.matcher(v).matches() ? Optional.of(v) : Optional.empty();
    }
}
