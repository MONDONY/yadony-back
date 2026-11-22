package com.yadony.api.payments.fee;

import java.util.UUID;

/**
 * Retenue d'annulation réglée : l'expéditeur vient d'être remboursé du montant payé moins le
 * dédommagement, la ligne de la retenue est {@code RETAINED} avec sa ventilation. Publié dans la
 * transaction du remboursement ; le versement du net au voyageur s'y abonne après commit.
 *
 * @param bidId colis dont la retenue a été réglée
 */
public record CancellationFeeRetainedEvent(UUID bidId) {}
