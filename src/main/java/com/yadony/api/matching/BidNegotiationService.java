package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionCalculator;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.matching.dto.BidCustomItemRequest;
import com.yadony.api.matching.dto.BidCustomItemResponse;
import com.yadony.api.matching.dto.BidGridItemLine;
import com.yadony.api.matching.dto.BidGridItemRequest;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationMessageResponse;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.settings.MinPricePerKgGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Moteur des tours de négociation d'un trajet, porté par le {@link BidEntity} lui-même.
 *
 * <p>Un fil vit en {@link BidStatus#NEGOTIATING} : ce n'est PAS une réservation, il
 * reste invisible de toutes les listes de colis (cf. {@code BidNegotiationLeakTest}).
 * À l'accord, le bid bascule en {@link BidStatus#AWAITING_PAYMENT} et rejoint le flux
 * de paiement existant sans autre adaptation.
 *
 * <p>Trois invariants gouvernent ce service :
 * <ul>
 *   <li><b>Tour de parole</b> : l'auteur de la dernière proposition ne peut ni
 *       contre-proposer ni accepter la sienne.</li>
 *   <li><b>Taux figé</b> : le taux de commission est résolu UNE SEULE fois, dans
 *       {@link #propose}, et stocké sur le bid. {@link #accept} relit ce snapshot ;
 *       le résolveur n'est jamais rappelé, sinon un changement de barème entre la
 *       proposition et l'accord ferait payer autre chose que le prix négocié.</li>
 *   <li><b>Vue par rôle</b> : le voyageur reçoit son net, l'expéditeur le brut et sa
 *       commission. Le net du voyageur ne quitte jamais le serveur vers l'expéditeur.</li>
 * </ul>
 */
@Service
public class BidNegotiationService {

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final BidNegotiationMessageRepository messageRepository;
    private final BidCustomItemRepository customItemRepository;
    private final BidGridItemRepository bidGridItemRepository;
    private final AnnouncementPriceGridItemRepository annGridItemRepository;
    private final BidPhotoService bidPhotoService;
    private final CommissionRateResolver commissionRateResolver;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final MatchingNegotiationConfig config;
    private final BidService bidService;
    private final BidNegotiationMobileMoneyPort mobileMoneyPort;
    private final CommissionGridService commissionGridService;

    public BidNegotiationService(BidRepository bidRepository,
                                 AnnouncementRepository announcementRepository,
                                 UserRepository userRepository,
                                 BidNegotiationMessageRepository messageRepository,
                                 BidCustomItemRepository customItemRepository,
                                 BidGridItemRepository bidGridItemRepository,
                                 AnnouncementPriceGridItemRepository annGridItemRepository,
                                 BidPhotoService bidPhotoService,
                                 CommissionRateResolver commissionRateResolver,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher,
                                 MatchingNegotiationConfig config,
                                 BidService bidService,
                                 BidNegotiationMobileMoneyPort mobileMoneyPort,
                                 CommissionGridService commissionGridService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.customItemRepository = customItemRepository;
        this.bidGridItemRepository = bidGridItemRepository;
        this.annGridItemRepository = annGridItemRepository;
        this.bidPhotoService = bidPhotoService;
        this.commissionRateResolver = commissionRateResolver;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.bidService = bidService;
        this.mobileMoneyPort = mobileMoneyPort;
        this.commissionGridService = commissionGridService;
    }

    /**
     * Garde du prix minimum par kilo, injectée par mutateur pour ne pas allonger un
     * constructeur déjà construit à la main par plusieurs tests. {@code null} hors Spring.
     */
    private MinPricePerKgGuard minPriceGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMinPriceGuard(MinPricePerKgGuard minPriceGuard) {
        this.minPriceGuard = minPriceGuard;
    }

    // ── Ouverture du fil ─────────────────────────────────────────────────────

    @Transactional
    public BidNegotiationResponse propose(UUID announcementId, String firebaseUid,
                                          BidNegotiationStartRequest request,
                                          HttpServletRequest httpRequest) {
        UserEntity sender = findUser(firebaseUid);
        AnnouncementEntity announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        if (!announcement.isNegotiable()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "announcement-not-negotiable", "Announcement Not Negotiable",
                    "Ce trajet n'accepte pas les propositions de prix");
        }
        assertTripNotDeparted(announcement);

        // Gardes partagées avec l'offre ferme : jamais dupliquées ici.
        String normalizedCategory =
                bidService.assertCanBidOn(sender, announcement, request.contentCategory());

        if (Boolean.FALSE.equals(request.disclaimerSigned())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "disclaimer-not-signed", "Disclaimer Not Signed",
                    "Le disclaimer légal doit être accepté");
        }

        List<BidGridItemRequest> gridItems =
                request.gridItems() != null ? request.gridItems() : List.of();
        List<BidCustomItemRequest> customItems =
                request.customItems() != null ? request.customItems() : List.of();
        boolean hasKg = request.weightKg() != null && request.weightKg().compareTo(BigDecimal.ZERO) > 0;
        boolean hasItems = !gridItems.isEmpty() || !customItems.isEmpty();

        if (!hasKg && !hasItems) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "bid-empty", "Bid Empty",
                    "Au moins un article ou un poids doit être renseigné");
        }

        if (announcement.getCapacityUnit() != CapacityUnit.KG_FREE
                && request.weightKg() != null
                && announcement.getAvailableKg() != null
                && request.weightKg().compareTo(announcement.getAvailableKg()) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "weight-exceeds-capacity", "Weight Exceeds Capacity",
                    "Poids demandé supérieur à la capacité disponible");
        }

        PaymentMethod paymentMethod =
                bidService.resolvePaymentMethodFor(announcement, request.paymentMethod());

        // Taux figé ICI et nulle part ailleurs — accept() relira ce snapshot.
        BigDecimal rate = commissionRateResolver.resolve(announcement.getTravelerId(), sender.getId());
        // Grille et réductions personnelles figées avec le taux : les rails de paiement les
        // relisent à l'acceptation, sans dépendre de la grille ni des taux spéciaux du jour.
        GridPricing grid = commissionGridService.current()
                .map(g -> {
                    CommissionReductions reductions = commissionRateResolver.reductions(
                            announcement.getTravelerId(), sender.getId(), null, null, null);
                    return new GridPricing(g, SupportedCurrency.fromCodeOrDefault(announcement.getCurrency()),
                            CommissionReductions.frozen(reductions.overrideRate(), reductions.voucherFactor()));
                })
                .orElse(null);
        assertAtLeastTravelerMinimum(announcement, request.proposedTotalEur(), request.weightKg(), rate, grid);
        assertCoversCommission(grid, request.proposedTotalEur());

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(request.weightKg());
        bid.setPricingMode(hasItems && hasKg ? BidPricingMode.MIXED
                         : hasItems          ? BidPricingMode.GRID
                         :                     BidPricingMode.KG);
        bid.setDescription(request.description());
        bid.setContentCategory(normalizedCategory);
        bid.setRecipientName(request.recipientName());
        bid.setRecipientPhone(request.recipientPhone());
        bid.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        bid.setDisclaimerSignedIp(bidService.resolveClientIp(httpRequest));
        if (paymentMethod != null) {
            bid.setPaymentMethod(paymentMethod);
        }
        if (paymentMethod == PaymentMethod.MOBILE_MONEY) {
            bidService.applyMobileMoneyPayer(bid, request.phoneNumber(), request.countryCode());
        }
        bid.setStatus(BidStatus.NEGOTIATING);
        bid.setCurrency(announcement.getCurrency());
        bid.setCommissionRate(rate);
        if (grid != null) {
            bid.setCommissionGridId(grid.grid().id());
            bid.setCommissionOverrideRate(grid.frozen().overrideRate());
            bid.setCommissionVoucherFactor(grid.frozen().voucherFactor());
        }
        bid.setNegotiationRound(1);

        BidEntity saved = bidRepository.save(bid);

        persistGridItems(saved, announcement, gridItems);
        persistCustomItems(saved, customItems);
        bidPhotoService.attachPhotos(saved.getId(), sender.getId(), request.photoKeys());

        BidNegotiationMessageEntity message = postMessage(saved, sender.getId(),
                BidNegotiationMessageKind.PROPOSAL, request.proposedTotalEur(), null);

        auditService.log("BID", saved.getId(), "BID_NEGOTIATION_PROPOSED", sender.getId(),
                Map.of("announcementId", announcementId.toString(),
                        "proposedGrossEur", request.proposedTotalEur().toPlainString(),
                        "round", "1"));

        publishPosted(saved, announcement, sender.getId(), BidNegotiationMessageKind.PROPOSAL,
                request.proposedTotalEur());

        return buildResponse(saved, announcement, sender.getId(), message);
    }

    // ── Rounds ───────────────────────────────────────────────────────────────

    @Transactional
    public BidNegotiationResponse counter(UUID bidId, String firebaseUid,
                                          BidNegotiationCounterRequest request) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);
        // FLUTTER-GA : passé la date limite de dépôt, plus de contre-offre ni d'accord —
        // le colis ne pourrait plus être remis. Le refus et le retrait restent possibles,
        // et HandoverDeadlineExpiryScheduler éteint le fil.
        HandoverDeadlineRules.assertNotPassed(ctx.announcement());

        BidNegotiationMessageEntity last = lastMessage(bidId);
        assertMyTurn(last, ctx.userId());

        if (ctx.bid().getNegotiationRound() >= config.maxRounds()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-round-limit-reached", "Negotiation Round Limit Reached",
                    "Le nombre maximum de propositions échangées est atteint");
        }

        GridPricing grid = gridPricing(ctx.bid());
        BigDecimal proposedGross = counterGross(ctx, request, grid);
        if (last != null && last.getProposedGrossEur() != null
                && last.getProposedGrossEur().compareTo(proposedGross) == 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "counter-same-amount", "Counter Same Amount",
                    "Ta contre-proposition est identique à la dernière proposition : accepte-la ou change le montant.");
        }

        // Côté expéditeur seulement : le voyageur fixe lui-même ses contre-offres, son
        // minimum et son exigence de profil vérifié ne s'appliquent qu'aux propositions
        // qu'on lui adresse. Rejoués à chaque tour, pour qu'un réglage activé en cours de
        // discussion soit respecté.
        if (!ctx.viewerIsTraveler()) {
            UserEntity sender = userRepository.findById(ctx.userId()).orElse(null);
            bidService.assertTravelerAcceptsSender(sender, ctx.announcement().getTravelerId());
            assertAtLeastTravelerMinimum(ctx.announcement(), proposedGross,
                    ctx.bid().getWeightKg(), ctx.bid().getCommissionRate(), grid);
        }
        assertCoversCommission(grid, proposedGross);

        ctx.bid().setNegotiationRound(ctx.bid().getNegotiationRound() + 1);
        BidEntity saved = bidRepository.save(ctx.bid());

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.COUNTER, proposedGross, request.body());

        Map<String, Object> audit = new HashMap<>();
        audit.put("proposedGrossEur", proposedGross.toPlainString());
        audit.put("round", String.valueOf(ctx.bid().getNegotiationRound()));
        if (request.proposedNetEur() != null) {
            audit.put("proposedNetEur", request.proposedNetEur().toPlainString());
        }
        auditService.log("BID", bidId, "BID_NEGOTIATION_COUNTERED", ctx.userId(), audit);

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.COUNTER, proposedGross);

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    /**
     * Brut de la contre-offre (FLUTTER-NS). Le brut ({@code proposedTotalEur}) est pris tel
     * quel ; le net ({@code proposedNetEur}), réservé au voyageur, est converti au contexte figé
     * du fil : {@link GridPricing#grossForNet} sur un fil à grille, sinon
     * {@link BidNegotiationPricing#grossForNet} au taux figé, à l'unité mineure de sa devise.
     * Un seul des deux montants.
     */
    private BigDecimal counterGross(Participant ctx, BidNegotiationCounterRequest request, GridPricing grid) {
        BigDecimal gross = request.proposedTotalEur();
        BigDecimal net = request.proposedNetEur();
        if (gross != null && net != null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "counter-amount-ambiguous", "Counter Amount Ambiguous",
                    "Indique soit le montant payé par l'expéditeur, soit ton net, pas les deux.");
        }
        if (net == null) {
            if (gross == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "counter-amount-required", "Counter Amount Required",
                        "Indique le montant de ta contre-proposition.");
            }
            return gross;
        }
        if (!ctx.viewerIsTraveler()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "counter-net-traveler-only", "Counter Net Traveler Only",
                    "Seul le voyageur propose un montant net.");
        }
        if (grid != null) {
            return grid.grossForNet(net);
        }
        BigDecimal rate = ctx.bid().getCommissionRate() != null ? ctx.bid().getCommissionRate() : BigDecimal.ZERO;
        int minorUnit = com.yadony.api.payments.currency.SupportedCurrency
                .fromCodeOrDefault(ctx.bid().getCurrency()).minorUnit();
        return BidNegotiationPricing.grossForNet(net, rate, minorUnit);
    }

    @Transactional
    public BidNegotiationResponse accept(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);
        // FLUTTER-GA : passé la date limite de dépôt, plus de contre-offre ni d'accord —
        // le colis ne pourrait plus être remis. Le refus et le retrait restent possibles,
        // et HandoverDeadlineExpiryScheduler éteint le fil.
        HandoverDeadlineRules.assertNotPassed(ctx.announcement());

        BidNegotiationMessageEntity last = lastMessage(bidId);
        assertMyTurn(last, ctx.userId());

        BigDecimal gross = last != null ? last.getProposedGrossEur() : null;
        if (gross == null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Aucune proposition de prix à accepter sur ce fil");
        }

        // Le taux (ou la grille et ses réductions) vient du bid, jamais d'une nouvelle
        // résolution : le prix accepté doit être exactement celui affiché à la proposition.
        BigDecimal rate = ctx.bid().getCommissionRate() != null
                ? ctx.bid().getCommissionRate() : BigDecimal.ZERO;
        BidNegotiationPricing.Split split = split(gross, rate, gridPricing(ctx.bid()));

        ctx.bid().setNegotiatedGrossEur(split.grossEur());
        ctx.bid().setNegotiatedNetEur(split.netEur());

        // Trois aval possibles, selon le mode figé à la proposition.
        //
        // ESPÈCES : rien à encaisser auprès de l'expéditeur, il réglera le voyageur de
        // la main à la main. Le laisser en AWAITING_PAYMENT était une impasse — aucun
        // écran n'aurait pu l'en sortir et AwaitingPaymentCleanupScheduler l'aurait
        // soft-delété au bout de awaiting-payment-hours. Le bid rejoint donc l'état
        // exact d'une demande cash fraîchement créée (PENDING + CashBidCreatedEvent),
        // d'où le voyageur le mène à ACCEPTED par le chemin existant et inchangé :
        // POST /bids/{id}/accept-with-commission → CashCommissionService.acceptCashBid,
        // qui prélève la commission Yadony (wallet puis carte) avant de sceller.
        // Ce second geste n'est pas une seconde acceptation du prix : c'est le
        // règlement de la commission, qui exige une source de fonds et peut demander
        // un 3DS — il ne peut donc pas être court-circuité ici, où le voyageur n'est
        // pas nécessairement l'auteur de l'acceptation.
        //
        // CARTE : l'expéditeur doit encore autoriser l'escrow, sur le bid EXISTANT,
        // via POST /bids/{id}/negotiation/checkout.
        //
        // MOBILE MONEY : l'accord vaut acceptation du voyageur (qu'il ait accepté le prix
        // ou que ce soit lui qui l'ait proposé). Le bid passe par PENDING puis le port
        // (MobileMoneyBidPaymentService.acceptBid côté payments/) le mène, DANS CETTE TRANSACTION, à
        // AWAITING_PAYMENT : capacité réservée, promo racheté, PaymentEntity pawaPay créé,
        // échéance de dépôt posée — exactement l'état d'une offre directe acceptée, que
        // l'expéditeur paie ensuite par POST /bids/{id}/mobile-money/initiate. Ne jamais
        // poser AWAITING_PAYMENT + MOBILE_MONEY soi-même : restoreCapacityIfNeeded suppose
        // la capacité déjà prélevée (surréservation silencieuse sinon). Un refus d'acceptBid
        // (compte de versement coupé, devise, capacité) annule tout l'accord.
        boolean cash = ctx.bid().getPaymentMethod() == PaymentMethod.CASH;
        boolean mobileMoney = ctx.bid().getPaymentMethod() == PaymentMethod.MOBILE_MONEY;
        if (mobileMoney) {
            ctx.bid().setStatus(BidStatus.PENDING);
            ctx.bid().setAwaitingPaymentExpiresAt(null);
            ctx.bid().setPendingSince(LocalDateTime.now(ZoneOffset.UTC));
        } else if (cash) {
            ctx.bid().setStatus(BidStatus.PENDING);
            ctx.bid().setAwaitingPaymentExpiresAt(null);
            // L'accord entre MAINTENANT dans la file d'attente du voyageur : c'est
            // d'ici que court le délai de BidTimeoutScheduler, et non de la création
            // du fil. Sans ce repère, un fil de plus de 24 h — le cas nominal, la
            // fenêtre d'inactivité étant de 72 h — voyait son accord annulé en
            // TRAVELER_NO_RESPONSE au tick suivant, avant même que le voyageur ait
            // pu régler la commission.
            ctx.bid().setPendingSince(LocalDateTime.now(ZoneOffset.UTC));
        } else {
            ctx.bid().setStatus(BidStatus.AWAITING_PAYMENT);
            ctx.bid().setAwaitingPaymentExpiresAt(
                    LocalDateTime.now(ZoneOffset.UTC).plusHours(config.awaitingPaymentHours()));
        }
        BidEntity saved = bidRepository.save(ctx.bid());
        if (mobileMoney) {
            mobileMoneyPort.acceptAgreement(bidId, ctx.announcement().getTravelerId());
        }

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.ACCEPT, split.grossEur(), null);

        auditService.log("BID", bidId, "BID_NEGOTIATION_ACCEPTED", ctx.userId(),
                Map.of("grossEur", split.grossEur().toPlainString(),
                        "netEur", split.netEur().toPlainString(),
                        "commissionEur", split.commissionEur().toPlainString(),
                        "round", String.valueOf(ctx.bid().getNegotiationRound())));

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.ACCEPT, split.grossEur());

        if (cash) {
            // Même événement que BidService.createBid pour une demande cash, marqué
            // « négocié » (FLUTTER-H7) : le voyageur n'a pas de demande à étudier mais la
            // commission Yadony à régler, depuis le fil, avant que BidTimeoutScheduler
            // n'annule l'accord. La notification le dit et ouvre le fil, où se trouve le
            // bouton de règlement ; « Nouvelle demande d'envoi » le laissait chercher.
            eventPublisher.publishEvent(new CashBidCreatedEvent(
                    ctx.bid().getId(), ctx.announcement().getId(), ctx.announcement().getTravelerId(),
                    ctx.bid().getSenderId(), displayName(ctx.bid().getSenderId()),
                    ctx.bid().getWeightKg(),
                    ctx.announcement().getDepartureCity() + " → " + ctx.announcement().getArrivalCity(),
                    true,
                    BidTimeoutScheduler.deadlineFor(ctx.bid().getPendingSince(),
                            ctx.announcement().getDepartureDate())));
        }

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    @Transactional
    public BidNegotiationResponse reject(UUID bidId, String firebaseUid) {
        return closeThread(bidId, firebaseUid, "BID_NEGOTIATION_REJECTED");
    }

    @Transactional
    public BidNegotiationResponse cancel(UUID bidId, String firebaseUid) {
        return closeThread(bidId, firebaseUid, "BID_NEGOTIATION_CANCELLED");
    }

    /**
     * Éteint le fil, quel que soit le geste (refus ou retrait) et quel que soit son
     * auteur. Le statut posé est toujours {@link BidStatus#NEGOTIATION_CLOSED} : la
     * nuance refus/retrait est une information d'historique, portée par l'action
     * d'audit, pas par un statut de colis.
     *
     * <p>Poser REJECTED / CANCELLED ici, comme au premier jet, sortait le bid de
     * {@code NEGOTIATION_ACTIVE} et le faisait ressurgir dans « Mes envois » et dans
     * la liste voyageur du trajet en demande de colis refusée — et, pour REJECTED,
     * jusque dans le dénominateur du taux d'acceptation du voyageur, qu'un refus de
     * prix par l'EXPÉDITEUR suffisait à dégrader.
     */
    private BidNegotiationResponse closeThread(UUID bidId, String firebaseUid,
                                               String auditAction) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        assertThreadOpen(ctx);

        ctx.bid().setStatus(BidStatus.NEGOTIATION_CLOSED);
        BidEntity saved = bidRepository.save(ctx.bid());

        BidNegotiationMessageEntity message = postMessage(ctx.bid(), ctx.userId(),
                BidNegotiationMessageKind.REJECT, null, null);

        auditService.log("BID", bidId, auditAction, ctx.userId(),
                Map.of("finalStatus", BidStatus.NEGOTIATION_CLOSED.name(),
                        "round", String.valueOf(ctx.bid().getNegotiationRound())));

        publishPosted(ctx.bid(), ctx.announcement(), ctx.userId(),
                BidNegotiationMessageKind.REJECT, null);

        return buildResponse(saved != null ? saved : ctx.bid(), ctx.announcement(), ctx.userId(), message);
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BidNegotiationResponse thread(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        return buildResponse(ctx.bid(), ctx.announcement(), ctx.userId(), lastMessage(bidId));
    }

    @Transactional
    public void markRead(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (ctx.viewerIsTraveler()) {
            ctx.bid().setTravelerLastReadAt(now);
        } else {
            ctx.bid().setSenderLastReadAt(now);
        }
        bidRepository.save(ctx.bid());
    }

    /**
     * « Discussions de prix » du demandeur : sans {@code archived}, les fils ni rangés
     * ni retirés de son côté ; avec, le filtre « Archivées ».
     */
    @Transactional(readOnly = true)
    public List<BidNegotiationSummaryResponse> myNegotiations(String firebaseUid, boolean archived) {
        UserEntity user = findUser(firebaseUid);
        List<BidNegotiationSummaryResponse> rows = new ArrayList<>();
        List<BidEntity> bids = archived
                ? bidRepository.findArchivedNegotiationsForUser(user.getId())
                : bidRepository.findNegotiationsForUser(user.getId());
        if (bids.isEmpty()) {
            return rows;
        }
        // Trois lectures groupées pour toute la liste (trajets, derniers messages,
        // contreparties) au lieu de trois par ligne : la liste garde désormais les fils
        // terminés (FLUTTER-HM) et grossit avec l'historique de l'utilisateur.
        Map<UUID, AnnouncementEntity> announcements = new HashMap<>();
        announcementRepository.findAllById(
                        bids.stream().map(BidEntity::getAnnouncementId).collect(Collectors.toSet()))
                .forEach(a -> announcements.put(a.getId(), a));
        Map<UUID, BidNegotiationMessageEntity> lastMessages = new HashMap<>();
        messageRepository.findLatestByBidIdIn(bids.stream().map(BidEntity::getId).toList())
                .forEach(m -> lastMessages.putIfAbsent(m.getBidId(), m));
        Set<UUID> counterpartIds = new HashSet<>();
        for (BidEntity bid : bids) {
            AnnouncementEntity announcement = announcements.get(bid.getAnnouncementId());
            if (announcement != null) {
                counterpartIds.add(announcement.getTravelerId().equals(user.getId())
                        ? bid.getSenderId() : announcement.getTravelerId());
            }
        }
        Map<UUID, String> displayNames = new HashMap<>();
        userRepository.findAllById(counterpartIds)
                .forEach(u -> displayNames.put(u.getId(), u.publicDisplayName()));

        for (BidEntity bid : bids) {
            AnnouncementEntity announcement = announcements.get(bid.getAnnouncementId());
            if (announcement == null) {
                continue;
            }
            boolean viewerIsTraveler = announcement.getTravelerId().equals(user.getId());
            BidNegotiationMessageEntity last = lastMessages.get(bid.getId());
            LocalDateTime lastReadAt = viewerIsTraveler
                    ? bid.getTravelerLastReadAt() : bid.getSenderLastReadAt();

            rows.add(new BidNegotiationSummaryResponse(
                    bid.getId(),
                    announcement.getId(),
                    bid.getStatus().name(),
                    bid.getNegotiationRound(),
                    // Même règle que le détail : un fil conclu (PENDING, AWAITING_PAYMENT…)
                    // n'attend plus personne, même si son dernier message vient de l'autre
                    // partie (FLUTTER-EA, compteur « offre en attente » faux).
                    bid.getStatus() == BidStatus.NEGOTIATING && isMyTurn(last, user.getId()),
                    hasUnread(last, user.getId(), lastReadAt),
                    currentGross(bid, last),
                    bid.getCurrency(),
                    displayNames.getOrDefault(
                            viewerIsTraveler ? bid.getSenderId() : announcement.getTravelerId(),
                            UserEntity.UNKNOWN_DISPLAY_NAME),
                    announcement.getDepartureCity(),
                    announcement.getArrivalCity(),
                    announcement.getDepartureDate(),
                    bid.getUpdatedAt(),
                    viewerIsTraveler ? "TRAVELER" : "SENDER",
                    bid.isNegotiationArchivedBy(viewerIsTraveler),
                    viewerIsTraveler
                            ? travelerNet(currentGross(bid, last),
                                    bid.getCommissionRate() != null ? bid.getCommissionRate() : BigDecimal.ZERO,
                                    gridPricing(bid))
                            : null));
        }
        return rows;
    }

    // ── Rangement / retrait par un participant (FLUTTER-EJ) ─────────────────
    //
    // Même modèle que les conversations (ConversationService#archiveConversation /
    // #deleteConversation) : seule la vue de l'appelant change, l'autre participant
    // garde la discussion intacte. Seule une discussion TERMINÉE se range ou se retire,
    // et le retrait n'est jamais une purge : le bid reste pour l'autre partie, le
    // back-office et l'audit.

    @Transactional
    public void archive(UUID bidId, String firebaseUid) {
        Participant ctx = loadVisibleNegotiation(bidId, firebaseUid);
        assertNegotiationTerminal(ctx);
        if (ctx.bid().isNegotiationArchivedBy(ctx.viewerIsTraveler())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (ctx.viewerIsTraveler()) {
            bidRepository.updateNegotiationTravelerArchivedAt(bidId, now);
        } else {
            bidRepository.updateNegotiationSenderArchivedAt(bidId, now);
        }
        auditService.log("BID", bidId, "BID_NEGOTIATION_ARCHIVED", ctx.userId(), visibilityAudit(ctx));
    }

    @Transactional
    public void unarchive(UUID bidId, String firebaseUid) {
        Participant ctx = loadVisibleNegotiation(bidId, firebaseUid);
        if (!ctx.bid().isNegotiationArchivedBy(ctx.viewerIsTraveler())) {
            return;
        }
        if (ctx.viewerIsTraveler()) {
            bidRepository.updateNegotiationTravelerArchivedAt(bidId, null);
        } else {
            bidRepository.updateNegotiationSenderArchivedAt(bidId, null);
        }
        auditService.log("BID", bidId, "BID_NEGOTIATION_UNARCHIVED", ctx.userId(), visibilityAudit(ctx));
    }

    @Transactional
    public void hide(UUID bidId, String firebaseUid) {
        Participant ctx = loadNegotiation(bidId, firebaseUid);
        if (ctx.bid().isNegotiationHiddenBy(ctx.viewerIsTraveler())) {
            return;
        }
        assertNegotiationTerminal(ctx);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (ctx.viewerIsTraveler()) {
            bidRepository.updateNegotiationTravelerHiddenAt(bidId, now);
        } else {
            bidRepository.updateNegotiationSenderHiddenAt(bidId, now);
        }
        auditService.log("BID", bidId, "BID_NEGOTIATION_HIDDEN", ctx.userId(), visibilityAudit(ctx));
    }

    /**
     * Participant d'un bid qui EST une discussion de prix ; une offre ferme répond 404, de même
     * qu'un bid matérialisé depuis un fil de demande d'envoi, dont le brut est le total payé
     * sur le fil (même règle que {@link BidRepository#findNegotiationsForUser}).
     */
    private Participant loadNegotiation(UUID bidId, String firebaseUid) {
        Participant ctx = loadParticipant(bidId, firebaseUid);
        BidEntity bid = ctx.bid();
        boolean isNegotiation = BidStatus.NEGOTIATION_STATUSES.contains(bid.getStatus())
                || (bid.getNegotiatedGrossEur() != null && bid.getLinkedNegotiationThreadId() == null);
        if (!isNegotiation) {
            throw negotiationNotFound();
        }
        return ctx;
    }

    /** Une discussion retirée par l'appelant n'existe plus pour lui. */
    private Participant loadVisibleNegotiation(UUID bidId, String firebaseUid) {
        Participant ctx = loadNegotiation(bidId, firebaseUid);
        if (ctx.bid().isNegotiationHiddenBy(ctx.viewerIsTraveler())) {
            throw negotiationNotFound();
        }
        return ctx;
    }

    private static YadonyBusinessException negotiationNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "negotiation-not-found",
                "Negotiation Not Found", "Discussion de prix introuvable");
    }

    /**
     * Terminée = n'attend plus rien de personne. Les seuls états ouverts sont
     * NEGOTIATING, et l'accord conclu en attente de règlement (AWAITING_PAYMENT carte ou
     * mobile money, PENDING espèces). Tout le reste est terminé : NEGOTIATION_CLOSED
     * (refus, retrait, péremption) et tout statut atteint après le règlement. Ces fils
     * terminés restent listés (findNegotiationsForUser, FLUTTER-HM) : c'est là que
     * l'utilisateur les range ou les retire.
     */
    private static void assertNegotiationTerminal(Participant ctx) {
        BidStatus status = ctx.bid().getStatus();
        if (OPEN_NEGOTIATION_STATUSES.contains(status)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "negotiation-still-open",
                    "Negotiation Still Open",
                    "Seule une discussion de prix terminée peut être archivée ou supprimée.",
                    Map.of("negotiationStatus", status.name()));
        }
    }

    private static final java.util.Set<BidStatus> OPEN_NEGOTIATION_STATUSES = java.util.EnumSet.of(
            BidStatus.NEGOTIATING, BidStatus.AWAITING_PAYMENT, BidStatus.PENDING);

    private static Map<String, Object> visibilityAudit(Participant ctx) {
        return Map.of("role", ctx.viewerIsTraveler() ? "TRAVELER" : "SENDER",
                "status", ctx.bid().getStatus().name());
    }

    // ── Gardes et helpers ────────────────────────────────────────────────────

    /**
     * Prix minimum par kilo du voyageur ({@link MinPricePerKgGuard}). La négociation porte
     * sur le brut : le net du voyageur en est déduit exactement comme à l'accord
     * ({@link #split}), puis ramené au kilo comme sur la demande affichée au voyageur. Sans
     * garde injectée (tests unitaires), aucun contrôle.
     *
     * <p>Sur un fil à grille, le taux passé à la garde (pour exprimer le minimum en brut dans le
     * message d'erreur) est le taux réel de cette proposition, {@code (brut − net) / net} : la
     * grille n'a pas de taux unique.
     */
    private void assertAtLeastTravelerMinimum(AnnouncementEntity announcement, BigDecimal grossTotal,
                                              BigDecimal weightKg, BigDecimal rate, GridPricing grid) {
        if (minPriceGuard == null || grossTotal == null || grossTotal.signum() <= 0) {
            return;
        }
        BigDecimal effectiveRate = rate != null ? rate : BigDecimal.ZERO;
        BidNegotiationPricing.Split split = split(grossTotal, effectiveRate, grid);
        if (grid != null) {
            effectiveRate = split.commissionEur().divide(split.netEur(), 6, RoundingMode.HALF_UP);
        }
        minPriceGuard.assertAtLeastMinimum(announcement.getTravelerId(), split.netEur(), weightKg,
                announcement.getCurrency(), effectiveRate);
    }

    /**
     * Fil à grille : un brut qui ne couvre pas la commission minimum est refusé tout de suite
     * (422 {@code budget-below-commission-minimum}, {@link CommissionCalculator#netForGross}),
     * avant toute écriture, plutôt qu'au premier affichage du net. Sans grille, aucun contrôle.
     */
    private static void assertCoversCommission(GridPricing grid, BigDecimal gross) {
        if (grid != null && gross != null) {
            grid.netForGross(gross);
        }
    }

    /**
     * Contexte de prix figé d'un fil à grille (spec §4.3) : la grille du bid et les réductions
     * personnelles figées à l'ouverture (taux spécial, bon de parrainage), jamais de code promo.
     * Les deux sens de conversion portent sur toute la chaîne (grille, taux spécial, minimum de
     * la devise, bon) et passent par {@link CommissionCalculator}, comme les rails de paiement
     * pour un net négocié. Un fil sans grille garde son taux figé et {@link BidNegotiationPricing}.
     */
    private record GridPricing(CommissionGrid grid, SupportedCurrency currency, CommissionReductions frozen) {

        /**
         * Net du voyageur pour un brut : le plus grand net dont le brut ne dépasse pas {@code gross}.
         *
         * @throws YadonyBusinessException 422 {@code budget-below-commission-minimum} quand le brut
         *         ne couvre pas la commission minimum.
         */
        BigDecimal netForGross(BigDecimal gross) {
            return CommissionCalculator.netForGross(gross, currency, grid, frozen);
        }

        /** Brut payé par l'expéditeur pour un net du voyageur ; un net nul ne coûte rien. */
        BigDecimal grossForNet(BigDecimal net) {
            BigDecimal n = net.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
            return n.signum() <= 0 ? n : CommissionCalculator.compute(n, currency, grid, frozen).gross();
        }
    }

    /** Contexte de prix figé d'un bid à grille ; {@code null} pour un bid sans grille (chemin hérité). */
    private GridPricing gridPricing(BidEntity bid) {
        if (bid.getCommissionGridId() == null) {
            return null;
        }
        return new GridPricing(commissionGridService.require(bid.getCommissionGridId()),
                SupportedCurrency.fromCodeOrDefault(bid.getCurrency()),
                CommissionReductions.frozen(bid.getCommissionOverrideRate(), bid.getCommissionVoucherFactor()));
    }

    /**
     * Répartition d'un brut négocié : net par l'inverse du contexte figé sur un fil à grille,
     * commission par soustraction ({@code net + commission = brut}, comme
     * {@link BidNegotiationPricing#split}, appliqué tel quel sans grille).
     */
    private static BidNegotiationPricing.Split split(BigDecimal gross, BigDecimal rate, GridPricing grid) {
        if (grid == null) {
            return BidNegotiationPricing.split(gross, rate);
        }
        BigDecimal g = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = grid.netForGross(g);
        return new BidNegotiationPricing.Split(g, net, g.subtract(net));
    }

    /** Contexte d'une action : le fil, son trajet, et le rôle de l'appelant. */
    private record Participant(BidEntity bid, AnnouncementEntity announcement,
                               UUID userId, boolean viewerIsTraveler) {}

    private Participant loadParticipant(UUID bidId, String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "bid-not-found", "Bid Not Found", "Demande introuvable"));
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Announcement Not Found", "Annonce introuvable"));

        boolean isSender = bid.getSenderId().equals(user.getId());
        boolean isTraveler = announcement.getTravelerId().equals(user.getId());
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous ne participez pas à cette discussion");
        }
        return new Participant(bid, announcement, user.getId(), isTraveler);
    }

    private void assertThreadOpen(Participant ctx) {
        if (ctx.bid().getStatus() != BidStatus.NEGOTIATING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Cette discussion de prix est terminée");
        }
        assertTripNotDeparted(ctx.announcement());
    }

    /**
     * Une fois le trajet parti, plus aucune action n'a de sens : le colis ne peut
     * plus embarquer. Le fil restant est éteint par
     * {@code BidNegotiationExpiryScheduler}, mais l'API doit refuser tout de suite.
     */
    private void assertTripNotDeparted(AnnouncementEntity announcement) {
        LocalDate departure = announcement.getDepartureDate();
        if (departure != null && departure.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "negotiation-closed", "Negotiation Closed",
                    "Ce trajet est déjà parti");
        }
    }

    private void assertMyTurn(BidNegotiationMessageEntity last, UUID callerId) {
        if (!isMyTurn(last, callerId)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "not-your-turn", "Not Your Turn",
                    "C'est à la contrepartie de répondre");
        }
    }

    private boolean isMyTurn(BidNegotiationMessageEntity last, UUID callerId) {
        return last == null || !callerId.equals(last.getAuthorId());
    }

    private boolean hasUnread(BidNegotiationMessageEntity last, UUID viewerId, LocalDateTime lastReadAt) {
        if (last == null || viewerId.equals(last.getAuthorId())) {
            return false;
        }
        return lastReadAt == null || last.getCreatedAt() == null || lastReadAt.isBefore(last.getCreatedAt());
    }

    private BidNegotiationMessageEntity lastMessage(UUID bidId) {
        return messageRepository.findFirstByBidIdOrderByCreatedAtDesc(bidId).orElse(null);
    }

    private BidNegotiationMessageEntity postMessage(BidEntity bid, UUID authorId,
                                                    BidNegotiationMessageKind kind,
                                                    BigDecimal proposedGrossEur, String body) {
        BidNegotiationMessageEntity message = BidNegotiationMessageEntity.create(
                bid.getId(), authorId, kind, proposedGrossEur, body);
        BidNegotiationMessageEntity saved = messageRepository.save(message);
        return saved != null ? saved : message;
    }

    private void publishPosted(BidEntity bid, AnnouncementEntity announcement, UUID authorId,
                               BidNegotiationMessageKind kind, BigDecimal proposedGrossEur) {
        UUID recipientId = authorId.equals(bid.getSenderId())
                ? announcement.getTravelerId() : bid.getSenderId();
        // Chacun reçoit le montant de SON fil : le voyageur son net, l'expéditeur le brut.
        BigDecimal amountForRecipient = proposedGrossEur;
        if (proposedGrossEur != null && recipientId.equals(announcement.getTravelerId())) {
            BigDecimal rate = bid.getCommissionRate() != null ? bid.getCommissionRate() : BigDecimal.ZERO;
            amountForRecipient = split(proposedGrossEur, rate, gridPricing(bid)).netEur();
        }
        eventPublisher.publishEvent(new BidNegotiationMessagePostedEvent(
                bid.getId(), announcement.getId(), authorId, recipientId,
                kind, proposedGrossEur, bid.getNegotiationRound(),
                bid.getCurrency(), amountForRecipient));
    }

    private void persistGridItems(BidEntity bid, AnnouncementEntity announcement,
                                  List<BidGridItemRequest> gridItems) {
        if (gridItems.isEmpty()) {
            return;
        }
        List<BidGridItemEntity> entities = new ArrayList<>();
        for (BidGridItemRequest req : gridItems) {
            AnnouncementPriceGridItemEntity annItem = annGridItemRepository
                    .findById(req.announcementGridItemId())
                    .filter(i -> i.getAnnouncementId().equals(announcement.getId()))
                    .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "invalid-grid-item", "Invalid Grid Item",
                            "Article hors grille de cette annonce : " + req.announcementGridItemId()));
            BidGridItemEntity entity = new BidGridItemEntity();
            entity.setBidId(bid.getId());
            entity.setAnnouncementGridItemId(req.announcementGridItemId());
            entity.setLabelSnapshot(annItem.getLabel());
            entity.setUnitPriceNetSnapshot(annItem.getUnitPriceNet());
            entity.setQuantity(req.quantity());
            entities.add(entity);
        }
        bidGridItemRepository.saveAll(entities);
    }

    private void persistCustomItems(BidEntity bid, List<BidCustomItemRequest> customItems) {
        if (customItems.isEmpty()) {
            return;
        }
        List<BidCustomItemEntity> entities = new ArrayList<>();
        for (BidCustomItemRequest req : customItems) {
            BidCustomItemEntity entity = new BidCustomItemEntity();
            entity.setBidId(bid.getId());
            entity.setLabel(req.label());
            entity.setQuantity(req.quantity());
            entity.setAmountEur(req.amountEur());
            entities.add(entity);
        }
        customItemRepository.saveAll(entities);
    }

    private BigDecimal currentGross(BidEntity bid, BidNegotiationMessageEntity last) {
        if (last != null && last.getProposedGrossEur() != null) {
            return last.getProposedGrossEur();
        }
        return bid.getNegotiatedGrossEur();
    }

    private String displayName(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::publicDisplayName)
                .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);
    }

    // ── Construction de la réponse ───────────────────────────────────────────

    /**
     * @param last dernier message du fil — passé explicitement plutôt que relu, pour
     *             que la réponse d'une mutation reflète le message qui vient d'être
     *             posté sans dépendre de l'ordre de flush.
     */
    private BidNegotiationResponse buildResponse(BidEntity bid, AnnouncementEntity announcement,
                                                 UUID viewerId, BidNegotiationMessageEntity last) {
        boolean viewerIsTraveler = announcement.getTravelerId().equals(viewerId);
        BigDecimal rate = bid.getCommissionRate() != null ? bid.getCommissionRate() : BigDecimal.ZERO;
        GridPricing grid = gridPricing(bid);
        BigDecimal gross = currentGross(bid, last);

        BigDecimal netEur = null;
        BigDecimal commissionEur = null;
        if (gross != null && gross.compareTo(BigDecimal.ZERO) > 0) {
            BidNegotiationPricing.Split split = split(gross, rate, grid);
            // Le net du voyageur ne descend jamais vers l'expéditeur, et
            // réciproquement la commission n'intéresse que celui qui la paie.
            netEur = viewerIsTraveler ? split.netEur() : null;
            commissionEur = viewerIsTraveler ? null : split.commissionEur();
        }

        List<BidGridItemEntity> gridEntities = bidGridItemRepository.findByBidId(bid.getId());
        List<BidGridItemLine> gridLines = gridEntities.stream()
                .map(e -> new BidGridItemLine(e.getId(), e.getLabelSnapshot(),
                        unitPriceDisplay(e.getUnitPriceNetSnapshot(), rate, grid), e.getQuantity()))
                .toList();

        List<BidCustomItemResponse> customLines =
                customItemRepository.findByBidIdOrderByCreatedAtAsc(bid.getId()).stream()
                        .map(e -> new BidCustomItemResponse(e.getId(), e.getLabel(),
                                e.getQuantity(), e.getAmountEur()))
                        .toList();

        // Net de chaque proposition pour le voyageur seul (FLUTTER-NS), même calcul que
        // netEur ci-dessus : l'expéditeur ne voit jamais le net.
        List<BidNegotiationMessageResponse> messages =
                messageRepository.findByBidIdOrderByCreatedAtAsc(bid.getId()).stream()
                        .map(m -> new BidNegotiationMessageResponse(m.getId(), m.getKind().name(),
                                m.getAuthorId(), m.getProposedGrossEur(), m.getBody(), m.getCreatedAt(),
                                viewerIsTraveler ? travelerNet(m.getProposedGrossEur(), rate, grid) : null))
                        .toList();

        List<String> photoUrls = bidPhotoService.activePhotos(bid.getId()).stream()
                .map(p -> p.url()).toList();

        boolean open = bid.getStatus() == BidStatus.NEGOTIATING;
        boolean myTurn = open && isMyTurn(last, viewerId);
        UUID counterpartyId = viewerIsTraveler ? bid.getSenderId() : announcement.getTravelerId();
        Optional<UserEntity> counterparty = userRepository.findById(counterpartyId);

        return new BidNegotiationResponse(
                bid.getId(),
                announcement.getId(),
                bid.getStatus().name(),
                viewerIsTraveler ? "TRAVELER" : "SENDER",
                bid.getNegotiationRound(),
                config.maxRounds(),
                myTurn,
                myTurn && bid.getNegotiationRound() < config.maxRounds(),
                bid.getCurrency(),
                gross,
                netEur,
                commissionEur,
                suggestedGross(bid, announcement, gridEntities, rate, grid),
                bid.getWeightKg(),
                bid.getDescription(),
                bid.getContentCategory(),
                gridLines,
                customLines,
                photoUrls,
                counterparty.map(UserEntity::publicDisplayName).orElse(UserEntity.UNKNOWN_DISPLAY_NAME),
                announcement.getDepartureCity(),
                announcement.getArrivalCity(),
                announcement.getDepartureDate(),
                expiresAt(bid, last, open),
                messages,
                bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : null,
                counterpartyId,
                commissionDueBy(bid, announcement),
                viewerIsTraveler ? rate : null,
                counterparty.map(UserEntity::isGpPro).orElse(false),
                bid.getCommissionGridId(),
                viewerIsTraveler ? bid.getCommissionOverrideRate() : null,
                viewerIsTraveler ? bid.getCommissionVoucherFactor() : null);
    }

    /** Net voyageur d'un brut proposé ({@link #split}), null sans montant. */
    private static BigDecimal travelerNet(BigDecimal gross, BigDecimal rate, GridPricing grid) {
        return gross == null || gross.signum() <= 0 ? null : split(gross, rate, grid).netEur();
    }

    /**
     * Échéance du règlement de la commission d'un accord en espèces (FLUTTER-H7) : celle
     * de {@link BidTimeoutScheduler}, seule à décider de l'annulation. Nulle hors de l'état
     * PENDING + CASH, ou sans repère d'entrée dans la file du voyageur.
     */
    private static LocalDateTime commissionDueBy(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getStatus() != BidStatus.PENDING || bid.getPaymentMethod() != PaymentMethod.CASH
                || bid.getPendingSince() == null) {
            return null;
        }
        return BidTimeoutScheduler.deadlineFor(bid.getPendingSince(), announcement.getDepartureDate());
    }

    /**
     * Échéance d'inactivité, comptée depuis le dernier message et non depuis la
     * dernière écriture sur le bid : c'est exactement le critère du scheduler, et une
     * simple lecture du fil ne doit pas repousser sa date d'expiration.
     */
    private LocalDateTime expiresAt(BidEntity bid, BidNegotiationMessageEntity last, boolean open) {
        if (!open) {
            return null;
        }
        LocalDateTime reference = last != null && last.getCreatedAt() != null
                ? last.getCreatedAt() : bid.getUpdatedAt();
        return reference != null ? reference.plusHours(config.inactivityHours()) : null;
    }

    /**
     * Total « au barème » du voyageur, brut : ce que coûterait le même colis sans
     * négociation. Sert de repère à l'écran. Les articles hors grille en sont exclus,
     * le voyageur ne les ayant jamais tarifés.
     */
    private BigDecimal suggestedGross(BidEntity bid, AnnouncementEntity announcement,
                                      List<BidGridItemEntity> gridEntities, BigDecimal rate, GridPricing grid) {
        BigDecimal net = BigDecimal.ZERO;
        for (BidGridItemEntity item : gridEntities) {
            net = net.add(item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (bid.getWeightKg() != null && announcement.getPricePerKg() != null) {
            net = net.add(announcement.getPricePerKg().multiply(bid.getWeightKg()));
        }
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return grossUp(net, rate, grid);
    }

    /**
     * Prix unitaire d'une ligne de grille, comme {@code PriceGridService#displayPrice} sur le
     * trajet : sur un fil à grille, le prix du voyageur (décision du propriétaire, spec §5.3) ;
     * la commission, ni proportionnelle ni divisible par article (tranches, minimum), n'est
     * comptée que sur le total ({@link #suggestedGross}). Sans grille, brut au taux figé.
     */
    private static BigDecimal unitPriceDisplay(BigDecimal net, BigDecimal rate, GridPricing grid) {
        return grid != null ? net : grossUp(net, rate, null);
    }

    /** Brut d'un net : {@link GridPricing#grossForNet} sur un fil à grille, sinon net × (1 + taux figé). */
    private static BigDecimal grossUp(BigDecimal net, BigDecimal rate, GridPricing grid) {
        if (grid != null) {
            return grid.grossForNet(net);
        }
        return net.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }
}
