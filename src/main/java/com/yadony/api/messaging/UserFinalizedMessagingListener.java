package com.yadony.api.messaging;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserFinalizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UserFinalizedMessagingListener {

    private static final Logger log = LoggerFactory.getLogger(UserFinalizedMessagingListener.class);

    private final FirestoreService firestoreService;
    private final UserRepository userRepository;

    public UserFinalizedMessagingListener(FirestoreService firestoreService, UserRepository userRepository) {
        this.firestoreService = firestoreService;
        this.userRepository = userRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        // Les documents Firestore (conversations) portent l'UID Firebase dans leurs champs
        // senderId/travelerId (cf. ConversationService), jamais l'UUID PostgreSQL — anonymizeUser
        // doit donc être appelé avec l'UID Firebase, sous peine de ne matcher aucun message.
        //
        // findByIdIncludingDeleted (et non findById) : ce listener tourne en AFTER_COMMIT, donc
        // après que AccountFinalizationService#finalize a déjà persisté deleted_at — une lecture
        // filtrée par @Where(deleted_at IS NULL) ne trouverait plus la ligne. firebase_uid n'est
        // en revanche jamais effacé par la finalisation.
        userRepository.findByIdIncludingDeleted(event.getUserId()).ifPresentOrElse(
                user -> firestoreService.anonymizeUser(user.getFirebaseUid()),
                () -> log.warn("UserFinalizedMessagingListener: user {} not found, skipping Firestore anonymization",
                        event.getUserId()));
    }
}
