package com.yadony.api.payments.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Le deposit mobile money est encaissé : le paiement passe en séquestre (ESCROW) et le
 * bid est finalisé (ACCEPTED), avec numéro de suivi et tokens QR/tracking générés.
 *
 * <p>Publié au plus une fois par
 * {@code MobileMoneyBidPaymentService#confirmEscrow}, protégé par l'UPDATE gardé
 * {@code PaymentRepository#markEscrowIfPending} (1 = première confirmation, 0 = rejeu
 * silencieusement ignoré) — jamais republié sur une confirmation en double, que celle-ci
 * vienne du callback pawaPay ou du poller de réconciliation.
 */
public record MobileMoneyPaymentConfirmedEvent(UUID bidId, UUID senderId, UUID travelerId, BigDecimal amount, String currency) {}
