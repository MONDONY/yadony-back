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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // Le payload ne contient que des données non-personnelles : reasonCode (valeur d'enum),
        // et un instantané des décomptes par code de constat (nombres). La clé « reason »
        // (motif libre) a été délibérément exclue : audit_log est immuable et ne peut être
        // corrigé après coup — y écrire un texte libre saisi par un administrateur crée un
        // risque réel d'y graver des données personnelles (noms, emails, téléphones).
        auditService.log("USER", userId, "USER_ADMIN_DELETION", adminId,
                buildAuditPayload(reasonCode, userId));
    }

    /**
     * Construit le payload d'audit : motif catalogué + instantané des décomptes par constat.
     *
     * <p>Seul {@code reasonCode} est inclus comme description humaine. Le motif libre ({@code reason})
     * en est délibérément absent : il s'agit d'un texte arbitraire susceptible de contenir des
     * données personnelles, et {@code audit_log} est protégé par un trigger d'immuabilité.</p>
     *
     * <p>Les décomptes (nombres entiers) ne constituent pas des données personnelles et sont
     * précieux pour l'audit a posteriori : ils reconstituent l'état exact du compte au moment
     * de la suppression, information que les entrées des contributeurs eux-mêmes n'enregistrent
     * pas nécessairement.</p>
     */
    private Map<String, Object> buildAuditPayload(String reasonCode, UUID userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", reasonCode);

        // Instantané des décomptes par code de constat, tel qu'exigé par la spécification.
        // On récupère le rapport complet (non filtré sur les bloquants) pour y inclure aussi
        // les avertissements et informatifs — l'historique doit refléter l'état réel, pas
        // seulement les motifs de refus éventuels.
        impactService.report(userId).findings().stream()
                .collect(Collectors.toMap(
                        f -> "impact_" + f.code().toLowerCase(),
                        f -> (Object) f.count(),
                        (a, b) -> a,
                        LinkedHashMap::new))
                .forEach(payload::put);

        return payload;
    }
}
