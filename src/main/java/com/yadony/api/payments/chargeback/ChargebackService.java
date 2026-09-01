package com.yadony.api.payments.chargeback;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class ChargebackService {

    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

    private final ChargebackRepository chargebackRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;
    private final ObjectMapper objectMapper;

    public ChargebackService(ChargebackRepository chargebackRepository,
                              PaymentRepository paymentRepository,
                              AuditService auditService,
                              AdminAlertService adminAlert,
                              ObjectMapper objectMapper) {
        this.chargebackRepository = chargebackRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
        this.objectMapper = objectMapper;
    }

    public Page<ChargebackDto> listAll(Pageable pageable) {
        return chargebackRepository.findAllByOrderByOpenedAtDesc(pageable)
                .map(ChargebackDto::from);
    }

    @Transactional
    public void handleDisputeCreated(Event event) {
        JsonNode dispute = parseDataObject(event);
        if (dispute == null) return;

        String disputeId = dispute.path("id").asText();
        String chargeId  = dispute.path("charge").asText(null);
        long amount      = dispute.path("amount").asLong();
        // Stripe parle en minuscules ; le schéma Yadony en MAJUSCULES (V236).
        String currency  = dispute.path("currency").asText("eur").toUpperCase(java.util.Locale.ROOT);
        String reason    = dispute.path("reason").asText(null);

        if (chargebackRepository.findByStripeDisputeId(disputeId).isPresent()) {
            log.info("Dispute {} already recorded — skipping", disputeId);
            return;
        }

        var chargeback = new ChargebackEntity();
        chargeback.setStripeDisputeId(disputeId);
        chargeback.setStripeChargeId(chargeId);
        chargeback.setAmount(amount);
        chargeback.setCurrency(currency);
        chargeback.setReason(reason);
        long created = dispute.path("created").asLong(0L);
        chargeback.setOpenedAt(created > 0 ? Instant.ofEpochSecond(created) : Instant.now());

        if (chargeId != null) {
            paymentRepository.findByStripeChargeId(chargeId).ifPresent(payment -> {
                chargeback.setPaymentId(payment.getId());
                chargeback.setBidId(payment.getBidId());
                payment.setDisputed(true);
                paymentRepository.save(payment);
                auditService.log("PAYMENT", payment.getId(), "PAYMENT_DISPUTED",
                        payment.getBidId(), Map.of("disputeId", disputeId, "reason", String.valueOf(reason)));
            });
        }

        chargebackRepository.save(chargeback);
        adminAlert.raise("STRIPE_CHARGEBACK_OPENED",
                "Litige " + disputeId + " ouvert (charge=" + chargeId + ", raison=" + reason + ")",
                Map.of("disputeId", disputeId, "chargeId", String.valueOf(chargeId)));
        log.warn("Chargeback {} recorded for charge {}", disputeId, chargeId);
    }

    @Transactional
    public void handleDisputeClosed(Event event) {
        JsonNode dispute = parseDataObject(event);
        if (dispute == null) return;

        String disputeId = dispute.path("id").asText();
        String outcome   = dispute.path("status").asText();

        chargebackRepository.findByStripeDisputeId(disputeId).ifPresent(cb -> {
            cb.setStatus("won".equals(outcome) ? ChargebackStatus.WON : ChargebackStatus.LOST);
            cb.setOutcome(outcome);
            cb.setResolvedAt(Instant.now());
            chargebackRepository.save(cb);

            // Levée du gel — la dispute est résolue dans les deux cas
            if (cb.getPaymentId() != null) {
                paymentRepository.findById(cb.getPaymentId()).ifPresent(payment -> {
                    payment.setDisputed(false);
                    paymentRepository.save(payment);
                    String auditAction = "won".equals(outcome) ? "PAYMENT_DISPUTE_WON" : "PAYMENT_DISPUTE_LOST";
                    auditService.log("PAYMENT", payment.getId(), auditAction,
                            payment.getBidId(), Map.of("disputeId", disputeId));
                });
            }

            auditService.log("CHARGEBACK", cb.getId(), "CHARGEBACK_CLOSED", null,
                    Map.of("disputeId", disputeId, "outcome", outcome));
            adminAlert.raise("STRIPE_CHARGEBACK_CLOSED",
                    "Litige " + disputeId + " cloture : " + outcome,
                    Map.of("disputeId", disputeId, "outcome", outcome));
        });
    }

    @Transactional
    public void handleFundsWithdrawn(Event event) {
        logFundsEvent(event, "CHARGEBACK_FUNDS_WITHDRAWN");
    }

    @Transactional
    public void handleFundsReinstated(Event event) {
        logFundsEvent(event, "CHARGEBACK_FUNDS_REINSTATED");
    }

    private void logFundsEvent(Event event, String action) {
        JsonNode dispute = parseDataObject(event);
        if (dispute == null) return;
        String disputeId = dispute.path("id").asText();
        chargebackRepository.findByStripeDisputeId(disputeId).ifPresent(cb ->
            auditService.log("CHARGEBACK", cb.getId(), action, null,
                    Map.of("disputeId", disputeId)));
    }

    private JsonNode parseDataObject(Event event) {
        try {
            return objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
        } catch (Exception e) {
            log.warn("Cannot parse dispute event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }
}
