package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.matching.MatchingService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.*;
import com.yadony.api.requests.entity.*;
import com.yadony.api.requests.event.*;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.yadony.api.city.CityEntity;
import com.yadony.api.payments.PriceBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PackageRequestService {


    private final PackageRequestRepository repository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final RequestsConfig config;
    private final NegotiationThreadRepository threadRepository;
    private final com.yadony.api.city.CityRepository cityRepository;
    private final CommissionProperties commissionProperties;
    private final StorageService storageService;
    private final PackageRequestPhotoService photoService;
    private final FavoriteRepository favoriteRepository;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final PackageRequestSearchMapper packageRequestSearchMapper;
    private final MatchingService matchingService;
    private final YadonyConfigProperties yadonyConfig;
    private final AnnouncementRepository announcementRepository;
    private final CommissionRateResolver commissionRateResolver;

    public PackageRequestService(PackageRequestRepository repository,
                                  UserRepository userRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  AuditService auditService,
                                  RequestsConfig config,
                                  NegotiationThreadRepository threadRepository,
                                  com.yadony.api.city.CityRepository cityRepository,
                                  CommissionProperties commissionProperties,
                                  StorageService storageService,
                                  PackageRequestPhotoService photoService,
                                  FavoriteRepository favoriteRepository,
                                  ActiveCurrencyResolver activeCurrencyResolver,
                                  PackageRequestSearchMapper packageRequestSearchMapper,
                                  MatchingService matchingService,
                                  YadonyConfigProperties yadonyConfig,
                                  AnnouncementRepository announcementRepository,
                                  CommissionRateResolver commissionRateResolver) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.config = config;
        this.threadRepository = threadRepository;
        this.cityRepository = cityRepository;
        this.commissionProperties = commissionProperties;
        this.storageService = storageService;
        this.photoService = photoService;
        this.favoriteRepository = favoriteRepository;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.packageRequestSearchMapper = packageRequestSearchMapper;
        this.matchingService = matchingService;
        this.yadonyConfig = yadonyConfig;
        this.announcementRepository = announcementRepository;
        this.commissionRateResolver = commissionRateResolver;
    }

    /**
     * Devis transparent pour l'expéditeur pendant qu'il fixe son budget (étape 3
     * de publication) : aucun voyageur/thread n'existe encore, donc pas d'override
     * voyageur possible — seuls override expéditeur + global + promo entrent en
     * jeu. [budgetEur] est le montant que l'expéditeur est prêt à payer (gross,
     * même sens que {@code totalBudgetEur}), FIXE : la commission affichée reste
     * toujours au taux de base (transparence, jamais faussée par le promo — même
     * contrat que {@link NegotiationService#quote}). Le promo, lui, ne réduit pas
     * ce budget mais AUGMENTE ce que le voyageur toucherait pour ce même budget
     * (net = seul champ que le promo fait bouger ici) — plus attractif pour les
     * voyageurs, à dépense égale pour l'expéditeur.
     */
    @Transactional(readOnly = true)
    public NegotiationQuoteResponse quote(UUID senderId, BigDecimal budgetEur, String promoCode) {
        String code = promoCode != null ? promoCode.strip() : null;

        // Résolu EN PREMIER : promo invalide → propage avant tout autre appel
        // (même contrat que NegotiationService.quote / BidService.quote).
        BigDecimal finalRate = null;
        boolean promoApplied = false;
        if (code != null && !code.isBlank()) {
            finalRate = commissionRateResolver.resolve(null, senderId, code);
            promoApplied = true;
        }

        BigDecimal baseRate = commissionRateResolver.resolve(null, senderId);
        BigDecimal netBase = budgetEur.divide(BigDecimal.ONE.add(baseRate), 2, RoundingMode.HALF_UP);
        BigDecimal commissionEur = budgetEur.subtract(netBase).setScale(2, RoundingMode.HALF_UP);

        String promoLabel = null;
        BigDecimal net = netBase;
        if (promoApplied) {
            net = budgetEur.divide(BigDecimal.ONE.add(finalRate), 2, RoundingMode.HALF_UP);
            BigDecimal discountPoints = baseRate.subtract(finalRate).max(BigDecimal.ZERO);
            long pct = discountPoints.multiply(BigDecimal.valueOf(100)).longValue();
            promoLabel = "Code " + code.toUpperCase() + " : " + pct + " % de réduction";
        }

        return new NegotiationQuoteResponse(net, baseRate, commissionEur, budgetEur, promoApplied, promoLabel);
    }

    // ─── create ─────────────────────────────────────────────────────────────────

    @Transactional
    public PackageRequestResponse create(UUID senderId, PackageRequestCreateRequest req) {
        return toResponse(createAndReturnEntity(senderId, req));
    }

    /**
     * Core creation logic returning the saved entity directly.
     * Used by {@link #create} and by tests that need to assert entity-level fields.
     *
     * <p>Business rules applied here:
     * <ul>
     *   <li>Transport mode is always {@code PLANE} (avion-only).</li>
     *   <li>{@link ParcelSize} is derived from {@code weightKg} via
     *       {@link ParcelSize#fromWeightKg}.</li>
     *   <li>The caller supplies a <em>gross</em> budget (including the commission, rate from {@code yadony.commission.rate}).
     *       We store the <em>net</em> price: {@code net = gross / (1 + rate)}.</li>
     *   <li>If {@code !negotiable}, a budget is mandatory (HTTP 422 otherwise).</li>
     * </ul>
     */
    /**
     * Le paiement mobile money est retiré des nouveaux envois (voir
     * mobile-money-bid-payment-retired dans BidService) — les demandes ne
     * peuvent donc plus le déclarer comme mode accepté, sinon le flux de
     * paiement échouerait après acceptation.
     */
    private static void rejectMobileMoneyMethods(Set<PaymentMethod> methods) {
        if (methods != null
                && (methods.contains(PaymentMethod.WAVE)
                    || methods.contains(PaymentMethod.ORANGE_MONEY))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "request/mobile-money-payment-retired");
        }
    }

    /**
     * D4 : expéditeur suspendu de publication (décision admin). Même format d'erreur
     * RFC 7807 que {@code AnnouncementService.assertPublishingNotSuspended} (code
     * {@code publishing-suspended}, catalogue d'erreurs app mobile partagé) — c'est
     * là que s'arrête la parité. Côté colis, le brouillon reste délibérément
     * autorisé pour un expéditeur suspendu : seule la publication (création directe
     * ou {@link #publish}) est bloquée, contrairement aux annonces où la garde
     * bloque aussi la création de brouillon. Ne pas « rétablir la parité » en
     * bloquant le brouillon ici — l'asymétrie est un choix assumé, pas un oubli.
     */
    private static void assertPublishingNotSuspended(UserEntity sender) {
        if (sender.isPublishingSuspended()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "publishing-suspended",
                    "Publishing Suspended",
                    "La publication est suspendue. Contactez le support.");
        }
    }

    @Transactional
    public PackageRequestEntity createAndReturnEntity(UUID senderId, PackageRequestCreateRequest req) {
        UserEntity sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));

        boolean isDraft = Boolean.TRUE.equals(req.saveAsDraft());

        // Un brouillon n'est pas publié : ni KYC ni quota de demandes ouvertes ne
        // s'appliquent encore. Les deux sont rejoués à la publication.
        //
        // Le contrôle KYC garde sa position d'origine (avant les validations métier)
        // et le contrôle de quota la sienne (après) : les tests de validation
        // existants (mobile money, budget ferme, corridor, date) n'attendent pas
        // d'appel à countBySenderIdAndStatusIn et échoueraient sur un mock non
        // stubbé (long non-stubbé = 0, comparé à maxOpenRequestsPerSender() = 0
        // par défaut → 409 déclenché avant la validation attendue).
        if (!isDraft && sender.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }
        if (!isDraft) {
            assertPublishingNotSuspended(sender);
        }
        rejectMobileMoneyMethods(req.acceptedPaymentMethods());
        if (req.departureCity().equalsIgnoreCase(req.arrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/invalid-corridor");
        }
        if (req.desiredDate().isAfter(LocalDate.now().plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/date-too-far");
        }
        // Un brouillon peut rester sans budget décidé — le prix ferme n'est exigé
        // qu'à la publication (cf. requireTargetPrice appelé dans publish()).
        if (!isDraft) {
            requireTargetPrice(req.totalBudgetEur());
            long openCount = repository.countBySenderIdAndStatusIn(senderId,
                List.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING));
            if (openCount >= config.maxOpenRequestsPerSender()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "request/max-open-reached");
            }
        } else {
            assertDraftQuotaAvailable(senderId, sender);
        }

        // gross → net conversion: net = gross / (1 + commissionRate)
        BigDecimal netTarget = null;
        if (req.totalBudgetEur() != null) {
            BigDecimal divisor = BigDecimal.ONE.add(commissionProperties.rate());
            netTarget = req.totalBudgetEur().divide(divisor, 2, RoundingMode.HALF_UP);
        }

        PackageRequestEntity entity = new PackageRequestEntity();
        entity.setSenderId(senderId);
        entity.setCurrency(activeCurrencyResolver.resolve(senderId));
        entity.setDepartureCity(req.departureCity());
        entity.setArrivalCity(req.arrivalCity());
        entity.setDesiredDate(req.desiredDate());
        entity.setDateToleranceDays((short) req.dateToleranceDays());
        entity.setWeightKg(req.weightKg());
        entity.setParcelSize(ParcelSize.fromWeightKg(req.weightKg()));
        entity.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        // Normalisé à l'écriture (C2) — cf. ContentCategoryNormalizer javadoc.
        entity.setContentCategory(ContentCategoryNormalizer.normalizeJoined(req.contentCategory()));
        entity.setDescription(req.description());
        entity.setTargetPriceEur(netTarget);
        entity.setPhotoUrl(sanitizeLegacyPhotoUrl(req.photoUrl()));
        entity.setPickupNeighborhood(req.pickupNeighborhood());
        entity.setDeliveryNeighborhood(req.deliveryNeighborhood());
        entity.setNegotiable(req.negotiable());
        entity.setAcceptedPaymentMethods(req.acceptedPaymentMethods());
        entity.setPromoCode(normalizePromoCode(req.promoCode()));
        entity.setStatus(isDraft ? PackageRequestStatus.DRAFT : PackageRequestStatus.OPEN);
        // Le disclaimer douanier est accepté à la publication. Tant que la demande
        // est un brouillon, l'expéditeur n'a rien publié — donc rien signé.
        if (!isDraft) {
            entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        }

        PackageRequestEntity saved = repository.save(entity);
        photoService.replacePhotos(saved.getId(), senderId, req.photoKeys());

        if (isDraft) {
            auditService.log("PACKAGE_REQUEST", saved.getId(), "DRAFT_CREATED", senderId,
                Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));
        } else {
            eventPublisher.publishEvent(new PackageRequestCreatedEvent(
                saved.getId(), senderId, saved.getDepartureCity(),
                saved.getArrivalCity(), saved.getDesiredDate()
            ));
            auditService.log("PACKAGE_REQUEST", saved.getId(), "CREATED", senderId,
                Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));
        }

        return saved;
    }

    /**
     * Plafonne les brouillons d'un expéditeur au même quota que les trajets
     * (yadony.limits.drafts) : un utilisateur a un quota de brouillons, pas un
     * quota par type d'objet. Appelé aussi à la dépublication, sans quoi
     * dépublier deviendrait un moyen de contourner le plafond.
     */
    private void assertDraftQuotaAvailable(UUID senderId, UserEntity sender) {
        YadonyConfigProperties.Limits limits = yadonyConfig.limits() != null
            ? yadonyConfig.limits()
            : new YadonyConfigProperties.Limits(null, null);
        int maxDrafts = sender.isProAccount() ? limits.maxDraftsPro() : limits.maxDrafts();
        long draftCount = repository.countBySenderIdAndStatus(senderId, PackageRequestStatus.DRAFT)
            + announcementRepository.countByTravelerIdAndStatus(senderId, AnnouncementStatus.DRAFT);
        if (draftCount >= maxDrafts) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "draft-limit-reached");
        }
    }

    // ─── update ──────────────────────────────────────────────────────────────────

    /**
     * Modifie une demande tant qu'aucun accord n'a été conclu avec un voyageur
     * (statut {@code DRAFT}, {@code OPEN} ou {@code NEGOTIATING}). Une fois {@code ACCEPTED} ou
     * terminée → 409 {@code request/not-editable}.
     *
     * <p>Les termes de la demande changent : toute offre en cours ({@code OPEN})
     * est automatiquement rejetée ({@code AUTO_REJECTED}) — les voyageurs devront
     * re-proposer sur les nouveaux termes — et la demande repasse {@code OPEN}.
     * Mêmes validations métier que la création (corridor, date, budget si ferme).
     */
    @Transactional
    public PackageRequestResponse update(UUID callerUid, UUID requestId, PackageRequestCreateRequest req) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() != PackageRequestStatus.DRAFT
            && entity.getStatus() != PackageRequestStatus.OPEN
            && entity.getStatus() != PackageRequestStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-editable");
        }
        rejectMobileMoneyMethods(req.acceptedPaymentMethods());
        if (req.departureCity().equalsIgnoreCase(req.arrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/invalid-corridor");
        }
        if (req.desiredDate().isAfter(LocalDate.now().plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/date-too-far");
        }
        // Même règle qu'à la création : un brouillon n'exige pas de budget décidé.
        if (entity.getStatus() != PackageRequestStatus.DRAFT) {
            requireTargetPrice(req.totalBudgetEur());
        }

        BigDecimal netTarget = null;
        if (req.totalBudgetEur() != null) {
            BigDecimal divisor = BigDecimal.ONE.add(commissionProperties.rate());
            netTarget = req.totalBudgetEur().divide(divisor, 2, RoundingMode.HALF_UP);
        }

        // Les termes changent → rejeter les offres en cours ; la demande repasse OPEN.
        threadRepository.findByPackageRequestId(requestId).forEach(t -> {
            if (t.getStatus() == NegotiationThreadStatus.OPEN) {
                t.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
                threadRepository.save(t);
            }
        });

        entity.setDepartureCity(req.departureCity());
        entity.setArrivalCity(req.arrivalCity());
        entity.setDesiredDate(req.desiredDate());
        entity.setDateToleranceDays((short) req.dateToleranceDays());
        entity.setWeightKg(req.weightKg());
        entity.setParcelSize(ParcelSize.fromWeightKg(req.weightKg()));
        // Normalisé à l'écriture (C2) — cf. ContentCategoryNormalizer javadoc.
        entity.setContentCategory(ContentCategoryNormalizer.normalizeJoined(req.contentCategory()));
        entity.setDescription(req.description());
        entity.setTargetPriceEur(netTarget);
        entity.setPhotoUrl(sanitizeLegacyPhotoUrl(req.photoUrl()));
        entity.setPickupNeighborhood(req.pickupNeighborhood());
        entity.setDeliveryNeighborhood(req.deliveryNeighborhood());
        entity.setNegotiable(req.negotiable());
        entity.setAcceptedPaymentMethods(req.acceptedPaymentMethods());
        entity.setPromoCode(normalizePromoCode(req.promoCode()));
        // Repasser en OPEN sert à sortir d'une négociation dont les termes ont
        // changé. Un brouillon n'a pas de négociation et ne doit pas être publié
        // par une simple édition.
        if (entity.getStatus() != PackageRequestStatus.DRAFT) {
            entity.setStatus(PackageRequestStatus.OPEN);
        }

        PackageRequestEntity saved = repository.save(entity);
        // photoKeys == null → on conserve les photos existantes (édition sans toucher aux photos).
        // photoKeys fourni (même vide) → remplace l'ensemble.
        if (req.photoKeys() != null) {
            photoService.replacePhotos(saved.getId(), callerUid, req.photoKeys());
        }

        auditService.log("PACKAGE_REQUEST", saved.getId(), "UPDATED", callerUid,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }

    // ─── publish ─────────────────────────────────────────────────────────────────

    /**
     * Publie un brouillon (DRAFT → OPEN).
     *
     * <p>Toutes les validations de publication sont rejouées et non supposées
     * acquises à la création : les données ont pu être modifiées depuis, et une
     * date sort naturellement de la fenêtre autorisée avec le temps.
     */
    @Transactional
    public PackageRequestResponse publish(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // 404 et non 403 : un brouillon est invisible des tiers, répondre « interdit »
        // révélerait son existence.
        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found");
        }
        if (entity.getStatus() != PackageRequestStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-draft");
        }

        UserEntity sender = userRepository.findById(callerUid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        assertPublishingNotSuspended(sender);
        if (sender.getKycStatus() != KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kyc/not-verified");
        }
        if (entity.getDepartureCity().equalsIgnoreCase(entity.getArrivalCity())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/invalid-corridor");
        }
        if (entity.getDesiredDate().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/desired-date-in-past");
        }
        if (entity.getDesiredDate().isAfter(LocalDate.now().plusDays(90))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/date-too-far");
        }
        requireTargetPrice(entity.getTargetPriceEur());
        long openCount = repository.countBySenderIdAndStatusIn(callerUid,
            List.of(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING));
        if (openCount >= config.maxOpenRequestsPerSender()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/max-open-reached");
        }

        entity.setStatus(PackageRequestStatus.OPEN);
        entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
        PackageRequestEntity saved = repository.save(entity);

        eventPublisher.publishEvent(new PackageRequestCreatedEvent(
            saved.getId(), callerUid, saved.getDepartureCity(),
            saved.getArrivalCity(), saved.getDesiredDate()
        ));
        auditService.log("PACKAGE_REQUEST", saved.getId(), "PUBLISHED", callerUid,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }

    // ─── unpublish ───────────────────────────────────────────────────────────────

    /**
     * Retire une demande de la circulation sans l'annuler (OPEN → DRAFT).
     *
     * <p>Annuler est terminal ; dépublier ne l'est pas. L'opération n'est ouverte
     * que tant qu'aucun voyageur ne s'est engagé : au-delà, des tiers ont agi sur
     * la foi de la publication et le retrait unilatéral ne leur est pas opposable.
     */
    @Transactional
    public PackageRequestResponse unpublish(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        // La demande est publique ici : 403 ne révèle rien qu'on ne sache déjà.
        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() != PackageRequestStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-unpublishable");
        }
        // Test distinct du précédent : un thread peut exister alors que la demande
        // est encore OPEN (offre reçue mais pas encore ouverte en négociation).
        if (!threadRepository.findByPackageRequestId(requestId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/has-offers");
        }

        UserEntity sender = userRepository.findById(callerUid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user/not-found"));
        assertDraftQuotaAvailable(callerUid, sender);

        entity.setStatus(PackageRequestStatus.DRAFT);
        PackageRequestEntity saved = repository.save(entity);

        auditService.log("PACKAGE_REQUEST", saved.getId(), "UNPUBLISHED", callerUid,
            Map.of("corridor", saved.getDepartureCity() + "->" + saved.getArrivalCity()));

        return toResponse(saved);
    }

    // ─── getById ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PackageRequestResponse getById(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        boolean isOwner = entity.getSenderId().equals(callerUid);
        boolean isThreadParticipant = threadRepository
            .existsByPackageRequestIdAndTravelerId(requestId, callerUid);
        // Les demandes publiquement listées en recherche (OPEN / NEGOTIATING)
        // sont consultables par n'importe quel voyageur : il doit pouvoir voir
        // le détail pour décider de faire une offre. Le DTO ne contient aucune
        // PII (aucune info destinataire) et les threads/messages restent privés
        // (endpoint dédié). Dès que la demande est ACCEPTED/terminée, l'accès
        // est de nouveau restreint au propriétaire et aux participants d'un thread.
        boolean isPubliclyListed = entity.getStatus() == PackageRequestStatus.OPEN
            || entity.getStatus() == PackageRequestStatus.NEGOTIATING;

        if (!isOwner && !isThreadParticipant && !isPubliclyListed) {
            // Un brouillon n'a jamais été rendu public : répondre « interdit »
            // apprendrait à un tiers qu'une demande existe derrière cet id. Les
            // autres statuts non listés ont, eux, déjà été publics.
            HttpStatus status = entity.getStatus() == PackageRequestStatus.DRAFT
                ? HttpStatus.NOT_FOUND
                : HttpStatus.FORBIDDEN;
            String reason = entity.getStatus() == PackageRequestStatus.DRAFT
                ? "request/not-found"
                : "request/forbidden";
            throw new ResponseStatusException(status, reason);
        }
        // Voyageur (non-owner) : expose son thread ACTIF pour que l'app bascule le CTA
        // (« Proposer mon trajet » → « Voir ma négociation » / proposition de trajet).
        var viewerThread = isOwner
            ? java.util.Optional.<com.yadony.api.requests.entity.NegotiationThreadEntity>empty()
            : threadRepository.findActiveByPackageRequestIdAndTravelerId(requestId, callerUid);
        return toResponse(entity,
            viewerThread.map(com.yadony.api.requests.entity.NegotiationThreadEntity::getId).orElse(null),
            viewerThread.map(t -> t.getStatus().name()).orElse(null));
    }

    // ─── findMine ─────────────────────────────────────────────────────────────────

    // Lecture seule : la réconciliation NEGOTIATING → OPEN (plus de thread actif)
    // est déjà assurée par NegotiationExpiryRunner (scheduler idempotent) et par
    // reopenRequestWhenNoActiveNegotiation() aux points de transition (reject,
    // cancelNegotiation). Un GET ne doit pas écrire — ça évitait un N+1 + save
    // conditionnel à chaque ouverture de "Mes demandes".
    public Page<PackageRequestResponse> findMine(UUID senderId, Pageable pageable) {
        return repository.findBySenderIdOrderByCreatedAtDesc(senderId, pageable)
            .map(this::toResponse);
    }

    // ─── cancel ──────────────────────────────────────────────────────────────────

    /**
     * L'expéditeur annule sa demande. Tous les fils encore actifs meurent avec
     * elle, quel que soit leur stade.
     *
     * <p>Le verrou pessimiste n'est pas décoratif : sans lui, un règlement de
     * commission concurrent (qui verrouille la demande puis vérifie qu'elle est
     * toujours disponible) pourrait débiter le voyageur et sceller l'accord
     * pendant que cette transaction annule et soft-delete la même demande. Avec
     * le verrou les deux se sérialisent : soit le règlement voit CANCELLED et
     * refuse avant tout appel Stripe, soit l'annulation voit ACCEPTED et rend
     * 409.
     *
     * <p>La demande est soft-deletée, donc introuvable ensuite ({@code
     * @SQLRestriction("deleted_at IS NULL")}). Tout ce qui reste attaché à un fil
     * doit donc être soldé ICI, pendant qu'on tient encore la demande : ne
     * traiter que les fils {@code OPEN}, comme avant, laissait un voyageur en
     * AWAITING_COMMISSION débité pour une demande évaporée, son trajet dédié
     * bloqué en « Mes trajets », et aucune notification pour l'en informer.
     */
    @Transactional
    public void cancel(UUID callerUid, UUID requestId) {
        PackageRequestEntity entity = repository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        if (entity.getStatus() == PackageRequestStatus.ACCEPTED ||
            entity.getStatus() == PackageRequestStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/already-accepted");
        }

        entity.setStatus(PackageRequestStatus.CANCELLED);
        entity.softDelete();
        repository.save(entity);

        String senderName = userRepository.findById(callerUid)
            .map(UserEntity::publicDisplayName)
            .orElse(UserEntity.UNKNOWN_DISPLAY_NAME);

        threadRepository.findByPackageRequestId(requestId).stream()
            .filter(t -> t.getStatus().isActive())
            .forEach(t -> {
                NegotiationThreadStatus previous = t.getStatus();
                t.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
                t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
                threadRepository.save(t);
                softDeleteOrphanedDedicatedTrip(t, callerUid);
                // Chaque fil tué laisse sa propre trace, comme sur tous les
                // autres chemins de mort d'un fil : c'est précisément ici qu'on
                // en aura besoin, la demande étant soft-deletée donc introuvable.
                auditService.log("NEGOTIATION_THREAD", t.getId(), "AUTO_REJECTED", callerUid,
                    Map.of("reason", "request-cancelled", "previousStatus", previous.name()));

                // Le statut d'avant l'annulation part dans l'événement — seul ce
                // point du code le connaît. Les effets financiers (hold Stripe à
                // annuler, commission à rembourser) en sont dérivés par le record
                // et exécutés par des écouteurs AFTER_COMMIT : un rollback ne
                // déclenche rien, donc aucune fuite d'argent sur une annulation
                // qui n'a pas eu lieu.
                eventPublisher.publishEvent(new NegotiationCancelledEvent(
                    t.getId(), requestId, callerUid, t.getTravelerId(), senderName, previous));
            });

        auditService.log("PACKAGE_REQUEST", requestId, "CANCELLED", callerUid,
            Map.of("status", "CANCELLED"));
    }

    /**
     * Soft-delete du trajet DÉDIÉ devenu orphelin — créé exclusivement pour
     * cette demande, sa capacité disponible reste à 0 tant que le surplus n'est
     * pas ouvert après paiement : une fois orphelin, plus personne ne peut le
     * réserver et il resterait {@code ACTIVE} pour toujours dans « Mes trajets »
     * du voyageur, capacité réservée bloquée.
     *
     * <p>Compare l'identifiant porté par le fil, jamais l'entité demande : elle
     * vient d'être soft-deletée par l'appelant, donc déjà introuvable.
     *
     * <p>Miroir de {@code NegotiationService#softDeleteOrphanedDedicatedTrip},
     * {@code NegotiationExpiryRunner#softDeleteOrphanedDedicatedTrip} et
     * {@code CommissionWindowExpiryRunner#softDeleteOrphanedDedicatedTrip} —
     * quatrième copie assumée : leur extraction en collaborateur commun casserait
     * les assertions de tests de chantiers antérieurs, dette actée dans le ledger.
     * Toute correction de cette règle doit être répercutée aux quatre endroits.
     */
    private void softDeleteOrphanedDedicatedTrip(NegotiationThreadEntity thread, UUID callerUid) {
        UUID announcementId = thread.getTravelerAnnouncementId();
        if (announcementId == null) {
            return;
        }
        announcementRepository.findById(announcementId).ifPresent(ann -> {
            if (thread.getPackageRequestId().equals(ann.getLinkedPackageRequestId())) {
                ann.softDelete();
                announcementRepository.save(ann);
                auditService.log("ANNOUNCEMENT", ann.getId(),
                    "DEDICATED_TRIP_ORPHANED_ON_REQUEST_CANCEL", callerUid,
                    Map.of("threadId", thread.getId().toString()));
            }
        });
    }

    // ─── completeDetails ─────────────────────────────────────────────────────────

    @Transactional
    public PackageRequestResponse completeDetails(UUID callerUid, UUID requestId,
                                                   PackageRequestCompleteDetailsRequest req,
                                                   String clientIp) {
        PackageRequestEntity entity = repository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));

        if (!entity.getSenderId().equals(callerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request/forbidden");
        }
        // Les détails peuvent être renseignés dès qu'un trajet est lié (thread
        // AWAITING_PAYMENT — juste avant le paiement, conformément au flux de
        // négociation) OU après acceptation complète (flux post-paiement « Mes envois »).
        boolean readyForDetails = entity.getStatus() == PackageRequestStatus.ACCEPTED
            || threadRepository.findByPackageRequestId(requestId).stream()
                .anyMatch(t -> t.getStatus()
                    == com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT);
        if (!readyForDetails) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "request/not-yet-accepted");
        }

        entity.setRecipientName(req.recipientName());
        entity.setRecipientPhone(req.recipientPhone());
        entity.setRecipientCity(req.recipientCity());
        // The disclaimer is normally signed at creation; set it defensively here
        // for legacy requests created before this behaviour existed.
        if (entity.getDisclaimerSignedAt() == null) {
            entity.setDisclaimerSignedAt(LocalDateTime.now(ZoneOffset.UTC));
            entity.setDisclaimerSignedIp(clientIp);
        }

        PackageRequestEntity saved = repository.save(entity);

        Map<String, Object> auditPayload = new java.util.HashMap<>();
        auditPayload.put("recipient", req.recipientName());
        if (req.recipientCity() != null) {
            auditPayload.put("city", req.recipientCity());
        }
        auditService.log("PACKAGE_REQUEST", requestId, "DETAILS_COMPLETED", callerUid, auditPayload);

        // Propagate to the marketplace-issued bid (if any) via the matching/
        // listener so "Mes envois" stays in sync with the package_request data.
        threadRepository.findByPackageRequestId(requestId).stream()
            .filter(t -> t.getStatus() == com.yadony.api.requests.entity.NegotiationThreadStatus.ACCEPTED
                      || t.getStatus() == com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT)
            .findFirst()
            .ifPresent(thread -> eventPublisher.publishEvent(
                new com.yadony.api.requests.event.PackageRequestDetailsCompletedEvent(
                    requestId,
                    thread.getId(),
                    callerUid,
                    req.recipientName(),
                    req.recipientPhone(),
                    saved.getDisclaimerSignedAt(),
                    saved.getDisclaimerSignedIp()
                )));

        return toResponse(saved);
    }

    // ─── search ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PackageRequestSearchResponse> search(Specification<PackageRequestEntity> spec,
                                                      Pageable pageable,
                                                      UUID callerId) {
        Set<UUID> favIds = loadFavIds(callerId);
        Page<PackageRequestEntity> page = repository.findAll(withActiveCurrency(spec, callerId), pageable);
        BatchMaps batch = buildBatchMaps(page.getContent());
        return page.map(e -> packageRequestSearchMapper.toSearchResponse(
                e, favIds.contains(e.getId()), batch.userMap, batch.cityMap, batch.photoMap));
    }

    /**
     * Recherche restreinte aux demandes compatibles avec les trajets actifs du
     * voyageur, triée par score de compatibilité décroissant.
     *
     * <p>La règle de match vit dans {@link MatchingService} et n'est pas exprimable
     * en SQL sans la dupliquer : on récupère donc l'ensemble des ids compatibles,
     * on applique la recherche filtrée dessus, puis on trie et pagine en mémoire.
     * L'ensemble est borné par le nombre de matchs du voyageur, du même ordre de
     * grandeur que ce que renvoie déjà {@code GET /travelers/me/matching-requests}
     * sans pagination.
     *
     * <p>Injection {@code requests → matching} assumée : lecture synchrone
     * unidirectionnelle nécessaire à la construction de la réponse, sans cycle.
     * Un Spring Event ne conviendrait pas, le résultat étant attendu par l'appelant.
     *
     * <p><b>Ordre des opérations :</b> on trie et on découpe les <em>entités</em>,
     * puis on ne mappe que la page. {@link #buildBatchMaps} déclenche une URL S3
     * présignée par photo et par avatar : le faire sur l'ensemble filtré signerait
     * des milliers d'URLs pour en renvoyer vingt. {@code totalElements} reste le
     * total filtré, pas la taille de la page.
     */
    @Transactional(readOnly = true)
    public Page<PackageRequestSearchResponse> searchMatchingMyTrips(Specification<PackageRequestEntity> spec,
                                                                     Pageable pageable,
                                                                     UUID callerId) {
        Map<UUID, MatchingService.MatchInfo> matches = matchingService.findBestMatchByRequestId(callerId);
        if (matches.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
        }

        Specification<PackageRequestEntity> restricted = withActiveCurrency(spec, callerId)
                .and(PackageRequestSpecifications.idIn(matches.keySet()));

        List<PackageRequestEntity> sorted = repository.findAll(restricted).stream()
                .sorted(matchOrder(matches))
                .toList();

        // Arithmétique en long : `size` est un int fourni par le client, from + size
        // déborderait en négatif sur une taille de page extrême (subList → 500).
        long fromLong = Math.min(pageable.getOffset(), sorted.size());
        long toLong = Math.min(fromLong + pageable.getPageSize(), sorted.size());
        List<PackageRequestEntity> pageEntities = sorted.subList((int) fromLong, (int) toLong);

        Set<UUID> favIds = loadFavIds(callerId);
        BatchMaps batch = buildBatchMaps(pageEntities);
        List<PackageRequestSearchResponse> content = pageEntities.stream()
                .map(e -> packageRequestSearchMapper.toSearchResponse(
                        e, favIds.contains(e.getId()), batch.userMap, batch.cityMap, batch.photoMap))
                .map(r -> r.withMatch(matches.get(r.id())))
                .toList();

        return new org.springframework.data.domain.PageImpl<>(content, pageable, sorted.size());
    }

    /**
     * Ordre total strict de la recherche « mes trajets » : score décroissant, puis
     * demande la plus récente, puis identifiant.
     *
     * <p>Le départage n'est pas cosmétique : dans ce chemin {@code dateScore} vaut
     * toujours 25 et {@code budgetScore} ne prend que 3 valeurs, les ex æquo sont la
     * règle. Sans ordre total, {@code findAll} sans {@code ORDER BY} laisse Postgres
     * choisir l'ordre des ex æquo, et une demande peut apparaître sur deux pages ou
     * sur aucune.
     */
    private static java.util.Comparator<PackageRequestEntity> matchOrder(
            Map<UUID, MatchingService.MatchInfo> matches) {
        return java.util.Comparator
                .comparingInt((PackageRequestEntity e) -> matches.get(e.getId()).matchScore())
                .reversed()
                .thenComparing(PackageRequestEntity::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(PackageRequestEntity::getId);
    }

    /**
     * Near-me variant: same filtering as {@link #search}, plus a geographic
     * post-filter applied in memory using the Haversine formula on the city
     * coordinates resolved via {@link com.yadony.api.city.CityRepository}.
     *
     * <p>Results within {@code radiusKm} of ({@code lat}, {@code lng}) are
     * returned, sorted by ascending distance. Requests whose departure city is
     * unknown to the city table are excluded from the geo set.
     *
     * <p>MVP trade-off: the SQL pagination still applies before the geo filter,
     * so a page may contain fewer items than {@code pageable.size}. Acceptable
     * because the dataset is small (<50k active requests). A future optimization
     * would JOIN the city table inside the JPA specification.
     */
    @Transactional(readOnly = true)
    public Page<PackageRequestSearchResponse> searchNearMe(Specification<PackageRequestEntity> spec,
                                                            Pageable pageable,
                                                            java.math.BigDecimal lat,
                                                            java.math.BigDecimal lng,
                                                            double radiusKm,
                                                            UUID callerId) {
        Set<UUID> favIds = loadFavIds(callerId);
        Page<PackageRequestEntity> rawPage = repository.findAll(withActiveCurrency(spec, callerId), pageable);
        BatchMaps batch = buildBatchMaps(rawPage.getContent());
        Page<PackageRequestSearchResponse> mapped = rawPage.map(e -> packageRequestSearchMapper.toSearchResponse(
                e, favIds.contains(e.getId()), batch.userMap, batch.cityMap, batch.photoMap));
        double latD = lat.doubleValue();
        double lngD = lng.doubleValue();
        List<PackageRequestSearchResponse> filtered = mapped.getContent().stream()
            .filter(r -> r.departureLat() != null && r.departureLng() != null)
            .map(r -> Map.entry(r, haversineKm(latD, lngD, r.departureLat().doubleValue(), r.departureLng().doubleValue())))
            .filter(e -> e.getValue() <= radiusKm)
            .sorted(java.util.Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .toList();
        return new org.springframework.data.domain.PageImpl<>(filtered, pageable, mapped.getTotalElements());
    }

    private Specification<PackageRequestEntity> withActiveCurrency(
            Specification<PackageRequestEntity> spec,
            UUID callerId) {
        return Specification.where(spec)
                .and(PackageRequestSpecifications.hasCurrency(activeCurrencyResolver.resolve(callerId)));
    }

    /** Immutable value object carrying the three batch-loaded maps for search mapping. */
    private record BatchMaps(Map<UUID, UserEntity> userMap,
                              Map<String, CityEntity> cityMap,
                              Map<UUID, List<PackageRequestPhotoResponse>> photoMap) {}

    /**
     * Batch-loads users, cities, and photos for a list of package-request entities in 3 queries
     * (one per resource type) instead of N×3 queries.
     */
    private BatchMaps buildBatchMaps(List<PackageRequestEntity> entities) {
        if (entities.isEmpty()) {
            return new BatchMaps(Map.of(), Map.of(), Map.of());
        }
        List<UUID> senderIds = entities.stream().map(PackageRequestEntity::getSenderId).distinct().toList();
        Map<UUID, UserEntity> userMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u, (a, b) -> a));

        Set<String> cityNames = new HashSet<>();
        for (PackageRequestEntity e : entities) {
            if (e.getDepartureCity() != null) cityNames.add(e.getDepartureCity());
            if (e.getArrivalCity() != null) cityNames.add(e.getArrivalCity());
        }
        Map<String, CityEntity> cityMap = cityRepository.findByNamesIgnoreCaseBatch(cityNames);

        List<UUID> requestIds = entities.stream().map(PackageRequestEntity::getId).toList();
        Map<UUID, List<PackageRequestPhotoResponse>> photoMap = photoService.activePhotosBatch(requestIds);

        return new BatchMaps(userMap, cityMap, photoMap);
    }

    /**
     * Batch-loads the set of package-request IDs favorited by the given traveler.
     * Returns an empty set when {@code callerId} is null (anonymous or non-traveler).
     */
    private Set<UUID> loadFavIds(UUID callerId) {
        if (callerId == null) {
            return Set.of();
        }
        return new HashSet<>(favoriteRepository.findTargetIds(callerId, FavoriteTargetType.PACKAGE_REQUEST));
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }

    // ─── Mappers ─────────────────────────────────────────────────────────────────

    PackageRequestResponse toResponse(PackageRequestEntity e) {
        return toResponse(e, null, null);
    }

    PackageRequestResponse toResponse(PackageRequestEntity e, java.util.UUID viewerThreadId,
                                      String viewerThreadStatus) {
        BigDecimal grossPriceEur = e.getTargetPriceEur() != null
            ? PriceBreakdown.fromNet(e.getTargetPriceEur(), commissionProperties.rate()).gross()
            : null;
        List<PackageRequestPhotoResponse> photos = photoService.activePhotos(e.getId());
        String photoUrl = photos.isEmpty() ? e.getPhotoUrl() : photos.get(0).url();
        return new PackageRequestResponse(
            e.getId(), e.getSenderId(),
            e.getDepartureCity(), e.getArrivalCity(),
            e.getDesiredDate(), e.getDateToleranceDays() != null ? e.getDateToleranceDays().intValue() : 0,
            e.getWeightKg(), e.getParcelSize(), e.getTransportMode(),
            e.getContentCategory(),
            e.getDescription(), e.getTargetPriceEur(), photoUrl,
            e.getPickupNeighborhood(), e.getDeliveryNeighborhood(),
            e.getStatus(), e.getCreatedAt(),
            e.isNegotiable(),
            e.getAcceptedPaymentMethods(),
            grossPriceEur,
            photos,
            viewerThreadId,
            viewerThreadStatus,
            e.getPromoCode(),
            e.getCurrency()
        );
    }

    /**
     * Reusable mapper: converts a {@link PackageRequestEntity} to a {@link PackageRequestSearchResponse}.
     * The {@code isFavorite} flag is supplied by the caller so this method remains pure and testable.
     * Delegates to {@link PackageRequestSearchMapper} so that external packages can also call
     * the mapper directly without injecting this service.
     */
    public PackageRequestSearchResponse toSearchResponse(PackageRequestEntity e, boolean isFavorite) {
        return packageRequestSearchMapper.toSearchResponse(e, isFavorite);
    }

    /**
     * Le champ legacy {@code photoUrl} n'accepte qu'une clé S3 interne, jamais une
     * URL absolue : sans ce garde-fou un expéditeur pouvait injecter du contenu
     * externe (pixel de tracking, image de phishing sous la marque Yadony) affiché à
     * tous les voyageurs parcourant le feed des demandes. Même protection que
     * {@code TrackingService}. Le chemin moderne validé est {@code photoKeys}.
     */
    private static String sanitizeLegacyPhotoUrl(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return photoUrl;
        }
        if (photoUrl.startsWith("http://") || photoUrl.startsWith("https://")
                || photoUrl.contains("..")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "request/invalid-photo-url");
        }
        return photoUrl;
    }

    private static void requireTargetPrice(BigDecimal targetPriceEur) {
        if (targetPriceEur == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "request/target-price-required");
        }
    }

    /** Trim + uppercase ; null/blank → null. Validation stricte différée au paiement réel. */
    private static String normalizePromoCode(String promoCode) {
        if (promoCode == null) return null;
        String trimmed = promoCode.strip();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }
}
