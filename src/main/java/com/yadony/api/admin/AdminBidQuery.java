package com.yadony.api.admin;

import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Paramètres de la liste des colis de l'admin, déjà normalisés pour la requête native
 * {@code BidRepository.searchAdmin} : jetons repliés (quatre au plus), identifiant exact reconnu,
 * contenu replié, énumérations sous leur nom SQL.
 *
 * @param tokens jetons repliés de la saisie (au plus {@link #MAX_TOKENS}), tous exigés
 * @param idTerm la saisie si c'est un UUID canonique (colis, trajet, paiement ou litige), sinon null
 * @param withRecipientPhone le téléphone du destinataire est cherchable (droit {@code USER_VIEW})
 * @param now horloge de la requête (UTC), d'où découlent les fenêtres d'alerte et de la vue « récents »
 */
public record AdminBidQuery(
        AdminBidView view,
        String status,
        String announcementId,
        List<String> tokens,
        UUID idTerm,
        AdminBidScope scope,
        boolean withRecipientPhone,
        String paymentMethod,
        String content,
        Integer alertRank,
        LocalDateTime from,
        LocalDateTime to,
        LocalDateTime now) {

    public static final int MAX_TOKENS = 4;

    /**
     * Jeton qui ne se trouve dans aucun texte : la recherche SQL compare à des champs passés en
     * minuscules, un jeton en majuscules n'y est jamais. Il remplace une saisie faite seulement de
     * {@code %} ou {@code _}, qui sinon perdrait tous ses jetons et ramènerait tous les colis.
     */
    public static final String NO_MATCH_TOKEN = "NOMATCH";

    public AdminBidQuery {
        tokens = List.copyOf(tokens);
    }

    /** Vue et périmètre absents valent {@code ALL} ; un contenu vide ne filtre pas. */
    public static AdminBidQuery of(AdminBidView view, BidStatus status, UUID announcementId, String query,
                                   AdminBidScope scope, boolean withRecipientPhone, PaymentMethod paymentMethod,
                                   String content, AdminBidAlert alert, LocalDateTime from, LocalDateTime to,
                                   LocalDateTime now) {
        String foldedContent = AdminSearchTerms.fold(content);
        if (foldedContent != null) {
            // Texte, pas motif : mêmes règles que les jetons.
            foldedContent = foldedContent.replace("%", "").replace("_", "").strip();
        }
        return new AdminBidQuery(
                view != null ? view : AdminBidView.ALL,
                status != null ? status.name() : null,
                announcementId != null ? announcementId.toString() : null,
                tokensOf(query),
                AdminSearchTerms.uuid(query),
                scope != null ? scope : AdminBidScope.ALL,
                withRecipientPhone,
                paymentMethod != null ? paymentMethod.name() : null,
                foldedContent == null || foldedContent.isEmpty() ? null : foldedContent,
                alert != null ? alert.rank() : null,
                from,
                to,
                now);
    }

    /** Jetons de la saisie ; une saisie non vide sans aucun jeton (que des % ou _) ne trouve rien. */
    private static List<String> tokensOf(String query) {
        List<String> tokens = AdminSearchTerms.tokens(query, MAX_TOKENS);
        if (tokens.isEmpty() && query != null && !query.isBlank()) {
            return List.of(NO_MATCH_TOKEN);
        }
        return tokens;
    }

    /** Le jeton {@code index} (0 à 3), ou null s'il n'y en a pas autant. */
    public String token(int index) {
        return index < tokens.size() ? tokens.get(index) : null;
    }

    /** Date avant laquelle l'arrivée prévue rend un colis en route « en retard ». */
    public LocalDate lateBefore() {
        return lateBefore(now);
    }

    public static LocalDate lateBefore(LocalDateTime now) {
        return now.minusDays(AdminBidSql.LATE_AFTER_DAYS).toLocalDate();
    }
}
