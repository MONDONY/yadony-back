package com.yadony.api.common;

import java.util.UUID;

/** Compte Yadony désigné comme destinataire d'un colis : son id et son identifiant. */
public record RecipientAccount(UUID userId, String username) {
}
