package com.yadony.api.common.stripe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Component
@ConditionalOnProperty(name = "yadony.stripe.webhook.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class StripeEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(StripeEventScheduler.class);

    private final StripeEventProcessor processor;
    private final StripeWebhookProperties props;

    public StripeEventScheduler(StripeEventProcessor processor, StripeWebhookProperties props) {
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${yadony.stripe.webhook.poll-interval:PT10S}")
    public void poll() {
        int processed = 0;
        try {
            while (processed < props.batchSize() && processor.processOne()) {
                processed++;
            }
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
            // YADONY-BACK-STAGING-11 : connexion du pool coupée (lenteurs disque du
            // staging le soir). Les événements restent dans l'inbox et le passage
            // suivant, poll-interval plus tard, les reprend : un avertissement suffit.
            log.warn("Stripe event scheduler: base indisponible, reprise au prochain passage "
                    + "({} événements traités) : {}", processed, e.getMessage());
            return;
        }
        if (processed > 0) log.info("Stripe event scheduler processed {} events", processed);
    }
}
