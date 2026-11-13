package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.amendment.dto.AmendmentLineRequest;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prix d'une modification : le net vaut {@code kilos × prix au kilo publié du trajet} (jamais le net négocié), ou
 * {@code m³ × prix au m³ publié du trajet} sur un trajet bateau vendu au volume, plus les articles de grille de CE
 * trajet. L'expéditeur ne paie que la différence de commission
 * ({@link IncrementalCommission}, grille figée du colis) sur le net déjà dû : {@link BidExtrasNet#dueNet} (principal
 * plus ajouts déjà acquis). Lecture pure : rien n'est consommé.
 */
@Component
public class BidAmendmentPricing {

    private final IncrementalCommission incrementalCommission;
    private final BidExtrasNet extrasNet;
    private final AnnouncementPriceGridItemRepository announcementGridItems;

    public BidAmendmentPricing(IncrementalCommission incrementalCommission, BidExtrasNet extrasNet,
                               AnnouncementPriceGridItemRepository announcementGridItems) {
        this.incrementalCommission = incrementalCommission;
        this.extrasNet = extrasNet;
        this.announcementGridItems = announcementGridItems;
    }

    /**
     * @param addedKg kilos ajoutés, nul ou 0 si seulement des articles.
     * @param items   articles de la grille du trajet, nul ou vide si seulement des kilos.
     * @throws YadonyBusinessException 422 {@code amendment-item-unknown} si un article n'appartient pas au
     *         trajet, 422 {@code amendment-item-duplicate} si un article revient sur deux lignes, 422
     *         {@code amendment-empty} si le net est nul, 422 {@code amendment-not-eligible} si des kilos sont
     *         demandés sur un trajet sans prix au kilo, 400 {@code validation} pour des kilos négatifs ou une
     *         quantité inférieure à 1 (revérifiés ici : le devis ne passe pas toujours par la validation du DTO).
     */
    public BidAmendmentQuote quote(BidEntity bid, AnnouncementEntity announcement, BigDecimal addedKg,
                                   List<AmendmentLineRequest> items) {
        return quote(bid, announcement, addedKg, null, items);
    }

    /**
     * Même devis, avec du volume ajouté : net = {@code addedVolumeM3 × prix au m³ publié du trajet} (net voyageur,
     * jamais le prix affiché), seulement sur un trajet vendu au m³. Même commission différentielle que les kilos
     * ({@link IncrementalCommission}), payée par l'expéditeur ; arrondi unique du net total à l'unité mineure.
     *
     * @param addedVolumeM3 m³ ajoutés, nul ou 0 sans volume.
     * @throws YadonyBusinessException 422 {@code amendment-not-eligible} si du volume est demandé sur un trajet qui
     *         n'est pas vendu au m³, 400 {@code validation} pour un volume négatif ; les refus de la variante sans
     *         volume sinon.
     */
    public BidAmendmentQuote quote(BidEntity bid, AnnouncementEntity announcement, BigDecimal addedKg,
                                   BigDecimal addedVolumeM3, List<AmendmentLineRequest> items) {
        requireWellFormed(addedKg, items);
        if (addedVolumeM3 != null && addedVolumeM3.signum() < 0) {
            throw invalid("problem.validation.le-volume-ajoute-ne-peut-pas");
        }
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
        BigDecimal net = BigDecimal.ZERO;

        if (addedKg != null && addedKg.signum() > 0) {
            if (announcement.getPricePerKg() == null) {
                throw unprocessable("amendment-not-eligible", "problem.amendment-not-eligible");
            }
            net = net.add(addedKg.multiply(announcement.getPricePerKg()));
        }
        if (addedVolumeM3 != null && addedVolumeM3.signum() > 0) {
            if (!announcement.isPricedPerM3()) {
                throw unprocessable("amendment-not-eligible", "problem.amendment-volume-not-sold");
            }
            net = net.add(addedVolumeM3.multiply(announcement.getPricePerM3()));
        }

        List<BidAmendmentQuote.Line> lines = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            Map<UUID, AnnouncementPriceGridItemEntity> grid = new HashMap<>();
            announcementGridItems.findByAnnouncementIdOrderByPositionAsc(announcement.getId())
                    .forEach(item -> grid.put(item.getId(), item));
            for (AmendmentLineRequest line : items) {
                AnnouncementPriceGridItemEntity item = grid.get(line.gridItemId());
                if (item == null) {
                    throw unprocessable("amendment-item-unknown", "problem.amendment-item-unknown");
                }
                net = net.add(item.getUnitPriceNet().multiply(BigDecimal.valueOf(line.quantity())));
                lines.add(new BidAmendmentQuote.Line(item.getId(), item.getLabel(), item.getUnitPriceNet(), line.quantity()));
            }
        }

        net = net.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        if (net.signum() <= 0) {
            throw unprocessable("amendment-empty", "problem.amendment-empty");
        }

        IncrementalCommission.Result r = incrementalCommission.compute(bid, announcement.getTravelerId(),
                extrasNet.dueNet(bid, announcement), net);
        return new BidAmendmentQuote(net, r.commission(), net.add(r.commission()), r.rateSnapshot(), r.gridId(),
                List.copyOf(lines));
    }

    /**
     * Kilos positifs, quantités d'au moins 1, chaque article une seule fois : un même article sur deux lignes
     * contournerait le plafond de quantité par ligne ({@code @Max(20)}).
     */
    private static void requireWellFormed(BigDecimal addedKg, List<AmendmentLineRequest> items) {
        if (addedKg != null && addedKg.signum() < 0) {
            throw invalid("problem.validation.les-kilos-ajoutes-ne-peuvent-pas");
        }
        if (items == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (AmendmentLineRequest line : items) {
            if (line == null || line.gridItemId() == null || line.quantity() < 1) {
                throw invalid("problem.validation.chaque-article-demande-un-identifiant-et");
            }
            if (!seen.add(line.gridItemId())) {
                throw unprocessable("amendment-item-duplicate", "problem.amendment-item-duplicate");
            }
        }
    }

    private static YadonyBusinessException invalid(String key, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.BAD_REQUEST, "validation", key, args);
    }

    private static YadonyBusinessException unprocessable(String code, String key, Object... args) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, code, key, args);
    }
}
