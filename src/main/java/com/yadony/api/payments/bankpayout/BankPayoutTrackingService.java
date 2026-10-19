package com.yadony.api.payments.bankpayout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.events.BankPayoutFailedEvent;
import com.yadony.api.payments.events.BankPayoutPaidEvent;
import com.yadony.api.payments.events.StripeTransferRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Suivi du virement bancaire d'un paiement carte versé au voyageur, du Transfer de la livraison
 * jusqu'à l'arrivée sur sa banque.
 *
 * <ol>
 *   <li>Transfer enregistré : ligne {@link BankPayoutTrackingEntity}, puis lecture chez Stripe du
 *       {@code destination_payment} (py_… sur le compte connecté) et de sa date de disponibilité
 *       ({@code balance_transaction.available_on}, en-tête {@code Stripe-Account}).</li>
 *   <li>Webhook {@code payout.*} des comptes connectés (ou rattrapage) : les balance transactions
 *       du payout donnent les py_… qu'il contient, donc les paiements concernés ; une
 *       {@code payout_failure} rattache aussi les paiements d'un payout échoué repris par un
 *       nouveau payout.</li>
 * </ol>
 *
 * <p>Idempotent et tolérant à l'ordre : un événement plus ancien que le dernier appliqué au même
 * payout est ignoré ; à date égale, un statut ne recule pas ({@code paid} ne redevient pas
 * {@code in_transit}). Push et alerte partent une seule fois par payout et statut
 * ({@code notified_key}), jamais sur un rejeu.
 */
@Service
public class BankPayoutTrackingService {

    private static final Logger log = LoggerFactory.getLogger(BankPayoutTrackingService.class);

    /** Au-delà, un virement retrouvé par le rattrapage est trop ancien pour être annoncé. */
    static final int NOTIFY_MAX_AGE_DAYS = 3;

    static final String STATUS_PAID = "paid";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_CANCELED = "canceled";

    private final BankPayoutTrackingRepository repository;
    private final BankPayoutStripeGateway gateway;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final AdminAlertService adminAlert;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public BankPayoutTrackingService(BankPayoutTrackingRepository repository, BankPayoutStripeGateway gateway,
                                     AuditService auditService, ApplicationEventPublisher events,
                                     AdminAlertService adminAlert, UserRepository userRepository,
                                     ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this(repository, gateway, auditService, events, adminAlert, userRepository, objectMapper,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    BankPayoutTrackingService(BankPayoutTrackingRepository repository, BankPayoutStripeGateway gateway,
                              AuditService auditService, ApplicationEventPublisher events,
                              AdminAlertService adminAlert, UserRepository userRepository,
                              ObjectMapper objectMapper, TransactionOperations transaction, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.events = events;
        this.adminAlert = adminAlert;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.clock = clock;
    }

    // ── 1. Transfer de la livraison ──────────────────────────────────────────

    /**
     * Ouvre le suivi d'un Transfer (idempotent : une ligne par paiement) et le complète au mieux.
     * Un échec Stripe est journalisé : le rattrapage planifié retentera.
     */
    public void recordTransfer(StripeTransferRecordedEvent event) {
        if (event.paymentId() == null || event.transferId() == null || event.connectedAccountId() == null) {
            return;
        }
        BankPayoutTrackingEntity row = openRow(event.paymentId(), event.travelerId(), event.transferId(),
                event.connectedAccountId());
        if (row != null) {
            enrichQuietly(row);
        }
    }

    /**
     * Ligne existante ou créée. {@code connectedAccountId} peut être {@code null} (rattrapage) :
     * il est alors lu sur le Transfer.
     */
    BankPayoutTrackingEntity openRow(UUID paymentId, UUID travelerId, String transferId, String connectedAccountId) {
        Optional<BankPayoutTrackingEntity> existing = repository.findByPaymentId(paymentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        BankPayoutTrackingEntity row = new BankPayoutTrackingEntity();
        row.setPaymentId(paymentId);
        row.setTravelerId(travelerId);
        row.setTransferId(transferId);
        row.setConnectedAccountId(connectedAccountId);
        if (row.getConnectedAccountId() == null) {
            try {
                BankPayoutStripeGateway.TransferInfo transfer = gateway.retrieveTransfer(transferId);
                row.setConnectedAccountId(transfer.destination());
                row.setDestinationPaymentId(transfer.destinationPayment());
            } catch (StripeException e) {
                log.warn("Suivi virement : Transfer {} illisible chez Stripe ({})", transferId, e.getMessage());
                return null;
            }
            if (row.getConnectedAccountId() == null) {
                return null;
            }
        }
        if (row.getTravelerId() == null) {
            row.setTravelerId(userRepository.findByStripeAccountId(row.getConnectedAccountId())
                    .map(UserEntity::getId).orElse(null));
        }
        try {
            return transaction.execute(tx -> repository.saveAndFlush(row));
        } catch (DataIntegrityViolationException race) {
            // Écouteur et rattrapage en même temps : l'autre a créé la ligne.
            return repository.findByPaymentId(paymentId).orElse(null);
        }
    }

    /** Complète destination_payment puis available_on ; journalise un échec Stripe. */
    void enrichQuietly(BankPayoutTrackingEntity row) {
        try {
            enrich(row);
        } catch (StripeException | RuntimeException e) {
            log.warn("Suivi virement du paiement {} : date de disponibilité illisible ({})",
                    row.getPaymentId(), e.getMessage());
        }
    }

    void enrich(BankPayoutTrackingEntity row) throws StripeException {
        boolean changed = false;
        if (row.getDestinationPaymentId() == null) {
            BankPayoutStripeGateway.TransferInfo transfer = gateway.retrieveTransfer(row.getTransferId());
            if (transfer.destinationPayment() != null) {
                row.setDestinationPaymentId(transfer.destinationPayment());
                changed = true;
            }
        }
        if (row.getAvailableOn() == null && row.getDestinationPaymentId() != null) {
            Optional<LocalDate> availableOn = gateway.availableOn(row.getConnectedAccountId(),
                    row.getDestinationPaymentId());
            if (availableOn.isPresent()) {
                row.setAvailableOn(availableOn.get());
                changed = true;
            }
        }
        if (changed) {
            transaction.executeWithoutResult(tx -> repository.save(row));
        }
    }

    // ── 2. Webhook payout.* des comptes connectés ────────────────────────────

    /**
     * Traite un {@code payout.created|updated|paid|failed|canceled} d'un compte connecté.
     *
     * @return {@code false} si l'événement ne vient pas d'un compte connecté (payout plateforme)
     * @throws IllegalStateException si Stripe est illisible : l'inbox rejouera l'événement
     */
    public boolean handlePayoutEvent(Event event) {
        String account = event.getAccount();
        if (account == null || account.isBlank()) {
            return false;
        }
        PayoutSnapshot snapshot;
        try {
            snapshot = parsePayout(objectMapper.readTree(event.getDataObjectDeserializer().getRawJson()));
        } catch (Exception e) {
            log.warn("Webhook {} ({}) : payout illisible ({})", event.getId(), event.getType(), e.getMessage());
            return true;
        }
        Instant eventAt = event.getCreated() != null ? Instant.ofEpochSecond(event.getCreated()) : now();
        try {
            int changed = applyPayout(account, snapshot, eventAt, eventAt);
            if (changed == 0 && STATUS_FAILED.equals(snapshot.status())
                    && repository.findByConnectedAccountIdAndPayoutIdIn(account, List.of(snapshot.id())).isEmpty()) {
                // Payout échoué sans colis suivi (payout manuel, fonds d'avant le suivi) : l'équipe
                // doit quand même le savoir, comme avant ce suivi.
                adminAlert.raise("STRIPE_PAYOUT_FAILED",
                        "Virement " + snapshot.id() + " du compte " + account + " échoué (aucun colis suivi) — code: "
                                + snapshot.failureCode(),
                        Map.of("payoutId", snapshot.id(), "accountId", account,
                                "failureCode", String.valueOf(snapshot.failureCode())));
            }
        } catch (StripeException e) {
            throw new IllegalStateException("Payout " + snapshot.id() + " du compte " + account
                    + " : balance transactions illisibles chez Stripe (" + e.getMessage() + ")", e);
        }
        return true;
    }

    static PayoutSnapshot parsePayout(JsonNode node) {
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("identifiant de payout absent");
        }
        JsonNode arrival = node.path("arrival_date");
        LocalDate arrivalDate = arrival.isNumber()
                ? BankPayoutStripeGatewayImpl.epochDay(arrival.asLong()) : null;
        JsonNode amount = node.path("amount");
        JsonNode automatic = node.path("automatic");
        JsonNode created = node.path("created");
        return new PayoutSnapshot(id, node.path("status").asText(null), arrivalDate,
                amount.isNumber() ? amount.asLong() : null,
                node.path("currency").asText(null),
                textOrNull(node.path("failure_code")),
                automatic.isBoolean() ? automatic.asBoolean() : null,
                created.isNumber() ? created.asLong() : null);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(null);
    }

    /**
     * Rattache un payout aux paiements qu'il contient et met leur suivi à jour.
     *
     * @param eventAt date de l'information (création de l'événement Stripe, ou maintenant pour le
     *                rattrapage qui lit l'état courant)
     * @param paidAt  date retenue pour {@code paidAt} si le payout est {@code paid}
     * @return le nombre de paiements dont le suivi a changé
     */
    public int applyPayout(String account, PayoutSnapshot payout, Instant eventAt, Instant paidAt)
            throws StripeException {
        if (payout.status() == null) {
            return 0;
        }
        if (Boolean.FALSE.equals(payout.automatic())) {
            // Stripe ne liste les balance transactions que des payouts automatiques.
            log.info("Payout manuel {} du compte {} : contenu non rattachable, ignoré", payout.id(), account);
            return 0;
        }
        for (BankPayoutTrackingEntity pending : repository.findByConnectedAccountIdAndDestinationPaymentIdIsNull(account)) {
            enrichQuietly(pending);
        }
        Set<String> paymentSources = new LinkedHashSet<>();
        Set<String> failedPayouts = new LinkedHashSet<>();
        failedPayouts.add(payout.id());
        for (BankPayoutStripeGateway.BalanceTxnRef ref : gateway.payoutBalanceTransactions(account, payout.id())) {
            if (ref.source() == null) {
                continue;
            }
            if ("payout_failure".equals(ref.type()) || ref.source().startsWith("po_")) {
                failedPayouts.add(ref.source());
            } else {
                paymentSources.add(ref.source());
            }
        }
        Map<UUID, BankPayoutTrackingEntity> rows = new LinkedHashMap<>();
        if (!paymentSources.isEmpty()) {
            repository.findByConnectedAccountIdAndDestinationPaymentIdIn(account, paymentSources)
                    .forEach(r -> rows.put(r.getId(), r));
        }
        repository.findByConnectedAccountIdAndPayoutIdIn(account, failedPayouts)
                .forEach(r -> rows.putIfAbsent(r.getId(), r));
        if (rows.isEmpty()) {
            log.info("Payout {} ({}) du compte {} : aucun colis suivi", payout.id(), payout.status(), account);
            return 0;
        }
        Integer changed = transaction.execute(tx -> applyToRows(account, payout, eventAt, paidAt,
                new ArrayList<>(rows.values())));
        return changed == null ? 0 : changed;
    }

    private int applyToRows(String account, PayoutSnapshot payout, Instant eventAt, Instant paidAt,
                            List<BankPayoutTrackingEntity> rows) {
        int changed = 0;
        List<BankPayoutTrackingEntity> toNotify = new ArrayList<>();
        String key = payout.id() + ":" + payout.status();
        boolean terminal = STATUS_PAID.equals(payout.status()) || STATUS_FAILED.equals(payout.status());
        for (BankPayoutTrackingEntity row : rows) {
            if (!apply(row, payout, eventAt, paidAt)) {
                continue;
            }
            changed++;
            if (terminal && !key.equals(row.getNotifiedKey())) {
                row.setNotifiedKey(key);
                toNotify.add(row);
                audit(row, payout);
            }
            repository.save(row);
        }
        if (!toNotify.isEmpty()) {
            announce(account, payout, toNotify);
        }
        return changed;
    }

    /** @return {@code true} si la ligne a changé */
    static boolean apply(BankPayoutTrackingEntity row, PayoutSnapshot payout, Instant eventAt, Instant paidAt) {
        if (payout.id().equals(row.getPayoutId()) && row.getPayoutEventAt() != null) {
            if (eventAt.isBefore(row.getPayoutEventAt())) {
                return false;
            }
            if (eventAt.equals(row.getPayoutEventAt()) && rank(payout.status()) < rank(row.getPayoutStatus())) {
                return false;
            }
        } else if (row.getPayoutId() != null && row.getPayoutEventAt() != null
                && !STATUS_FAILED.equals(row.getPayoutStatus()) && !STATUS_CANCELED.equals(row.getPayoutStatus())
                && eventAt.isBefore(row.getPayoutEventAt())) {
            // Un autre payout, encore valable, a été vu plus récemment.
            return false;
        }
        boolean samePayout = payout.id().equals(row.getPayoutId());
        Instant previousPaidAt = row.getPaidAt();
        String before = row.getPayoutId() + "|" + row.getPayoutStatus() + "|" + row.getArrivalDate() + "|"
                + row.getFailureCode() + "|" + row.getPaidAt();
        row.setPayoutId(payout.id());
        row.setPayoutStatus(payout.status());
        row.setPayoutEventAt(eventAt);
        if (payout.arrivalDate() != null) {
            row.setArrivalDate(payout.arrivalDate());
        }
        row.setFailureCode(STATUS_FAILED.equals(payout.status()) ? payout.failureCode() : null);
        if (STATUS_PAID.equals(payout.status())) {
            row.setPaidAt(samePayout && previousPaidAt != null ? previousPaidAt : paidAt);
        } else {
            row.setPaidAt(null);
        }
        String after = row.getPayoutId() + "|" + row.getPayoutStatus() + "|" + row.getArrivalDate() + "|"
                + row.getFailureCode() + "|" + row.getPaidAt();
        return !before.equals(after) || !samePayout;
    }

    /** Ordre d'un statut au sein d'un même payout. */
    static int rank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "pending" -> 1;
            case "in_transit" -> 2;
            case STATUS_PAID -> 3;
            case STATUS_FAILED, STATUS_CANCELED -> 4;
            default -> 0;
        };
    }

    private void audit(BankPayoutTrackingEntity row, PayoutSnapshot payout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutId", payout.id());
        payload.put("connectedAccountId", row.getConnectedAccountId());
        payload.put("transferId", row.getTransferId());
        payload.put("arrivalDate", String.valueOf(row.getArrivalDate()));
        if (row.getFailureCode() != null) {
            payload.put("failureCode", row.getFailureCode());
        }
        auditService.log("PAYMENT", row.getPaymentId(),
                STATUS_PAID.equals(payout.status()) ? "BANK_PAYOUT_PAID" : "BANK_PAYOUT_FAILED", null, payload);
    }

    /** Un push (et pour un échec une alerte admin) par payout, jamais pour un virement ancien. */
    private void announce(String account, PayoutSnapshot payout, List<BankPayoutTrackingEntity> rows) {
        UUID travelerId = rows.stream().map(BankPayoutTrackingEntity::getTravelerId)
                .filter(java.util.Objects::nonNull).findFirst()
                .orElseGet(() -> userRepository.findByStripeAccountId(account).map(UserEntity::getId).orElse(null));
        String currency = payout.currency() == null ? null : payout.currency().toUpperCase(Locale.ROOT);
        BigDecimal amount = amount(payout.amountMinor(), currency);
        boolean failed = STATUS_FAILED.equals(payout.status());
        if (failed) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("payoutId", payout.id());
            context.put("accountId", account);
            context.put("failureCode", String.valueOf(payout.failureCode()));
            if (travelerId != null) {
                context.put("userId", travelerId.toString());
            }
            adminAlert.raise("STRIPE_PAYOUT_FAILED",
                    "Virement " + payout.id() + " du compte " + account + " vers la banque du voyageur échoué — code: "
                            + payout.failureCode(), context);
        }
        if (travelerId == null || tooOld(payout)) {
            return;
        }
        if (failed) {
            events.publishEvent(new BankPayoutFailedEvent(travelerId, payout.id(), payout.failureCode(), amount, currency));
        } else {
            events.publishEvent(new BankPayoutPaidEvent(travelerId, payout.id(), amount, currency, payout.arrivalDate()));
        }
    }

    private boolean tooOld(PayoutSnapshot payout) {
        return payout.arrivalDate() != null
                && payout.arrivalDate().isBefore(today().minusDays(NOTIFY_MAX_AGE_DAYS));
    }

    static BigDecimal amount(Long minor, String currency) {
        if (minor == null) {
            return null;
        }
        SupportedCurrency known = currency == null ? null : SupportedCurrency.fromCode(currency);
        int scale = known != null ? known.minorUnit() : 2;
        return BigDecimal.valueOf(minor, scale);
    }

    Instant now() {
        return clock.instant();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
