package com.yadony.api.videomodal;

import com.yadony.api.common.StorageService;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * URL lisible de l'image d'un modal. Le dépôt R2 est privé (aucune URL publique dans le projet,
 * même mécanisme que les avatars, {@link StorageService#avatarUrl}) : l'URL est présignée à
 * chaque lecture, valable 7 jours (maximum de la signature V4), donc lisible par l'app sans
 * compte, visiteurs inclus.
 */
@Component
public class VideoModalImageUrls {

    static final Duration TTL = Duration.ofDays(7);

    private final StorageService storageService;

    public VideoModalImageUrls(StorageService storageService) {
        this.storageService = storageService;
    }

    /** URL présignée de la clé, ou null s'il n'y a pas d'image. */
    public String urlOf(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) return null;
        return storageService.generatePresignedUrl(imageKey, TTL);
    }
}
