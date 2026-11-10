package com.yadony.api.videomodal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Action d'un bouton de modal INFO, stockée en JSON ({@code primary_action},
 * {@code secondary_action}) et renvoyée telle quelle à l'app.
 *
 * @param screen renseigné seulement pour {@code SCREEN}
 * @param url    renseigné seulement pour {@code URL} (https uniquement)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VideoModalAction(VideoModalActionKind kind, InfoScreen screen, String url) {
}
