package com.yadony.api.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Raison donnee par l'utilisateur en quittant sa verification d'identite (liste fermee). */
public record KycAbandonReasonRequest(@NotBlank @Size(max = 40) String reason) {}
