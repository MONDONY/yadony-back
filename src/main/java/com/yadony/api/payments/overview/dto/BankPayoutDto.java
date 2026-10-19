package com.yadony.api.payments.overview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yadony.api.payments.bankpayout.BankPayoutStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Virement bancaire d'un colis payé par carte, côté voyageur (contrat figé avec l'app) :
 * payé sur le compte Stripe du voyageur → disponible → virement en cours → arrivé sur la banque.
 * Les champs facultatifs sont servis à {@code null} (jamais omis) : le contrat les déclare nullables.
 *
 * @param availableOn date à laquelle l'argent devient disponible sur le compte Stripe (UTC)
 * @param arrivalDate date d'arrivée prévue (IN_TRANSIT) ou effective (PAID) du virement
 * @param paidAt      instant où Stripe a confirmé l'arrivée (PAID)
 * @param failureCode code Stripe de l'échec ({@code account_closed}…), FAILED seulement
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BankPayoutDto(BankPayoutStatus status, LocalDate availableOn, LocalDate arrivalDate, Instant paidAt,
                            String failureCode) {
}
