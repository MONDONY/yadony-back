package com.yadony.api.notifications;

import java.util.Optional;
import java.util.UUID;

/**
 * Bouton propre à un type de notification dans son e-mail, à la place de « Ouvrir Yadony ».
 *
 * <p>Le package qui possède la démarche fournit le lien (implémentation {@code @Component}) :
 * {@link NotificationEmailService} ne connaît que cette interface, sans dépendre de lui.
 * Exemple : la relance Stripe Connect mène directement au formulaire Stripe.
 */
public interface NotificationEmailLink {

    /** Type de notification ({@code data.type}) servi par ce lien. */
    String type();

    /** Bouton pour ce destinataire, ou vide pour garder le bouton par défaut. */
    Optional<Cta> ctaFor(UUID userId);

    /**
     * Bouton de l'e-mail : son adresse, et les clés i18n de son libellé et de la ligne qui
     * remplace celle de la famille (qui dirait sinon « se termine dans l'application »).
     */
    record Cta(String url, String labelKey, String detailKey) {}
}
