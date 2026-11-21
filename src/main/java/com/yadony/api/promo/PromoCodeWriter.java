package com.yadony.api.promo;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insère un code promo dans sa propre transaction. Une violation d'unicité sur {@code code}
 * annule seulement cette transaction : l'appelant l'attrape et la transaction englobante
 * (PostgreSQL la marque « aborted » après une erreur SQL) reste utilisable. Doit être appelé
 * à travers le proxy Spring, donc depuis un autre bean.
 */
@Component
public class PromoCodeWriter {

    private final PromoCodeRepository promoCodeRepository;

    public PromoCodeWriter(PromoCodeRepository promoCodeRepository) {
        this.promoCodeRepository = promoCodeRepository;
    }

    /**
     * Compatibilité des verrous : la transaction englobante du rachat tient un verrou sur la ligne
     * {@code users} du filleul ({@code UserRepository.lockForUpdate}, un UPDATE hors colonne clé =
     * FOR NO KEY UPDATE). La clé étrangère {@code owner_user_id} prend ici un FOR KEY SHARE sur
     * cette même ligne : les deux sont compatibles. Remplacer ce verrou par un SELECT ... FOR UPDATE
     * ferait attendre cette insertion sa propre transaction englobante, sans interblocage détecté
     * (verrouillé par ReferralRedeemWelcomeCodePostgresIT).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PromoCodeEntity insertNow(PromoCodeEntity promo) {
        return promoCodeRepository.saveAndFlush(promo);
    }
}
