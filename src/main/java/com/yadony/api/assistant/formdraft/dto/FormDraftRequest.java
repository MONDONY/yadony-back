package com.yadony.api.assistant.formdraft.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Un tour de dialogue. Aucune session côté serveur : l'app renvoie toute la conversation et le brouillon courant.
 * @param draft brouillon du tour précédent, revalidé à chaque tour (jamais cru tel quel) ; absent au premier tour
 */
public record FormDraftRequest(
        @NotEmpty @Size(max = 16) List<@Valid @NotNull FormDraftTurn> messages,
        @Size(max = 40) Map<String, Object> draft,
        @Size(max = 10) String locale,
        @Size(max = 10) String today,
        @Size(max = 64) String timezone,
        @Size(max = 3) String defaultCurrency) {
}
