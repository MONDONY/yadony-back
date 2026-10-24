package com.yadony.api.matching;

import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.matching.dto.BidMoneyBreakdown;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param amendments       modifications à afficher : les acquises, puis la dernière non acquise si elle est ouverte
 *                         ou close depuis moins de 7 jours. Visibilité déjà appliquée (l'expéditeur n'a aucun net).
 * @param amendmentAllowed l'appelant (expéditeur du colis) peut demander une modification maintenant.
 * @param securedAddedKg   kilos des modifications acquises, 0 sans modification.
 * @param moneyEntries     transactions des modifications acquises ou ouvertes visibles de l'appelant, pour
 *                         {@link BidMoneyBreakdown} ; vide sans modification à ventiler.
 */
public record BidAmendmentView(List<BidAmendmentResponse> amendments, boolean amendmentAllowed,
                               BigDecimal securedAddedKg, List<BidMoneyBreakdown.Entry> moneyEntries) {

    public static final BidAmendmentView NONE = new BidAmendmentView(List.of(), false, BigDecimal.ZERO, List.of());
}
