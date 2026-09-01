package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ligne de revenus agrégée par annonce, alimentée par les paiements d'un statut
 * et d'une période donnés. Construite via une projection JPQL groupée
 * ({@code PaymentRepository#findReleasedRevenueByAnnouncement}) afin que la somme
 * des lignes se réconcilie exactement avec le KPI « Revenus nets » (même clause
 * WHERE, simplement groupée par annonce au lieu d'être sommée par voyageur).
 */
public record AnnouncementRevenueRow(
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        /** Devise de l'annonce (majuscule) — une ligne = une seule devise, les montants la portent. */
        String currency,
        long parcelCount,
        BigDecimal gross,
        BigDecimal commission
) {}
