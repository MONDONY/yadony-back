package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yadony.api.common.PageResponse;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoService;
import com.yadony.api.referral.events.ReferralWalletRewardChosenEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core business logic for the referral system.
 *
 * <ul>
 *   <li>{@link #generateCodeForUser} — creates and persists a unique referral code on registration</li>
 *   <li>{@link #getMyReferral} — returns code + stats (lazy-creates code if absent)</li>
 *   <li>{@link #redeemCode} — links the current user as a referee under a referrer</li>
 *   <li>{@link #regenerateCode} — replaces the code (respects cooldown)</li>
 *   <li>{@link #getReferees} — the referrer's referees and their reward state</li>
 *   <li>{@link #chooseReward} — the referrer picks the reward of a referee's first delivery</li>
 * </ul>
 *
 * <p>{@link PromoService} est injecté directement, comme {@code CommissionVoucherService} et
 * comme {@code common/CommissionRateResolver} le fait : le code personnel est une règle de
 * tarification partagée dont l'identifiant doit être connu dans la même transaction que le
 * choix. Le crédit de solde, lui, passe par un événement (paquet {@code payments/wallet}).
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);
    private static final int MAX_CODE_RETRIES = 5;
    private static final String SHARE_URL_PREFIX = "https://yadony.com/r/";
    /** Le crédit de solde du parrainage est toujours en euros (décision produit V321). */
    static final String REWARD_WALLET_CURRENCY = "EUR";
    private static final String PERSONAL_CODE_PREFIX = "MERCI-";
    private static final int MAX_REFEREES_PAGE_SIZE = 50;

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralInvitationRepository referralInvitationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ReferralConfig config;
    private final com.yadony.api.voucher.CommissionVoucherService voucherService;
    private final com.yadony.api.voucher.VoucherConfig voucherConfig;
    private final PromoService promoService;
    private final ApplicationEventPublisher eventPublisher;
    private final Random random = new Random();

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralInvitationRepository referralInvitationRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           ReferralConfig config,
                           com.yadony.api.voucher.CommissionVoucherService voucherService,
                           com.yadony.api.voucher.VoucherConfig voucherConfig,
                           PromoService promoService,
                           ApplicationEventPublisher eventPublisher) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralInvitationRepository = referralInvitationRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.config = config;
        this.voucherService = voucherService;
        this.voucherConfig = voucherConfig;
        this.promoService = promoService;
        this.eventPublisher = eventPublisher;
    }

    // ── Code generation ──────────────────────────────────────────────────────

    /**
     * Generates and persists a unique referral code for the given user.
     * Called automatically after user registration via {@link UserRegisteredReferralListener}.
     */
    @Transactional
    public ReferralCodeEntity generateCodeForUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                        "Utilisateur introuvable : " + userId));

        String prefix = buildPrefix(user);
        String code = generateUniqueCode(prefix);

        ReferralCodeEntity entity = new ReferralCodeEntity();
        entity.setUserId(userId);
        entity.setCode(code);
        return referralCodeRepository.save(entity);
    }

    // ── Main read endpoint ───────────────────────────────────────────────────

    /**
     * Returns the referral dashboard for the authenticated user.
     * Lazily creates a code if the user does not have one yet.
     */
    @Transactional
    public MyReferralResponse getMyReferral(String firebaseUid) {
        UserEntity user = loadUserByFirebaseUid(firebaseUid);

        ReferralCodeEntity codeEntity = referralCodeRepository.findByUserId(user.getId())
                .orElseGet(() -> generateCodeForUser(user.getId()));

        String code = codeEntity.getCode();
        String shareUrl = SHARE_URL_PREFIX + code;

        UUID userId = user.getId();
        long totalInvited = referralInvitationRepository.countByReferrerUserId(userId);
        // Cumulatifs : un filleul qui a déjà envoyé reste compté comme inscrit. Auparavant
        // « signedUp » ne comptait que le statut SIGNED_UP, si bien qu'un filleul passé
        // REWARDED disparaissait des inscrits.
        long signedUp = referralInvitationRepository.countByReferrerUserIdAndRefereeUserIdIsNotNull(userId);
        long firstDelivery = referralInvitationRepository.countByReferrerUserIdAndStatusIn(
                userId, List.of("FIRST_DELIVERY", "REWARDED"));
        long rewarded = referralInvitationRepository.countByReferrerUserIdAndRewardStatus(
                userId, ReferralRewardStatus.CHOSEN);
        long pendingRewards = referralInvitationRepository.countByReferrerUserIdAndRewardStatus(
                userId, ReferralRewardStatus.AVAILABLE);

        // Lot 3 : ce n'est plus un montant crédité mais des bons de réduction de
        // commission — le premier à expirer est celui qui presse le plus l'écran.
        java.util.List<com.yadony.api.voucher.CommissionVoucherEntity> activeVouchers =
                voucherService.listActive(user.getId());
        java.time.LocalDateTime nextVoucherExpiresAt = activeVouchers.isEmpty()
                ? null
                : activeVouchers.get(0).getExpiresAt();
        // Le facteur affiché est toujours le barème courant, pas celui d'un bon
        // particulier : la promesse « invite et gagne X % » doit rester correcte
        // même pour un parrain qui n'a encore aucun bon actif.
        java.math.BigDecimal voucherFactor = voucherConfig.getFactor();

        boolean hasBeenReferred = referralInvitationRepository.existsByRefereeUserId(userId);

        return new MyReferralResponse(
                code, shareUrl,
                (int) totalInvited, (int) signedUp, (int) rewarded,
                hasBeenReferred, activeVouchers.size(), voucherFactor, nextVoucherExpiresAt,
                (int) firstDelivery, (int) pendingRewards,
                config.getRewardWalletAmount(), REWARD_WALLET_CURRENCY, config.getRewardDiscountPercent()
        );
    }

    // ── Redeem ───────────────────────────────────────────────────────────────

    /**
     * Links the authenticated user (referee) to a referrer via the supplied code.
     * Creates a {@link ReferralInvitationEntity} with status {@code SIGNED_UP}.
     *
     * @throws YadonyBusinessException 404 if the code is unknown
     * @throws YadonyBusinessException 409 if the user tries to refer themselves
     * @throws YadonyBusinessException 409 if the user was already referred
     */
    @Transactional
    public void redeemCode(String firebaseUid, String code) {
        UserEntity referee = loadUserByFirebaseUid(firebaseUid);

        ReferralCodeEntity referralCode = referralCodeRepository.findByCode(code)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "referral-code-not-found",
                        "Code de parrainage introuvable",
                        "Le code " + code + " n'existe pas"));

        UUID referrerId = referralCode.getUserId();

        if (referrerId.equals(referee.getId())) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "self-referral",
                    "Auto-parrainage interdit",
                    "Vous ne pouvez pas utiliser votre propre code de parrainage");
        }

        // Un compte n'est parrainé qu'une fois, quel que soit l'avancement de l'invitation.
        if (referralInvitationRepository.existsByRefereeUserId(referee.getId())) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT, "already-referred",
                    "Parrainage déjà effectué",
                    "Vous avez déjà utilisé un code de parrainage");
        }

        ReferralInvitationEntity invitation = new ReferralInvitationEntity();
        invitation.setReferrerUserId(referrerId);
        invitation.setRefereeUserId(referee.getId());
        invitation.setStatus("SIGNED_UP");
        invitation.setCodeUsed(code);
        referralInvitationRepository.save(invitation);

        auditService.log(
                "REFERRAL_INVITATION",
                invitation.getId(),
                "REFERRAL_CODE_REDEEMED",
                referee.getId(),
                Map.of(
                        "referrerId", referrerId.toString(),
                        "refereeId", referee.getId().toString(),
                        "code", code
                )
        );

        log.info("Referral code redeemed: referee={} referrer={} code={}",
                referee.getId(), referrerId, code);
    }

    // ── Code regeneration ────────────────────────────────────────────────────

    /**
     * Regenerates the user's referral code, subject to a cooldown period.
     *
     * @throws YadonyBusinessException 429 if cooldown has not expired
     */
    @Transactional
    public MyReferralResponse regenerateCode(String firebaseUid) {
        UserEntity user = loadUserByFirebaseUid(firebaseUid);

        Optional<ReferralCodeEntity> existing = referralCodeRepository.findByUserId(user.getId());
        if (existing.isPresent()) {
            LocalDateTime cooldownBoundary = LocalDateTime.now(ZoneOffset.UTC)
                    .minusDays(config.getCodeRegenerationCooldownDays());
            if (existing.get().getCreatedAt().isAfter(cooldownBoundary)) {
                throw new YadonyBusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "regeneration-cooldown",
                        "Délai de régénération non écoulé",
                        "Vous ne pouvez régénérer votre code qu'une fois tous les "
                                + config.getCodeRegenerationCooldownDays() + " jours");
            }
            referralCodeRepository.delete(existing.get());
        }

        generateCodeForUser(user.getId());
        return getMyReferral(firebaseUid);
    }

    // ── Referees & reward choice ─────────────────────────────────────────────

    /**
     * Filleuls du parrain connecté, du plus récent au plus ancien, avec l'état de leur
     * récompense. Taille de page bornée à {@value #MAX_REFEREES_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReferralRefereeResponse> getReferees(String firebaseUid, int page, int size) {
        UserEntity user = loadUserByFirebaseUid(firebaseUid);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_REFEREES_PAGE_SIZE));
        Page<ReferralInvitationEntity> invitations =
                referralInvitationRepository.findByReferrerUserIdOrderByCreatedAtDesc(user.getId(), pageable);

        Map<UUID, UserEntity> referees = userRepository.findAllById(invitations.getContent().stream()
                        .map(ReferralInvitationEntity::getRefereeUserId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, PromoCodeEntity> promos = promoService.findAllByIds(invitations.getContent().stream()
                        .map(ReferralInvitationEntity::getRewardPromoCodeId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(PromoCodeEntity::getId, Function.identity()));

        return PageResponse.from(invitations.map(inv -> toRefereeResponse(inv,
                inv.getRefereeUserId() == null ? null : referees.get(inv.getRefereeUserId()),
                inv.getRewardPromoCodeId() == null ? null : promos.get(inv.getRewardPromoCodeId()))));
    }

    /**
     * Le parrain choisit la récompense du premier envoi livré d'un filleul : crédit de solde
     * (EUR) ou code promo personnel. Un seul choix par invitation (verrou pessimiste).
     *
     * @throws YadonyBusinessException 422 {@code referral-reward-choice-invalid} pour un choix non proposé,
     *                                 404 {@code referral-invitation-not-found} si l'invitation n'existe pas
     *                                 ou n'appartient pas au parrain connecté,
     *                                 409 {@code referral-reward-already-chosen} si déjà choisie,
     *                                 409 {@code referral-reward-not-available} si pas encore disponible.
     */
    @Transactional
    public ReferralRefereeResponse chooseReward(String firebaseUid, UUID invitationId, ReferralRewardChoice choice) {
        UserEntity user = loadUserByFirebaseUid(firebaseUid);
        if (choice == null || !choice.isSelectable()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "referral-reward-choice-invalid", "Récompense invalide",
                    "Choisissez WALLET_CREDIT ou DISCOUNT_CODE");
        }

        ReferralInvitationEntity inv = referralInvitationRepository.findByIdForUpdate(invitationId)
                .filter(i -> user.getId().equals(i.getReferrerUserId()))
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "referral-invitation-not-found", "Filleul introuvable",
                        "Ce filleul est introuvable"));

        if (inv.getRewardStatus() == ReferralRewardStatus.CHOSEN) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "referral-reward-already-chosen", "Récompense déjà choisie",
                    "La récompense de ce filleul a déjà été choisie");
        }
        if (inv.getRewardStatus() != ReferralRewardStatus.AVAILABLE) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "referral-reward-not-available", "Récompense pas encore disponible",
                    "La récompense sera disponible après le premier envoi livré de votre filleul");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("choice", choice.name());
        details.put("refereeId", String.valueOf(inv.getRefereeUserId()));
        PromoCodeEntity promo = null;

        if (choice == ReferralRewardChoice.WALLET_CREDIT) {
            BigDecimal amount = config.getRewardWalletAmount();
            inv.setCreditAmountCents(amount.movePointRight(2).intValueExact());
            details.put("amount", amount.toPlainString());
            details.put("currency", REWARD_WALLET_CURRENCY);
            // Crédité par payments/wallet après le commit, une fois par invitation (clé
            // d'idempotence) : un choix annulé ne crédite rien.
            eventPublisher.publishEvent(new ReferralWalletRewardChosenEvent(
                    inv.getId(), user.getId(), amount, REWARD_WALLET_CURRENCY));
        } else {
            BigDecimal percent = BigDecimal.valueOf(config.getRewardDiscountPercent()).movePointLeft(2);
            promo = promoService.createPersonalCode(user.getId(), personalCodePrefix(user), percent,
                    now.plusMonths(config.getRewardDiscountValidityMonths()));
            inv.setRewardPromoCodeId(promo.getId());
            details.put("promoCodeId", promo.getId().toString());
            details.put("code", promo.getCode());
        }

        inv.setRewardStatus(ReferralRewardStatus.CHOSEN);
        inv.setRewardChoice(choice);
        inv.setRewardChosenAt(now);
        inv.setStatus("REWARDED");
        inv.setRewardedAt(now);
        referralInvitationRepository.save(inv);

        auditService.log("REFERRAL_INVITATION", inv.getId(), "REFERRAL_REWARD_CHOSEN", user.getId(), details);
        log.info("Referral reward chosen: referrer={} invitation={} choice={}", user.getId(), inv.getId(), choice);

        UserEntity referee = inv.getRefereeUserId() == null
                ? null
                : userRepository.findById(inv.getRefereeUserId()).orElse(null);
        return toRefereeResponse(inv, referee, promo);
    }

    private ReferralRefereeResponse toRefereeResponse(ReferralInvitationEntity inv, UserEntity referee,
                                                      PromoCodeEntity promo) {
        boolean wallet = inv.getRewardChoice() == ReferralRewardChoice.WALLET_CREDIT;
        ReferralRefereeResponse.DiscountCode discountCode = null;
        if (inv.getRewardChoice() == ReferralRewardChoice.DISCOUNT_CODE && promo != null) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            discountCode = new ReferralRefereeResponse.DiscountCode(
                    promo.getCode(),
                    promo.getRate().movePointRight(2).intValue(),
                    promo.getValidTo(),
                    promo.getRedeemedCount() > 0,
                    promo.getValidTo() != null && now.isAfter(promo.getValidTo()));
        }
        return new ReferralRefereeResponse(
                inv.getId(),
                referee == null ? null : referee.publicDisplayName(),
                inv.getCreatedAt(),
                inv.getStatus(),
                inv.getRewardStatus(),
                inv.getRewardChoice(),
                inv.getRewardAvailableAt(),
                inv.getRewardChosenAt(),
                wallet && inv.getCreditAmountCents() != null
                        ? BigDecimal.valueOf(inv.getCreditAmountCents()).movePointLeft(2)
                        : null,
                wallet ? REWARD_WALLET_CURRENCY : null,
                discountCode);
    }

    /** {@code MERCI-ABOU} : jusqu'à 4 lettres du prénom, sans accents. */
    private static String personalCodePrefix(UserEntity user) {
        String firstName = user.getFirstName() == null ? "" : user.getFirstName();
        String letters = Normalizer.normalize(firstName, Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z]", "")
                .toUpperCase(Locale.ROOT);
        if (letters.isEmpty()) {
            letters = "YADO";
        }
        return PERSONAL_CODE_PREFIX + letters.substring(0, Math.min(4, letters.length()));
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private UserEntity loadUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found",
                        "Utilisateur introuvable",
                        "Aucun compte associé à cet identifiant Firebase"));
    }

    private String buildPrefix(UserEntity user) {
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        String raw = (firstName != null ? firstName : "") + (lastName != null ? lastName : "");
        if (raw.length() < 4) {
            raw = raw + "YADONY";
        }
        return raw.substring(0, 4).toUpperCase().replaceAll("[^A-Z0-9]", "X");
    }

    private String generateUniqueCode(String prefix) {
        for (int attempt = 0; attempt < MAX_CODE_RETRIES; attempt++) {
            String candidate = prefix + String.format("%04d", random.nextInt(10000));
            if (!referralCodeRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        // Last resort: add timestamp suffix to guarantee uniqueness
        String fallback = prefix + System.currentTimeMillis() % 100000;
        return fallback.substring(0, Math.min(fallback.length(), 20));
    }
}
