package com.yadony.api.admin;

import com.yadony.api.admin.dto.DeletionImpactResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import com.yadony.api.common.deletion.UserDeletionImpactContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rassemble ce que chaque package a à dire sur la suppression d'un compte.
 *
 * <p>Ce service ne connaît aucun des packages contributeurs : il n'a que l'interface de
 * {@code common}. Ajouter un domaine à l'analyse revient à déclarer un bean, sans toucher une
 * ligne ici.
 */
@Service
public class UserDeletionImpactService {

    /** Ce qu'on affiche quand une contrepartie a elle-même déjà été anonymisée. */
    private static final String UNKNOWN_PARTY = "Compte supprimé";

    private final List<UserDeletionImpactContributor> contributors;
    private final UserRepository userRepository;

    public UserDeletionImpactService(List<UserDeletionImpactContributor> contributors,
                                     UserRepository userRepository) {
        this.contributors = contributors;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public DeletionImpactResponse report(UUID userId) {
        List<ImpactFinding> findings = contributors.stream()
                .flatMap(c -> c.contribute(userId).stream())
                .sorted(Comparator.comparing(ImpactFinding::severity))
                .toList();

        Map<UUID, String> names = resolveNames(findings);

        return new DeletionImpactResponse(
                findings.stream().anyMatch(f -> f.severity() == ImpactSeverity.BLOCKING),
                findings.stream().map(f -> toDto(f, names)).toList());
    }

    /**
     * Vrai si au moins un constat empêche la suppression.
     *
     * <p>Réévalué à l'exécution : le rapport affiché à l'administrateur a pu vieillir de plusieurs
     * minutes, et un escrow peut s'ouvrir entre-temps.
     */
    @Transactional(readOnly = true)
    public boolean hasBlocking(UUID userId) {
        return contributors.stream()
                .flatMap(c -> c.contribute(userId).stream())
                .anyMatch(f -> f.severity() == ImpactSeverity.BLOCKING);
    }

    /** Un seul aller-retour pour tous les constats réunis, plutôt qu'un par contrepartie. */
    private Map<UUID, String> resolveNames(List<ImpactFinding> findings) {
        Set<UUID> ids = findings.stream()
                .flatMap(f -> f.affectedParties().stream())
                .map(ImpactFinding.AffectedParty::userId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::publicDisplayName));
    }

    private DeletionImpactResponse.Finding toDto(ImpactFinding f, Map<UUID, String> names) {
        return new DeletionImpactResponse.Finding(
                f.severity().name(),
                f.code(),
                f.count(),
                f.affectedParties().stream()
                        .map(p -> new DeletionImpactResponse.Party(
                                p.userId(),
                                names.getOrDefault(p.userId(), UNKNOWN_PARTY),
                                p.relatedEntityId()))
                        .toList());
    }
}
