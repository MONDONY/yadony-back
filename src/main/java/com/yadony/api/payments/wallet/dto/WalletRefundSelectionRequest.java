package com.yadony.api.payments.wallet.dto;

import java.util.List;
import java.util.UUID;

/**
 * Sélection de recharges à rembourser envoyée par l'app. {@code transactionIds} vide ou
 * absent (aucun body) est traité comme "rien sélectionné" et rejeté par
 * {@code WalletSelfRefundService} avec le code {@code wallet-not-refund-eligible}, pas par
 * une validation Bean Validation, pour rester une erreur métier RFC 7807 et non un 400
 * générique.
 */
public record WalletRefundSelectionRequest(List<UUID> transactionIds) {
}
