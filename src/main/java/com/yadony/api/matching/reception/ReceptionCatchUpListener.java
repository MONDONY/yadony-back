package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserPhoneAttachedEvent;
import com.yadony.api.auth.events.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Retrouve les colis envoyés au numéro d'un utilisateur dès qu'il s'inscrit
 * ({@link UserRegisteredEvent}) ou rattache un numéro vérifié ({@link UserPhoneAttachedEvent}),
 * sans attendre qu'il ouvre ses réceptions (YADONY-FLUTTER-PROD-12). Seul le numéro Firebase,
 * vérifié par code, sert au rapprochement ({@link ReceptionLinker#catchUpAndNotify}).
 *
 * <p>Après validation et en asynchrone : l'inscription ou l'ajout du numéro est déjà acquis,
 * aucune erreur ici (Firebase injoignable, base) ne doit remonter jusqu'à eux.
 */
@Component
public class ReceptionCatchUpListener {

    private static final Logger log = LoggerFactory.getLogger(ReceptionCatchUpListener.class);

    private final ReceptionLinker linker;
    private final UserRepository userRepository;

    public ReceptionCatchUpListener(ReceptionLinker linker, UserRepository userRepository) {
        this.linker = linker;
        this.userRepository = userRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserRegistered(UserRegisteredEvent event) {
        catchUp(event.userId(), "inscription");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPhoneAttached(UserPhoneAttachedEvent event) {
        catchUp(event.userId(), "ajout du numéro");
    }

    private void catchUp(UUID userId, String trigger) {
        try {
            userRepository.findById(userId).ifPresent(user -> {
                int created = linker.catchUpAndNotify(user);
                if (created > 0) {
                    log.info("Rattrapage des réceptions ({}) : {} colis rattaché(s) à {}", trigger, created, userId);
                }
            });
        } catch (Exception e) {
            log.warn("Rattrapage des réceptions ({}) impossible pour {} : {}", trigger, userId, e.toString());
        }
    }
}
