package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminBidPhotosResponse;
import com.yadony.api.common.StorageService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidPhotoService;
import com.yadony.api.messaging.MessagingImageEntity;
import com.yadony.api.messaging.MessagingImageRepository;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
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

    private final BidPhotoService photoService;
    private final TrackingEventRepository trackingRepo;
    private final ConversationRepository conversationRepo;
    private final MessagingImageRepository imageRepo;
    private final DisputeRepository disputeRepo;
    private final StorageService storageService;

    public AdminBidPhotoAssembler(BidPhotoService photoService, TrackingEventRepository trackingRepo,
                                  ConversationRepository conversationRepo, MessagingImageRepository imageRepo,
                                  DisputeRepository disputeRepo, StorageService storageService) {
        this.photoService = photoService;
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
            // Le service signe lui-même les URL ; la table ne porte pas de date par photo : celle du colis.
            return photoService.activePhotos(bid.getId()).stream()
                    .map(p -> staged(Stage.CREATION, p.id().toString(), "BID_PHOTO", bid.getCreatedAt(), p.url(), null))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Photos du colis {} illisibles : {}", bid.getId(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<Staged> scans(BidEntity bid) {
        List<Staged> out = new ArrayList<>();
        for (TrackingEventEntity e : trackingRepo.findByBidIdOrderByScannedAtAsc(bid.getId())) {
            if (e.getEventType() == null || e.getPhotoPurgedAt() != null) continue;
            String url = AdminBidDetailAssembler.sign(storageService, e.getPhotoUrl());
            if (url == null) continue;
            out.add(staged(Stage.valueOf(e.getEventType().name()), String.valueOf(e.getId()),
                    e.getEventType().name(), e.getScannedAt(), url, e.getGpsLabel()));
        }
        return out;
    }

    private List<Staged> refusal(BidEntity bid) {
        String url = AdminBidDetailAssembler.sign(storageService, bid.getRefusalPhotoUrl());
        return url == null ? List.of()
                : List.of(staged(Stage.REFUSAL, "refusal", "REFUSAL", bid.getUpdatedAt(), url, null));
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
