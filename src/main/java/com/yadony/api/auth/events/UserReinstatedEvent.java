package com.yadony.api.auth.events;

import java.util.UUID;

/**
 * Bannissement leve par un administrateur ({@code UserService#unsuspendUser} sur un compte
 * BANNED). La levee d'une simple suspension ne le publie pas : une suspension ne gele rien.
 */
public record UserReinstatedEvent(UUID userId, UUID adminId) {
}
