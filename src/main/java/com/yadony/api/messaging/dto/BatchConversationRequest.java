package com.yadony.api.messaging.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Corps des actions groupées sur les conversations ({@code POST /conversations/batch/*}).
 * Au plus {@link #MAX_IDS} identifiants : le client découpe au-delà.
 */
public record BatchConversationRequest(
        @NotEmpty @Size(max = BatchConversationRequest.MAX_IDS) List<@NotNull UUID> ids
) {
    public static final int MAX_IDS = 100;
}
