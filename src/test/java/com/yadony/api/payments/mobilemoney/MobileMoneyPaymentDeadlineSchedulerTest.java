package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
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

/**
 * Scheduler d'expiration mobile money. {@code expire()} rend un {@link ExpireOutcome} au lieu de
 * lever elle-même une alerte ou d'évincer le cache — c'est ce scheduler qui agit sur cette
 * valeur, hors des verrous tenus par {@code expire}. La déduplication des alertes appartient à
 * {@link AdminAlertEscalator} (testée à part) : ici on vérifie seulement quel type est levé, et
 * quand rien ne doit l'être.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyPaymentDeadlineSchedulerTest {

    @Mock BidRepository bidRepository;
    @Mock MobileMoneyBidPaymentService service;
    @Mock AdminAlertEscalator alerts;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @InjectMocks MobileMoneyPaymentDeadlineScheduler scheduler;

    private void stubDue(UUID... bidIds) {
        when(bidRepository.findIdsByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), any()))
                .thenReturn(List.of(bidIds));
    }

    @Test
    void expiresEachBid_independently() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubDue(a, b);
        doThrow(new IllegalStateException("boom")).when(service).expire(a);
        when(service.expire(b)).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verify(service).expire(a);
        verify(service).expire(b);
    }

    /**
     * Preuve du borné : le scheduler ne demande jamais une liste illimitée, mais une page de
     * taille fixe {@link MobileMoneyPaymentDeadlineScheduler#BATCH_SIZE} — même motif, même
     * valeur que {@code PawapayReconciliationPoller}.
     */
    @Test
    void expireUnpaidBids_boundsTheBatch() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bidRepository.findIdsByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), pageable.capture()))
                .thenReturn(List.of());

        scheduler.expireUnpaidBids();

        assertThat(pageable.getValue().getPageSize()).isEqualTo(MobileMoneyPaymentDeadlineScheduler.BATCH_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    // ── Éviction du cache hors verrou, sur CANCELLED uniquement ─────────────────────────────

    @Test
    void expireUnpaidBids_evictsSearchCache_whenBidWasCancelled() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.CANCELLED);
        when(cacheManager.getCache(MobileMoneyPaymentDeadlineScheduler.SEARCH_CACHE_NAME)).thenReturn(cache);

        scheduler.expireUnpaidBids();

        verify(cache).clear();
    }

    /**
     * Un tick qui n'annule rien n'évince pas : sans cette garde, dès qu'un seul bid reste en
     * échec fermé dans la file (dépôt bloqué en PROCESSING, par exemple), le cache de recherche
     * serait intégralement purgé toutes les minutes, indéfiniment.
     */
    @Test
    void expireUnpaidBids_doesNotEvictSearchCache_whenNothingCancelled() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verifyNoInteractions(cacheManager);
    }

    // ── Alerte dédupliquée par bid, hors verrou ─────────────────────────────────────────────

    @Test
    void expireUnpaidBids_escalatesPaymentMissing_dedupedByBid() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.PAYMENT_MISSING);

        scheduler.expireUnpaidBids();

        verify(alerts).raiseOnce(eq("MM_EXP_NO_PAYMENT_" + a), any(), any());
    }

    // ── DEPOSIT_COMPLETED_NOT_APPLIED est réparé, pas seulement alerté ──────────────────────

    /**
     * Deposit COMPLETED côté pawaPay, paiement encore PENDING côté yadony (confirmation perdue,
     * ex. redémarrage ou exception dans l'écouteur) : quand la réparation (confirmEscrow,
     * idempotent par construction) réussit, AUCUNE alerte ne part — le filet ne doit crier que
     * si la réparation échoue elle-même.
     */
    @Test
    void expireUnpaidBids_repairsDepositCompletedNotApplied_whenRepairSucceeds() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);

        scheduler.expireUnpaidBids();

        verify(service).repairDepositCompletedNotApplied(a);
        verifyNoInteractions(alerts);
    }

    /**
     * Le comportement précédent (alerte inconditionnelle) devient le repli : si la réparation
     * échoue à son tour (deposit ou paiement disparus entre-temps — état structurellement
     * incohérent), l'alerte dédupliquée part comme avant.
     */
    @Test
    void expireUnpaidBids_escalatesDepositCompletedNotApplied_whenRepairFails() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);
        doThrow(new IllegalStateException("deposit disparu")).when(service).repairDepositCompletedNotApplied(a);

        scheduler.expireUnpaidBids();

        verify(alerts).raiseOnce(eq("MM_EXP_DEPOSIT_DONE_" + a), any(), any());
    }

    @Test
    void expireUnpaidBids_ignoresOutcome_raisesNothingAndEvictsNothing() {
        UUID a = UUID.randomUUID();
        stubDue(a);
        when(service.expire(a)).thenReturn(ExpireOutcome.IGNORED);

        scheduler.expireUnpaidBids();

        verifyNoInteractions(alerts);
        verifyNoInteractions(cacheManager);
    }

    // ── admin_alerts.type est VARCHAR(60) (migration V20) ───────────────────────────────────

    /**
     * Sans cette garde, un préfixe trop long fait dépasser {@code admin_alerts.type}
     * ({@code VARCHAR(60)}) une fois l'UUID du bid concaténé (36 caractères) : l'escalateur
     * refuse alors bruyamment, à chaque tick, et l'alerte ne part jamais.
     */
    @Test
    void paymentMissingAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPaymentDeadlineScheduler.PAYMENT_MISSING_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }

    @Test
    void depositCompletedAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPaymentDeadlineScheduler.DEPOSIT_COMPLETED_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }
}
