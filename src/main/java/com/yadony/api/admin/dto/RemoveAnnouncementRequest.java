package com.yadony.api.admin.dto;

import com.yadony.api.matching.AnnouncementRemovalReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Retrait d'une annonce par la modération.
 *
 * <p>Les deux champs ont des destinataires <b>différents</b>, et c'est tout l'objet de cette
 * séparation :
 * <ul>
 *   <li>{@code publicReason} — motif catalogué, annoncé au voyageur ;</li>
 *   <li>{@code internalNote} — note libre, réservée à {@code audit_log}.</li>
 * </ul>
 *
 * <p>Auparavant un seul champ libre servait aux deux usages : un modérateur écrivant
 * « signalé par Awa Ndiaye, ticket #4821 » envoyait littéralement cette phrase à la personne
 * sanctionnée. La note interne peut désormais être complète sans exposer le signalant.
 */
public record RemoveAnnouncementRequest(
        @NotNull(message = "Le motif de retrait est obligatoire")
        AnnouncementRemovalReason publicReason,

        @Size(max = 500, message = "La note interne ne peut pas dépasser 500 caractères")
        String internalNote
) {}
