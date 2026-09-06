package com.yadony.api.support.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code content} n'est plus obligatoire : un message peut n'etre qu'une image.
 * La regle « texte non vide OU au moins une piece jointe » porte sur deux
 * champs a la fois, elle vit donc dans le service et non dans une annotation.
 */
public record CreateSupportMessageRequest(
        @Size(max = 4000) String content,
        List<String> attachmentKeys) {
}
