package com.yadony.api.kyc.erasure;

import com.yadony.api.auth.events.IdentitySessionsErasureRequestedEvent;
import com.yadony.api.kyc.KycRefusedSessionEntity;
import com.yadony.api.kyc.KycRefusedSessionRepository;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Suppression RGPD d'un compte : efface chez Didit ses sessions de vérification.
 *
 * <ul>
 *   <li><b>Mise en file, synchrone</b> ({@code @EventListener}) : dans la transaction de la
 *       finalisation, pour être validée ou annulée avec elle. Elle ajoute aux sessions reçues
 *       celles que l'utilisateur a vu refuser ou révoquer ({@code kyc_refused_sessions}), qui
 *       restent chez Didit comme les autres.</li>
 *   <li><b>Effacement, après commit et en asynchrone</b> : jamais dans la requête ni dans la
 *       transaction. Un échec est repris par {@link DiditSessionDeletionRetryJob}.</li>
 * </ul>
 */
@Component
public class DiditSessionErasureListener {

    private static final Logger log = LoggerFactory.getLogger(DiditSessionErasureListener.class);

    private final DiditSessionErasureService erasureService;
    private final KycRefusedSessionRepository refusedSessionRepository;

    public DiditSessionErasureListener(DiditSessionErasureService erasureService,
                                       KycRefusedSessionRepository refusedSessionRepository) {
        this.erasureService = erasureService;
        this.refusedSessionRepository = refusedSessionRepository;
    }

    @EventListener
    public void enqueue(IdentitySessionsErasureRequestedEvent event) {
        List<String> sessionIds = new ArrayList<>(event.diditSessionIds());
        for (KycRefusedSessionEntity refused : refusedSessionRepository.findAllByUserId(event.userId())) {
            if (refused.getProvider() == VerificationProviderKind.DIDIT) {
                sessionIds.add(refused.getSessionId());
            }
        }
        int created = erasureService.enqueue(event.userId(), sessionIds);
        if (created > 0) {
            log.info("{} session(s) Didit à effacer pour le compte supprimé {}", created, event.userId());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void eraseAfterCommit(IdentitySessionsErasureRequestedEvent event) {
        try {
            erasureService.attemptPendingFor(event.userId());
        } catch (RuntimeException e) {
            log.warn("Effacement Didit après suppression du compte {} reporté au planificateur ({})",
                    event.userId(), e.getClass().getSimpleName());
        }
    }
}
