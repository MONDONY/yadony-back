package com.yadony.api.config;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un reglage vu par cle, pour le back-office.
 *
 * <p>A distinguer de {@link PlatformSettingsSnapshot}, qui est la representation TYPEE et
 * mise en cache lue par le {@code ConfigController} public : celle-ci est cle/valeur, comme
 * la table, et porte la date et l'auteur <b>de ce reglage precis</b>. Un instantane global
 * ne garderait que la derniere modification toutes cles confondues — on ne saurait plus qui
 * a change quoi.
 *
 * <p>{@code updatedAt} et {@code updatedBy} valent {@code null} ensemble tant qu'aucun
 * administrateur n'a touche au reglage : {@code BaseEntity} horodate des l'amorcage, exposer
 * cette date ferait passer le seed pour une modification humaine.
 */
public record PlatformSettingView(
        PlatformSettingKey key,
        String value,
        LocalDateTime updatedAt,
        UUID updatedBy) {
}
