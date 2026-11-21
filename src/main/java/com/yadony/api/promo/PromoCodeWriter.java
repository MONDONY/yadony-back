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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PromoCodeEntity insertNow(PromoCodeEntity promo) {
        return promoCodeRepository.saveAndFlush(promo);
    }
}
