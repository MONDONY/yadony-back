package com.yadony.api.admin.broadcast;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.AudienceSqlCompiler;
import com.yadony.api.admin.broadcast.rule.CompiledRule;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolution paginee des destinataires d'un broadcast. Lecture seule. */
@Service
@Transactional(readOnly = true)
public class BroadcastAudienceService {

    /**
     * 200 destinataires par page. Compromis assume : assez petit pour que la page tienne
     * en memoire et que l'envoi progresse par a-coups visibles, assez grand pour ne pas
     * multiplier les allers-retours SQL. L'envoi FCM restant unitaire
     * ({@code FcmService.sendToToken} boucle sur les jetons), ce nombre borne la memoire,
     * pas le nombre d'appels reseau.
     */
    public static final int PAGE_SIZE = 200;

    private final BroadcastAudienceRepository repository;
    private final UserRepository userRepository;
    private final RuleAudienceRepository ruleRepository;

    public BroadcastAudienceService(BroadcastAudienceRepository repository, UserRepository userRepository,
                                    RuleAudienceRepository ruleRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.ruleRepository = ruleRepository;
    }

    /**
     * Compte visé par un ciblage nominatif, pour l'affichage et la garde d'envoi.
     *
     * @param reachable faux pour un compte banni, en attente de suppression ou supprimé :
     *                  la requête d'audience l'écarte, un envoi n'atteindrait personne.
     */
    public record TargetUser(UUID userId, String displayName, boolean reachable) {}

    /**
     * Vide pour un ciblage par segment. Pour {@code USER} : 404 {@code user-not-found} si
     * l'identifiant ne désigne aucune ligne, y compris supprimée (un compte supprimé existe
     * encore, anonymisé, et se signale comme injoignable plutôt qu'inconnu).
     */
    public Optional<TargetUser> targetUser(BroadcastTarget target) {
        if (target.type() != BroadcastTargetType.USER) {
            return Optional.empty();
        }
        UserEntity user = userRepository.findByIdIncludingDeleted(target.userId())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.not-found.user"));
        boolean reachable = user.getDeletedAt() == null
                && user.getStatus() != UserStatus.BANNED
                && user.getStatus() != UserStatus.PENDING_DELETION;
        return Optional.of(new TargetUser(user.getId(), MatchingTextUtil.buildName(user), reachable));
    }

    public long count(BroadcastTarget target) {
        return page(target, 0).getTotalElements();
    }

    /** Destinataires joignables en push (au moins un appareil avec jeton FCM), tous types de cible. */
    public long countPushReachable(BroadcastTarget target) {
        return ruleRepository.countPushReachable(compiled(target));
    }

    /** Page de destinataires avec leurs attributs d'affichage, pour l'aperçu de l'audience. */
    public Page<RecipientRow> recipients(BroadcastTarget target, int pageNumber, int size) {
        return ruleRepository.pageRecipients(compiled(target), PageRequest.of(pageNumber, size));
    }

    /**
     * Règle qui désigne les mêmes comptes que l'ancien type de cible. Garde les cinq anciens
     * ciblages et le moteur de règles d'accord (tests d'équivalence). {@code USER} n'a pas de
     * règle : un compte désigné par id n'est pas un comportement.
     */
    public static AudienceRule.Group equivalentRule(BroadcastTarget target) {
        return switch (target.type()) {
            case ALL -> new AudienceRule.Group(AudienceRule.Op.AND, List.of());
            case SENDERS -> andOf(new AudienceRule.Condition("created_request", "EVER", null));
            case TRAVELERS -> andOf(new AudienceRule.Condition("published_trip", "EVER", null));
            case CORRIDOR -> {
                ObjectNode value = JsonNodeFactory.instance.objectNode();
                value.put("kind", "CITY");
                value.put("origin", target.origin());
                value.put("destination", target.destination());
                value.putArray("sources").add("PUBLISHED").add("REQUESTED");
                yield andOf(new AudienceRule.Condition("corridor_interest", "MATCHES", value));
            }
            case RULE -> target.rule();
            case USER -> throw new IllegalArgumentException("Un ciblage par utilisateur n'a pas de règle équivalente");
        };
    }

    private static AudienceRule.Group andOf(AudienceRule.Condition condition) {
        return new AudienceRule.Group(AudienceRule.Op.AND, List.of(condition));
    }

    /** USER garde sa garde propre (SUSPENDED joignable) ; les autres passent par la règle. */
    private static CompiledRule compiled(BroadcastTarget target) {
        if (target.type() == BroadcastTargetType.USER) {
            return new CompiledRule(BroadcastAudienceRepository.REACHABLE_USER,
                    new MapSqlParameterSource("userId", target.userId()));
        }
        return AudienceSqlCompiler.compile(equivalentRule(target));
    }

    /** Page d'envoi par curseur d'une cible RULE ({@code after} nul pour la première page). */
    public List<RuleAudienceRepository.IdCursor> rulePageAfter(BroadcastTarget target,
                                                               RuleAudienceRepository.IdCursor after) {
        return ruleRepository.idsAfter(AudienceSqlCompiler.compile(target.rule()), after, PAGE_SIZE);
    }

    public Page<UUID> page(BroadcastTarget target, int pageNumber) {
        Pageable pageable = PageRequest.of(pageNumber, PAGE_SIZE);
        return switch (target.type()) {
            case ALL -> repository.findActiveIds(pageable);
            case SENDERS -> repository.findActiveSenderIds(pageable);
            case TRAVELERS -> repository.findActiveTravelerIds(pageable);
            case CORRIDOR -> repository.findActiveCorridorIds(
                    target.origin(), target.destination(), pageable);
            case USER -> repository.findExistingIdById(target.userId(), pageable);
            case RULE -> ruleRepository.pageIds(AudienceSqlCompiler.compile(target.rule()), pageable);
        };
    }
}
