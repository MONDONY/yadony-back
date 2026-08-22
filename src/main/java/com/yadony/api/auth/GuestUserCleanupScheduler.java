package com.yadony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 */
@Component
public class GuestUserCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(GuestUserCleanupScheduler.class);

    /**
     * En dessous, la purge refuse de tourner. Un seuil nul ou négatif supprimerait les lignes
     * invitées à peine créées, y compris celles d'un visiteur en pleine navigation. Face à une
     * configuration absurde, une tâche destructrice ne fait rien plutôt que de deviner.
     */
    private static final int MIN_RETENTION_DAYS = 1;

    private final UserRepository userRepository;
    private final int retentionDays;

    public GuestUserCleanupScheduler(UserRepository userRepository,
                                     @Value("${yadony.guest.retention-days:30}") int retentionDays) {
        this.userRepository = userRepository;
        this.retentionDays = retentionDays;
    }

    /**
     * Tous les jours à 3 h 30 UTC, heure creuse.
     *
     * <p>Idempotent : les lignes supprimées ne ressortent plus au passage suivant, et un
     * passage qui ne trouve rien n'écrit rien.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void purgeAbandonedGuestRows() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            log.error("Purge des lignes invitées désactivée: yadony.guest.retention-days={} "
                    + "est inférieur au minimum de {} jour(s). Aucune ligne supprimée.",
                    retentionDays, MIN_RETENTION_DAYS);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays);
        int deleted = userRepository.deleteAbandonedGuestRows(cutoff);

        if (deleted > 0) {
            log.info("Purge des lignes invitées: {} ligne(s) supprimée(s) (sans rôle, sans favori, "
                    + "créée(s) avant {})", deleted, cutoff);
        }
    }
}
