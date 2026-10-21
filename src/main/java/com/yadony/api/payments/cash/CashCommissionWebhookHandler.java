package com.yadony.api.payments.cash;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.event.CommissionMethodDetachedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CashCommissionWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(CashCommissionWebhookHandler.class);
    private static final String COMMISSION_PURPOSE_KEY = "commission_purpose";
    private static final String COMMISSION_PURPOSE_VALUE = "cash_bid";

    /**
     * Bids morts avant toute acceptation. Leur écouteur d'annulation
     * ({@link BidCancelledCommissionRefundListener}) est déjà passé et n'a rien remboursé,
     * la commission n'étant pas encore {@code CHARGED} : un débit 3DS qui aboutit après
     * coup doit donc être rendu ici, sinon le voyageur paie pour un colis qui n'existe plus.
     */
    private static final Set<BidStatus> DEAD_BEFORE_ACCEPTANCE = EnumSet.of(
            BidStatus.CANCELLED, BidStatus.REJECTED, BidStatus.EXPIRED, BidStatus.NEGOTIATION_CLOSED);

    /** Statuts de commission déjà réglés : un webhook tardif ne doit pas les réécrire. */
    private static final Set<CommissionStatus> SETTLED = EnumSet.of(
            CommissionStatus.CHARGED, CommissionStatus.REFUNDED, CommissionStatus.REFUND_FAILED);

    private final UserRepository userRepo;
    private final BidRepository bidRepo;
    private final ApplicationEventPublisher events;
    private final CashCommissionService cashCommissionService;

    public CashCommissionWebhookHandler(UserRepository userRepo,
                                        BidRepository bidRepo,
                                        ApplicationEventPublisher events,
                                        CashCommissionService cashCommissionService) {
        this.userRepo = userRepo;
        this.bidRepo = bidRepo;
        this.events = events;
        this.cashCommissionService = cashCommissionService;
    }

    public void handleSetupIntentSucceeded(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            SetupIntent si = (SetupIntent) obj;
            String customerId = si.getCustomer();
            String pmId = si.getPaymentMethod();
            if (customerId == null || pmId == null) {
                log.warn("setup_intent.succeeded: missing customer or payment_method — skipping");
                return;
            }
            try {
                com.stripe.model.PaymentMethod pm = com.stripe.model.PaymentMethod.retrieve(pmId);
                if (pm.getCard() == null) {
                    log.warn("setup_intent.succeeded: PM {} is not a card — skipping", pmId);
                    return;
                }
                userRepo.findByStripeCustomerId(customerId).ifPresent(user -> {
                    user.setCommissionPaymentMethodId(pmId);
                    user.setCommissionCardBrand(pm.getCard().getBrand());
                    user.setCommissionCardLast4(pm.getCard().getLast4());
                    user.setCommissionCardExpMonth(pm.getCard().getExpMonth().intValue());
                    user.setCommissionCardExpYear(pm.getCard().getExpYear().intValue());
                    userRepo.save(user);
                    log.info("Commission PM saved for customer {}: {} ****{}",
                            customerId, pm.getCard().getBrand(), pm.getCard().getLast4());
                });
            } catch (StripeException e) {
                log.error("Failed to retrieve PM {} for setup_intent.succeeded: {}", pmId, e.getMessage());
            }
        });
    }

    public void handlePaymentIntentSucceeded(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            if (!isCommissionPi(pi)) return;
            String bidIdStr = pi.getMetadata().get("bid_id");
            if (bidIdStr == null) return;
            bidRepo.findById(UUID.fromString(bidIdStr)).ifPresent(bid -> {
                if (SETTLED.contains(bid.getCommissionStatus())) return;
                bid.setCommissionStatus(CommissionStatus.CHARGED);
                bid.setCommissionChargedVia(com.yadony.api.payments.cash.CommissionChargedVia.CARD);
                bid.setCommissionPaymentIntentId(pi.getId());
                bidRepo.save(bid);
                log.info("Commission CHARGED via webhook for bid {}", bidIdStr);
                if (DEAD_BEFORE_ACCEPTANCE.contains(bid.getStatus())) {
                    log.warn("Commission charged on bid {} already {}: refunding", bidIdStr, bid.getStatus());
                    cashCommissionService.refundCommission(bid);
                }
            });
        });
    }

    public void handlePaymentIntentFailed(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            PaymentIntent pi = (PaymentIntent) obj;
            if (!isCommissionPi(pi)) return;
            String bidIdStr = pi.getMetadata().get("bid_id");
            if (bidIdStr == null) return;
            bidRepo.findById(UUID.fromString(bidIdStr)).ifPresent(bid -> {
                if (bid.getCommissionStatus() == CommissionStatus.FAILED) return;
                bid.setCommissionStatus(CommissionStatus.FAILED);
                bidRepo.save(bid);
                log.warn("Commission FAILED via webhook for bid {}", bidIdStr);
            });
        });
    }

    public void handlePaymentMethodDetached(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            com.stripe.model.PaymentMethod pm = (com.stripe.model.PaymentMethod) obj;
            String pmId = pm.getId();
            userRepo.findByCommissionPaymentMethodId(pmId).ifPresent(user -> {
                user.setCommissionPaymentMethodId(null);
                user.setCommissionCardBrand(null);
                user.setCommissionCardLast4(null);
                user.setCommissionCardExpMonth(null);
                user.setCommissionCardExpYear(null);
                userRepo.save(user);
                events.publishEvent(new CommissionMethodDetachedEvent(user.getId()));
                log.info("Commission PM detached externally for user {}", user.getId());
            });
        });
    }

    private boolean isCommissionPi(PaymentIntent pi) {
        return COMMISSION_PURPOSE_VALUE.equals(
                pi.getMetadata() != null ? pi.getMetadata().get(COMMISSION_PURPOSE_KEY) : null);
    }
}
