package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRemovalReason;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.PackageRequestRemovedByAdminEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retrait et restauration d'une demande d'envoi par la modération. Pendant, côté demandes,
 * de {@code AnnouncementService#removeByAdmin}/{@code #restoreByAdmin} : motif public
 * catalogué annoncé à l'expéditeur, note interne réservée à l'audit, statut d'origine
 * mémorisé pour la restauration.
 *
 * <h2>Classement des fils de négociation au retrait</h2>
 * <ul>
 *   <li><b>Bloquants</b> (409 {@code package-request-has-active-shipment}, à traiter en
 *       litige) : {@code ACCEPTED} (payé, colis scellé), {@code AWAITING_DEPOSIT} (dépôt
 *       mobile money en vol, l'argent a pu quitter le compte de l'expéditeur),
 *       {@code AWAITING_COMMISSION} dont la commission a été tentée ou réglée
 *       (PaymentIntent de commission ou canal de débit renseigné), et tout fil ayant
 *       matérialisé un colis ({@code materializedBidId}).</li>
 *   <li><b>Annulés</b> par le chemin existant de l'annulation d'une demande
 *       ({@link PackageRequestService#terminateActiveNegotiations}) : {@code OPEN},
 *       {@code AWAITING_TRIP}, {@code AWAITING_PAYMENT} (le hold carte éventuel n'est
 *       jamais capturé avant ACCEPTED ; il est annulé par l'écouteur AFTER_COMMIT de
 *       {@code NegotiationCancelledEvent}), {@code AWAITING_COMMISSION} sans commission
 *       tentée.</li>
 *   <li><b>Ignorés</b> (déjà terminés) : {@code REJECTED}, {@code CANCELLED},
 *       {@code AUTO_REJECTED}, {@code EXPIRED}.</li>
 * </ul>
 */
@Service
public class PackageRequestModerationService {

    /** Nom affiché au voyageur dont la négociation est close par le retrait. */
    public static final String ADMIN_ACTOR_NAME = "Yadony";
    static final String CANCEL_REASON = "request-removed-by-admin";

    public static final String ALREADY_REMOVED = "package-request-already-removed";
    public static final String DRAFT = "package-request-draft";
    public static final String COMPLETED = "package-request-completed";
    public static final String HAS_ACTIVE_SHIPMENT = "package-request-has-active-shipment";
    public static final String NOT_REMOVED = "package-request-not-removed";
    public static final String NOT_FOUND = "package-request-not-found";

    private final PackageRequestRepository requestRepository;
    private final NegotiationThreadRepository threadRepository;
    private final PackageRequestService packageRequestService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public PackageRequestModerationService(PackageRequestRepository requestRepository,
                                           NegotiationThreadRepository threadRepository,
                                           PackageRequestService packageRequestService,
                                           AuditService auditService,
                                           ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.threadRepository = threadRepository;
        this.packageRequestService = packageRequestService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /** Ce fil a-t-il engagé de l'argent, ou créé un colis, au point d'interdire le retrait ? */
    public static boolean blocksRemoval(NegotiationThreadEntity thread) {
        if (thread.getMaterializedBidId() != null) {
            return true;
        }
        return switch (thread.getStatus()) {
            case ACCEPTED, AWAITING_DEPOSIT -> true;
            case AWAITING_COMMISSION -> thread.getCommissionPaymentIntentId() != null
                    || thread.getCommissionChargedVia() != null;
            case OPEN, AWAITING_TRIP, AWAITING_PAYMENT, REJECTED, CANCELLED, AUTO_REJECTED, EXPIRED -> false;
        };
    }

    /** {@code null} si la demande peut être retirée, sinon le code du 409 qui l'en empêche. */
    public String removalBlockedReason(PackageRequestEntity request, List<NegotiationThreadEntity> threads) {
        return switch (request.getStatus()) {
            case REMOVED_BY_ADMIN -> ALREADY_REMOVED;
            case DRAFT -> DRAFT;
            case COMPLETED -> COMPLETED;
            case ACCEPTED -> HAS_ACTIVE_SHIPMENT;
            case OPEN, NEGOTIATING, EXPIRED, CANCELLED ->
                    threads.stream().anyMatch(PackageRequestModerationService::blocksRemoval)
                            ? HAS_ACTIVE_SHIPMENT : null;
        };
    }

    /**
     * Verrou pessimiste sur la demande, comme {@code PackageRequestService#cancel} : un
     * règlement de commission concurrent verrouille la même ligne avant de débiter, les deux
     * se sérialisent. Les effets financiers des fils annulés (hold carte, commission) partent
     * par les écouteurs AFTER_COMMIT de {@code NegotiationCancelledEvent}, jamais en ligne.
     */
    @Transactional
    @CacheEvict(value = "negotiations-me", allEntries = true)
    public PackageRequestEntity removeByAdmin(UUID requestId, UUID adminId,
                                              AnnouncementRemovalReason publicReason, String internalNote) {
        PackageRequestEntity request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(PackageRequestModerationService::notFound);
        List<NegotiationThreadEntity> threads = threadRepository.findByPackageRequestId(requestId);

        String blocked = removalBlockedReason(request, threads);
        if (blocked != null) {
            throw conflict(blocked);
        }

        PackageRequestStatus previous = request.getStatus();
        request.setStatusBeforeRemoval(previous);
        request.setStatus(PackageRequestStatus.REMOVED_BY_ADMIN);
        requestRepository.save(request);

        int cancelled = packageRequestService.terminateActiveNegotiations(requestId, adminId,
                ADMIN_ACTOR_NAME, NegotiationThreadStatus.CANCELLED, CANCEL_REASON);

        auditService.log("PACKAGE_REQUEST", requestId, "PACKAGE_REQUEST_REMOVED_BY_ADMIN", adminId,
                Map.of("publicReason", publicReason.name(),
                        "internalNote", internalNote != null ? internalNote : "",
                        "cancelledNegotiations", String.valueOf(cancelled),
                        "previousStatus", previous.name()));

        // SEUL le code catalogué part vers l'expéditeur, jamais la note interne.
        eventPublisher.publishEvent(new PackageRequestRemovedByAdminEvent(
                requestId, request.getSenderId(), publicReason.name()));
        return request;
    }

    /**
     * Restitue le statut d'avant le retrait. Les négociations annulées au retrait ne sont pas
     * rétablies : une demande NEGOTIATING sans plus aucun fil actif redevient OPEN. Une
     * demande encore ouverte dont la date souhaitée est passée repart EXPIRED, avec la même
     * règle que le balayage d'expiration ({@code PackageRequestRepository#findExpired},
     * date souhaitée antérieure au jour UTC) : la rouvrir la ferait expirer au passage
     * suivant, avec une notification « demande expirée » en prime.
     */
    @Transactional
    public PackageRequestEntity restoreByAdmin(UUID requestId, UUID adminId) {
        PackageRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(PackageRequestModerationService::notFound);
        if (request.getStatus() != PackageRequestStatus.REMOVED_BY_ADMIN) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, NOT_REMOVED,
                    "Package Request Not Removed", "Cette demande n'a pas été retirée par la modération.");
        }

        PackageRequestStatus target = request.getStatusBeforeRemoval() != null
                ? request.getStatusBeforeRemoval()
                : PackageRequestStatus.OPEN;
        if (target == PackageRequestStatus.NEGOTIATING
                && threadRepository.findByPackageRequestId(requestId).stream()
                        .noneMatch(t -> t.getStatus().isActive())) {
            target = PackageRequestStatus.OPEN;
        }
        if ((target == PackageRequestStatus.OPEN || target == PackageRequestStatus.NEGOTIATING)
                && request.getDesiredDate() != null
                && request.getDesiredDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            target = PackageRequestStatus.EXPIRED;
        }

        request.setStatus(target);
        request.setStatusBeforeRemoval(null);
        requestRepository.save(request);

        auditService.log("PACKAGE_REQUEST", requestId, "PACKAGE_REQUEST_RESTORED_BY_ADMIN", adminId,
                Map.of("restoredStatus", target.name()));
        return request;
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, NOT_FOUND,
                "Package Request Not Found", "Demande d'envoi introuvable");
    }

    private static YadonyBusinessException conflict(String code) {
        String detail = switch (code) {
            case ALREADY_REMOVED -> "Cette demande a déjà été retirée par la modération.";
            case DRAFT -> "Un brouillon n'est pas publié : il n'y a rien à retirer.";
            case COMPLETED -> "Cette demande est terminée : le colis a été livré.";
            default -> "Un envoi est engagé sur cette demande (paiement, commission ou colis). "
                    + "Traitez-le par un litige plutôt que par un retrait.";
        };
        return new YadonyBusinessException(HttpStatus.CONFLICT, code, "Package Request Not Removable", detail);
    }
}
