package com.yadony.api.promo;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Validation et rachat des codes promo.
 * Appelé par {@link com.yadony.api.common.CommissionRateResolver} (source unique du taux effectif).
 */
@Service
public class PromoService {

    private static final Logger log = LoggerFactory.getLogger(PromoService.class);

    private final PromoCodeRepository promoCodeRepository;
    private final PromoRedemptionRepository redemptionRepository;
    private final AuditService auditService;

    public PromoService(PromoCodeRepository promoCodeRepository,
                        PromoRedemptionRepository redemptionRepository,
                        AuditService auditService) {
        this.promoCodeRepository = promoCodeRepository;
        this.redemptionRepository = redemptionRepository;
        this.auditService = auditService;
    }

    /**
     * Valide un code promo et retourne le taux associé.
     * Lève une {@link YadonyBusinessException} si le code est invalide pour l'utilisateur donné.
     *
     * @param code   Code promo brut (insensible à la casse).
     * @param userId ID de l'utilisateur qui soumet le code (expéditeur au checkout).
     * @param target Rôle de l'utilisateur (SENDER ou TRAVELER) pour vérifier la cible du promo.
     */
    public BigDecimal validateAndGetRate(String code, UUID userId, PromoCodeTarget target) {
        if (code == null || code.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-not-found", "Promo Not Found", "Code promo introuvable");
        }
        PromoCodeEntity promo = promoCodeRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found",
                        "Code promo introuvable : " + code.toUpperCase()));

        if (promo.getStatus() != PromoCodeStatus.ACTIVE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo n'est plus actif");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (promo.getValidFrom() != null && now.isBefore(promo.getValidFrom())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo n'est pas encore valide");
        }
        if (promo.getValidTo() != null && now.isAfter(promo.getValidTo())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo a expiré");
        }

        if (promo.getMaxRedemptions() != null && promo.getRedeemedCount() >= promo.getMaxRedemptions()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-limit-reached", "Promo Limit Reached",
                    "Ce code promo a atteint son nombre maximum d'utilisations");
        }

        if (promo.getTarget() != PromoCodeTarget.ANY && promo.getTarget() != target) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "promo-not-eligible", "Promo Not Eligible",
                    "Ce code promo n'est pas disponible pour votre profil");
        }

        long userRedemptions = redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(
                promo.getId(), userId);
        if (userRedemptions >= promo.getPerUserLimit()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-limit-reached", "Promo Limit Reached",
                    "Vous avez déjà utilisé ce code promo le nombre maximum de fois autorisé");
        }

        return promo.getRate();
    }

    /**
     * Enregistre l'utilisation du promo et incrémente le compteur global.
     * Idempotent : si la rédemption (promoCodeId, bidId) existe déjà, retourne sans double-décompte.
     *
     * <p>Les limites {@code per_user_limit} et {@code max_redemptions} sont REVÉRIFIÉES sous le
     * verrou pessimiste de la ligne {@code promo_codes} : {@link #validateAndGetRate} les lit
     * sans verrou, si bien que deux paiements concurrents du même code pouvaient tous deux
     * passer la validation puis tous deux racheter. Si la limite est atteinte à ce moment,
     * lève {@code promo-limit-reached} (422) — à appeler AVANT que l'argent ne bouge (carte :
     * avant la création du PaymentIntent ; mobile money : avant le dépôt).
     */
    @Transactional
    public PromoRedemptionEntity redeem(String code, UUID userId, UUID bidId, BigDecimal appliedRate) {
        return doRedeem(code, userId, bidId, appliedRate, true);
    }

    /**
     * Enregistre le rachat d'une remise DÉJÀ accordée — l'argent a été prélevé au taux réduit
     * (commission espèces débitée, séquestre de négociation capturé) : refuser à ce stade
     * laisserait la remise accordée sans trace, ou annulerait une transaction dont l'effet
     * externe (Stripe) ne s'annule pas. Les limites sont relues sous verrou mais un
     * dépassement est journalisé (log + audit {@code overLimit}) au lieu d'être levé.
     * L'appelant qui le peut contrôle les limites AVANT de prélever via
     * {@link #lockForRedemption}.
     *
     * @return la rédemption, ou {@code null} si le code n'existe plus (supprimé entre-temps).
     */
    @Transactional
    public PromoRedemptionEntity recordGrantedRedemption(String code, UUID userId, UUID bidId,
                                                         BigDecimal appliedRate) {
        return doRedeem(code, userId, bidId, appliedRate, false);
    }

    /**
     * Verrouille la ligne du code (jusqu'à la fin de la transaction appelante) et vérifie que
     * {@code userId} peut encore le racheter pour {@code bidId}, sans rien écrire. Permet de
     * refuser proprement AVANT tout prélèvement, puis d'enregistrer le rachat après le
     * prélèvement dans la même transaction sans qu'un rachat concurrent ne s'intercale.
     *
     * @return l'identifiant du code promo.
     * @throws YadonyBusinessException {@code promo-limit-reached} (422) si une limite est atteinte,
     *                                 {@code promo-not-found} (404) si le code n'existe plus.
     */
    @Transactional
    public UUID lockForRedemption(String code, UUID userId, UUID bidId) {
        PromoCodeEntity promo = findOrThrow(code);
        PromoCodeEntity locked = promoCodeRepository.findByIdForUpdate(promo.getId()).orElseThrow();
        if (!redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(locked.getId(), bidId)) {
            String exceeded = exceededLimit(locked, userId);
            if (exceeded != null) {
                throw limitReached(exceeded);
            }
        }
        return locked.getId();
    }

    private PromoRedemptionEntity doRedeem(String code, UUID userId, UUID bidId, BigDecimal appliedRate,
                                           boolean enforceLimits) {
        PromoCodeEntity promo;
        if (enforceLimits) {
            promo = promoCodeRepository.findByCode(code.toUpperCase()).orElseThrow();
        } else {
            promo = promoCodeRepository.findByCode(code.toUpperCase()).orElse(null);
            if (promo == null) {
                log.error("Promo code {} introuvable : rachat du bid {} non enregistré (remise déjà accordée)",
                        code, bidId);
                return null;
            }
        }

        if (redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(promo.getId(), bidId)) {
            log.info("Promo code {} already redeemed for bid {} (idempotent skip)", code, bidId);
            return redemptionRepository.findByPromoCodeIdAndBidId(promo.getId(), bidId).orElseThrow();
        }

        // Verrou pessimiste : sérialise les rachats d'un même code. Tout ce qui suit relit la
        // base sous ce verrou (READ COMMITTED : chaque requête voit ce qui a été validé avant).
        PromoCodeEntity locked = promoCodeRepository.findByIdForUpdate(promo.getId()).orElseThrow();
        // Double acceptation concurrente du même bid : la première a pu valider entre-temps.
        // Lu en base (requête scalaire) : une ligne libérée chargée avant le verrou serait périmée.
        if (redemptionRepository.existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(locked.getId(), bidId)) {
            log.info("Promo code {} redeemed concurrently for bid {} (idempotent skip)", code, bidId);
            return redemptionRepository.findByPromoCodeIdAndBidId(locked.getId(), bidId).orElseThrow();
        }

        String exceeded = exceededLimit(locked, userId);
        if (exceeded != null) {
            if (enforceLimits) {
                throw limitReached(exceeded);
            }
            log.warn("Promo code {} : limite {} dépassée pour le bid {} — remise déjà accordée, rachat enregistré",
                    code, exceeded, bidId);
        }

        // Compteur relu en base sous verrou : l'entité gérée peut être celle chargée AVANT
        // le verrou (findByCode, validateAndGetRate) et porter un compteur périmé.
        int freshCount = promoCodeRepository.findRedeemedCountById(locked.getId());
        locked.setRedeemedCount(freshCount + 1);
        promoCodeRepository.save(locked);

        // Une ligne libérée pour ce bid est réactivée : UNIQUE(promo_code_id, bid_id) interdit
        // d'en créer une seconde, et la ligne d'origine garde son identifiant.
        PromoRedemptionEntity released = redemptionRepository
                .findByPromoCodeIdAndBidId(locked.getId(), bidId).orElse(null);
        PromoRedemptionEntity redemption = released != null ? released : new PromoRedemptionEntity();
        redemption.setPromoCodeId(promo.getId());
        redemption.setUserId(userId);
        redemption.setBidId(bidId);
        redemption.setAppliedRate(appliedRate);
        redemption.setRedeemedAt(LocalDateTime.now(ZoneOffset.UTC));
        redemption.setReleasedAt(null);
        redemption.setReleaseReason(null);
        PromoRedemptionEntity saved = redemptionRepository.save(redemption);

        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("code", code.toUpperCase());
        details.put("bidId", bidId.toString());
        details.put("rate", appliedRate.toPlainString());
        if (exceeded != null) {
            details.put("overLimit", exceeded);
        }
        if (released != null) {
            details.put("reactivated", true);
        }
        auditService.log("PROMO", promo.getId(), "PROMO_CODE_REDEEMED", userId, details);

        return saved;
    }

    /**
     * Rend le(s) code(s) promo utilisé(s) pour {@code bidId} : l'envoi s'est terminé sans
     * livraison et Yadony ne conserve pas la commission remisée (la décision appartient à
     * l'appelant, {@link PromoReleaseListener}). Chaque rachat actif du bid est marqué libéré,
     * ne compte plus dans {@code per_user_limit}, et {@code redeemed_count} est décrémenté
     * (plancher 0) sous le même verrou pessimiste que {@link #redeem}.
     *
     * <p>Idempotent : la libération passe par une mise à jour conditionnelle
     * ({@code released_at IS NULL}) ; un événement rejoué ou concurrent ne décrémente pas deux
     * fois. Sans rachat actif (bid sans code, jamais racheté, déjà libéré) : no-op.
     *
     * <p>{@code REQUIRES_NEW} : appelé depuis un écouteur {@code AFTER_COMMIT}, où une
     * transaction {@code REQUIRED} rejoindrait celle, déjà validée, de l'émetteur et
     * n'écrirait rien.
     *
     * @return le nombre de rachats libérés par CET appel.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseForBid(UUID bidId, String reason) {
        int releasedCount = 0;
        for (PromoRedemptionEntity redemption : redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)) {
            PromoCodeEntity locked = promoCodeRepository.findByIdForUpdate(redemption.getPromoCodeId())
                    .orElse(null);
            if (locked == null) {
                // Code supprimé (soft delete) entre-temps : plus de limite à rendre.
                log.warn("Promo code {} introuvable : rachat du bid {} non libéré",
                        redemption.getPromoCodeId(), bidId);
                continue;
            }
            if (redemptionRepository.markReleasedIfActive(redemption.getId(),
                    OffsetDateTime.now(ZoneOffset.UTC), reason) == 0) {
                log.info("Rachat promo {} du bid {} déjà libéré (idempotent skip)", redemption.getId(), bidId);
                continue;
            }
            int freshCount = promoCodeRepository.findRedeemedCountById(locked.getId());
            locked.setRedeemedCount(Math.max(0, freshCount - 1));
            promoCodeRepository.save(locked);

            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("code", locked.getCode());
            details.put("bidId", bidId.toString());
            details.put("redemptionId", redemption.getId().toString());
            details.put("reason", reason);
            if (freshCount <= 0) {
                details.put("counterFloor", true);
            }
            auditService.log("PROMO", locked.getId(), "PROMO_CODE_RELEASED", redemption.getUserId(), details);
            log.info("Promo code {} libéré pour le bid {} ({})", locked.getCode(), bidId, reason);
            releasedCount++;
        }
        return releasedCount;
    }

    /**
     * Limite atteinte pour un NOUVEAU rachat de {@code userId}, lue en base sous le verrou
     * déjà pris par l'appelant : {@code "max_redemptions"}, {@code "per_user_limit"} ou
     * {@code null} si le rachat est permis.
     */
    private String exceededLimit(PromoCodeEntity locked, UUID userId) {
        if (locked.getMaxRedemptions() != null
                && promoCodeRepository.findRedeemedCountById(locked.getId()) >= locked.getMaxRedemptions()) {
            return "max_redemptions";
        }
        if (redemptionRepository.countByPromoCodeIdAndUserIdAndReleasedAtIsNull(locked.getId(), userId)
                >= locked.getPerUserLimit()) {
            return "per_user_limit";
        }
        return null;
    }

    private PromoCodeEntity findOrThrow(String code) {
        return promoCodeRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found",
                        "Code promo introuvable : " + code.toUpperCase()));
    }

    private static YadonyBusinessException limitReached(String exceeded) {
        String detail = "per_user_limit".equals(exceeded)
                ? "Vous avez déjà utilisé ce code promo le nombre maximum de fois autorisé"
                : "Ce code promo a atteint son nombre maximum d'utilisations";
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "promo-limit-reached", "Promo Limit Reached", detail);
    }
}
