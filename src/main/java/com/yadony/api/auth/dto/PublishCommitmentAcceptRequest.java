package com.yadony.api.auth.dto;

import com.yadony.api.auth.PublishCommitmentRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Acceptation des engagements d'un rôle.
 *
 * @param role    rôle dont les engagements ont été lus
 * @param version version du texte affiché ; refusée (409) si ce n'est plus la version en vigueur
 */
public record PublishCommitmentAcceptRequest(
        @NotNull PublishCommitmentRole role,
        @NotBlank @Size(max = 32) String version) {}
