package com.yadony.api.voucher;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bons de réduction de commission (lot 3) : octroi à l'invitation, consommation
 * atomique unique à la charge effective d'une commission.
 *
 * <p>Miroir volontaire de {@link com.yadony.api.promo.PromoService} : {@link #peekActive}
 * valide/lit sans effet de bord (devis, résolution de taux), {@link #consume} est le
 * seul point d'écriture, un UPDATE conditionnel unique pour éviter la double consommation.
 */
@Service
public class CommissionVoucherService {

    private static final Logger log = LoggerFactory.getLogger(CommissionVoucherService.class);

    private final CommissionVoucherRepository repository;
    private final AuditService auditService;
    private final VoucherConfig config;

    public CommissionVoucherService(CommissionVoucherRepository repository,
                                     AuditService auditService,
                                     VoucherConfig config) {
        this.repository = repository;
        this.auditService = auditService;
        this.config = config;
    }

    /**
     * Octroie un bon au détenteur. Idempotent par {@code sourceInvitationId} — une
     * invitation ne peut jamais générer deux bons, même rejouée.
     */
    @Transactional
    public CommissionVoucherEntity grant(UUID userId, UUID sourceInvitationId) {
        Optional<CommissionVoucherEntity> existing =
                repository.findBySourceInvitationId(sourceInvitationId);
        if (existing.isPresent()) {
            log.info("Voucher already granted for invitation {} (idempotent skip)", sourceInvitationId);
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CommissionVoucherEntity voucher = new CommissionVoucherEntity();
        voucher.setUserId(userId);
        voucher.setFactor(config.getFactor());
        voucher.setGrantedAt(now);
        voucher.setExpiresAt(now.plusMonths(config.getValidityMonths()));
        voucher.setSourceInvitationId(sourceInvitationId);
        return repository.save(voucher);
    }

    /**
     * Lit sans consommer : le bon le plus ancien encore disponible pour ce détenteur,
     * s'il en existe un. Utilisé pour calculer un taux/prélèvement à titre indicatif
     * (devis) avant tout engagement financier.
     */
    @Transactional(readOnly = true)
    public Optional<CommissionVoucherEntity> peekActive(UUID userId) {
        List<CommissionVoucherEntity> active = listActive(userId);
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    /**
     * Le bon applicable à une transaction identifiée ({@code reference} = bid ou fil de
     * négociation) : celui qui a DÉJÀ été consommé pour cette référence s'il existe,
     * sinon le plus ancien encore disponible.
     *
     * <p>Sans ce repli, un réessai de prélèvement (carte refusée puis retentée, 3DS
     * complétée plus tard) recalculait le taux après consommation du bon et le faisait
     * remonter au plein tarif : le détenteur perdait sa remise entre deux tentatives du
     * même règlement. Lecture pure, aucun effet de bord.
     */
    @Transactional(readOnly = true)
    public Optional<CommissionVoucherEntity> peekForReference(UUID userId, UUID reference) {
        if (reference == null) {
            return peekActive(userId);
        }
        Optional<CommissionVoucherEntity> alreadyConsumed =
                repository.findByConsumedOnBidIdAndUserId(reference, userId);
        return alreadyConsumed.isPresent() ? alreadyConsumed : peekActive(userId);
    }

    /** Tous les bons encore disponibles pour ce détenteur, triés du plus ancien au plus récent. */
    @Transactional(readOnly = true)
    public List<CommissionVoucherEntity> listActive(UUID userId) {
        return repository.findActiveByUserId(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Consomme atomiquement le bon le plus ancien disponible pour {@code userId} au
     * profit de {@code bidId}. Idempotent : rejouer avec le même {@code bidId} renvoie
     * le même bon sans le reconsommer. Ne lève jamais — un bon absent ou déjà pris par
     * un autre thread entre le peek et le verrou renvoie simplement {@code empty()},
     * l'appelant doit alors facturer au taux plein (jamais bloquer un paiement pour ça).
     */
    @Transactional
    public Optional<CommissionVoucherEntity> consume(UUID userId, UUID bidId) {
        // Scopé (bidId, userId) : un bid peut porter deux consommations distinctes
        // (bon de l'expéditeur ET bon du voyageur, deux utilisateurs différents).
        Optional<CommissionVoucherEntity> alreadyConsumedForThisBid =
                repository.findByConsumedOnBidIdAndUserId(bidId, userId);
        if (alreadyConsumedForThisBid.isPresent()) {
            log.info("Voucher already consumed for bid {} (idempotent skip)", bidId);
            return alreadyConsumedForThisBid;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CommissionVoucherEntity> active = repository.findActiveByUserId(userId, now);
        if (active.isEmpty()) {
            return Optional.empty();
        }

        // UPDATE conditionnel plutot que lecture-puis-ecriture sous verrou pessimiste :
        // Hibernate posait bien le verrou mais rendait l'instance deja geree par le
        // contexte de persistance, sans rafraichir son etat depuis la base — le test
        // consumedAt != null pouvait donc porter sur une version anterieure au commit
        // d'un thread concurrent, et le bon etre consomme deux fois. Ici la garde est
        // evaluee par la base au moment de l'ecriture : le perdant de la course voit
        // 0 ligne affectee et facture au taux plein.
        if (repository.consumeIfAvailable(active.get(0).getId(), now, bidId) == 0) {
            return Optional.empty();
        }
        CommissionVoucherEntity saved = repository.findById(active.get(0).getId()).orElseThrow();

        auditService.log("COMMISSION_VOUCHER", saved.getId(), "COMMISSION_VOUCHER_CONSUMED",
                userId, Map.of("bidId", bidId.toString(), "factor", saved.getFactor().toPlainString()));

        log.info("Voucher {} consumed by user={} bid={}", saved.getId(), userId, bidId);
        return Optional.of(saved);
    }
}
