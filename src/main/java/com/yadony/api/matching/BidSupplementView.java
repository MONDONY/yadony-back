package com.yadony.api.matching;

import com.yadony.api.matching.dto.BidSupplementResponse;

import java.math.BigDecimal;

/**
 * @param supplement     supplément affiché (visibilité déjà appliquée), ou null.
 * @param requestAllowed l'appelant (voyageur du trajet) peut demander un supplément maintenant.
 * @param paidGross      brut d'un supplément payé et non remboursé, sinon null.
 * @param paidNet        net de ce même supplément, sinon null.
 */
public record BidSupplementView(BidSupplementResponse supplement, boolean requestAllowed,
                                BigDecimal paidGross, BigDecimal paidNet) {

    public static final BidSupplementView NONE = new BidSupplementView(null, false, null, null);
}
