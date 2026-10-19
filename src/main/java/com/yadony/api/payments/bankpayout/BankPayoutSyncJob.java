package com.yadony.api.payments.bankpayout;

import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rattrapage du suivi de virement bancaire, filet du webhook {@code payout.*} des comptes
 * connectés (toutes les 6 h par défaut, 3 min après le démarrage) :
 * <ol>
 *   <li>ouvre le suivi des paiements transférés récents qui n'en ont pas (Transfers d'avant cette
 *       fonctionnalité, écouteur en échec) ;</li>
 *   <li>complète destination_payment et available_on des lignes incomplètes ;</li>
 *   <li>relit les payouts récents ({@code GET /v1/payouts}) des comptes dont un virement n'est pas
 *       encore arrivé, et les applique comme un webhook (état courant, donc daté de maintenant).</li>
 * </ol>
 * Idempotent : rejouer ne change rien et n'envoie aucune notification déjà envoyée.
 */
@Component
public class BankPayoutSyncJob {

    private static final Logger log = LoggerFactory.getLogger(BankPayoutSyncJob.class);

    /** Marge sur la date de création des payouts relus. */
    static final long PAYOUT_LOOKBACK_MARGIN_SECONDS = 86_400L;

    private static final String UNTRACKED_SQL = """
            SELECT p.id AS payment_id, p.stripe_transfer_id
            FROM payments p
            WHERE p.deleted_at IS NULL
              AND p.rail = 'STRIPE'
              AND p.stripe_transfer_id IS NOT NULL
              AND p.created_at >= :since
              AND NOT EXISTS (SELECT 1 FROM bank_payout_tracking t WHERE t.payment_id = p.id)
            ORDER BY p.created_at
            LIMIT :limit
            """;

    private final BankPayoutTrackingService service;
    private final BankPayoutTrackingRepository repository;
    private final BankPayoutStripeGateway gateway;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;
    private final int lookbackDays;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public BankPayoutSyncJob(BankPayoutTrackingService service, BankPayoutTrackingRepository repository,
                             BankPayoutStripeGateway gateway, NamedParameterJdbcTemplate jdbc,
                             @Value("${yadony.payments.bank-payout-sync.enabled:true}") boolean enabled,
                             @Value("${yadony.payments.bank-payout-sync.lookback-days:60}") int lookbackDays,
                             @Value("${yadony.payments.bank-payout-sync.batch-size:100}") int batchSize) {
        this(service, repository, gateway, jdbc, enabled, lookbackDays, batchSize, Clock.systemUTC());
    }

    BankPayoutSyncJob(BankPayoutTrackingService service, BankPayoutTrackingRepository repository,
                      BankPayoutStripeGateway gateway, NamedParameterJdbcTemplate jdbc, boolean enabled,
                      int lookbackDays, int batchSize, Clock clock) {
        this.service = service;
        this.repository = repository;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.lookbackDays = Math.max(1, lookbackDays);
        this.batchSize = Math.max(1, batchSize);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yadony.payments.bank-payout-sync.interval:PT6H}",
            initialDelayString = "${yadony.payments.bank-payout-sync.initial-delay:PT3M}")
    public void scheduledRun() {
        if (enabled) {
            run();
        }
    }

    /** Bilan d'un passage, pour les journaux et les tests. */
    public record Report(int opened, int completed, int payoutsApplied) {}

    public Report run() {
        LocalDateTime since = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(lookbackDays);
        int opened = openUntracked(since);
        int completed = completeIncomplete(since);
        int applied = resyncPayouts(since);
        if (opened + completed + applied > 0) {
            log.info("Suivi virement bancaire : {} ouverts, {} complétés, {} paiements mis à jour par les payouts",
                    opened, completed, applied);
        }
        return new Report(opened, completed, applied);
    }

    int openUntracked(LocalDateTime since) {
        List<Object[]> untracked = jdbc.query(UNTRACKED_SQL,
                new MapSqlParameterSource().addValue("since", since).addValue("limit", batchSize),
                (rs, i) -> new Object[]{rs.getObject("payment_id"), rs.getString("stripe_transfer_id")});
        int opened = 0;
        for (Object[] row : untracked) {
            UUID paymentId = row[0] instanceof UUID u ? u : UUID.fromString(String.valueOf(row[0]));
            try {
                BankPayoutTrackingEntity tracking = service.openRow(paymentId, null, (String) row[1], null);
                if (tracking != null) {
                    service.enrichQuietly(tracking);
                    opened++;
                }
            } catch (RuntimeException e) {
                log.warn("Suivi virement du paiement {} non ouvert : {}", paymentId, e.getMessage());
            }
        }
        return opened;
    }

    int completeIncomplete(LocalDateTime since) {
        int completed = 0;
        for (BankPayoutTrackingEntity row : repository.findIncomplete(since, PageRequest.of(0, batchSize))) {
            service.enrichQuietly(row);
            if (row.getAvailableOn() != null) {
                completed++;
            }
        }
        return completed;
    }

    int resyncPayouts(LocalDateTime since) {
        int applied = 0;
        Instant now = clock.instant();
        for (String account : repository.findAccountsAwaitingBank(since)) {
            Optional<LocalDateTime> oldest = repository.findOldestAwaitingBank(account, since);
            long createdGte = oldest.orElse(since).toEpochSecond(ZoneOffset.UTC) - PAYOUT_LOOKBACK_MARGIN_SECONDS;
            try {
                List<PayoutSnapshot> payouts = new ArrayList<>(gateway.recentPayouts(account, createdGte));
                // Du plus ancien au plus récent : un payout qui reprend un payout échoué passe après lui.
                payouts.sort(Comparator.comparing(PayoutSnapshot::created,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
                for (PayoutSnapshot payout : payouts) {
                    if (alreadyApplied(account, payout)) {
                        continue;
                    }
                    Instant paidAt = payout.arrivalDate() != null
                            ? payout.arrivalDate().atStartOfDay(ZoneOffset.UTC).toInstant() : now;
                    applied += service.applyPayout(account, payout, now, paidAt);
                }
            } catch (StripeException | RuntimeException e) {
                log.warn("Suivi virement : payouts du compte {} illisibles ({})", account, e.getMessage());
            }
        }
        return applied;
    }

    /** Payout terminé déjà appliqué tel quel : inutile de relire son contenu chez Stripe. */
    private boolean alreadyApplied(String account, PayoutSnapshot payout) {
        if (payout.status() == null || BankPayoutTrackingService.rank(payout.status()) < 3) {
            return false;
        }
        List<BankPayoutTrackingEntity> rows = repository.findByConnectedAccountIdAndPayoutIdIn(account,
                List.of(payout.id()));
        return !rows.isEmpty() && rows.stream().allMatch(r -> payout.status().equals(r.getPayoutStatus()));
    }
}
