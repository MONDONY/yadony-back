package com.yadony.api.gppro.dto;

import com.yadony.api.gppro.GpProDocumentType;

import java.util.UUID;

/** Réponse au téléversement : l'identifiant suffit, jamais d'URL. */
public record GpProDocumentUploadResponse(UUID documentId, GpProDocumentType type) {
}
