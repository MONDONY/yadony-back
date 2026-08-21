package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de {@code POST /auth/guest/claim}.
 *
 * <p>Le jeton de la session anonyme, et rien d'autre : c'est sa possession qui prouve que
 * l'appelant contrôlait cette session. Ne jamais accepter un simple UID à la place, il se
 * devine.
 */
public record GuestClaimRequest(@NotBlank String guestIdToken) {
}
