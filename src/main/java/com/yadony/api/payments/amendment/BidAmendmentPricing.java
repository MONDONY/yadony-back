package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidNetTotal;
import com.yadony.api.payments.amendment.dto.AmendmentLineRequest;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prix d'une modification : le net vaut {@code kilos × prix au kilo publié du trajet} (jamais le net négocié)
 * plus les articles de grille de CE trajet. L'expéditeur ne paie que la différence de commission
 * ({@link IncrementalCommission}, grille figée du colis) sur le net déjà dû : {@link BidNetTotal} plus le net
 * des ajouts déjà acquis ({@link BidExtrasNet}). Lecture pure : rien n'est consommé.
 */
@Component
public class BidAmendmentPricing {

    private final IncrementalCommission incrementalCommission;
    private final BidExtrasNet extrasNet;
    private final BidGridItemRepository bidGridItems;
    private final AnnouncementPriceGridItemRepository announcementGridItems;

    public BidAmendmentPricing(IncrementalCommission incrementalCommission, BidExtrasNet extrasNet,
                               BidGridItemRepository bidGridItems,
                               AnnouncementPriceGridItemRepository announcementGridItems) {
        this.incrementalCommission = incrementalCommission;
        this.extrasNet = extrasNet;
        this.bidGridItems = bidGridItems;
        this.announcementGridItems = announcementGridItems;
    }

    /**
     * @param addedKg kilos ajoutés, nul ou 0 si seulement des articles.
     * @param items   articles de la grille du trajet, nul ou vide si seulement des kilos.
     * @throws YadonyBusinessException 422 {@code amendment-item-unknown} si un article n'appartient pas au
     *         trajet, 422 {@code amendment-empty} si le net est nul, 422 {@code amendment-not-eligible} si des
     *         kilos sont demandés sur un trajet sans prix au kilo.
     */
    public BidAmendmentQuote quote(BidEntity bid, AnnouncementEntity announcement, BigDecimal addedKg,
                                   List<AmendmentLineRequest> items) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
        BigDecimal net = BigDecimal.ZERO;

        if (addedKg != null && addedKg.signum() > 0) {
            if (announcement.getPricePerKg() == null) {
                throw unprocessable("amendment-not-eligible", "Amendment Unavailable",
                        "Ce trajet ne vend pas de kilos au kilo");
            }
            net = net.add(addedKg.multiply(announcement.getPricePerKg()));
        }

        List<BidAmendmentQuote.Line> lines = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            Map<UUID, AnnouncementPriceGridItemEntity> grid = new HashMap<>();
            announcementGridItems.findByAnnouncementIdOrderByPositionAsc(announcement.getId())
                    .forEach(item -> grid.put(item.getId(), item));
            for (AmendmentLineRequest line : items) {
                AnnouncementPriceGridItemEntity item = grid.get(line.gridItemId());
                if (item == null) {
                    throw unprocessable("amendment-item-unknown", "Amendment Item Unknown",
                            "Cet article ne fait pas partie de la grille du trajet");
                }
                net = net.add(item.getUnitPriceNet().multiply(BigDecimal.valueOf(line.quantity())));
                lines.add(new BidAmendmentQuote.Line(item.getId(), item.getLabel(), item.getUnitPriceNet(), line.quantity()));
            }
        }

        net = net.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        if (net.signum() <= 0) {
            throw unprocessable("amendment-empty", "Amendment Empty", "La modification n'ajoute rien au colis");
        }

        BigDecimal base = BidNetTotal.compute(bid, announcement,
                        bid.getNegotiatedNetEur() != null ? List.of() : bidGridItems.findByBidId(bid.getId()))
                .add(extrasNet.paidExtrasNet(bid.getId()));
        IncrementalCommission.Result r = incrementalCommission.compute(bid, announcement.getTravelerId(), base, net);
        return new BidAmendmentQuote(net, r.commission(), net.add(r.commission()), r.rateSnapshot(), r.gridId(),
                List.copyOf(lines));
    }

    private static YadonyBusinessException unprocessable(String code, String title, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }
}
