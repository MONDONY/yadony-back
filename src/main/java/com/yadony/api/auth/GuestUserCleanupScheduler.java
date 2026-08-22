package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Purge nocturne des lignes {@code users} nées d'une visite anonyme et jamais devenues un compte.
 *
 * <p><b>Pourquoi.</b> {@link GuestUserProvisioner} crée une ligne au premier favori d'un
 * visiteur. Beaucoup ne serviront jamais : le visiteur ne revient pas, ou perd sa session. À
 * cela s'ajoutent les lignes que {@code GuestClaimService} soft-delete à chaque réclamation
 * réussie, qui n'ont plus aucune raison d'exister. Sans cette tâche, la table {@code users}
 * accumule indéfiniment des lignes vides qui faussent tout comptage d'utilisateurs.
 *
 * <p><b>Le critère est dans {@link UserRepository#ABANDONED_GUEST_ROW_PREDICATE}</b>, avec la
 * justification de chacune de ses conditions. Il est déclaré une seule fois et partagé, parce
 * qu'il commande une suppression physique de lignes {@code users} : une divergence entre deux
 * copies détruirait des comptes réels, toutes les nuits et en silence.
 *
 * <p><b>Ce que cette classe ne fait jamais.</b> Elle ne touche pas aux rôles. L'invariant
 * « une ligne à rôles est un compte réel » porte à lui seul la sécurité de trois mécanismes
 * ({@code GuestUserProvisioner#reactivateIfSoftDeleted}, la barrière B de
 * {@code GuestClaimService}, et le critère ci-dessous). Une purge qui dépouillerait une ligne
 * de ses rôles avant de la supprimer se retirerait à elle-même son propre garde-fou.
 *
 * <p><b>Trois filets, parce que la suppression est irréversible.</b> Une entrée
 * {@code audit_log} nomme les lignes détruites, sans quoi une erreur de critère ne serait ni
 * réparable ni même diagnosticable ; le critère est réévalué au moment du {@code DELETE} ; et
 * tout échec est journalisé en ERROR et remonté dans Sentry, pour qu'une purge qui meurt ne
 * meure pas en silence.
 */
@Component
public class GuestUserCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(GuestUserCleanupScheduler.class);

    /** Action portée par l'entrée {@code audit_log} de chaque passage ayant réellement supprimé. */
    static final String AUDIT_ACTION = "GUEST_ROWS_PURGED";

    /**
     * En dessous, la purge refuse de tourner. Un seuil nul ou négatif supprimerait les lignes
     * invitées à peine créées, y compris celles d'un visiteur en pleine navigation. Face à une
     * configuration absurde, une tâche destructrice ne fait rien plutôt que de deviner.
     */
    private static final int MIN_RETENTION_DAYS = 1;

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final int retentionDays;

    /**
     * {@link TransactionTemplate} plutôt que {@code @Transactional} sur la méthode planifiée :
     * il faut que le {@code try/catch} soit <b>en dehors</b> de la transaction. Rattraper une
     * violation de contrainte à l'intérieur laisserait une transaction marquée rollback-only,
     * que l'intercepteur tenterait ensuite de valider, et l'échec initial se retrouverait
     * masqué derrière une {@code UnexpectedRollbackException} sans rapport.
     */
    public GuestUserCleanupScheduler(UserRepository userRepository,
                                     AuditService auditService,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${yadony.guest.retention-days:30}") int retentionDays) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.retentionDays = retentionDays;
    }

    /**
     * Tous les jours à 3 h 30 UTC, heure creuse.
     *
     * <p>Idempotent : les lignes supprimées ne ressortent plus au passage suivant.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void purgeAbandonedGuestRows() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            log.error("Purge des lignes invitées désactivée: yadony.guest.retention-days={} "
                    + "est inférieur au minimum de {} jour(s). Aucune ligne supprimée.",
                    retentionDays, MIN_RETENTION_DAYS);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);
        try {
            transactionTemplate.executeWithoutResult(status -> purgeBatch(cutoff));
        } catch (RuntimeException e) {
            // Une clé étrangère non anticipée, une base indisponible : sans ce log, la purge
            // cesserait de tourner sans que personne ne l'apprenne, cette nuit-là et toutes
            // les suivantes. L'échec est bruyant, et rien n'a été supprimé (rollback).
            log.error("Purge des lignes invitées: ÉCHEC, aucune ligne supprimée (seuil {}). "
                    + "Vérifier les clés étrangères de la table users.", cutoff, e);
            Sentry.captureException(e);
        }
    }

    /** Sélection, suppression et trace, dans une seule et même transaction. */
    private void purgeBatch(LocalDateTime cutoff) {
        // Les identifiants remontent en chaînes (cf. UserRepository#findAbandonedGuestRowIds :
        // le pilote H2 ne rend pas une colonne uuid convertible en UUID sur requête native).
        List<UUID> candidates = userRepository.findAbandonedGuestRowIds(cutoff)
                .stream().map(UUID::fromString).toList();
        if (candidates.isEmpty()) {
            // DEBUG et non INFO : « rien à purger » est le cas normal. Mais il faut bien une
            // trace, sinon « purge vide » et « purge qui ne tourne plus » sont indiscernables.
            log.debug("Purge des lignes invitées: aucune ligne éligible (créées avant {})", cutoff);
            return;
        }

        userRepository.deleteAbandonedGuestRows(candidates, cutoff);

        // Ce qui a réellement disparu, et non ce qu'on comptait supprimer : entre les deux
        // requêtes, une ligne a pu cesser d'être éligible (un visiteur qui revient poser un
        // favori). L'entrée d'audit est immuable, elle doit dire vrai du premier coup.
        Set<UUID> survivors = userRepository.findExistingUserIds(candidates)
                .stream().map(UUID::fromString).collect(java.util.stream.Collectors.toSet());
        List<UUID> deleted = candidates.stream().filter(id -> !survivors.contains(id)).toList();

        if (!survivors.isEmpty()) {
            log.warn("Purge des lignes invitées: {} ligne(s) épargnée(s) entre la sélection et la "
                    + "suppression, elles ne satisfaisaient plus le critère.", survivors.size());
        }
        if (deleted.isEmpty()) {
            log.debug("Purge des lignes invitées: aucune ligne supprimée (créées avant {})", cutoff);
            return;
        }

        // Trace immuable de ce qui a été détruit. Sans elle, une erreur de critère serait
        // irréversible ET indiagnosticable : personne ne pourrait dire ce qui a disparu.
        // Écrite seulement quand quelque chose a disparu — une entrée « 0 ligne » chaque nuit
        // n'aurait aucune valeur d'enquête et grossirait une table qu'on ne peut pas purger.
        // Aucune des clés ci-dessous n'est masquée par AuditService.redact() (denylist relue),
        // et aucune donnée personnelle n'est en jeu : ces lignes n'ont ni nom, ni téléphone,
        // ni email.
        auditService.log("USER", null, AUDIT_ACTION, null, auditPayload(cutoff, deleted));

        log.info("Purge des lignes invitées: {} ligne(s) supprimée(s) (sans rôle, sans favori, "
                + "sans activité, créée(s) avant {})", deleted.size(), cutoff);
    }

    private Map<String, Object> auditPayload(LocalDateTime cutoff, List<UUID> deleted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deletedCount", deleted.size());
        payload.put("retentionDays", retentionDays);
        payload.put("cutoff", cutoff.toString());
        payload.put("deletedUserIds", deleted.stream().map(UUID::toString).toList());
        return payload;
    }
}
