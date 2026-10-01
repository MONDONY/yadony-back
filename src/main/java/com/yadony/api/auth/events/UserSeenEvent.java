package com.yadony.api.auth.events;

import java.util.UUID;

/** L'utilisateur vient d'ouvrir l'app (lecture de son profil au démarrage). */
public record UserSeenEvent(UUID userId) {}
