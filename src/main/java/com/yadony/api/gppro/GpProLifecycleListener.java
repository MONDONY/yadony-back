package com.yadony.api.gppro;

import com.yadony.api.activation.UserIntent;
import com.yadony.api.activation.events.UserIntentDeclaredEvent;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.gppro.events.GpProStatusChangedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/** Fait suivre à la demande GP Pro ouverte le KYC, l'intention et la suppression du compte. */
@Component
public class GpProLifecycleListener {

    private static final String ENTITY = "GP_PRO_APPLICATION";

    private final GpProApplicationRepository applications;
    private final GpProDocumentService documentService;
    private final UserRepository users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public GpProLifecycleListener(GpProApplicationRepository applications,
                                  GpProDocumentService documentService,
                                  UserRepository users,
                                  AuditService audit,
                                  ApplicationEventPublisher events) {
        this.applications = applications;
        this.documentService = documentService;
        this.users = users;
        this.audit = audit;
        this.events = events;
    }

    /** KYC vérifié : la demande qui l'attendait part en file de revue. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onKycVerified(UserKycVerifiedEvent event) {
        applications.findFirstByUserIdAndStatusIn(event.getUserId(),
                        EnumSet.of(GpProApplicationStatus.AWAITING_KYC))
                .ifPresent(app -> {
                    app.setStatus(GpProApplicationStatus.PENDING);
                    applications.save(app);
                    audit.log(ENTITY, app.getId(), "GP_PRO_KYC_RELEASED", event.getUserId(), Map.of());
                });
    }

    /** Un expéditeur pur n'a rien à faire avec le badge : la demande ouverte est annulée. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIntentDeclared(UserIntentDeclaredEvent event) {
        if (event.intent() == UserIntent.SENDER) {
            cancelOpenApplication(event.userId(), "intent_sender");
        }
    }

    /**
     * Suppression demandée : demande annulée et justificatifs purgés. {@code gp_pro} reste
     * intact, la finalisation de la suppression rend le compte invisible.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountDeletionRequested(AccountDeletionRequestedEvent event) {
        cancelOpenApplication(event.getUserId(), "account_deletion");
        users.findById(event.getUserId())
                .filter(u -> u.isGpPro())
                .ifPresent(u -> events.publishEvent(new GpProStatusChangedEvent(u.getId(), false)));
    }

    private void cancelOpenApplication(UUID userId, String reason) {
        applications.findFirstByUserIdAndStatusIn(userId,
                        EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING))
                .ifPresent(app -> {
                    app.setStatus(GpProApplicationStatus.CANCELLED);
                    applications.save(app);
                    documentService.purgeAll(app.getId());
                    audit.log(ENTITY, app.getId(), "GP_PRO_APPLICATION_CANCELLED", userId,
                            Map.of("reason", reason));
                });
    }
}
