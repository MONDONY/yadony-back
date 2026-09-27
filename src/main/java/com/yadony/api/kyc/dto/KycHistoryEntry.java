package com.yadony.api.kyc.dto;

import java.time.LocalDateTime;

/**
 * Une entree d'audit d'une verification d'identite, lisible au back-office.
 *
 * @param actorKind  {@code USER} (session ouverte ou abandonnee par l'utilisateur),
 *                   {@code PROVIDER} (webhook du fournisseur), {@code ADMIN} (geste du
 *                   back-office), {@code SYSTEM} (toute autre action)
 * @param actorEmail email de l'administrateur pour {@code ADMIN}, {@code null} sinon
 * @param detail     code et motif d'une decision, ou fournisseur d'une session ouverte
 */
public record KycHistoryEntry(String action,
                              LocalDateTime at,
                              String actorKind,
                              String actorEmail,
                              String detail) {}
