package com.yadony.api.gppro.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Justificatifs à rattacher à la demande ouverte. Le plafond de 4 par demande est vérifié par le service. */
public record GpProAttachDocumentsRequest(@NotEmpty @Size(max = 20) List<@NotNull UUID> documentIds) {
}
