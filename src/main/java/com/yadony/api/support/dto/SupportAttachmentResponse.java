package com.yadony.api.support.dto;

import java.util.UUID;

/**
 * {@code url} est presignee et expire en une heure. La cle d'objet n'est jamais
 * exposee : une photo de justificatif ne doit pas etre devinable.
 */
public record SupportAttachmentResponse(
        UUID id,
        String url,
        String contentType,
        long sizeBytes) {
}
