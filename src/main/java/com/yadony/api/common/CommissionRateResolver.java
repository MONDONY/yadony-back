package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.promo.PromoCodeTarget;
import com.yadony.api.promo.PromoService;
import com.yadony.api.voucher.CommissionVoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SOURCE UNIQUE du taux de commission Yadony effectif pour une transaction.
 *
 * <p>Placé dans {@code common/} car il s'agit de logique partagée (matching,
 * payments, promo).
 *
 * <p>Règle (cf. {@code docs/specs/commission-rate-overrides-and-promo.md}) :
 * <ol>
 *   <li><b>Phase 1</b> — taux de base : {@code min( override(voyageur), override(expéditeur), global )}.</li>
 *   <li><b>Phase 2</b> — si {@code promoCode} non null et valide : le taux du promo est
 *       retranché en points du taux de base (plancher 0), pas substitué. Un promo dont le
 *       taux égale ou dépasse le taux de base annule donc entièrement la commission au lieu
 *       de rester sans effet (régression WELCOME05 : 5 % de promo = 5 % de taux global
 *       depuis le passage 12 %→5 % ne faisait auparavant gagner aucune remise réelle).</li>
 *   <li><b>Phase 3</b> (lot 3) — si l'expéditeur détient un bon de parrainage actif : son
 *       facteur (0,5 par défaut) est appliqué en MULTIPLICATION sur ce qui reste après le
 *       promo, jamais avant. Contrairement au promo, aucun code à saisir : c'est automatique
 *       dès qu'un bon est disponible. Ne consomme rien ici — {@link CommissionVoucherService#peekActive}
 *       est une lecture pure, la consommation a lieu à la charge effective de la commission.</li>
 * </ol>
 */
@Service
public class CommissionRateResolver {

    private static final Logger log = LoggerFactory.getLogger(CommissionRateResolver.class);

    private final UserRepository userRepository;
    private final PlatformSettingsService settings;
    private final PromoService promoService;
    private final CommissionVoucherService voucherService;

    public CommissionRateResolver(UserRepository userRepository,
                                  PlatformSettingsService settings,
                                  PromoService promoService,
                                  CommissionVoucherService voucherService) {
        this.userRepository = userRepository;
        this.settings = settings;
        this.promoService = promoService;
        this.voucherService = voucherService;
    }

    /**
     * Taux global par défaut.
     *
     * <p>Lu depuis la table {@code platform_settings}, pas depuis la property : c'est ce
     * taux qui calcule l'escrow et l'{@code application_fee_amount} envoyé à Stripe. S'il
     * restait sur la property, une modification faite depuis le back-office changerait le
     * montant ANNONCÉ à l'expéditeur (via {@code /config/commission-rate}) sans changer le
     * montant PRÉLEVÉ — l'application annoncerait un prix et Stripe en débiterait un autre.
     *
     * <p>{@code PlatformSettingsService} retombe lui-même sur la property quand la ligne
     * manque : le filet de sécurité est conservé.
     */
    public BigDecimal globalRate() {
        return settings.commissionRate();
    }

    /** Taux effectif à la navigation : seul le voyageur est connu (pas de promo). */
    public BigDecimal resolve(UUID travelerId) {
        return resolve(travelerId, null);
    }

    /**
     * Taux effectif pour le couple (voyageur, expéditeur) sans code promo.
     * {@code senderId} peut être {@code null}.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId) {
        return resolve(travelerId, senderId, null, null);
    }

    /**
     * Taux effectif complet (Phase 2) : promo > overrides > global.
     *
     * @param travelerId ID du voyageur (non null).
     * @param senderId   ID de l'expéditeur (nullable — non connu à la navigation).
     * @param promoCode  Code promo brut (nullable).
     * @param senderId2  Pour la validation du promo (userId = l'expéditeur, target SENDER).
     *                   Alias {@code senderId} — le promo est entré par l'expéditeur.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId, String promoCode) {
        return resolve(travelerId, senderId, promoCode, senderId);
    }

    /**
     * Résolution complète avec contexte utilisateur explicite pour la validation promo.
     * <p>Utilisé dans les contextes où l'ID de l'utilisateur qui entre le code promo
     * diffère du senderId (rare, mais couvert pour extensibilité Phase 3+).
     * <p>Si {@code promoCode} est non null : valide strictement (lève
     * {@link com.yadony.api.common.YadonyBusinessException} si invalide — laisser remonter
     * dans le contexte devis, attraper et logger dans le contexte paiement), puis retranche
     * le taux du promo en points du taux de base (plancher 0 — un promo ne peut jamais rendre
     * la commission négative).
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId, String promoCode, UUID promoUserId) {
        return resolve(travelerId, senderId, promoCode, promoUserId, null);
    }

    /**
     * Même résolution, rattachée à une transaction identifiée.
     *
     * @param reference bid ou fil de négociation auquel se rattache le prélèvement. Quand
     *                  il est fourni, le bon pris en compte est celui DÉJÀ consommé pour
     *                  cette référence s'il existe, sinon le plus ancien disponible : un
     *                  réessai de prélèvement ne peut donc plus faire remonter le taux
     *                  après que le bon a été consommé. Toujours une lecture pure — la
     *                  consommation reste à la charge du point d'engagement financier.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId, String promoCode, UUID promoUserId,
                              UUID reference) {
        BigDecimal rate = globalRate();
        rate = minNullable(rate, overrideOf(travelerId));
        rate = minNullable(rate, overrideOf(senderId));
        if (promoCode != null && !promoCode.isBlank() && promoUserId != null) {
            BigDecimal promoRate = promoService.validateAndGetRate(
                    promoCode, promoUserId, PromoCodeTarget.SENDER);
            rate = rate.subtract(promoRate).max(BigDecimal.ZERO);
        }
        if (senderId != null) {
            BigDecimal beforeVoucher = rate;
            var voucher = reference == null
                    ? voucherService.peekActive(senderId)
                    : voucherService.peekForReference(senderId, reference);
            rate = voucher
                    .map(v -> beforeVoucher.multiply(v.getFactor()))
                    .orElse(beforeVoucher);
        }
        return rate.max(BigDecimal.ZERO);
    }

    private BigDecimal overrideOf(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(UserEntity::getCommissionRateOverride)
                .orElse(null);
    }

    private static BigDecimal minNullable(BigDecimal base, BigDecimal candidate) {
        return candidate == null ? base : base.min(candidate);
    }
}
