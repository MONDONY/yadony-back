package com.yadony.api.gppro;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserFinalizedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * RGPD : à la finalisation du compte, les justificatifs GP Pro disparaissent du stockage, les textes libres
 * (nom commercial, motifs) sont vidés et les signalements de revue ouverts sont clos. Aucune suppression physique.
 */
@Component
public class GpProAccountFinalizedListener {

    private final GpProDocumentRepository documentRepository;
    private final GpProDocumentService documentService;
    private final GpProApplicationRepository applicationRepository;
    private final GpProReviewFlagRepository reviewFlagRepository;
    private final UserRepository userRepository;

    public GpProAccountFinalizedListener(GpProDocumentRepository documentRepository,
                                         GpProDocumentService documentService,
                                         GpProApplicationRepository applicationRepository,
                                         GpProReviewFlagRepository reviewFlagRepository,
                                         UserRepository userRepository) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.applicationRepository = applicationRepository;
        this.reviewFlagRepository = reviewFlagRepository;
        this.userRepository = userRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        documentRepository.findByOwnerIdAndPurgedAtIsNull(event.getUserId())
                .forEach(documentService::purge);
        applicationRepository.clearPersonalTextByUserId(event.getUserId());
        userRepository.clearGpProRevokedReason(event.getUserId());
        reviewFlagRepository.resolveOpenByUserId(event.getUserId(), LocalDateTime.now(ZoneOffset.UTC));
    }
}
