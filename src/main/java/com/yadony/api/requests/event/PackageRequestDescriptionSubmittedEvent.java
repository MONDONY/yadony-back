package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * La description d'une demande d'expéditeur devient visible des voyageurs : création ou
 * modification d'une demande publiée, ou publication d'un brouillon. Un brouillon n'est pas
 * concerné tant qu'il n'est pas publié. Lu après le commit par {@code signalements/} pour la
 * détection automatique d'envoi d'argent.
 */
public record PackageRequestDescriptionSubmittedEvent(UUID packageRequestId, UUID senderId, String description) {
}
