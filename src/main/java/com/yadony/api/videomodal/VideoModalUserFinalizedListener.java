package com.yadony.api.videomodal;

import com.yadony.api.auth.events.UserFinalizedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;

/** Finalisation du compte : les réponses aux modals vidéo de l'utilisateur sont supprimées (douce). */
@Component
public class VideoModalUserFinalizedListener {

    private final VideoModalResponseRepository responseRepository;
    private final Clock clock;

    @Autowired
    public VideoModalUserFinalizedListener(VideoModalResponseRepository responseRepository) {
        this(responseRepository, Clock.systemUTC());
    }

    VideoModalUserFinalizedListener(VideoModalResponseRepository responseRepository, Clock clock) {
        this.responseRepository = responseRepository;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        responseRepository.softDeleteByUserId(event.getUserId(), LocalDateTime.now(clock));
    }
}
