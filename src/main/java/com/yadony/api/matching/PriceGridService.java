package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.yadony.api.matching.dto.PriceGridItemRequest;
import com.yadony.api.matching.dto.PriceGridItemResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PriceGridService {

    private static final int MAX_GRID_ITEMS = 20;

    private final PriceGridItemRepository gridRepo;
    private final AnnouncementPriceGridItemRepository annGridRepo;
    private final AuditService auditService;
    private final CommissionRateResolver commissionRateResolver;

    public PriceGridService(PriceGridItemRepository gridRepo,
                            AnnouncementPriceGridItemRepository annGridRepo,
                            AuditService auditService,
                            CommissionRateResolver commissionRateResolver) {
        this.gridRepo = gridRepo;
        this.annGridRepo = annGridRepo;
        this.auditService = auditService;
        this.commissionRateResolver = commissionRateResolver;
    }

    public List<PriceGridItemResponse> getItems(UUID travelerId) {
        return gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public PriceGridItemResponse addItem(UUID travelerId, PriceGridItemRequest req, UUID actorId) {
        long currentCount = gridRepo.countByTravelerId(travelerId);
        if (currentCount >= MAX_GRID_ITEMS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "price-grid-limit: maximum 20 articles autorisés");
        }
        int position = (int) currentCount;
        PriceGridItemEntity entity = new PriceGridItemEntity();
        entity.setTravelerId(travelerId);
        entity.setLabel(req.label());
        entity.setUnitPriceNet(req.unitPriceNet());
        entity.setPosition(position);
        PriceGridItemEntity saved = gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            saved.getId(),
            "PRICE_GRID_ITEM_CREATED",
            actorId,
            java.util.Map.<String, Object>of("label", req.label(), "unitPriceNet", req.unitPriceNet().toString())
        );
        return toResponse(saved);
    }

    @Transactional
    public PriceGridItemResponse updateItem(UUID travelerId, UUID itemId,
                                            PriceGridItemRequest req, UUID actorId) {
        PriceGridItemEntity entity = gridRepo.findById(itemId)
                .filter(e -> e.getTravelerId().equals(travelerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "price-grid-item-not-found"));
        entity.setLabel(req.label());
        entity.setUnitPriceNet(req.unitPriceNet());
        PriceGridItemEntity saved = gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            saved.getId(),
            "PRICE_GRID_ITEM_UPDATED",
            actorId,
            java.util.Map.<String, Object>of("label", req.label(), "unitPriceNet", req.unitPriceNet().toString())
        );
        return toResponse(saved);
    }

    @Transactional
    public void deleteItem(UUID travelerId, UUID itemId, UUID actorId) {
        PriceGridItemEntity entity = gridRepo.findById(itemId)
                .filter(e -> e.getTravelerId().equals(travelerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "price-grid-item-not-found"));
        entity.softDelete();
        gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            itemId,
            "PRICE_GRID_ITEM_DELETED",
            actorId,
            java.util.Map.<String, Object>of("label", entity.getLabel())
        );
    }

    @Transactional
    public void snapshotToAnnouncement(UUID travelerId, UUID announcementId) {
        List<PriceGridItemEntity> items = gridRepo.findByTravelerIdOrderByPositionAsc(travelerId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "price-grid-empty: au moins 1 article requis pour le mode MIXED");
        }
        List<AnnouncementPriceGridItemEntity> snapshots = items.stream().map(item -> {
            AnnouncementPriceGridItemEntity snap = new AnnouncementPriceGridItemEntity();
            snap.setAnnouncementId(announcementId);
            snap.setLabel(item.getLabel());
            snap.setUnitPriceNet(item.getUnitPriceNet());
            snap.setPosition(item.getPosition());
            return snap;
        }).toList();
        annGridRepo.deleteByAnnouncementId(announcementId);
        annGridRepo.saveAll(snapshots);
        auditService.log(
            "ANNOUNCEMENT",
            announcementId,
            "ANNOUNCEMENT_PRICE_GRID_SNAPSHOTTED",
            travelerId,
            java.util.Map.<String, Object>of("itemCount", String.valueOf(items.size()))
        );
    }

    @Transactional
    public List<PriceGridItemResponse> reorder(UUID travelerId, List<UUID> orderedIds) {
        List<PriceGridItemEntity> items = gridRepo.findByTravelerIdOrderByPositionAsc(travelerId);
        for (int i = 0; i < orderedIds.size(); i++) {
            final int pos = i;
            UUID id = orderedIds.get(i);
            items.stream().filter(e -> e.getId().equals(id)).findFirst()
                 .ifPresent(e -> e.setPosition(pos));
        }
        return gridRepo.saveAll(items).stream().map(this::toResponse).toList();
    }

    /**
     * Grille tarifaire d'une annonce, telle qu'elle doit être servie au lecteur courant.
     *
     * <p>{@code unitPriceNet} est masqué pour une session anonyme (voir
     * {@link com.yadony.api.common.GuestSession#travelerNetOrNull}) : c'est le net voyageur, et
     * il voyagerait sinon à côté de son propre brut dans le même objet. {@code unitPriceDisplay}
     * reste toujours servi, c'est lui que le visiteur a besoin de lire.
     *
     * <p>Point unique de construction d'{@link AnnouncementPriceGridItemResponse} : le masquage
     * couvre donc d'un coup la recherche de trajets, le détail d'un trajet et les favoris. Les
     * autres appelants (édition d'une annonce par son voyageur, page publique d'affiche) ne sont
     * pas atteignables par un invité, ou ne lisent que {@code unitPriceDisplay}.
     *
     * <p>À ne pas confondre avec {@link #toResponse(PriceGridItemEntity)}, qui sert la grille du
     * profil voyageur sur {@code /me/price-grid} : cet écran est celui du propriétaire, il édite
     * ses propres nets et n'est pas ouvert aux invités.
     */
    public List<AnnouncementPriceGridItemResponse> getAnnouncementGridItems(UUID announcementId, UUID travelerId) {
        return annGridRepo.findByAnnouncementIdOrderByPositionAsc(announcementId)
                .stream().map(e -> new AnnouncementPriceGridItemResponse(
                        e.getId(), e.getLabel(),
                        com.yadony.api.common.GuestSession.travelerNetOrNull(e.getUnitPriceNet()),
                        displayPrice(e.getUnitPriceNet(), travelerId)
                )).toList();
    }

    private PriceGridItemResponse toResponse(PriceGridItemEntity e) {
        return new PriceGridItemResponse(e.getId(), e.getLabel(), e.getUnitPriceNet(),
                displayPrice(e.getUnitPriceNet(), e.getTravelerId()), e.getPosition());
    }

    /**
     * Prix « affiché expéditeur » = net × (1 + commission Yadony). Le taux est résolu par
     * {@link CommissionRateResolver} à partir du voyageur (override éventuel) et de la
     * config {@code yadony.commission.rate} — SOURCE UNIQUE du pourcentage de commission.
     */
    BigDecimal displayPrice(BigDecimal netPrice, UUID travelerId) {
        BigDecimal multiplier = BigDecimal.ONE.add(commissionRateResolver.resolve(travelerId));
        return netPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
