package com.yadony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Taux de fiabilité du profil public ({@link ReliabilityScore}, FLUTTER-NV). Une requête
 * agrégée sur les colis livrés, plus les compteurs déjà portés par le compte ; mis en cache
 * une minute par utilisateur ({@code profile-reliability}) : le profil public est ouvert en
 * rafale depuis les listes, et un retard d'une minute sur un taux est invisible.
 */
@Service
public class ReliabilityService {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityService.class);

    private final UserRepository userRepository;

    public ReliabilityService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** @return pourcentage entier, ou {@code null} sous 3 envois engagés ou si le calcul échoue */
    @Cacheable(value = "profile-reliability", key = "#user.id")
    public Integer reliabilityPercent(UserEntity user) {
        try {
            long completed = userRepository.countCompletedShipments(user.getId());
            return ReliabilityScore.percent(completed, ReliabilityScore.attributableFailures(user));
        } catch (RuntimeException e) {
            // Indicateur de confort : son échec ne doit jamais masquer le profil.
            log.warn("Fiabilité non calculée pour {} : {}", user.getId(), e.getMessage());
            return null;
        }
    }
}
