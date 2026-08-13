package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Core business logic for the referral system.
 *
 * <ul>
 *   <li>{@link #generateCodeForUser} — creates and persists a unique referral code on registration</li>
 *   <li>{@link #getMyReferral} — returns code + stats (lazy-creates code if absent)</li>
 *   <li>{@link #redeemCode} — links the current user as a referee under a referrer</li>
 *   <li>{@link #regenerateCode} — replaces the code (respects cooldown)</li>
 * </ul>
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);
    private static final int MAX_CODE_RETRIES = 5;
    private static final String SHARE_URL_PREFIX = "https://yadony.com/r/";

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralInvitationRepository referralInvitationRepository;
    private final UserCreditRepository userCreditRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ReferralConfig config;
    private final com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    private final Random random = new Random();

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralInvitationRepository referralInvitationRepository,
                           UserCreditRepository userCreditRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           ReferralConfig config,
                           com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralInvitationRepository = referralInvitationRepository;
        this.userCreditRepository = userCreditRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.config = config;
        this.activeCurrencyResolver = activeCurrencyResolver;
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

        long totalInvited = referralInvitationRepository.countByReferrerUserId(user.getId());
        long signedUp = referralInvitationRepository.countByReferrerUserIdAndStatus(user.getId(), "SIGNED_UP");
        long rewarded = referralInvitationRepository.countByReferrerUserIdAndStatus(user.getId(), "REWARDED");
        // Total de la seule devise active : additionner toutes devises confondues
        // mêlerait dollars et euros en un chiffre qu'aucun symbole ne peut légender.
        String currency = activeCurrencyResolver.resolve(user.getId());
        int totalEarnedCents =
                userCreditRepository.sumAmountCentsByUserIdAndCurrency(user.getId(), currency);

        // reward-amount-cents est configuré en centimes d'euro. Exposé tel quel,
        // il annonçait 500 F CFA à un parrain qui n'en recevra que 5 : le montant
        // est donc converti dans les unités mineures de sa devise, exactement
        // comme le crédit qui sera versé.
        var rewardCurrency = com.yadony.api.payments.currency.SupportedCurrency
                .fromCodeOrDefault(currency);
        int rewardAmountInMinorUnits = (int) com.yadony.api.payments.currency.CurrencyAmount
                .of(java.math.BigDecimal.valueOf(config.getRewardAmountCents()).movePointLeft(2),
                        rewardCurrency)
                .minor();

        boolean hasBeenReferred =
                referralInvitationRepository.findByRefereeUserIdAndStatus(user.getId(), "SIGNED_UP").isPresent() ||
                referralInvitationRepository.findByRefereeUserIdAndStatus(user.getId(), "REWARDED").isPresent();

        return new MyReferralResponse(
                code, shareUrl,
                (int) totalInvited, (int) signedUp, (int) rewarded,
                totalEarnedCents, hasBeenReferred, currency, rewardAmountInMinorUnits
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

        // Check if this referee already has an active or completed referral
        boolean alreadySignedUp = referralInvitationRepository
                .findByRefereeUserIdAndStatus(referee.getId(), "SIGNED_UP").isPresent();
        boolean alreadyRewarded = referralInvitationRepository
                .findByRefereeUserIdAndStatus(referee.getId(), "REWARDED").isPresent();

        if (alreadySignedUp || alreadyRewarded) {
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
