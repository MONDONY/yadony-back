package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Le lien d'un colis à son destinataire vient de passer CONFIRMED (« c'est pour moi » ou
 * rattachement d'emblée confirmé d'un destinataire de confiance). Publié dans la transaction
 * de la confirmation ; l'écouteur de réception agit après validation : si le colis est déjà
 * remis au voyageur et qu'un code existe, le destinataire est prévenu que son code est prêt.
 * Ne porte jamais le code.
 */
public record RecipientLinkConfirmedEvent(UUID bidId) {}
