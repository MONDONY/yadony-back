package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserProStatusChangedEvent;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Point unique de synchronisation entre l'état d'abonnement et
 * {@code UserEntity.isProAccount}, drapeau que tout le code PRO existant lit
 * (matching, export fiscal, automatisations, quotas de brouillons).
 *
 * <p>Aucun autre composant de {@code billing/} ne doit écrire ce drapeau.
 */
@Component
public class ProAccessSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ProAccessSynchronizer.class);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProAccessSynchronizer(UserRepository userRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Aligne le drapeau PRO de l'utilisateur sur {@code shouldHaveAccess}.
     *
     * <p>L'événement n'est publié que si le drapeau change réellement : les
     * listeners qu'il déclenche (annonces, automatisations) sont coûteux et
     * ne doivent pas tourner pour une transition sans effet — par exemple un
     * PAST_DUE qui revient ACTIVE, où l'accès n'a jamais été interrompu.
     */
    public void sync(UUID userId, boolean shouldHaveAccess) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Cannot sync PRO access for unknown user {}", userId);
            return;
        }
        if (user.isProAccount() == shouldHaveAccess) {
            return;
        }
        user.setProAccount(shouldHaveAccess);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, shouldHaveAccess));
        log.info("PRO access for user {} set to {}", userId, shouldHaveAccess);
    }
}
