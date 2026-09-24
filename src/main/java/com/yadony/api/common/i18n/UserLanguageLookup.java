package com.yadony.api.common.i18n;

import java.util.Optional;
import java.util.UUID;

/**
 * Port lu par {@link MessagesResolver} pour connaître la langue enregistrée
 * d'un utilisateur, sans que {@code common} dépende de {@code auth}.
 */
@FunctionalInterface
public interface UserLanguageLookup {

    /**
     * Langue enregistrée de l'utilisateur, vide s'il n'existe pas.
     */
    Optional<AppLanguage> languageOf(UUID userId);
}
