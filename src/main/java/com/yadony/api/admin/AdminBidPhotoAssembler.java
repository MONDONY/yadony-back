package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminBidPhotosResponse;
import com.yadony.api.common.StorageService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidPhotoEntity;
import com.yadony.api.matching.BidPhotoRepository;
import com.yadony.api.matching.BidPhotoStatus;
import com.yadony.api.messaging.MessagingImageEntity;
import com.yadony.api.messaging.MessagingImageRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Photos d'un colis rangées par étape (création, départ, transit, arrivée, refus, litige) pour la
 * fiche admin. Les URL sont signées à la demande (15 min), jamais stockées ni journalisées. Une clé
 * qui ne se signe pas est ignorée et une panne de stockage donne une liste partielle, jamais une 500.
 */
@Component
public class AdminBidPhotoAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdminBidPhotoAssembler.class);

    /** Ordre d'affichage des étapes. */
    private enum Stage { CREATION, DEPART, TRANSIT, ARRIVEE, REFUSAL, DISPUTE }

    private static final String DISPUTE_IMAGE_LABEL = "Image de la conversation";

    private final BidPhotoRepository photoRepo;
    private final TrackingEventRepository trackingRepo;
    private final ConversationRepository conversationRepo;
    private final MessagingImageRepository imageRepo;
    private final DisputeRepository disputeRepo;
    private final StorageService storageService;

    public AdminBidPhotoAssembler(BidPhotoRepository photoRepo, TrackingEventRepository trackingRepo,
                                  ConversationRepository conversationRepo, MessagingImageRepository imageRepo,
                                  DisputeRepository disputeRepo, StorageService storageService) {
        this.photoRepo = photoRepo;
        this.trackingRepo = trackingRepo;
        this.conversationRepo = conversationRepo;
        this.imageRepo = imageRepo;
        this.disputeRepo = disputeRepo;
        this.storageService = storageService;
    }

    public List<AdminBidPhotosResponse.Photo> photos(BidEntity bid, AdminBidDetailAssembler.Access access) {
        List<Staged> out = new ArrayList<>();
        out.addAll(creation(bid));
        out.addAll(scans(bid));
        out.addAll(refusal(bid));
        if (access.moderation() && access.disputes()) out.addAll(disputeImages(bid));
        return out.stream()
                .sorted(Comparator.comparing(Staged::stage)
                        .thenComparing(Staged::at, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Staged::photo)
                .toList();
    }

    private List<Staged> creation(BidEntity bid) {
        try {
            // Photos ACTIVE par position ; la table ne porte pas de date par photo : celle du colis.
            // Signature photo par photo : une clé qui échoue est ignorée, les autres restent.
            List<Staged> out = new ArrayList<>();
            for (BidPhotoEntity p : photoRepo.findByBidIdAndStatusOrderByPositionAsc(bid.getId(), BidPhotoStatus.ACTIVE)) {
                String url = AdminBidDetailAssembler.sign(storageService, p.getObjectKey());
                if (url != null) {
                    out.add(staged(Stage.CREATION, p.getId().toString(), "BID_PHOTO", bid.getCreatedAt(), url, null));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Photos du colis {} illisibles : {}", bid.getId(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<Staged> scans(BidEntity bid) {
        List<Staged> out = new ArrayList<>();
        for (TrackingEventEntity e : trackingRepo.findByBidIdOrderByScannedAtAsc(bid.getId())) {
            Stage stage = stageOf(e.getEventType());
            if (stage == null || e.getPhotoPurgedAt() != null) continue;
            String url = AdminBidDetailAssembler.sign(storageService, e.getPhotoUrl());
            if (url == null) continue;
            out.add(staged(stage, String.valueOf(e.getId()),
                    e.getEventType().name(), e.getScannedAt(), url, e.getGpsLabel()));
        }
        return out;
    }

    /** Étape d'un scan ; null (ignoré, jamais une 500) pour une valeur absente ou inconnue. */
    private static Stage stageOf(TrackingEventType type) {
        if (type == null) return null;
        return switch (type) {
            case DEPART -> Stage.DEPART;
            case TRANSIT -> Stage.TRANSIT;
            case ARRIVEE -> Stage.ARRIVEE;
            default -> null;
        };
    }

    /**
     * Photo de refus. {@code refusalPhotoUrl} est une chaîne libre envoyée par le voyageur (aucune
     * validation à l'écriture) : on ne signe que la clé téléversée par l'app pour CE colis
     * ({@code tracking/<bidId>/...}), jamais une URL ni la clé d'un autre dossier (ex. KYC).
     * Date : la date de refus n'est pas stockée, on prend la dernière mise à jour du colis
     * (approximation : le refus fait passer le colis en PARCEL_REFUSED, sa dernière écriture courante).
     */
    private List<Staged> refusal(BidEntity bid) {
        String key = bid.getRefusalPhotoUrl();
        if (key == null || key.isBlank()) return List.of();
        if (!isOwnRefusalKey(bid.getId(), key)) {
            log.warn("Photo de refus du colis {} ignorée : clé hors du dossier du colis", bid.getId());
            return List.of();
        }
        String url = AdminBidDetailAssembler.sign(storageService, key);
        return url == null ? List.of()
                : List.of(staged(Stage.REFUSAL, "refusal", "REFUSAL", bid.getUpdatedAt(), url, null));
    }

    private static boolean isOwnRefusalKey(java.util.UUID bidId, String key) {
        return key.startsWith("tracking/" + bidId + "/")
                && !key.contains("..") && !key.contains("\\") && !key.contains("://");
    }

    /** Images de la conversation : seulement quand un litige existe sur le colis, et jamais les purgées. */
    private List<Staged> disputeImages(BidEntity bid) {
        if (!disputeRepo.existsByBidId(bid.getId())) return List.of();
        Optional<ConversationEntity> conversation = conversationRepo.findByBidId(bid.getId())
                .filter(c -> c.getDeletedAt() == null);
        if (conversation.isEmpty()) return List.of();
        List<Staged> out = new ArrayList<>();
        for (MessagingImageEntity img : imageRepo.findByConversationId(conversation.get().getId())) {
            if (img.isPurged() || img.getDeletedAt() != null) continue;
            String url = AdminBidDetailAssembler.sign(storageService, img.getImageKey());
            if (url == null) continue;
            out.add(staged(Stage.DISPUTE, String.valueOf(img.getId()), DISPUTE_IMAGE_LABEL,
                    img.getCreatedAt(), url, null));
        }
        return out;
    }

    private static Staged staged(Stage stage, String id, String label, LocalDateTime at, String url, String gps) {
        return new Staged(stage, at, new AdminBidPhotosResponse.Photo(id, stage.name(), label, at, url, gps));
    }

    private record Staged(Stage stage, LocalDateTime at, AdminBidPhotosResponse.Photo photo) {
    }
}
