package com.yadony.api.auth.events;

import java.util.UUID;

/**
 * Un administrateur a annulé la demande de suppression de compte d'un utilisateur, encore en
 * délai de grâce. Écouté par {@code notifications} pour prévenir l'utilisateur, après commit.
 */
public record AccountDeletionCancelledByAdminEvent(UUID userId, UUID adminId) {}
