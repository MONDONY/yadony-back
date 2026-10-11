package com.yadony.api.common.stripe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class StripeEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(StripeEventProcessor.class);

    private final StripeEventInboxRepository repo;
    private final StripeEventDispatcher dispatcher;
    private final StripeWebhookProperties props;
    private final AdminAlertService adminAlert;

    public StripeEventProcessor(StripeEventInboxRepository repo,
                                 StripeEventDispatcher dispatcher,
                                 StripeWebhookProperties props,
                                 AdminAlertService adminAlert) {
        this.repo = repo;
        this.dispatcher = dispatcher;
        this.props = props;
        this.adminAlert = adminAlert;
    }

    /**
     * Claims the next PENDING event via SKIP LOCKED (non-blocking, safe for concurrent
     * scheduler replicas) and dispatches it inside its own REQUIRES_NEW transaction so
     * a handler failure never rolls back the inbox-row update.
     *
     * @return {@code true} if an event was claimed and processed (success, skipped, or
     *         moved to FAILED/DEAD_LETTER); {@code false} if the queue was empty.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne() {
        return repo.claimNext().map(inbox -> {
            try {
                // Conflit de verrou (interblocage avec confirm-payment…) : rejoué aussitôt, chaque
                // tentative dans la transaction REQUIRES_NEW du dispatcher ; au-delà, l'échec suit
                // la voie normale ci-dessous (FAILED puis nouvel essai différé).
                boolean handled = com.yadony.api.common.TransientLockRetry.run(
                        "Stripe event " + inbox.getEventId(),
                        () -> dispatcher.dispatch(inbox.getPayload()),
                        conflict -> conflict);
                inbox.setStatus(handled ? StripeEventStatus.PROCESSED : StripeEventStatus.SKIPPED);
                inbox.setProcessedAt(Instant.now());
            } catch (Exception e) {
                int newCount = inbox.getRetryCount() + 1;
                inbox.setRetryCount(newCount);
                inbox.setLastError(e.getMessage());

                if (newCount >= props.maxRetries()) {
                    inbox.setStatus(StripeEventStatus.DEAD_LETTER);
                    inbox.setProcessedAt(Instant.now());
                    adminAlert.raise("STRIPE_DEAD_LETTER",
                            "Event " + inbox.getEventId() + " (" + inbox.getEventType() + ") exhausted retries",
                            Map.of("eventId", inbox.getEventId(), "error", String.valueOf(e.getMessage())));
                } else {
                    inbox.setStatus(StripeEventStatus.FAILED);
                    long backoffSeconds = props.retryBackoffBase().getSeconds() * (1L << newCount);
                    inbox.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                    log.warn("Event {} failed (attempt {}/{}), retry in {}s: {}",
                            inbox.getEventId(), newCount, props.maxRetries(), backoffSeconds, e.getMessage());
                }
            }
            repo.save(inbox);
            return true;
        }).orElse(false);
    }
}
