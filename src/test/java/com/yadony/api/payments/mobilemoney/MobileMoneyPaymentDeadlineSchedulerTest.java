package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.mobilemoney.MobileMoneyBidPaymentService.ExpireOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tâche 15 — scheduler d'expiration mobile money.
 *
 * <p><b>Ronde 2 (revue)</b> : {@code expire()} rend désormais un {@link ExpireOutcome} au lieu de
 * lever elle-même une alerte ou d'évincer le cache — c'est ce scheduler qui agit sur cette valeur,
 * hors des verrous tenus par {@code expire}. Nouveaux mocks : {@code alerts}/{@code alertRepository}
 * (dédup par bid, structure reprise d'{@code escalateUnknown}) et {@code cacheManager} (éviction
 * programmatique de {@code announcements-search}, seulement sur {@code CANCELLED}).
 *
 * <p>Écart par rapport au cahier des charges (voir task-15-report.md) : le brief appelait
 * {@code BidRepository#findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore} à 3
 * arguments (non bornée). Consigne explicite de la tâche : le lot doit être borné, sur le
 * modèle de {@code PawapayReconciliationPoller} (tâche 10). La méthode a donc gagné un 4e
 * paramètre {@code Pageable}.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyPaymentDeadlineSchedulerTest {

    @Mock BidRepository bidRepository;
    @Mock MobileMoneyBidPaymentService service;
    @Mock AdminAlertService alerts;
    @Mock AdminAlertRepository alertRepository;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @InjectMocks MobileMoneyPaymentDeadlineScheduler scheduler;

    private static BidEntity bid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        return b;
    }

    private void stubDue(BidEntity... bids) {
        when(bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), any()))
                .thenReturn(List.of(bids));
    }

    @Test
    void expiresEachBid_independently() {
        BidEntity a = bid();
        BidEntity b = bid();
        stubDue(a, b);
        doThrow(new IllegalStateException("boom")).when(service).expire(a.getId());
        when(service.expire(b.getId())).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verify(service).expire(a.getId());
        verify(service).expire(b.getId());
    }

    /**
     * Preuve du borné : le scheduler ne demande jamais une liste illimitée, mais une page de
     * taille fixe {@link MobileMoneyPaymentDeadlineScheduler#BATCH_SIZE} — même motif, même
     * valeur que {@code PawapayReconciliationPoller} (tâche 10).
     */
    @Test
    void expireUnpaidBids_boundsTheBatch() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), pageable.capture()))
                .thenReturn(List.of());

        scheduler.expireUnpaidBids();

        assertThat(pageable.getValue().getPageSize()).isEqualTo(MobileMoneyPaymentDeadlineScheduler.BATCH_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    // ── Ronde 2, point 2 : éviction du cache hors verrou, sur CANCELLED uniquement ──────────

    @Test
    void expireUnpaidBids_evictsSearchCache_whenBidWasCancelled() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.CANCELLED);
        when(cacheManager.getCache(MobileMoneyPaymentDeadlineScheduler.SEARCH_CACHE_NAME)).thenReturn(cache);

        scheduler.expireUnpaidBids();

        verify(cache).clear();
    }

    /**
     * Ronde 2, point 2 — un tick qui n'annule rien n'évince pas : sans cette garde, dès qu'un
     * seul bid reste en échec fermé dans la file (dépôt bloqué en PROCESSING, par exemple), le
     * cache de recherche serait intégralement purgé toutes les minutes, indéfiniment.
     */
    @Test
    void expireUnpaidBids_doesNotEvictSearchCache_whenNothingCancelled() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verifyNoInteractions(cacheManager);
    }

    // ── Ronde 2, point 1 : alerte dédupliquée, hors verrou ──────────────────────────────────

    @Test
    void expireUnpaidBids_escalatesPaymentMissing() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.PAYMENT_MISSING);
        String expectedType = "MM_EXP_NO_PAYMENT_" + a.getId();
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of());

        scheduler.expireUnpaidBids();

        verify(alertRepository).save(any(AdminAlertEntity.class));
        verify(alerts).raise(eq(expectedType), any(), any());
    }

    @Test
    void expireUnpaidBids_escalatesDepositCompletedNotApplied() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);
        String expectedType = "MM_EXP_DEPOSIT_DONE_" + a.getId();
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of());

        scheduler.expireUnpaidBids();

        verify(alertRepository).save(any(AdminAlertEntity.class));
        verify(alerts).raise(eq(expectedType), any(), any());
    }

    /**
     * LE TEST DEMANDÉ PAR LA REVUE (Ronde 2, point 1) : le bid bloqué reste dans la file (échec
     * fermé, deadline toujours dépassée) et le scheduler le resélectionne au tick suivant — sans
     * dédup, chaque tick relèverait une alerte (Sentry + Telegram synchrone) indéfiniment. Deux
     * ticks consécutifs sur le même bid ne doivent produire qu'UN SEUL {@code raise}.
     */
    @Test
    void expireUnpaidBids_deduplicatesAlert_acrossTwoConsecutiveTicks() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.PAYMENT_MISSING);
        String expectedType = "MM_EXP_NO_PAYMENT_" + a.getId();
        // Premier tick : pas encore d'alerte. Second tick : l'alerte créée par le premier tick
        // existe déjà (simule la ligne admin_alerts posée par escalate() au premier passage).
        when(alertRepository.findByTypeAndResolved(expectedType, false))
                .thenReturn(List.of())
                .thenReturn(List.of(new AdminAlertEntity()));

        scheduler.expireUnpaidBids();
        scheduler.expireUnpaidBids();

        verify(service, times(2)).expire(a.getId());
        verify(alertRepository, times(1)).save(any());
        verify(alerts, times(1)).raise(eq(expectedType), any(), any());
    }

    @Test
    void expireUnpaidBids_doesNotEscalate_whenAlreadyEscalated() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.PAYMENT_MISSING);
        String expectedType = "MM_EXP_NO_PAYMENT_" + a.getId();
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType(expectedType);
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of(existing));

        scheduler.expireUnpaidBids();

        verify(alertRepository, never()).save(any());
        verify(alerts, never()).raise(any(), any(), any());
    }

    @Test
    void expireUnpaidBids_ignoresOutcome_raisesNothingAndEvictsNothing() {
        BidEntity a = bid();
        stubDue(a);
        when(service.expire(a.getId())).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verifyNoInteractions(alerts);
        verifyNoInteractions(alertRepository);
        verifyNoInteractions(cacheManager);
    }

    // ── Ronde 3 : admin_alerts.type est VARCHAR(60) (migration V20) ─────────────────────────

    /**
     * Ronde 3 — sans cette garde, un préfixe trop long fait dépasser {@code admin_alerts.type}
     * ({@code VARCHAR(60)}, migration V20) une fois l'UUID du bid concaténé (36 caractères) :
     * l'INSERT lève une {@code DataIntegrityViolationException}, avalée par le
     * {@code catch (Exception e)} de {@link MobileMoneyPaymentDeadlineScheduler#expireUnpaidBids},
     * journalisée en ERROR — l'alerte n'est alors jamais créée ni envoyée, à chaque tick,
     * indéfiniment. Même mode de panne que celui déjà signalé à la tâche 10 pour
     * {@code PAWAPAY_UNKNOWN_OP_} (55 caractères, marge de cinq).
     */
    @Test
    void paymentMissingAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPaymentDeadlineScheduler.PAYMENT_MISSING_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(60);
    }

    /** Ronde 3 — même garde que {@link #paymentMissingAlertType_fitsInAdminAlertsTypeColumn}. */
    @Test
    void depositCompletedAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPaymentDeadlineScheduler.DEPOSIT_COMPLETED_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(60);
    }
}
