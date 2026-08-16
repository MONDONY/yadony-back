package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.StorageService;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.NegotiationEscrowPort;
import com.yadony.api.requests.NegotiationProperties;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.*;
import com.yadony.api.requests.entity.*;
import com.yadony.api.requests.event.*;
import com.yadony.api.requests.repository.*;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NegotiationService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(NegotiationService.class);


    private final PackageRequestRepository requestRepo;
    private final NegotiationThreadRepository threadRepo;
    private final NegotiationMessageRepository messageRepo;
    private final UserRepository userRepository;
    private final com.yadony.api.matching.AnnouncementRepository announcementRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;
    private final NegotiationProperties negotiationProperties;
    private final CommissionProperties commissionProperties;
    private final CashGatePort cashGatePort;
    private final NegotiationEscrowPort escrowPort;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final CurrencyMatchGuard currencyMatchGuard;
    private final StorageService storageService;
    private final PackageRequestPhotoService photoService;
    private final CommissionRateResolver commissionRateResolver;

    public NegotiationService(PackageRequestRepository requestRepo,
                               NegotiationThreadRepository threadRepo,
                               NegotiationMessageRepository messageRepo,
                               UserRepository userRepository,
                               com.yadony.api.matching.AnnouncementRepository announcementRepo,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               RequestsConfig config,
                               NegotiationProperties negotiationProperties,
                               CommissionProperties commissionProperties,
                               CashGatePort cashGatePort,
                               NegotiationEscrowPort escrowPort,
                               ActiveCurrencyResolver activeCurrencyResolver,
                               CurrencyMatchGuard currencyMatchGuard,
                               StorageService storageService,
                               PackageRequestPhotoService photoService,
                               CommissionRateResolver commissionRateResolver) {
        this.requestRepo = requestRepo;
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.userRepository = userRepository;
        this.announcementRepo = announcementRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
        this.negotiationProperties = negotiationProperties;
        this.commissionProperties = commissionProperties;
        this.cashGatePort = cashGatePort;
        this.escrowPort = escrowPort;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.currencyMatchGuard = currencyMatchGuard;
        this.storageService = storageService;
        this.photoService = photoService;
        this.commissionRateResolver = commissionRateResolver;
    }

    /**
     * Self-reference resolved to the Spring proxy so {@link #checkout} can call
     * the {@code @Transactional} {@link #finalizeAfterPayment} <em>through</em>
     * the proxy: the commit-time {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * (raised when the concurrent webhook finalize wins the {@code version} race)
     * then propagates back to {@code checkout}'s catch instead of bubbling raw to
     * the controller. Lazily injected to avoid a self-referential bean cycle at
     * startup; defaults to {@code this} so unit tests (no Spring context) work.
     */
    private NegotiationService self = this;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy NegotiationService self) {
        this.self = self;
    }

    @Transactional
    public NegotiationThreadResponse start(UUID travelerId, NegotiationStartRequest req) {
        UserEntity traveler = userRepository.findById(travelerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));

        if (traveler.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }

        // Verrou pessimiste : sans lui, un unpublish() concurrent (qui verrouille
        // via findByIdForUpdate) peut être silencieusement annulé par ce start()
        // qui repasserait la demande à NEGOTIATING juste après.
        PackageRequestEntity request = requestRepo.findByIdForUpdate(req.packageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (request.getSenderId().equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/cannot-bid-own-request");
        }

        if (request.getStatus() == PackageRequestStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.GONE, "request/expired");
        }

        if (request.getStatus() == PackageRequestStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found");
        }

        if (request.getStatus() == PackageRequestStatus.ACCEPTED
                || request.getStatus() == PackageRequestStatus.COMPLETED
                || request.getStatus() == PackageRequestStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-finalized");
        }

        if (request.getStatus() != PackageRequestStatus.OPEN
                && request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-open");
        }

        String travelerCurrency = activeCurrencyResolver.resolve(travelerId);
        currencyMatchGuard.assertMatches(request.getCurrency(), travelerCurrency);
        assertPriceWithinBounds(req.proposedPriceEur(), request.getCurrency());

        if (!request.isNegotiable()) {
            if (request.getTargetPriceEur() == null
                || req.proposedPriceEur().compareTo(request.getTargetPriceEur()) != 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "negotiation/firm-price-must-match");
            }
        }

        if (threadRepo.findActiveByPackageRequestIdAndTravelerId(req.packageRequestId(), travelerId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/duplicate-thread");
        }

        long openCount = threadRepo.countByTravelerIdAndStatus(travelerId, NegotiationThreadStatus.OPEN);
        if (openCount >= config.maxOpenThreadsPerTraveler()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/max-open-reached");
        }

        long recent = threadRepo.countCreatedBy(travelerId, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        if (recent >= config.threadsPerMinuteRateLimit()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "negotiation/rate-limit");
        }

        // Trajet obligatoire dès l'offre (cf. spec 2026-08-16) : soit un trajet
        // existant validé, soit la création d'un trajet dédié. Exactement l'un des
        // deux doit être fourni — un record ne peut pas exprimer un XOR en Bean
        // Validation pur, la vérification se fait donc ici.
        if (req.travelerAnnouncementId() != null && req.createDedicatedTrip()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "trip-not-eligible-both");
        }
        if (req.travelerAnnouncementId() == null && !req.createDedicatedTrip()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "trip-required");
        }

        com.yadony.api.matching.AnnouncementEntity linkedTripAnn;
        java.util.Set<PaymentMethod> availableMethods;
        java.time.LocalDate resolvedTravelDate;

        if (req.createDedicatedTrip()) {
            if (req.dedicatedTrip() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "dedicated-trip-invalid");
            }
            java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
            java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
            if (req.dedicatedTrip().departureDate().isBefore(from) || req.dedicatedTrip().departureDate().isAfter(to)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "announcement/date-mismatch");
            }
            availableMethods = computeAvailableMethods(request, traveler);
            assertNonEmptyOrThrow(availableMethods, request.getAcceptedPaymentMethods());
            linkedTripAnn = announcementRepo.save(
                buildDedicatedTripAnnouncement(request, traveler, req.proposedPriceEur(), req.dedicatedTrip()));
            resolvedTravelDate = linkedTripAnn.getDepartureDate();
            auditService.log("ANNOUNCEMENT", linkedTripAnn.getId(), "DEDICATED_TRIP_CREATED_AT_OFFER", travelerId,
                Map.of("packageRequestId", request.getId().toString()));
        } else {
            linkedTripAnn = validateAndFetchExistingTrip(req.travelerAnnouncementId(), travelerId, request);
            availableMethods = computeAvailableMethods(request, traveler);
            assertNonEmptyOrThrow(availableMethods, request.getAcceptedPaymentMethods());
            resolvedTravelDate = linkedTripAnn.getDepartureDate();
        }

        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(req.packageRequestId());
        thread.setTravelerId(travelerId);
        thread.setTravelerAnnouncementId(linkedTripAnn.getId());
        thread.setTravelerTravelDate(resolvedTravelDate);
        thread.setTravelerAvailableKg(req.travelerAvailableKg());
        thread.setStatus(NegotiationThreadStatus.OPEN);
        thread.setCurrency(request.getCurrency());
        thread.setCurrentPriceEur(req.proposedPriceEur());
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        thread.setAvailablePaymentMethods(availableMethods);
        // Code promo saisi par l'expéditeur à la publication de sa demande (étape 3)
        // → appliqué automatiquement au paiement, sans re-saisie (cf. javadoc
        // NegotiationThreadEntity.promoCode).
        thread.setPromoCode(request.getPromoCode());

        NegotiationThreadEntity saved = threadRepo.save(thread);

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            saved.getId(), travelerId, NegotiationMessageKind.PROPOSAL,
            req.proposedPriceEur(), req.body()
        );
        messageRepo.save(msg);

        if (request.getStatus() == PackageRequestStatus.OPEN) {
            request.setStatus(PackageRequestStatus.NEGOTIATING);
            requestRepo.save(request);
        }

        eventPublisher.publishEvent(new NegotiationStartedEvent(
            saved.getId(), saved.getPackageRequestId(),
            request.getSenderId(), travelerId, req.proposedPriceEur()
        ));

        auditService.log("NEGOTIATION_THREAD", saved.getId(), "CREATED", travelerId,
            Map.of("price", req.proposedPriceEur().toString()));

        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        return toResponse(saved, List.of(toMessageResponse(msg)), null, traveler, request, travelerId, senderName, linkedTripAnn);
    }

    @Transactional
    /**
     * Vérifie qu'un prix proposé tient dans les bornes de sa devise.
     *
     * <p>Les DTO ne portent qu'un garde-fou large : une annotation Bean Validation
     * est une constante de compilation et ne peut pas connaître la devise du fil.
     * Le plafond réel se calcule donc ici. Auparavant les DTO imposaient 500 quelle
     * que soit la devise, ce qui plafonnait un voyageur en franc CFA à 0,76 €/kg et
     * lui interdisait de fait toute proposition réaliste.
     */
    private void assertPriceWithinBounds(java.math.BigDecimal price, String currencyCode) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(currencyCode);
        java.math.BigDecimal max = CurrencyBounds.maxNegotiationPrice(currency);
        java.math.BigDecimal min = CurrencyBounds.smallestUnit(currency);
        if (price.compareTo(min) < 0 || price.compareTo(max) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "negotiation/price-out-of-bounds");
        }
    }

    public NegotiationThreadResponse counter(UUID callerId, UUID threadId, NegotiationCounterRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.GONE, "thread/expired");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!request.isNegotiable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "negotiation/counter-not-allowed-firm-price");
        }

        assertPriceWithinBounds(req.proposedPriceEur(), thread.getCurrency());

        UUID senderId = request.getSenderId();
        UUID travelerId = thread.getTravelerId();
        if (!callerId.equals(senderId) && !callerId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        if (thread.getRoundsCount() >= config.maxNegotiationRounds()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/max-rounds-reached");
        }

        List<NegotiationMessageEntity> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/inconsistent-thread");
        }
        NegotiationMessageEntity lastMessage = messages.get(messages.size() - 1);
        if (lastMessage.getFromUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/not-your-turn");
        }

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.COUNTER, req.proposedPriceEur(), req.body());
        messageRepo.save(msg);

        thread.setCurrentPriceEur(req.proposedPriceEur());
        thread.setRoundsCount((short) (thread.getRoundsCount() + 1));
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        UUID toUser = callerId.equals(senderId) ? travelerId : senderId;
        eventPublisher.publishEvent(new NegotiationCounterPostedEvent(
            threadId, msg.getId(), callerId, toUser, req.proposedPriceEur(),
            thread.getRoundsCount().intValue()
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "COUNTER_POSTED", callerId,
            Map.of("price", req.proposedPriceEur().toString(),
                "round", String.valueOf(thread.getRoundsCount())));

        List<NegotiationMessageEntity> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        List<NegotiationMessageResponse> responses = allMsgs.stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        return toResponse(thread, responses, null, traveler, request, callerId, senderName, null);
    }

    @Transactional
    public void reject(UUID callerId, UUID threadId, NegotiationRejectRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/already-finalized");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UUID senderId = request.getSenderId();
        UUID travelerId = thread.getTravelerId();
        if (!callerId.equals(senderId) && !callerId.equals(travelerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        NegotiationMessageEntity msg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.REJECT, null, req.reason());
        messageRepo.save(msg);

        thread.setStatus(NegotiationThreadStatus.REJECTED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);
        reopenRequestWhenNoActiveNegotiation(request);
        softDeleteOrphanedDedicatedTrip(thread.getTravelerAnnouncementId(), request, callerId, threadId,
            "DEDICATED_TRIP_ORPHANED_ON_REJECT");

        auditService.log("NEGOTIATION_THREAD", threadId, "REJECTED", callerId,
            Map.of("reason", req.reason() != null ? req.reason() : ""));
    }

    /**
     * Either participant (sender or traveler) ends the negotiation before payment.
     * Allowed while the thread is still OPEN, AWAITING_TRIP or AWAITING_PAYMENT —
     * once ACCEPTED (paid) the thread can no longer be cancelled this way.
     * If a Stripe escrow hold is in flight (AWAITING_PAYMENT), it is released
     * (idempotent, best-effort) and any orphaned DEDICATED trip announcement
     * created exclusively for this request is soft-deleted, mirroring
     * {@link #refuseTrip}. The other party is notified via
     * {@link NegotiationCancelledEvent}. The linked {@code PackageRequest} is
     * intentionally left untouched — it can still be negotiated with other
     * travelers.
     */
    @Transactional
    public void cancelNegotiation(UUID callerId, UUID threadId, String reason) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        boolean isTraveler = callerId.equals(thread.getTravelerId());
        boolean isSender = callerId.equals(request.getSenderId());
        if (!isTraveler && !isSender) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        NegotiationThreadStatus st = thread.getStatus();
        if (st != NegotiationThreadStatus.OPEN
                && st != NegotiationThreadStatus.AWAITING_TRIP
                && st != NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/not-cancellable");
        }

        // Un hold Stripe en vol n'existe qu'en AWAITING_PAYMENT. Son annulation
        // (side-effect non-transactionnel) NE doit PAS être exécutée inline avant
        // le commit : un rollback ultérieur (contrainte, race, webhook concurrent)
        // voiderait le hold de façon irréversible pendant que la DB revient en
        // arrière (CLAUDE.md règle #18). On délègue donc à un listener paiements
        // AFTER_COMMIT + REQUIRES_NEW via ce drapeau.
        boolean releaseEscrow = (st == NegotiationThreadStatus.AWAITING_PAYMENT);

        // Soft-delete du trajet DÉDIÉ orphelin (créé exclusivement pour cette
        // demande via createDedicatedTrip) — miroir exact de refuseTrip. C'est
        // du travail DB transactionnel, donc il reste inline. Depuis que start()
        // attache le trajet dès la création (Task 4), un trajet dédié peut exister
        // dès OPEN, pas seulement AWAITING_PAYMENT — donc pas de garde de statut ici.
        softDeleteOrphanedDedicatedTrip(thread.getTravelerAnnouncementId(), request, callerId, threadId,
            "DEDICATED_TRIP_ORPHANED_ON_CANCEL");

        thread.setStatus(NegotiationThreadStatus.CANCELLED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);
        reopenRequestWhenNoActiveNegotiation(request);

        UUID otherParty = isSender ? thread.getTravelerId() : request.getSenderId();
        String byName = userRepository.findById(callerId).map(this::buildDisplayName).orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        eventPublisher.publishEvent(new NegotiationCancelledEvent(
            thread.getId(), request.getId(), callerId, otherParty, byName, releaseEscrow));
        auditService.log("NEGOTIATION_THREAD", threadId, "CANCELLED", callerId,
            Map.of("reason", reason == null ? "" : reason));
    }

    private void reopenRequestWhenNoActiveNegotiation(PackageRequestEntity request) {
        if (request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            return;
        }
        boolean hasActiveThread = threadRepo.findByPackageRequestId(request.getId()).stream()
            .anyMatch(thread -> thread.getStatus().isActive());
        if (!hasActiveThread) {
            request.setStatus(PackageRequestStatus.OPEN);
            requestRepo.save(request);
        }
    }

    /**
     * Soft-deletes the DEDICATED trip announcement (created exclusively for this
     * package_request via {@link #createDedicatedTrip}) once it is orphaned — i.e.
     * detached/abandoned before payment. A dedicated trip's {@code availableKg} is
     * always 0 until surplus is opened after payment, so once orphaned nobody can
     * ever book it; without this cleanup it stays {@code ACTIVE} forever, a dead
     * entry in the traveler's "Mes trajets" with {@code reservedKg} stuck.
     *
     * <p>No-op if {@code travelerAnnouncementId} is {@code null}, or if the
     * announcement isn't a dedicated trip for THIS request (i.e. it was linked via
     * {@link #submitTrip} instead — those are real, reusable trips and must never
     * be deleted).
     *
     * <p>Shared by {@link #cancelNegotiation} and {@link #refuseTrip}; each passes
     * its own {@code auditAction} so the audit_log entry keeps its distinct meaning.
     */
    private void softDeleteOrphanedDedicatedTrip(UUID travelerAnnouncementId, PackageRequestEntity request,
                                                  UUID callerId, UUID threadId, String auditAction) {
        if (travelerAnnouncementId == null) {
            return;
        }
        announcementRepo.findById(travelerAnnouncementId).ifPresent(ann -> {
            if (request.getId().equals(ann.getLinkedPackageRequestId())) {
                ann.softDelete();
                announcementRepo.save(ann);
                auditService.log("ANNOUNCEMENT", ann.getId(), auditAction, callerId,
                    Map.of("threadId", threadId.toString()));
            }
        });
    }

    /**
     * Bilateral accept — both the sender AND the traveler can accept the other's counter-offer.
     * <ul>
     *   <li>If the thread already has a trip linked (guaranteed since start(), Task 4) → AWAITING_PAYMENT,
     *       regardless of who accepts.</li>
     *   <li>Otherwise (legacy pre-migration thread without a trip) → AWAITING_TRIP.</li>
     * </ul>
     */
    @Transactional
    public NegotiationThreadResponse accept(UUID callerId, UUID threadId, NegotiationAcceptRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Participant check — sender OU traveler
        if (!callerId.equals(request.getSenderId()) && !callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/already-finalized");
        }

        // On ne peut pas accepter son propre message
        List<NegotiationMessageEntity> allMessages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
        if (allMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/inconsistent-thread");
        }
        NegotiationMessageEntity lastMsg = allMessages.get(allMessages.size() - 1);
        if (lastMsg.getFromUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation/not-your-turn");
        }

        NegotiationMessageEntity acceptMsg = NegotiationMessageEntity.create(
            threadId, callerId, NegotiationMessageKind.ACCEPT, null,
            req == null ? null : req.body());
        messageRepo.save(acceptMsg);

        // Depuis que start() exige un trajet (cf. spec 2026-08-16), tout thread créé
        // après ce déploiement a déjà travelerAnnouncementId non-null : on passe
        // directement à AWAITING_PAYMENT. Le garde-fou sur travelerAnnouncementId
        // reste nécessaire pour les threads pré-migration encore OPEN au déploiement
        // (rares — la migration V210, Task 7, ne traite que les AWAITING_TRIP, pas
        // les OPEN legacy sans trajet).
        NegotiationThreadStatus nextStatus = thread.getTravelerAnnouncementId() != null
            ? NegotiationThreadStatus.AWAITING_PAYMENT
            : NegotiationThreadStatus.AWAITING_TRIP;

        thread.setStatus(nextStatus);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        String auditAction = nextStatus == NegotiationThreadStatus.AWAITING_PAYMENT
            ? "ACCEPT_AWAITING_PAYMENT" : "ACCEPT_AWAITING_TRIP";
        auditService.log("NEGOTIATION_THREAD", threadId, auditAction, callerId,
            Map.of("price", thread.getCurrentPriceEur().toString()));

        if (nextStatus == NegotiationThreadStatus.AWAITING_PAYMENT) {
            eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
                thread.getId(), request.getId(),
                request.getSenderId(), thread.getTravelerId(),
                thread.getCurrentPriceEur(), thread.getTravelerAnnouncementId()
            ));
        } else {
            eventPublisher.publishEvent(new NegotiationAwaitingTripEvent(
                thread.getId(), request.getId(),
                request.getSenderId(), thread.getTravelerId(),
                thread.getCurrentPriceEur()
            ));
        }

        List<NegotiationMessageResponse> responses = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        com.yadony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, responses, null, traveler, request, callerId, senderName, linkedAnn);
    }

    /**
     * Traveler links an existing announcement (or just-created one) to the accepted thread.
     * The announcement must belong to caller and match the package_request corridor + date window.
     * Thread moves to AWAITING_PAYMENT. Sender is notified to checkout.
     */
    @Transactional
    public NegotiationThreadResponse submitTrip(UUID callerId, UUID threadId, NegotiationSubmitTripRequest req) {
        UUID travelerAnnouncementId = req.travelerAnnouncementId();

        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_TRIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-trip");
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UserEntity traveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        java.util.Set<PaymentMethod> available = computeAvailableMethods(request, traveler);
        assertNonEmptyOrThrow(available, request.getAcceptedPaymentMethods());

        com.yadony.api.matching.AnnouncementEntity ann = validateAndFetchExistingTrip(travelerAnnouncementId, callerId, request);

        thread.setTravelerAnnouncementId(travelerAnnouncementId);
        thread.setTravelerTravelDate(ann.getDepartureDate());
        thread.setAvailablePaymentMethods(available);
        thread.setPaymentMethod(null); // l'expéditeur choisit au checkout parmi le SET
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur(), travelerAnnouncementId
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_LINKED", callerId,
            Map.of("announcementId", travelerAnnouncementId.toString(),
                   "availablePaymentMethods", available.toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        return toResponse(thread, allMsgs, null, traveler, request, callerId, senderName, ann);
    }

    /**
     * Le voyageur change le trajet lié tant que la négociation n'est pas encore
     * AWAITING_PAYMENT (paiement possible à tout moment ensuite). Restreint à
     * OPEN : AWAITING_TRIP est l'état du flux legacy (thread rouvert par
     * refuseTrip) et reste géré exclusivement par submitTrip, qui transitionne
     * vers AWAITING_PAYMENT et recalcule availablePaymentMethods — changeTrip
     * ne fait ni l'un ni l'autre, l'autoriser depuis AWAITING_TRIP laisserait
     * le thread bloqué avec un trajet lié mais aucun chemin vers le paiement.
     * Notifie l'expéditeur (et non le voyageur) via
     * {@link com.yadony.api.requests.event.NegotiationTripChangedEvent} :
     * NegotiationAwaitingTripEvent est réservé au cas "aucun trajet encore lié,
     * le voyageur doit choisir" et notifie le voyageur — sémantique inverse ici.
     */
    @Transactional
    public NegotiationThreadResponse changeTrip(UUID callerId, UUID threadId, NegotiationChangeTripRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "negotiation-trip-locked");
        }
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UUID previousAnnouncementId = thread.getTravelerAnnouncementId();
        com.yadony.api.matching.AnnouncementEntity ann =
            validateAndFetchExistingTrip(req.travelerAnnouncementId(), callerId, request);

        thread.setTravelerAnnouncementId(ann.getId());
        thread.setTravelerTravelDate(ann.getDepartureDate());
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        // Si le trajet précédent était un trajet dédié créé pour CETTE demande,
        // il devient orphelin une fois détaché — même nettoyage que cancel/reject.
        if (previousAnnouncementId != null && !previousAnnouncementId.equals(ann.getId())) {
            softDeleteOrphanedDedicatedTrip(previousAnnouncementId, request, callerId, threadId,
                "DEDICATED_TRIP_ORPHANED_ON_TRIP_CHANGE");
        }

        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_CHANGED", callerId,
            Map.of("announcementId", ann.getId().toString()));

        eventPublisher.publishEvent(new com.yadony.api.requests.event.NegotiationTripChangedEvent(
            thread.getId(), request.getId(), request.getSenderId(), thread.getTravelerId(), ann.getId()));

        List<NegotiationMessageResponse> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName).orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        return toResponse(thread, messages, null, traveler, request, callerId, senderName, ann);
    }

    /**
     * Traveler creates a brand-new "dedicated trip" announcement that is linked
     * exclusively to this package_request. Used when none of the traveler's
     * existing trips match the corridor/date.
     *
     * Locked from the package_request: corridor, weightKg (= availableKg/totalKg),
     * transportMode, agreed price (= thread.currentPriceEur, stored as pricePerKg
     * = currentPrice / weightKg since the trip is private and never priced again).
     *
     * Editable by the traveler: departureDate (must fall in the tolerance window),
     * times, addresses, description, content type lists.
     *
     * On success the thread transitions AWAITING_TRIP → AWAITING_PAYMENT and the
     * sender is notified to checkout, exactly like {@link #submitTrip}.
     */

    /**
     * Valide qu'une announcement existante peut porter ce package_request :
     * appartient à l'appelant, ACTIVE, capacité suffisante, corridor et fenêtre
     * de dates compatibles. Partagée par {@link #submitTrip} (thread
     * AWAITING_TRIP) et {@link #start} (trajet fourni dès la première offre) et
     * par le futur endpoint de changement de trajet.
     */
    com.yadony.api.matching.AnnouncementEntity validateAndFetchExistingTrip(
            UUID travelerAnnouncementId, UUID callerId, PackageRequestEntity request) {
        com.yadony.api.matching.AnnouncementEntity ann = announcementRepo.findById(travelerAnnouncementId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "announcement/not-found"));
        if (!ann.getTravelerId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "announcement/not-yours");
        }
        if (ann.getStatus() != com.yadony.api.matching.AnnouncementStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "announcement/not-active");
        }
        if (ann.getAvailableKg().compareTo(request.getWeightKg()) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/insufficient-capacity");
        }
        if (!cityKey(ann.getDepartureCity()).equals(cityKey(request.getDepartureCity()))
            || !cityKey(ann.getArrivalCity()).equals(cityKey(request.getArrivalCity()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/corridor-mismatch");
        }
        java.time.LocalDate annDate = ann.getDepartureDate();
        java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
        java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
        if (annDate.isBefore(from) || annDate.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/date-mismatch");
        }
        return ann;
    }

    /**
     * Construit (sans sauvegarder) l'AnnouncementEntity d'un trajet dédié à un
     * seul package_request : corridor, poids et prix dérivés et verrouillés,
     * capacité réservée intégralement au sender jusqu'à openSurplus(). Partagée
     * par {@link #createDedicatedTrip} (thread AWAITING_TRIP existant) et
     * {@link #start} (création dédiée dès la première offre).
     */
    private com.yadony.api.matching.AnnouncementEntity buildDedicatedTripAnnouncement(
            PackageRequestEntity request, UserEntity traveler, BigDecimal agreedPriceEur,
            NegotiationCreateDedicatedTripRequest req) {
        com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
        ann.setTravelerId(traveler.getId());
        ann.setTravelerIsPro(traveler.isProAccount());
        ann.setDepartureCity(request.getDepartureCity());
        ann.setArrivalCity(request.getArrivalCity());
        ann.setDepartureDate(req.departureDate());
        ann.setDepartureTime(req.departureTime());
        ann.setArrivalTime(req.arrivalTime());
        java.time.LocalTime handoverEnd =
            req.departureTime() != null ? req.departureTime() : java.time.LocalTime.of(23, 59);
        ann.setHandoverDeadline(java.time.LocalDateTime.of(req.departureDate(), handoverEnd));
        ann.setPickupAddressLabel(req.pickupAddress().label());
        ann.setPickupLat(BigDecimal.valueOf(req.pickupAddress().lat()));
        ann.setPickupLng(BigDecimal.valueOf(req.pickupAddress().lng()));
        ann.setDeliveryAddressLabel(req.deliveryAddress().label());
        ann.setDeliveryLat(BigDecimal.valueOf(req.deliveryAddress().lat()));
        ann.setDeliveryLng(BigDecimal.valueOf(req.deliveryAddress().lng()));
        ann.setAvailableKg(java.math.BigDecimal.ZERO);
        ann.setTotalKg(request.getWeightKg());
        ann.setReservedKg(request.getWeightKg());
        BigDecimal derivedPricePerKg = agreedPriceEur
            .divide(request.getWeightKg(), 2, RoundingMode.HALF_UP);
        if (derivedPricePerKg.signum() <= 0) {
            derivedPricePerKg = new BigDecimal("0.01");
        }
        ann.setPricePerKg(derivedPricePerKg);
        ann.setTransportMode(request.getTransportMode());
        ann.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
        ann.setDescription(req.description());
        ann.setAcceptedContentTypes(req.acceptedContentTypes() != null
                ? com.yadony.api.config.ContentCategoryNormalizer.normalizeList(req.acceptedContentTypes())
                : new ArrayList<>());
        ann.setRefusedTypes(req.refusedTypes() != null
                ? com.yadony.api.config.ContentCategoryNormalizer.normalizeList(req.refusedTypes())
                : new ArrayList<>());
        ann.setLinkedPackageRequestId(request.getId());
        ann.setReservedSenderId(request.getSenderId());
        return ann;
    }

    @Transactional
    public NegotiationThreadResponse createDedicatedTrip(UUID callerId, UUID threadId,
                                                          NegotiationCreateDedicatedTripRequest req) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_TRIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-trip");
        }

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        UserEntity traveler = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        java.util.Set<PaymentMethod> available = computeAvailableMethods(request, traveler);
        assertNonEmptyOrThrow(available, request.getAcceptedPaymentMethods());

        // Validate the chosen date falls within the sender's tolerance window.
        java.time.LocalDate from = request.getDesiredDate().minusDays(request.getDateToleranceDays());
        java.time.LocalDate to = request.getDesiredDate().plusDays(request.getDateToleranceDays());
        if (req.departureDate().isBefore(from) || req.departureDate().isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "announcement/date-mismatch");
        }

        // Build the dedicated announcement with all locked fields derived server-side.
        com.yadony.api.matching.AnnouncementEntity ann =
            buildDedicatedTripAnnouncement(request, traveler, thread.getCurrentPriceEur(), req);

        com.yadony.api.matching.AnnouncementEntity savedAnn = announcementRepo.save(ann);

        // Link the dedicated trip to the thread and transition to AWAITING_PAYMENT.
        thread.setTravelerAnnouncementId(savedAnn.getId());
        thread.setTravelerTravelDate(savedAnn.getDepartureDate());
        thread.setAvailablePaymentMethods(available);
        thread.setPaymentMethod(null); // l'expéditeur choisit au checkout parmi le SET
        thread.setStatus(NegotiationThreadStatus.AWAITING_PAYMENT);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        eventPublisher.publishEvent(new NegotiationAwaitingPaymentEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur(), savedAnn.getId()
        ));
        auditService.log("NEGOTIATION_THREAD", threadId, "DEDICATED_TRIP_CREATED", callerId,
            Map.of("announcementId", savedAnn.getId().toString(),
                   "linkedPackageRequestId", request.getId().toString(),
                   "availablePaymentMethods", available.toString()));

        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        return toResponse(thread, allMsgs, null, traveler, request, callerId, senderName, savedAnn);
    }

    /**
     * Sender confirms payment for an AWAITING_PAYMENT thread.
     *
     * <p>For a STRIPE (or any non-CASH) thread this finalizes immediately, via
     * {@link #sealAcceptedThread}: thread → ACCEPTED, package_request → ACCEPTED,
     * all competing OPEN/AWAITING_TRIP/AWAITING_PAYMENT/AWAITING_COMMISSION threads
     * on the same request → AUTO_REJECTED, payment_intent_id stored on thread.
     *
     * <p>For a CASH thread nothing is sealed here — Yadony hasn't collected its
     * commission yet, and the traveler can still back out. The thread moves to
     * {@code AWAITING_COMMISSION} instead; the package_request stays OPEN and
     * competing threads stay alive until the traveler settles the commission
     * ({@code settleCommission}, Task 5) or the deadline expires.
     *
     * Currently this is a synchronous placeholder — the real Stripe escrow call
     * is wired separately in {@code PaymentService.createNegotiationEscrow} (Phase 3).
     * Caller passes the paymentIntentId returned by Stripe (or a placeholder for now).
     */
    @Transactional
    public NegotiationThreadResponse finalizeAfterPayment(UUID callerId, UUID threadId, String paymentIntentId) {
        // Trusted webhook finalize: the PaymentIntent was already verified by the
        // signed Stripe webhook (amount_capturable_updated) before reaching here,
        // so no escrow re-verification is performed on this path.
        return finalizeInternal(callerId, threadId, paymentIntentId, null, false);
    }

    /**
     * Variante avec mode de paiement finalisé par l'expéditeur (parmi ceux
     * acceptés par la demande). {@code chosenMethod == null} → on garde le mode
     * déjà porté par le thread (choix du voyageur à la liaison du trajet).
     *
     * <p>Point d'entrée du {@code /checkout} synchrone (NON fiable) : pour les
     * méthodes online (STRIPE…), l'escrow est vérifié auprès de Stripe via
     * {@link NegotiationEscrowPort} avant toute finalisation.
     */
    @Transactional
    public NegotiationThreadResponse finalizeAfterPayment(UUID callerId, UUID threadId,
                                                          String paymentIntentId, PaymentMethod chosenMethod) {
        return finalizeInternal(callerId, threadId, paymentIntentId, chosenMethod, true);
    }

    /**
     * Synchronous {@code POST /checkout} entry point — <strong>idempotent</strong>
     * against the concurrent Stripe webhook finalize
     * ({@link NegotiationPaymentListener}).
     *
     * <p>When the sender pays, Stripe fires {@code amount_capturable_updated}
     * (→ webhook finalize) <em>and</em> the app calls this {@code /checkout} at
     * nearly the same instant. Both finalize the SAME thread. The {@code @Version}
     * guard on {@link com.yadony.api.requests.entity.NegotiationThreadEntity} makes
     * the loser's commit fail with an
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}; a
     * read that lands just after the webhook flipped the status instead hits the
     * {@code thread/not-awaiting-payment} 409. In both cases the payment actually
     * <em>succeeded</em> — surfacing a 409 to the sender ("La ressource a été
     * modifiée simultanément") is wrong. We resolve the race: if the thread is now
     * {@code ACCEPTED}, return it as success.
     *
     * <p>The webhook path swallows the same loss silently (logs only); this is the
     * user-facing twin. Calls {@link #finalizeAfterPayment} via {@code self} (the
     * Spring proxy) so the transaction commits inside this method and the
     * commit-time optimistic-lock exception is catchable here.
     *
     * <p>Same race for CASH, different resting state: a concurrent finalize can win
     * the {@code @Version} race or land just after the thread flipped to
     * {@code AWAITING_COMMISSION} — that transition is this method's own success
     * state (nothing is sealed yet), so it is treated as idempotent success exactly
     * like {@code ACCEPTED} is for the card path. See {@link #resolveConcurrentCheckout}.
     */
    public NegotiationThreadResponse checkout(UUID callerId, UUID threadId,
                                              String paymentIntentId, PaymentMethod chosenMethod) {
        try {
            return self.finalizeAfterPayment(callerId, threadId, paymentIntentId, chosenMethod);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException race) {
            // Concurrent webhook finalize won the version race.
            return resolveConcurrentCheckout(callerId, threadId, race);
        } catch (ResponseStatusException ex) {
            // Webhook may have flipped the status before we even read it.
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                return resolveConcurrentCheckout(callerId, threadId, ex);
            }
            throw ex;
        }
    }

    /**
     * Returns the finalized thread when a concurrent webhook already accepted it, or
     * when a concurrent CASH finalize already suspended it in
     * {@code AWAITING_COMMISSION} — both idempotent successes, nothing left to do —
     * otherwise rethrows the original conflict — the thread is in a genuinely
     * unexpected state and the caller must hear about it.
     */
    private NegotiationThreadResponse resolveConcurrentCheckout(UUID callerId, UUID threadId,
                                                                RuntimeException original) {
        NegotiationThreadResponse current = self.getById(callerId, threadId);
        if (current.status() == NegotiationThreadStatus.ACCEPTED
                || current.status() == NegotiationThreadStatus.AWAITING_COMMISSION) {
            return current;
        }
        throw original;
    }

    private NegotiationThreadResponse finalizeInternal(UUID callerId, UUID threadId,
                                                       String paymentIntentId, PaymentMethod chosenMethod,
                                                       boolean verifyEscrow) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        // Idempotent: the synchronous /checkout and the Stripe webhook both finalize the same
        // payment. The first already accepted this thread with THIS exact PaymentIntent — return
        // the finalized state instead of a 409. Re-finalizing would re-publish
        // PackageRequestAcceptedEvent (duplicate bid/QR/tracking) and, on the webhook path,
        // surfaces a "409 + UnexpectedRollbackException". A genuine bad state (REJECTED, or a
        // different PaymentIntent) still falls through to the 409 below.
        if (thread.getStatus() == NegotiationThreadStatus.ACCEPTED
                && paymentIntentId != null
                && paymentIntentId.equals(thread.getPaymentIntentId())) {
            return buildFinalizedResponse(thread, request, callerId, paymentIntentId);
        }
        // Idempotent (CASH) : le premier appel a déjà suspendu ce thread en
        // AWAITING_COMMISSION (double tap de l'expéditeur, relance après timeout
        // réseau). Rien n'est scellé à ce stade donc il n'y a pas de PaymentIntent à
        // comparer comme pour la carte — le statut du thread suffit à prouver que le
        // premier appel a déjà réussi. Sans ce garde-fou, le second appel retombe sur
        // la 409 ci-dessous et l'expéditeur voit une erreur pour une action qui a
        // pourtant fonctionné.
        if (thread.getStatus() == NegotiationThreadStatus.AWAITING_COMMISSION
                && thread.getPaymentMethod() == PaymentMethod.CASH) {
            return buildFinalizedResponse(thread, request, callerId, paymentIntentId);
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-payment");
        }

        // Résolution du mode final. L'expéditeur choisit au checkout (chosenMethod).
        // Sur le webhook Stripe (chosenMethod null), amount_capturable_updated ne se produit
        // que pour un escrow CARTE → le mode est STRIPE par définition ; thread.paymentMethod
        // peut être null (modèle SET : le voyageur ne fixe plus de mode au trip-linking).
        PaymentMethod method = chosenMethod != null
            ? chosenMethod
            : (thread.getPaymentMethod() != null ? thread.getPaymentMethod() : PaymentMethod.STRIPE);

        // Le mode retenu doit appartenir au SET fournissable (null = thread legacy → on
        // s'appuie seulement sur la validation "accepté par la demande" ci-dessous).
        java.util.Set<PaymentMethod> available = thread.getAvailablePaymentMethods();
        if (available != null && !available.contains(method)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "payment-method/not-in-available-set");
        }
        // Défense en profondeur : reste dans les méthodes acceptées par la demande.
        if (!request.getAcceptedPaymentMethods().contains(method)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "payment-method/not-accepted");
        }

        // Bascule vers CASH : si un hold CARTE est en vol (l'expéditeur avait initié un escrow
        // Stripe puis choisi cash), on l'annule pour ne pas le laisser orphelin. STRIPE→STRIPE
        // n'est PAS une bascule (thread.paymentMethod est null jusqu'ici sous le modèle SET),
        // donc on ne touche jamais un escrow carte valide.
        if (method == PaymentMethod.CASH) {
            if (!escrowPort.releaseEscrowForMethodSwitch(threadId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "payment-method/escrow-release-failed");
            }
        }
        thread.setPaymentMethod(method);

        if (request.getRecipientName() == null || request.getRecipientPhone() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "request/details-incomplete");
        }

        // STRIPE (online /checkout non fiable uniquement — jamais le webhook, déjà
        // vérifié par Stripe) : ne JAMAIS faire confiance au paymentIntentId fourni
        // par le client. On confirme auprès de Stripe qu'il s'agit d'un escrow réel
        // et autorisé (requires_capture) lié à CE thread avant de finaliser. Ferme
        // le bypass où un expéditeur finalisait une expédition (voyageur engagé +
        // QR + tracking) sans avoir payé. CASH n'a aucun escrow à vérifier ici —
        // son sort se joue juste en dessous.
        if (thread.getPaymentMethod() != PaymentMethod.CASH && verifyEscrow) {
            if (!escrowPort.verifyNegotiationEscrow(threadId, paymentIntentId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment/escrow-not-verified");
            }
        }

        if (thread.getPaymentMethod() == PaymentMethod.CASH) {
            // Un accord en espèces ne scelle rien : Yadony n'a pas encore encaissé sa
            // commission, et le voyageur peut encore renoncer. La demande reste donc
            // ouverte et les offres concurrentes vivantes, jusqu'au règlement ou à
            // l'expiration du délai.
            thread.setStatus(NegotiationThreadStatus.AWAITING_COMMISSION);
            thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
            threadRepo.save(thread);
            BigDecimal commission = PriceBreakdown
                .fromNet(thread.getCurrentPriceEur(), commissionProperties.rate()).commission();
            LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusMinutes(negotiationProperties.commissionWindowMinutes());
            eventPublisher.publishEvent(new NegotiationCommissionPendingEvent(
                thread.getId(), request.getId(), thread.getTravelerId(),
                request.getSenderId(), commission, thread.getCurrency(), expiresAt));
            auditService.log("NEGOTIATION_THREAD", thread.getId(), "AWAITING_COMMISSION", callerId,
                Map.of("commission", commission.toPlainString()));
        } else {
            sealAcceptedThread(thread, request, callerId, paymentIntentId);
        }

        return buildFinalizedResponse(thread, request, callerId, paymentIntentId);
    }

    /**
     * Scelle définitivement un accord : c'est ici que la demande se ferme, que les
     * offres concurrentes tombent et que le colis est matérialisé. Appelée par le
     * paiement carte de l'expéditeur, et par le règlement de la commission du
     * voyageur pour les accords en espèces.
     */
    private void sealAcceptedThread(NegotiationThreadEntity thread, PackageRequestEntity request,
                                    UUID callerId, String paymentIntentId) {
        thread.setPaymentIntentId(paymentIntentId);
        thread.setStatus(NegotiationThreadStatus.ACCEPTED);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        // Trajet dédié → le voyageur peut désormais ouvrir sa capacité restante au public.
        UUID annId = thread.getTravelerAnnouncementId();
        if (annId != null) {
            announcementRepo.findById(annId).ifPresent(ann -> {
                if (ann.getLinkedPackageRequestId() != null) {
                    ann.setSurplusEligible(true);
                    announcementRepo.save(ann);
                }
            });
        }

        request.setStatus(PackageRequestStatus.ACCEPTED);
        requestRepo.save(request);

        // AWAITING_COMMISSION balayé au même titre que les autres statuts en attente :
        // la demande reste disponible tant qu'un accord cash n'est pas scellé, donc un
        // autre voyageur peut très bien avoir conclu entre-temps. Sans ce statut ici,
        // un thread cash resterait AWAITING_COMMISSION sur une demande déjà emportée —
        // le voyageur perdant ne serait jamais prévenu, son trajet dédié resterait
        // bloqué, et il pourrait tenter de régler la commission d'une demande qui n'est
        // plus libre (Task 5 s'appuie sur ce statut pour refuser ce règlement).
        threadRepo.findByPackageRequestId(request.getId()).stream()
            .filter(t -> !t.getId().equals(thread.getId())
                      && (t.getStatus() == NegotiationThreadStatus.OPEN
                          || t.getStatus() == NegotiationThreadStatus.AWAITING_TRIP
                          || t.getStatus() == NegotiationThreadStatus.AWAITING_PAYMENT
                          || t.getStatus() == NegotiationThreadStatus.AWAITING_COMMISSION))
            .forEach(t -> {
                t.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
                t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
                threadRepo.save(t);
                softDeleteOrphanedDedicatedTrip(t.getTravelerAnnouncementId(), request, callerId, t.getId(),
                    "DEDICATED_TRIP_ORPHANED_ON_AUTO_REJECT");
                auditService.log("NEGOTIATION_THREAD", t.getId(), "AUTO_REJECTED", callerId,
                    Map.of("reason", "competing-accepted",
                        "winningThreadId", thread.getId().toString()));
            });

        eventPublisher.publishEvent(new PackageRequestAcceptedEvent(
            thread.getId(), request.getId(), request.getSenderId(),
            thread.getTravelerId(), thread.getCurrentPriceEur(),
            thread.getTravelerAnnouncementId(),
            request.getWeightKg(),
            request.getDescription(),
            request.getContentCategory(),
            paymentIntentId,
            request.getRecipientName(),
            request.getRecipientPhone(),
            request.getDisclaimerSignedAt(),
            request.getDisclaimerSignedIp(),
            thread.getPaymentMethod(),
            photoService.objectKeys(request.getId()),
            thread.getCommissionChargedVia(),
            thread.getPromoCode(),
            thread.getCommissionRate()
        ));
        // paymentIntentId est null pour un accord cash scellé par settleCommission/
        // confirmCommission (aucun escrow Stripe pour ce fil) — Map.of() rejette les
        // valeurs null, d'où la substitution par "" (même convention que ailleurs
        // dans cette classe, cf. cancelNegotiation/reject).
        auditService.log("NEGOTIATION_THREAD", thread.getId(), "ACCEPTED", callerId,
            Map.of("price", thread.getCurrentPriceEur().toString(),
                "paymentIntentId", paymentIntentId != null ? paymentIntentId : ""));
        auditService.log("PACKAGE_REQUEST", request.getId(), "ACCEPTED", callerId,
            Map.of("threadId", thread.getId().toString()));
    }

    /**
     * Builds the {@link NegotiationThreadResponse} for a finalized (ACCEPTED) thread.
     * Shared by the nominal finalize path and the idempotent early-return so both produce
     * the exact same response shape.
     */
    private NegotiationThreadResponse buildFinalizedResponse(NegotiationThreadEntity thread,
                                                             PackageRequestEntity request,
                                                             UUID callerId, String paymentIntentId) {
        List<NegotiationMessageResponse> allMsgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(thread.getId())
            .stream().map(this::toMessageResponse).toList();
        UserEntity finalTraveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        com.yadony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, allMsgs, paymentIntentId, finalTraveler, request, callerId, senderName, linkedAnn);
    }

    /**
     * Le voyageur règle la commission Yadony et emporte ainsi la demande. C'est ce
     * règlement qui scelle l'accord : tant qu'il n'a pas eu lieu, la demande reste
     * ouverte et un autre voyageur peut la conclure.
     *
     * <p>La course, traitée avec soin : la demande reste disponible tant qu'aucun
     * accord cash n'est scellé (elle passe en {@code NEGOTIATING} dès la première
     * offre, jamais en {@code OPEN} à ce stade en production), donc plusieurs
     * threads peuvent être {@code AWAITING_COMMISSION} en même temps sur la même
     * demande, et l'expéditeur peut même en conclure un autre entre-temps. Le
     * premier voyageur qui règle l'emporte. La demande est donc vérifiée encore
     * disponible AVANT tout débit : débiter puis découvrir qu'elle est prise
     * obligerait à rembourser un voyageur qui n'a rien à se reprocher.
     *
     * <p>Verrou pessimiste ({@link PackageRequestRepository#findByIdForUpdate})
     * plutôt qu'une simple lecture : {@code PackageRequestEntity} ne porte aucun
     * {@code @Version}, donc sans ce verrou, deux voyageurs tous deux {@code
     * AWAITING_COMMISSION} sur la même demande peuvent tous deux lire "disponible"
     * avant que l'un ou l'autre n'ait débité, débiter chacun, puis sceller chacun
     * — deux fils {@code ACCEPTED}, deux colis matérialisés pour un seul colis
     * physique. Le verrou sérialise : le second lecteur bloque jusqu'au commit du
     * premier, puis relit "déjà pris" avant tout débit. Même mécanisme que {@link
     * #start}.
     */
    @Transactional
    public AcceptBidResponse settleCommission(UUID callerId, UUID threadId, CommissionSource source) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_COMMISSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-commission");
        }
        PackageRequestEntity request = requestRepo.findByIdForUpdate(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Garde de course : ne jamais débiter pour une demande déjà emportée par un
        // autre voyageur. OPEN et NEGOTIATING sont tous deux "encore disponibles" —
        // exiger OPEN seul refuserait tous les règlements en production, la demande
        // étant déjà passée en NEGOTIATING dès la toute première offre (start()).
        if (request.getStatus() != PackageRequestStatus.OPEN
                && request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }

        AcceptBidResponse resp = cashGatePort.settleNegotiationCommission(
            thread.getTravelerId(), request.getSenderId(), threadId, thread.getCurrentPriceEur(), source);

        if (resp.status() == AcceptanceStatusDto.ACCEPTED) {
            sealAcceptedThread(thread, request, callerId, null);
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_SETTLED", callerId,
                Map.of("via", String.valueOf(thread.getCommissionChargedVia())));
        }
        return resp;
    }

    /**
     * Confirme un règlement de commission passé par une authentification 3D
     * Secure : relit le PaymentIntent auprès de Stripe (via {@link CashGatePort},
     * {@code CashCommissionService.confirmCommissionAcceptance} en miroir pour le
     * flux classique) et scelle l'accord si Stripe confirme "succeeded". Ne
     * rappelle jamais {@link #settleCommission} : la clé d'idempotence Stripe
     * rejouerait la réponse "authentification requise" en boucle et le voyageur ne
     * pourrait jamais aboutir.
     *
     * <p>La 3DS est asynchrone : le voyageur peut mettre plusieurs secondes (ou
     * plus, entre deux ouvertures d'app) à la compléter dans son app bancaire,
     * pendant lesquelles un concurrent peut très bien avoir scellé la même
     * demande. Deux issues à traiter sans perdre l'argent du voyageur :
     * <ul>
     *   <li>ce thread a déjà scellé par le passé (double appel) → succès
     *       idempotent, rien à rembourser, c'est LUI qui a gagné ;</li>
     *   <li>un AUTRE thread a scellé entre temps (ce thread n'est plus {@code
     *       AWAITING_COMMISSION}, ou la demande n'est plus disponible malgré le
     *       verrou) → la 3DS a pu réussir quand même : on relit Stripe et on
     *       rembourse plutôt que de laisser Yadony encaisser une commission sans
     *       contrepartie, et le voyageur reçoit "place déjà prise", pas une
     *       erreur technique de concurrence.
     * </ul>
     */
    @Transactional
    public ConfirmAcceptanceResponse confirmCommission(UUID callerId, UUID threadId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        if (!callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (thread.getStatus() == NegotiationThreadStatus.ACCEPTED) {
            // Idempotent : CE thread a déjà scellé l'accord (double appel/relance).
            // Rien à refaire, rien à rembourser — c'est lui qui a gagné.
            return ConfirmAcceptanceResponse.ok();
        }
        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_COMMISSION) {
            refundIfChargedAfterLoss(callerId, threadId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }

        // Même verrou pessimiste que settleCommission, pour la même raison : sans
        // lui, cette confirmation pourrait sceller une demande déjà emportée par un
        // concurrent pendant l'attente de la 3DS.
        PackageRequestEntity request = requestRepo.findByIdForUpdate(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (request.getStatus() != PackageRequestStatus.OPEN
                && request.getStatus() != PackageRequestStatus.NEGOTIATING) {
            refundIfChargedAfterLoss(callerId, threadId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }

        ConfirmAcceptanceResponse resp = cashGatePort.confirmNegotiationCommission(callerId, threadId);
        if (resp.accepted()) {
            sealAcceptedThread(thread, request, callerId, null);
            auditService.log("NEGOTIATION_THREAD", threadId, "CASH_COMMISSION_SETTLED", callerId,
                Map.of("via", String.valueOf(thread.getCommissionChargedVia())));
        }
        return resp;
    }

    /**
     * Rembourse une commission qui a fini par être débitée (3DS aboutie après
     * coup, côté Stripe) alors que la place est déjà partie à un concurrent —
     * jamais laisser Yadony encaisser pour un accord qui n'a pas eu lieu. No-op
     * silencieux si rien n'a été débité par carte (le port relit Stripe lui-même
     * pour trancher, jamais d'exception qui remonte).
     */
    private void refundIfChargedAfterLoss(UUID travelerId, UUID threadId) {
        cashGatePort.refundNegotiationCommissionIfCharged(travelerId, threadId);
    }

    /**
     * Traveler opens the remaining (surplus) capacity of a DEDICATED trip to the
     * public, AFTER the negotiating sender has paid (thread status ACCEPTED).
     *
     * <p>Capacity model: the reserved part stays locked. We reuse the public
     * {@code availableKg}/{@code pricePerKg} columns for the surplus so the
     * existing search / card / bid flow works unchanged:
     * <ul>
     *   <li>{@code availableKg = surplusKg}</li>
     *   <li>{@code totalKg = reservedKg + surplusKg}</li>
     *   <li>{@code pricePerKg = surplusPricePerKg}</li>
     *   <li>{@code surplusPublished = true} (irreversible)</li>
     * </ul>
     *
     * <p>Lives here (requests/) — not in matching/ — because it needs both the
     * announcement and the negotiation thread; matching/ must never read the
     * thread. Both repos are already injected, so no new cross-package coupling.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "announcements-search", allEntries = true)
    public void openSurplus(UUID callerId, UUID announcementId,
                            BigDecimal surplusKg, BigDecimal pricePerKg) {
        com.yadony.api.matching.AnnouncementEntity ann = announcementRepo.findById(announcementId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "announcement/not-found"));
        if (!callerId.equals(ann.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-traveler");
        }
        if (ann.getLinkedPackageRequestId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "surplus/not-dedicated");
        }
        if (ann.isSurplusPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "surplus/already-open");
        }
        if (surplusKg == null || surplusKg.compareTo(BigDecimal.ONE) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "surplus/invalid-kg");
        }
        if (pricePerKg == null || pricePerKg.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "surplus/invalid-price");
        }
        threadRepo.findByTravelerAnnouncementIdAndStatus(announcementId, NegotiationThreadStatus.ACCEPTED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "surplus/negotiation-not-accepted"));

        ann.setAvailableKg(surplusKg);
        ann.setTotalKg(ann.getReservedKg().add(surplusKg));
        ann.setPricePerKg(pricePerKg);
        ann.setSurplusPublished(true);
        announcementRepo.save(ann);

        auditService.log("ANNOUNCEMENT", announcementId, "SURPLUS_OPENED", callerId,
            Map.of("surplusKg", surplusKg.toPlainString(), "pricePerKg", pricePerKg.toPlainString()));
    }

    @Transactional
    public NegotiationThreadResponse refuseTrip(UUID callerId, UUID threadId, String reason) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // Seul l'expéditeur peut refuser un trajet lié
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/sender-only");
        }

        if (thread.getStatus() != NegotiationThreadStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/not-awaiting-payment");
        }

        if (thread.getTravelerAnnouncementId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "thread/no-trip-linked");
        }
        UUID oldAnnouncementId = thread.getTravelerAnnouncementId();

        // Effacer le trajet lié et repasser en AWAITING_TRIP
        thread.setTravelerAnnouncementId(null);
        thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
        thread.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        threadRepo.save(thread);

        // Si le trajet détaché est un trajet DÉDIÉ créé exclusivement pour cette
        // demande (via createDedicatedTrip — linkedPackageRequestId == request.id ;
        // jamais le cas pour un trajet existant lié via submitTrip), il n'a plus
        // aucune utilité une fois détaché : availableKg=0 pour toujours, personne
        // ne pourra jamais le réserver. Sans ce nettoyage il reste ACTIVE pour
        // toujours, orphelin dans « Mes trajets » du voyageur avec reservedKg figé.
        softDeleteOrphanedDedicatedTrip(oldAnnouncementId, request, callerId, threadId,
            "DEDICATED_TRIP_ORPHANED_ON_REFUSAL");

        // Persister la raison du refus comme message visible dans le thread
        if (reason != null && !reason.isBlank()) {
            NegotiationMessageEntity refusalMsg = new NegotiationMessageEntity();
            refusalMsg.setThreadId(threadId);
            refusalMsg.setFromUserId(callerId);
            refusalMsg.setKind(NegotiationMessageKind.REJECT);
            refusalMsg.setBody(reason);
            messageRepo.save(refusalMsg);
        }

        auditService.log("NEGOTIATION_THREAD", threadId, "TRIP_REFUSED", callerId,
            Map.of("reason", reason != null ? reason : "sender-refused"));

        // Notifier le voyageur via l'event existant NegotiationAwaitingTripEvent
        eventPublisher.publishEvent(new NegotiationAwaitingTripEvent(
            thread.getId(), request.getId(),
            request.getSenderId(), thread.getTravelerId(),
            thread.getCurrentPriceEur()
        ));

        List<NegotiationMessageResponse> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity traveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        // linkedAnn est null car on vient de clear le travelerAnnouncementId
        return toResponse(thread, messages, null, traveler, request, callerId, senderName, null);
    }

    /**
     * The waiting party nudges the party who must act, reminding them to
     * respond. Guards MIRROR the {@code canNudge} eligibility computed in
     * {@link #toResponse} so the endpoint and the button agree on when a
     * nudge is allowed.
     */
    @Transactional
    public NegotiationThreadResponse nudge(UUID callerId, UUID threadId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        boolean isTraveler = callerId.equals(thread.getTravelerId());
        boolean isSender = callerId.equals(request.getSenderId());
        if (!isTraveler && !isSender) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }
        if (thread.getStatus() != NegotiationThreadStatus.OPEN
                && thread.getStatus() != NegotiationThreadStatus.AWAITING_TRIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "nudge/not-active");
        }

        // Qui doit agir (la cible de la relance) ? En AWAITING_TRIP : le voyageur.
        // En OPEN : le destinataire du dernier message (l'autre que l'émetteur).
        UUID mustActUserId;
        if (thread.getStatus() == NegotiationThreadStatus.AWAITING_TRIP) {
            mustActUserId = thread.getTravelerId();
        } else {
            List<NegotiationMessageEntity> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
            if (messages.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "nudge/not-active");
            }
            UUID lastFrom = messages.get(messages.size() - 1).getFromUserId();
            mustActUserId = lastFrom.equals(thread.getTravelerId())
                ? request.getSenderId() : thread.getTravelerId();
        }
        // Le caller doit être la partie qui ATTEND (donc PAS celle qui doit agir).
        if (callerId.equals(mustActUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "nudge/not-your-wait");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (thread.getLastActivityAt() != null && thread.getLastActivityAt().isAfter(now.minusHours(1))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "nudge/too-early");
        }
        if (thread.getLastNudgeAt() != null && thread.getLastNudgeAt().isAfter(now.minusHours(1))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "nudge/rate-limited");
        }

        String callerName = userRepository.findById(callerId).map(this::buildDisplayName).orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        eventPublisher.publishEvent(new NegotiationNudgeSentEvent(
            thread.getId(), request.getId(), callerId, mustActUserId, callerName));

        thread.setLastNudgeAt(now); // NE PAS toucher lastActivityAt
        threadRepo.save(thread);
        auditService.log("NEGOTIATION_THREAD", threadId, "NUDGE_SENT", callerId,
            Map.of("target", mustActUserId.toString()));

        List<NegotiationMessageResponse> msgs = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity travelerEntity = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName).orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        com.yadony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null) : null;
        return toResponse(thread, msgs, null, travelerEntity, request, callerId, senderName, linkedAnn);
    }

    /**
     * Devis transparent pour l'expéditeur avant paiement : net voyageur, commission
     * Yadony (taux de base, jamais affecté par le promo — la remise n'apparaît que
     * sur le total, cf. {@code BidService.quote}), et prévisualisation d'un code
     * promo optionnel. Sender-only : seul celui qui paie peut appliquer un promo.
     */
    @Transactional(readOnly = true)
    public NegotiationQuoteResponse quote(UUID callerId, UUID threadId, String promoCode) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));
        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!callerId.equals(request.getSenderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        BigDecimal net = thread.getCurrentPriceEur();
        UUID travelerId = thread.getTravelerId();
        // Auto-appliqué : à défaut d'un override explicite, reflète le code porté par
        // le thread depuis la publication de la demande (jamais resaisi par défaut).
        String rawCode = promoCode != null ? promoCode : thread.getPromoCode();
        String code = rawCode != null ? rawCode.strip() : null;

        boolean promoApplied = false;
        String promoLabel = null;
        BigDecimal rate;
        BigDecimal commissionEur;
        BigDecimal totalEur;

        if (code != null && !code.isBlank()) {
            // Résolu EN PREMIER : promo invalide → propage avant de calculer quoi que ce
            // soit d'autre (même contrat que BidService.quote — cf. régression WELCOME05).
            BigDecimal finalRate = commissionRateResolver.resolve(travelerId, callerId, code);
            promoApplied = true;

            rate = commissionRateResolver.resolve(travelerId, callerId);
            commissionEur = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal finalCommissionEur = net.multiply(finalRate).setScale(2, RoundingMode.HALF_UP);
            totalEur = net.add(finalCommissionEur).setScale(2, RoundingMode.HALF_UP);

            BigDecimal discountPoints = rate.subtract(finalRate).max(BigDecimal.ZERO);
            long pct = discountPoints.multiply(BigDecimal.valueOf(100)).longValue();
            promoLabel = "Code " + code.toUpperCase() + " : " + pct + " % de réduction";
        } else {
            rate = commissionRateResolver.resolve(travelerId, callerId);
            commissionEur = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            totalEur = net.add(commissionEur).setScale(2, RoundingMode.HALF_UP);
        }

        return new NegotiationQuoteResponse(net, rate, commissionEur, totalEur, promoApplied, promoLabel);
    }

    /**
     * Stamp le code promo et le taux appliqués sur le thread après une création
     * d'escrow réussie (initiate-payment) — voir {@link #quote} et le javadoc de
     * {@code NegotiationThreadEntity.promoCode}. Consommé par {@code ThreadAcceptedBidListener}
     * pour déclencher le rachat une fois le bid matérialisé.
     */
    @Transactional
    public void recordAppliedPromo(UUID threadId, String promoCode, BigDecimal rate) {
        threadRepo.findById(threadId).ifPresent(thread -> {
            thread.setPromoCode(promoCode != null ? promoCode.strip().toUpperCase() : null);
            thread.setCommissionRate(rate);
            threadRepo.save(thread);
        });
    }

    @Transactional
    public NegotiationThreadResponse getById(UUID callerId, UUID threadId) {
        NegotiationThreadEntity thread = threadRepo.findById(threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "thread/not-found"));

        PackageRequestEntity request = requestRepo.findById(thread.getPackageRequestId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!callerId.equals(request.getSenderId()) && !callerId.equals(thread.getTravelerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "negotiation/not-thread-participant");
        }

        if (thread.getStatus().isActive()) {
            LocalDateTime readAt = LocalDateTime.now(ZoneOffset.UTC);
            if (callerId.equals(request.getSenderId())) {
                thread.setSenderLastReadAt(readAt);
            } else {
                thread.setTravelerLastReadAt(readAt);
            }
            threadRepo.save(thread);
        }

        List<NegotiationMessageResponse> messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(threadId)
            .stream().map(this::toMessageResponse).toList();
        UserEntity threadTraveler = userRepository.findById(thread.getTravelerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        com.yadony.api.matching.AnnouncementEntity linkedAnn = thread.getTravelerAnnouncementId() != null
            ? announcementRepo.findById(thread.getTravelerAnnouncementId()).orElse(null)
            : null;
        return toResponse(thread, messages, null, threadTraveler, request, callerId, senderName, linkedAnn, true);
    }

    // TTL courte (8 s, cf. CacheConfig), sans @CacheEvict : un thread de
    // négociation est bilatéral (expéditeur + voyageur) et une douzaine de
    // méthodes le mutent (accept/counter/reject/cancel/submitTrip/...) — les
    // évincer toutes pour les DEUX participants à chaque fois serait fragile.
    // L'expiration courte suffit, le client tolère déjà ce délai.
    @Transactional(readOnly = true)
    @Cacheable(value = "negotiations-me", key = "#userId")
    public List<NegotiationThreadResponse> listMine(UUID userId) {
        List<NegotiationThreadEntity> threads = threadRepo.findByParticipant(userId);

        // Batch-load announcements to avoid N+1
        List<UUID> announcementIds = threads.stream()
            .map(NegotiationThreadEntity::getTravelerAnnouncementId)
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<UUID, com.yadony.api.matching.AnnouncementEntity> annMap =
            announcementRepo.findAllById(announcementIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.yadony.api.matching.AnnouncementEntity::getId, a -> a));

        return threads.stream()
            .flatMap(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                var travelerOpt = userRepository.findById(t.getTravelerId());
                var requestOpt = requestRepo.findById(t.getPackageRequestId());
                // Skip orphaned threads (soft-deleted request or unknown user) instead of failing the whole list
                if (travelerOpt.isEmpty() || requestOpt.isEmpty()) {
                    return java.util.stream.Stream.empty();
                }
                String senderName = userRepository.findById(requestOpt.get().getSenderId())
                    .map(this::buildDisplayName)
                    .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
                com.yadony.api.matching.AnnouncementEntity linkedAnn = t.getTravelerAnnouncementId() != null
                    ? annMap.get(t.getTravelerAnnouncementId())
                    : null;
                return java.util.stream.Stream.of(toResponse(t, messages, null, travelerOpt.get(), requestOpt.get(), userId, senderName, linkedAnn, true));
            })
            .toList();
    }

    /**
     * All threads attached to a single package_request (sender's inbox view
     * for one of their requests). Caller must be the sender of the request
     * — ownership check is enforced.
     */
    @Transactional(readOnly = true)
    public List<NegotiationThreadResponse> listForRequest(UUID callerId, UUID requestId) {
        PackageRequestEntity request = requestRepo.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (!request.getSenderId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        String senderName = userRepository.findById(request.getSenderId())
            .map(this::buildDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
        var threads = threadRepo.findByPackageRequestId(requestId);
        // Batch-load announcements pour éviter N+1
        List<UUID> announcementIds = threads.stream()
            .map(t -> t.getTravelerAnnouncementId())
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<UUID, com.yadony.api.matching.AnnouncementEntity> annMap = announcementRepo.findAllById(announcementIds).stream()
            .collect(java.util.stream.Collectors.toMap(
                com.yadony.api.matching.AnnouncementEntity::getId, a -> a));
        return threads.stream()
            .map(t -> {
                var messages = messageRepo.findByThreadIdOrderByCreatedAtAsc(t.getId())
                    .stream().map(this::toMessageResponse).toList();
                UserEntity lt_traveler = userRepository.findById(t.getTravelerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
                com.yadony.api.matching.AnnouncementEntity linkedAnn = t.getTravelerAnnouncementId() != null
                    ? annMap.get(t.getTravelerAnnouncementId())
                    : null;
                return toResponse(t, messages, null, lt_traveler, request, callerId, senderName, linkedAnn, true);
            })
            .toList();
    }

    NegotiationThreadResponse toResponse(NegotiationThreadEntity t,
                                          List<NegotiationMessageResponse> messages,
                                          String paymentIntentClientSecret,
                                          UserEntity traveler,
                                          PackageRequestEntity request,
                                          UUID callerId,
                                          String senderName,
                                          com.yadony.api.matching.AnnouncementEntity linkedAnn) {
        return toResponse(t, messages, paymentIntentClientSecret, traveler, request, callerId,
            senderName, linkedAnn, false);
    }

    /**
     * @param checkCashAvailability whether to consult {@link CashGatePort} to compute
     *        {@code cashCommissionAvailable}. Only the read paths that actually feed the
     *        mobile payment-method picker ({@code getById}/{@code listMine}/{@code
     *        listForRequest}) need this — action methods (start/accept/counter/submitTrip/
     *        finalize…) skip it to avoid an extra wallet/card lookup on every write, and to
     *        keep the existing "cash gate untouched for non-cash flows" test invariants.
     */
    NegotiationThreadResponse toResponse(NegotiationThreadEntity t,
                                          List<NegotiationMessageResponse> messages,
                                          String paymentIntentClientSecret,
                                          UserEntity traveler,
                                          PackageRequestEntity request,
                                          UUID callerId,
                                          String senderName,
                                          com.yadony.api.matching.AnnouncementEntity linkedAnn,
                                          boolean checkCashAvailability) {
        boolean isMyTurn = false;
        boolean canAccept = false;
        boolean canCounter = false;

        if (t.getStatus() == NegotiationThreadStatus.OPEN && callerId != null && !messages.isEmpty()) {
            NegotiationMessageResponse last = messages.get(messages.size() - 1);
            isMyTurn = !last.fromUserId().equals(callerId);
            boolean lastIsTarifaire = last.kind() == com.yadony.api.requests.entity.NegotiationMessageKind.PROPOSAL
                || last.kind() == com.yadony.api.requests.entity.NegotiationMessageKind.COUNTER;
            canAccept = isMyTurn && lastIsTarifaire;
            canCounter = isMyTurn && t.getRoundsCount() < config.maxNegotiationRounds()
                         && request.isNegotiable();
        }
        int roundsRemaining = Math.max(0, config.maxNegotiationRounds() - t.getRoundsCount().intValue());

        com.yadony.api.requests.dto.LinkedTripSummary linkedTrip = null;
        if (linkedAnn != null) {
            linkedTrip = new com.yadony.api.requests.dto.LinkedTripSummary(
                linkedAnn.getId(),
                linkedAnn.getDepartureCity(),
                linkedAnn.getArrivalCity(),
                linkedAnn.getDepartureDate() != null ? linkedAnn.getDepartureDate().toString() : null,
                linkedAnn.getDepartureTime() != null ? linkedAnn.getDepartureTime().toString() : null,
                linkedAnn.getTransportMode() != null ? linkedAnn.getTransportMode().name() : null,
                linkedAnn.getPickupAddressLabel(),
                linkedAnn.getDeliveryAddressLabel(),
                linkedAnn.getAvailableKg() != null ? linkedAnn.getAvailableKg().intValue() : 0,
                linkedAnn.getCapacityUnit() != null ? linkedAnn.getCapacityUnit().name() : null,
                linkedAnn.getDescription()
            );
        }

        BigDecimal gross = t.getCurrentPriceEur() != null
            ? PriceBreakdown.fromNet(t.getCurrentPriceEur(), commissionProperties.rate()).gross()
            : null;

        String senderPhotoUrl = storageService.avatarUrl(
            userRepository.findById(request.getSenderId())
                .map(com.yadony.api.auth.UserEntity::getAvatarUrl)
                .orElse(null));

        // Default true (non-blocking) when unchecked: action responses aren't used to
        // render the payment-method picker, so we don't want a stale "false" here to
        // ever be mistaken for an authoritative "cash unavailable".
        boolean cashCommissionAvailable = true;
        if (checkCashAvailability && t.getCurrentPriceEur() != null) {
            BigDecimal commission = PriceBreakdown.fromNet(
                t.getCurrentPriceEur(), commissionProperties.rate()).commission();
            cashCommissionAvailable = cashGatePort.hasSufficientFunds(t.getTravelerId(), commission, t.getCurrency())
                || cashGatePort.hasCommissionCard(t.getTravelerId());
        }

        // canNudge : le viewer peut relancer l'autre partie si le thread est encore en
        // négociation (OPEN/AWAITING_TRIP), que ce n'est pas son tour d'agir, que la dernière
        // activité date de plus d'1h, et qu'aucune relance n'a déjà été envoyée depuis moins d'1h.
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        boolean nudgeStatus = t.getStatus() == NegotiationThreadStatus.OPEN
                           || t.getStatus() == NegotiationThreadStatus.AWAITING_TRIP;
        boolean waitedEnough = t.getLastActivityAt() != null
                && !t.getLastActivityAt().isAfter(nowUtc.minusHours(1));
        boolean nudgeNotRecent = t.getLastNudgeAt() == null
                || !t.getLastNudgeAt().isAfter(nowUtc.minusHours(1));
        // La partie qui attend n'est pas la même selon l'état : en AWAITING_TRIP,
        // isMyTurn vaut toujours false (calculé seulement pour OPEN plus haut), donc
        // !isMyTurn serait vrai pour les DEUX viewers — y compris le voyageur, qui
        // doit pourtant AGIR (lier un trajet) et non attendre. Seul l'expéditeur attend.
        boolean callerIsWaiting = t.getStatus() == NegotiationThreadStatus.AWAITING_TRIP
                ? !callerId.equals(t.getTravelerId())   // en AWAITING_TRIP, le voyageur doit agir -> l'expéditeur attend
                : !isMyTurn;                             // en OPEN, la partie qui n'a pas la main attend
        boolean canNudge = nudgeStatus && callerIsWaiting && waitedEnough && nudgeNotRecent;

        LocalDateTime lastReadAt = callerId.equals(request.getSenderId())
            ? t.getSenderLastReadAt()
            : t.getTravelerLastReadAt();
        boolean hasUnread = messages.stream().anyMatch(message ->
            !message.fromUserId().equals(callerId)
                && (lastReadAt == null
                    || message.createdAt() == null
                    || message.createdAt().isAfter(lastReadAt)));

        return new NegotiationThreadResponse(
            t.getId(), t.getPackageRequestId(), t.getTravelerId(),
            t.getTravelerAnnouncementId(), t.getTravelerTravelDate(), t.getTravelerAvailableKg(),
            linkedAnn != null && linkedAnn.getCapacityUnit() != null ? linkedAnn.getCapacityUnit().name() : null,
            t.getStatus(), t.getCurrentPriceEur(), t.getRoundsCount().intValue(),
            t.getLastActivityAt(), t.getCreatedAt(),
            messages, paymentIntentClientSecret,
            buildDisplayName(traveler), traveler.getAverageRating(),
            traveler.getTotalTrips(), storageService.avatarUrl(traveler.getAvatarUrl()),
            request.getDepartureCity(), request.getArrivalCity(), request.getWeightKg(),
            senderName,
            senderPhotoUrl,
            isMyTurn, canAccept, canCounter, roundsRemaining,
            linkedTrip,
            gross,
            t.getPaymentMethod(),
            t.getMaterializedBidId(),
            cashCommissionAvailable,
            t.getAvailablePaymentMethods(),
            canNudge,
            hasUnread,
            t.getPromoCode(),
            t.getCommissionRate(),
            t.getCurrency()
        );
    }

    /**
     * Délègue à {@link UserEntity#publicDisplayName()} : le repli est le username du compte,
     * pas le rôle qu'il tient dans ce fil. Un même compte apparaissait sinon « Voyageur » ici
     * et « Expéditeur » ailleurs selon le sens de la négociation.
     */
    private String buildDisplayName(UserEntity user) {
        return user.publicDisplayName();
    }

    NegotiationMessageResponse toMessageResponse(NegotiationMessageEntity m) {
        return new NegotiationMessageResponse(
            m.getId(), m.getThreadId(), m.getFromUserId(),
            m.getKind(), m.getProposedPriceEur(), m.getBody(),
            m.getCreatedAt()
        );
    }

    /** Normalizes a city string for comparison by keeping only the part before
     *  the first comma and lowercasing. "Paris, France" → "paris". */
    private static String cityKey(String city) {
        if (city == null) return "";
        int comma = city.indexOf(',');
        return (comma >= 0 ? city.substring(0, comma) : city).strip().toLowerCase();
    }

    /**
     * Returns {@code true} if the traveler is technically capable of offering
     * the given payment method.
     * <ul>
     *   <li>STRIPE requires a fully onboarded Stripe Connect account.</li>
     *   <li>CASH / WAVE / ORANGE_MONEY are always available.</li>
     * </ul>
     */
    private boolean travelerCanOffer(UserEntity t, PaymentMethod m) {
        return switch (m) {
            case STRIPE -> t.getStripeAccountStatus() == StripeAccountStatus.ONBOARDING_COMPLETE;
            case CASH, WAVE, ORANGE_MONEY -> true;
        };
    }

    /**
     * SET des modes réellement fournissables = colis.acceptedPaymentMethods ∩ capacité voyageur.
     *
     * STRIPE : exige un compte Connect onboardé — sans lui le voyageur ne peut structurellement
     * pas être payé, le blocage doit donc tomber ici, avant tout engagement.
     *
     * CASH : jamais conditionné au solde du portefeuille. Le solde n'est qu'une modalité de
     * règlement de la commission, pas une capacité : il peut être rechargé à tout moment et
     * n'a de sens qu'au moment où le voyageur règle lui-même la commission
     * ({@code settleCommission}, wallet puis carte), une fois l'accord conclu.
     */
    private java.util.Set<PaymentMethod> computeAvailableMethods(
            PackageRequestEntity request, UserEntity traveler) {
        java.util.Set<PaymentMethod> set = java.util.EnumSet.noneOf(PaymentMethod.class);
        java.util.Set<PaymentMethod> accepted = request.getAcceptedPaymentMethods();

        if (accepted.contains(PaymentMethod.STRIPE) && travelerCanOffer(traveler, PaymentMethod.STRIPE)) {
            set.add(PaymentMethod.STRIPE);
        }
        if (accepted.contains(PaymentMethod.CASH)) {
            set.add(PaymentMethod.CASH);
        }
        return set;
    }

    /**
     * 422 discriminant quand aucun mode n'est fournissable, selon ce que le colis exigeait.
     */
    private void assertNonEmptyOrThrow(
            java.util.Set<PaymentMethod> set, java.util.Set<PaymentMethod> accepted) {
        if (!set.isEmpty()) {
            return;
        }
        boolean wantsCard = accepted.contains(PaymentMethod.STRIPE);
        boolean wantsCash = accepted.contains(PaymentMethod.CASH);
        String reason = (wantsCard && wantsCash) ? "payment-method/none-available"
                      : wantsCard                ? "payment-method/card-capability-required"
                      :                            "payment-method/cash-funds-required";
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
