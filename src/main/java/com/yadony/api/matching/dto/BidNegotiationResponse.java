package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fil complet. {@code netEur} et {@code commissionEur} ne sont renseignés que pour
 * le voyageur (son net) et pour l'expéditeur (son détail) : la vue dépend du rôle,
 * calculée dans le service, jamais dans le client.
 *
 * <p>{@code role} dit le rôle explicitement, avec les mêmes valeurs que
 * {@link BidNegotiationSummaryResponse#role()} — TRAVELER ou SENDER. Le client le
 * déduisait de {@code netEur != null}, ce qui confond deux causes : « je suis
 * l'expéditeur » et « aucun montant n'a encore été proposé ». Les deux montants sont
 * tus quand le brut est absent ou nul, et un voyageur se voyait alors servir la vue
 * expéditeur.
 */
public record BidNegotiationResponse(
        UUID bidId,
        UUID announcementId,
        String status,
        String role,
        int round,
        int maxRounds,
        boolean myTurn,
        boolean canCounter,
        String currency,
        BigDecimal proposedGrossEur,
        BigDecimal netEur,
        BigDecimal commissionEur,
        BigDecimal suggestedGrossEur,
        BigDecimal weightKg,
        String description,
        String contentCategory,
        List<BidGridItemLine> gridItems,
        List<BidCustomItemResponse> customItems,
        List<String> photoUrls,
        String counterpartyName,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalDateTime expiresAt,
        List<BidNegotiationMessageResponse> messages,
        // Mode figé à la proposition (STRIPE, CASH, MOBILE_MONEY) : l'app en déduit le
        // geste de paiement après l'accord (carte → checkout, mobile money → dépôt pawaPay).
        String paymentMethod,
        // FLUTTER-G8 : identifiant de la contrepartie (voyageur pour l'expéditeur, et
        // inversement), pour ouvrir son profil public depuis la carte du fil. Champ
        // ajouté en fin de contrat : un client qui l'ignore ne change pas.
        UUID counterpartyId,
        // FLUTTER-H7 : accord en espèces en attente du voyageur (PENDING + CASH), heure
        // UTC à laquelle BidTimeoutScheduler l'annulera si la commission n'est pas réglée.
        // Nulle dans tout autre état. Champ de fin de contrat : un client qui l'ignore ne
        // change pas.
        LocalDateTime commissionDueBy,
        // FLUTTER-NS : taux de commission figé du fil, servi au seul voyageur (null pour
        // l'expéditeur) pour l'aperçu « je touche N → l'expéditeur paie G » de sa
        // contre-offre en net. N'apprend rien de neuf : netEur et proposedGrossEur, déjà
        // servis au voyageur, le donnent par division.
        BigDecimal commissionRate,
        // Badge GP Pro de la contrepartie (voyageur pour l'expéditeur, et inversement).
        // Champ de fin de contrat : un client qui l'ignore ne change pas.
        boolean counterpartyGpPro,
        // Grille de commission figée sur le fil (spec §4.4), null sur un fil d'avant les grilles
        // (taux unique commissionRate). Servie aux deux rôles : elle n'apprend rien du net.
        UUID commissionGridId,
        // Réductions personnelles figées à l'ouverture d'un fil à grille, servies au seul voyageur
        // comme commissionRate : avec la grille, elles permettent à l'app l'aperçu exact
        // « je touche N → l'expéditeur paie G ». Taux spécial et facteur du bon, null sans.
        BigDecimal commissionOverrideRate,
        BigDecimal commissionVoucherFactor
) {
    /** Ancienne arité, sans {@code commissionRate}, {@code counterpartyGpPro} ni grille. */
    public BidNegotiationResponse(UUID bidId, UUID announcementId, String status, String role, int round,
                                  int maxRounds, boolean myTurn, boolean canCounter, String currency,
                                  BigDecimal proposedGrossEur, BigDecimal netEur, BigDecimal commissionEur,
                                  BigDecimal suggestedGrossEur, BigDecimal weightKg, String description,
                                  String contentCategory, List<BidGridItemLine> gridItems,
                                  List<BidCustomItemResponse> customItems, List<String> photoUrls,
                                  String counterpartyName, String departureCity, String arrivalCity,
                                  LocalDate departureDate, LocalDateTime expiresAt,
                                  List<BidNegotiationMessageResponse> messages, String paymentMethod,
                                  UUID counterpartyId, LocalDateTime commissionDueBy) {
        this(bidId, announcementId, status, role, round, maxRounds, myTurn, canCounter, currency,
                proposedGrossEur, netEur, commissionEur, suggestedGrossEur, weightKg, description,
                contentCategory, gridItems, customItems, photoUrls, counterpartyName, departureCity,
                arrivalCity, departureDate, expiresAt, messages, paymentMethod, counterpartyId,
                commissionDueBy, null, false, null, null, null);
    }

    /** Arité d'avant {@code counterpartyGpPro}. */
    public BidNegotiationResponse(UUID bidId, UUID announcementId, String status, String role, int round,
                                  int maxRounds, boolean myTurn, boolean canCounter, String currency,
                                  BigDecimal proposedGrossEur, BigDecimal netEur, BigDecimal commissionEur,
                                  BigDecimal suggestedGrossEur, BigDecimal weightKg, String description,
                                  String contentCategory, List<BidGridItemLine> gridItems,
                                  List<BidCustomItemResponse> customItems, List<String> photoUrls,
                                  String counterpartyName, String departureCity, String arrivalCity,
                                  LocalDate departureDate, LocalDateTime expiresAt,
                                  List<BidNegotiationMessageResponse> messages, String paymentMethod,
                                  UUID counterpartyId, LocalDateTime commissionDueBy,
                                  BigDecimal commissionRate) {
        this(bidId, announcementId, status, role, round, maxRounds, myTurn, canCounter, currency,
                proposedGrossEur, netEur, commissionEur, suggestedGrossEur, weightKg, description,
                contentCategory, gridItems, customItems, photoUrls, counterpartyName, departureCity,
                arrivalCity, departureDate, expiresAt, messages, paymentMethod, counterpartyId,
                commissionDueBy, commissionRate, false, null, null, null);
    }

    /** Arité d'avant la grille de commission ({@code commissionGridId} et réductions figées). */
    public BidNegotiationResponse(UUID bidId, UUID announcementId, String status, String role, int round,
                                  int maxRounds, boolean myTurn, boolean canCounter, String currency,
                                  BigDecimal proposedGrossEur, BigDecimal netEur, BigDecimal commissionEur,
                                  BigDecimal suggestedGrossEur, BigDecimal weightKg, String description,
                                  String contentCategory, List<BidGridItemLine> gridItems,
                                  List<BidCustomItemResponse> customItems, List<String> photoUrls,
                                  String counterpartyName, String departureCity, String arrivalCity,
                                  LocalDate departureDate, LocalDateTime expiresAt,
                                  List<BidNegotiationMessageResponse> messages, String paymentMethod,
                                  UUID counterpartyId, LocalDateTime commissionDueBy,
                                  BigDecimal commissionRate, boolean counterpartyGpPro) {
        this(bidId, announcementId, status, role, round, maxRounds, myTurn, canCounter, currency,
                proposedGrossEur, netEur, commissionEur, suggestedGrossEur, weightKg, description,
                contentCategory, gridItems, customItems, photoUrls, counterpartyName, departureCity,
                arrivalCity, departureDate, expiresAt, messages, paymentMethod, counterpartyId,
                commissionDueBy, commissionRate, counterpartyGpPro, null, null, null);
    }
}
