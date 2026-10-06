package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Story 6.5 — Operator fallback scheduler.
 * Runs every hour and creates an AdminAlert for any ESCROW payment older than 48 h
 * that has not yet been released. The alert signals to operators that a manual
 * force-release may be needed.
 *
 * The scheduler is idempotent: it checks for an existing unresolved alert before
 * creating a new one (keyed on paymentId in the JSON payload substring).
 */
@Component
public class EscrowScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscrowScheduler.class);

    private static final String ALERT_TYPE = "ESCROW_J48_TIMEOUT";
    private static final long ESCROW_TIMEOUT_HOURS = 48L;

    private final PaymentRepository paymentRepository;
    private final AdminAlertRepository adminAlertRepository;

    public EscrowScheduler(PaymentRepository paymentRepository,
                           AdminAlertRepository adminAlertRepository) {
        this.paymentRepository = paymentRepository;
        this.adminAlertRepository = adminAlertRepository;
    }

    /** Every hour at minute 0. */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @Transactional
    public void checkEscrowTimeouts() {
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusHours(ESCROW_TIMEOUT_HOURS);

        List<PaymentEntity> timedOut = paymentRepository
                .findByStatusAndCreatedAtBefore(PaymentStatus.ESCROW, threshold);

        if (timedOut.isEmpty()) {
            return;
        }

        log.info("EscrowScheduler: found {} payment(s) in ESCROW older than {}h", timedOut.size(), ESCROW_TIMEOUT_HOURS);

        // Load all existing unresolved J48 alerts once to avoid N+1 queries
        List<AdminAlertEntity> existingAlerts =
                adminAlertRepository.findByTypeAndResolved(ALERT_TYPE, false);

        for (PaymentEntity payment : timedOut) {
            String paymentIdStr = payment.getId().toString();

            // Check whether an unresolved alert already exists for this payment
            boolean alertExists = existingAlerts.stream()
                    .anyMatch(a -> a.getPayload() != null && a.getPayload().contains(paymentIdStr));

            if (alertExists) {
                log.debug("Alert {} already exists for payment {} — skipping", ALERT_TYPE, paymentIdStr);
                continue;
            }

            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(ALERT_TYPE);
            alert.setPayload(buildPayload(payment));
            alert.setSeverity("WARN");
            alert.setDetail("Paiement " + paymentIdStr + " en séquestre depuis plus de "
                    + ESCROW_TIMEOUT_HOURS + " h sans versement au voyageur (bid " + payment.getBidId() + ")");
            alert.setResolved(false);
            adminAlertRepository.save(alert);

            log.warn("Escrow J+48 timeout alert created for payment {}", paymentIdStr);
        }
    }

    private String buildPayload(PaymentEntity payment) {
        return String.format(
                "{\"paymentId\":\"%s\",\"bidId\":\"%s\",\"amount\":\"%s\"}",
                payment.getId(),
                payment.getBidId(),
                payment.getAmount().toPlainString()
        );
    }
}
