package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.gppro.dto.GpProApplyRequest;
import com.yadony.api.gppro.dto.GpProAttachDocumentsRequest;
import com.yadony.api.gppro.dto.GpProStatusResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Demande, statut et annulation du badge GP Pro côté utilisateur. */
@Service
public class GpProApplicationService {

    private static final Set<GpProApplicationStatus> OPEN =
            EnumSet.of(GpProApplicationStatus.AWAITING_KYC, GpProApplicationStatus.PENDING);

    private final GpProApplicationRepository applicationRepository;
    private final GpProDocumentRepository documentRepository;
    private final GpProDocumentService documentService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public GpProApplicationService(GpProApplicationRepository applicationRepository,
                                   GpProDocumentRepository documentRepository,
                                   GpProDocumentService documentService,
                                   UserRepository userRepository,
                                   AuditService auditService) {
        this.applicationRepository = applicationRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public GpProStatusResponse apply(String firebaseUid, GpProApplyRequest r) {
        if (r.source() != GpProApplicationSource.SIGNUP && r.source() != GpProApplicationSource.PROFILE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-invalid-source", "Invalid Source", "Source de demande invalide");
        }
        UserEntity user = findUser(firebaseUid);
        // Verrou par utilisateur : sérialise les demandes concurrentes avec l'index unique des demandes ouvertes.
        userRepository.findByIdForUpdate(user.getId());
        // L'entité chargée avant le verrou peut être périmée : on relit le drapeau en base, une fois le verrou pris.
        if (userRepository.findGpProById(user.getId()).orElse(user.isGpPro())) {
            throw conflict("gp-pro-already-active", "Already Active", "Le badge GP Pro est déjà actif");
        }
        if (applicationRepository.findFirstByUserIdAndStatusIn(user.getId(), OPEN).isPresent()) {
            throw openConflict();
        }
        boolean kycVerified = user.getKycStatus() == KycStatus.VERIFIED;
        if (!kycVerified && r.source() == GpProApplicationSource.PROFILE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-kyc-required", "KYC Required", "La vérification d'identité est requise");
        }
        List<UUID> ids = r.documentIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(r.documentIds()));
        List<GpProDocumentEntity> docs = ids.isEmpty() ? List.of()
                : documentRepository.findByIdInAndOwnerIdAndApplicationIdIsNull(ids, user.getId());
        if (docs.size() != ids.size()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-document-invalid", "Invalid Document", "Justificatif invalide");
        }
        GpProApplicationEntity app = new GpProApplicationEntity();
        app.setUserId(user.getId());
        app.setStatus(kycVerified ? GpProApplicationStatus.PENDING : GpProApplicationStatus.AWAITING_KYC);
        app.setSource(r.source());
        app.setTravelFrequency(r.travelFrequency());
        app.setCommercialName(blankToNull(r.commercialName()));
        try {
            app = applicationRepository.saveAndFlush(app);
        } catch (DataIntegrityViolationException e) {
            throw openConflict();
        }
        for (GpProDocumentEntity d : docs) {
            d.setApplicationId(app.getId());
        }
        documentRepository.saveAll(docs);
        // Jamais le nom commercial dans l'audit.
        auditService.log("GP_PRO_APPLICATION", app.getId(), "GP_PRO_APPLIED", user.getId(),
                Map.of("source", r.source().name(), "frequency", r.travelFrequency().name(),
                        "documentCount", docs.size()));
        return new GpProStatusResponse(app.getStatus().name(), app.getTravelFrequency(),
                app.getCommercialName(), docs.size(), null, null, documentsOf(docs));
    }

    /** Ajoute des justificatifs à la demande encore ouverte (4 au plus au total, purgés exclus). */
    @Transactional
    public GpProStatusResponse attachDocuments(String firebaseUid, GpProAttachDocumentsRequest r) {
        UserEntity user = findUser(firebaseUid);
        userRepository.findByIdForUpdate(user.getId());
        GpProApplicationEntity app = applicationRepository.findFirstByUserIdAndStatusIn(user.getId(), OPEN)
                .orElseThrow(GpProApplicationService::applicationNotFound);
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(r.documentIds()));
        List<GpProDocumentEntity> docs = documentRepository
                .findByIdInAndOwnerIdAndApplicationIdIsNull(ids, user.getId()).stream()
                .filter(d -> d.getPurgedAt() == null)
                .toList();
        if (docs.size() != ids.size()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-document-invalid", "Invalid Document", "Justificatif invalide");
        }
        List<GpProDocumentEntity> existingDocs = documentRepository.findByApplicationIdAndPurgedAtIsNull(app.getId());
        int existing = existingDocs.size();
        if (existing + docs.size() > GpProDocumentService.MAX_UNATTACHED_DOCUMENTS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "gp-pro-too-many-documents", "Too Many Documents",
                    "Au plus " + GpProDocumentService.MAX_UNATTACHED_DOCUMENTS + " justificatifs par demande");
        }
        for (GpProDocumentEntity d : docs) {
            d.setApplicationId(app.getId());
        }
        documentRepository.saveAll(docs);
        for (GpProDocumentEntity d : docs) {
            auditService.log("GP_PRO_APPLICATION", app.getId(), "GP_PRO_DOCUMENT_ADDED", user.getId(),
                    Map.of("docType", d.getDocType().name()));
        }
        List<GpProDocumentEntity> all = new java.util.ArrayList<>(existingDocs);
        all.addAll(docs);
        return new GpProStatusResponse(app.getStatus().name(), app.getTravelFrequency(),
                app.getCommercialName(), all.size(), null, null, documentsOf(all));
    }

    /**
     * Supprime un justificatif non rattaché ou rattaché à la demande encore ouverte. Tout autre cas
     * (inconnu, d'un autre utilisateur, déjà purgé, demande décidée) rend le même 404.
     */
    @Transactional
    public void removeDocument(String firebaseUid, UUID documentId) {
        UserEntity user = findUser(firebaseUid);
        userRepository.findByIdForUpdate(user.getId());
        GpProDocumentEntity doc = documentRepository.findById(documentId)
                .filter(d -> user.getId().equals(d.getOwnerId()) && d.getPurgedAt() == null)
                .orElseThrow(GpProApplicationService::documentNotFound);
        UUID applicationId = doc.getApplicationId();
        if (applicationId != null) {
            boolean open = applicationRepository.findById(applicationId)
                    .map(a -> OPEN.contains(a.getStatus()))
                    .orElse(false);
            if (!open) {
                throw documentNotFound();
            }
        }
        if (!documentService.purge(doc)) {
            throw new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "gp-pro-document-storage-unavailable", "Storage Unavailable",
                    "Le justificatif n'a pas pu être supprimé, réessayez plus tard");
        }
        boolean attached = applicationId != null;
        auditService.log(attached ? "GP_PRO_APPLICATION" : "GP_PRO_DOCUMENT",
                attached ? applicationId : doc.getId(), "GP_PRO_DOCUMENT_REMOVED", user.getId(),
                Map.of("docType", doc.getDocType().name()));
    }

    private static List<GpProStatusResponse.Document> documentsOf(List<GpProDocumentEntity> docs) {
        return docs.stream()
                .filter(d -> d.getPurgedAt() == null)
                .map(d -> new GpProStatusResponse.Document(d.getId(), d.getDocType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public GpProStatusResponse status(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        if (user.isGpPro()) {
            return new GpProStatusResponse("ACTIVE", null, null, 0, null, user.getGpProGrantedAt());
        }
        Optional<GpProApplicationEntity> open = applicationRepository.findFirstByUserIdAndStatusIn(user.getId(), OPEN);
        if (open.isPresent()) {
            GpProApplicationEntity a = open.get();
            List<GpProDocumentEntity> docs = documentRepository.findByApplicationIdAndPurgedAtIsNull(a.getId());
            return new GpProStatusResponse(a.getStatus().name(), a.getTravelFrequency(),
                    a.getCommercialName(), docs.size(), null, null, documentsOf(docs));
        }
        Optional<GpProApplicationEntity> rejected = applicationRepository
                .findFirstByUserIdAndStatusOrderByDecidedAtDesc(user.getId(), GpProApplicationStatus.REJECTED);
        boolean hasRejection = rejected.isPresent() && rejected.get().getDecidedAt() != null;
        boolean hasRevocation = user.getGpProRevokedAt() != null;
        if (hasRevocation && (!hasRejection || !rejected.get().getDecidedAt().isAfter(user.getGpProRevokedAt()))) {
            return new GpProStatusResponse("REVOKED", null, null, 0,
                    user.getGpProRevokedReason(), user.getGpProRevokedAt());
        }
        if (hasRejection) {
            GpProApplicationEntity a = rejected.get();
            return new GpProStatusResponse("REJECTED", a.getTravelFrequency(), a.getCommercialName(), 0,
                    a.getDecisionReason(), a.getDecidedAt());
        }
        return new GpProStatusResponse("NONE", null, null, 0, null, null);
    }

    @Transactional
    public void cancel(String firebaseUid) {
        UserEntity user = findUser(firebaseUid);
        userRepository.findByIdForUpdate(user.getId());
        GpProApplicationEntity app = applicationRepository.findFirstByUserIdAndStatusIn(user.getId(), OPEN)
                .orElseThrow(GpProApplicationService::applicationNotFound);
        app.setStatus(GpProApplicationStatus.CANCELLED);
        applicationRepository.saveAndFlush(app);
        int purged = documentService.purgeAll(app.getId());
        auditService.log("GP_PRO_APPLICATION", app.getId(), "GP_PRO_APPLICATION_CANCELLED", user.getId(),
                Map.of("purgedDocuments", purged));
    }

    private UserEntity findUser(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "User Not Found", "Utilisateur introuvable"));
    }

    private static YadonyBusinessException applicationNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "gp-pro-application-not-found", "Application Not Found", "Aucune demande en cours");
    }

    private static YadonyBusinessException documentNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "gp-pro-document-not-found", "Document Not Found", "Justificatif introuvable");
    }

    private static YadonyBusinessException conflict(String code, String title, String detail) {
        return new YadonyBusinessException(HttpStatus.CONFLICT, code, title, detail);
    }

    private static YadonyBusinessException openConflict() {
        return conflict("gp-pro-application-open", "Application Open", "Une demande est déjà en cours");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
