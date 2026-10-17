package com.yadony.api.activation.events;

import com.yadony.api.activation.UserIntent;

import java.util.UUID;

/** L'utilisateur vient de déclarer (ou de changer) son intention. */
public record UserIntentDeclaredEvent(UUID userId, UserIntent intent) {
}
