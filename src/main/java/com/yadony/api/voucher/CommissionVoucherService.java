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
 * seul point d'écriture, verrouillé pessimiste pour éviter la double consommation.
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

        List<CommissionVoucherEntity> active =
                repository.findActiveByUserId(userId, LocalDateTime.now(ZoneOffset.UTC));
        if (active.isEmpty()) {
            return Optional.empty();
        }

        CommissionVoucherEntity locked = repository.findByIdForUpdate(active.get(0).getId())
                .orElseThrow();
        // Reverifier sous verrou : un autre thread a pu consommer ce même bon entre le
        // peek ci-dessus et l'acquisition du verrou.
        if (locked.getConsumedAt() != null) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!locked.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        locked.setConsumedAt(now);
        locked.setConsumedOnBidId(bidId);
        CommissionVoucherEntity saved = repository.save(locked);

        auditService.log("COMMISSION_VOUCHER", saved.getId(), "COMMISSION_VOUCHER_CONSUMED",
                userId, Map.of("bidId", bidId.toString(), "factor", saved.getFactor().toPlainString()));

        log.info("Voucher {} consumed by user={} bid={}", saved.getId(), userId, bidId);
        return Optional.of(saved);
    }
}
