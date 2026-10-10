package com.yadony.api.common;

import java.util.UUID;

/**
 * Une procédure ouverte retient-elle les photos d'une demande d'envoi ?
 *
 * <p>Les photos d'une demande close sont purgées après le délai de rétention, sauf tant
 * qu'un signalement est ouvert sur la demande : ce sont des pièces. Le package
 * signalements/ l'implémente ; requests/ l'interroge sans dépendre de ses services.
 */
public interface PackageRequestMediaRetentionHold {

    /** @return vrai si une procédure ouverte impose de garder les photos de cette demande */
    boolean holds(UUID packageRequestId);
}
