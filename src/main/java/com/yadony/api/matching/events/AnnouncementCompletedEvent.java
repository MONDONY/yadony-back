package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Un trajet vient de passer {@code COMPLETED} : plus aucun colis en vol. Publié par
 * {@code AnnouncementCompletionListener} une fois l'annonce enregistrée.
 *
 * <p>Sert au package {@code tracking} à effacer les positions floues du voyageur, qui n'ont plus
 * lieu d'être une fois le dernier colis livré.
 */
public record AnnouncementCompletedEvent(UUID announcementId) {}
