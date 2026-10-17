package com.yadony.api.gppro;

import com.yadony.api.auth.events.UserFinalizedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** RGPD : les justificatifs GP Pro disparaissent du stockage à la finalisation du compte. */
@Component
public class GpProAccountFinalizedListener {

    private final GpProDocumentRepository documentRepository;
    private final GpProDocumentService documentService;

    public GpProAccountFinalizedListener(GpProDocumentRepository documentRepository,
                                         GpProDocumentService documentService) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        documentRepository.findByOwnerIdAndPurgedAtIsNull(event.getUserId())
                .forEach(documentService::purge);
    }
}
