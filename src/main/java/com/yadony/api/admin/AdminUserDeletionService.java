package com.yadony.api.admin;

import com.yadony.api.auth.AccountFinalizationService;
import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Suppression d'un compte décidée par l'administrateur, distincte d'une demande RGPD reçue.
 *
 * <p>L'anonymisation elle-même est déléguée au moteur existant : il efface les données
 * personnelles, purge les pièces KYC de R2, supprime le compte Firebase — donc l'email et le
 * téléphone, dont Firebase est l'unique source — et publie l'événement qui annule annonces et
 * offres et prévient les contreparties.
 */
@Service
public class AdminUserDeletionService {

    private final UserRepository userRepository;
    private final UserDeletionImpactService impactService;
    private final AccountFinalizationService finalizationService;
    private final AuditService auditService;

    public AdminUserDeletionService(UserRepository userRepository,
                                    UserDeletionImpactService impactService,
                                    AccountFinalizationService finalizationService,
                                    AuditService auditService) {
        this.userRepository = userRepository;
        this.impactService = impactService;
        this.finalizationService = finalizationService;
        this.auditService = auditService;
    }

    @Transactional
    public void delete(UUID userId, UUID adminId, String reasonCode, String reason) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found",
                        "Utilisateur introuvable"));

        // Recalcul, et non relecture d'un rapport transmis par le client : entre l'écran de
        // confirmation et ce clic, un escrow a pu s'ouvrir. Le front ne fait jamais autorité.
        if (impactService.hasBlocking(userId)) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "deletion-blocked", "Unprocessable",
                    "Suppression impossible — des engagements financiers sont encore en cours");
        }

        finalizationService.finalize(user, FinalizationReason.ADMIN_INITIATED);

        // AuditService masque déjà toute clé contenant name, email ou phone : seuls le motif
        // et les identifiants survivent, ce qui est exactement ce qu'on veut conserver.
        auditService.log("USER", userId, "USER_ADMIN_DELETION", adminId,
                Map.of("reasonCode", reasonCode, "reason", reason));
    }
}
