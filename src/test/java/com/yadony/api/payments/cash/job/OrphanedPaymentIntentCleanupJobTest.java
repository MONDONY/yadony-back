package com.yadony.api.payments.cash.job;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CashCommissionProperties;
import com.yadony.api.payments.cash.CommissionStatus;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrphanedPaymentIntentCleanupJobTest {

    @Mock private BidRepository bidRepo;
    @Mock private AdminAlertEscalator alertEscalator;

    private OrphanedPaymentIntentCleanupJob job;

    @BeforeEach
    void setUp() {
        CashCommissionProperties props = new CashCommissionProperties(
                "0 */15 * * * *", 30, "0 0 * * * *", 30);
        job = new OrphanedPaymentIntentCleanupJob(bidRepo, props, alertEscalator);
    }

    private BidEntity bidWith3ds(String piId) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        bid.setCommissionPaymentIntentId(piId);
        bid.setCommissionRetryCount(0);
        return bid;
    }

    @Test
    void cancelsPiAndResetsStatusForOrphanedBid() throws StripeException {
        BidEntity bid = bidWith3ds("pi_orphan");
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(eq(CommissionStatus.REQUIRES_3DS), any()))
                .thenReturn(List.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_orphan")).thenReturn(pi);

            job.cleanup();

            verify(pi).cancel();
        }

        assertThat(bid.getCommissionStatus()).isNull();
        assertThat(bid.getCommissionPaymentIntentId()).isNull();
        assertThat(bid.getCommissionRetryCount()).isEqualTo(1);
        verify(bidRepo).save(bid);
    }

    @Test
    void stripeInjoignable_neRemetRienAZero() {
        // Avant : un échec Stripe effaçait quand même PI et statut, et incrémentait le
        // numéro d'essai — donc la clé d'idempotence du prochain prélèvement. Si le PI avait
        // en fait réussi, la commission était encaissée sans trace, puis prélevée une
        // seconde fois. Désormais rien ne bouge : le passage suivant retente.
        BidEntity bid = bidWith3ds("pi_fail");
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(eq(CommissionStatus.REQUIRES_3DS), any()))
                .thenReturn(List.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_fail"))
                    .thenThrow(mock(com.stripe.exception.InvalidRequestException.class));

            job.cleanup();
        }

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REQUIRES_3DS);
        assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_fail");
        assertThat(bid.getCommissionRetryCount()).isZero();
        verify(bidRepo, never()).save(any());
    }

    @Test
    void piDejaReussi_garderLEtatEtAlerter_sansAnnuler() throws StripeException {
        // 3DS aboutie mais l'app n'a jamais confirmé : la commission EST encaissée. On garde
        // PI et statut (la confirmation de l'app finalisera l'acceptation) et on prévient.
        BidEntity bid = bidWith3ds("pi_paid");
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(eq(CommissionStatus.REQUIRES_3DS), any()))
                .thenReturn(List.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_paid")).thenReturn(pi);

            job.cleanup();

            verify(pi, never()).cancel();
        }

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REQUIRES_3DS);
        assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_paid");
        assertThat(bid.getCommissionRetryCount()).isZero();
        verify(bidRepo, never()).save(any());
        verify(alertEscalator).raiseOnce(eq("COMMISSION_3DS_UNCONFIRMED_" + bid.getId()), anyString(), anyMap());
    }

    @Test
    void annulationRefusee_neRemetRienAZero() throws StripeException {
        BidEntity bid = bidWith3ds("pi_nocancel");
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(eq(CommissionStatus.REQUIRES_3DS), any()))
                .thenReturn(List.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_action");
            when(pi.cancel()).thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            piStatic.when(() -> PaymentIntent.retrieve("pi_nocancel")).thenReturn(pi);

            job.cleanup();
        }

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REQUIRES_3DS);
        assertThat(bid.getCommissionPaymentIntentId()).isEqualTo("pi_nocancel");
        verify(bidRepo, never()).save(any());
    }

    @Test
    void piDejaAnnule_remetAZeroSansRappelerStripe() throws StripeException {
        BidEntity bid = bidWith3ds("pi_canceled");
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(eq(CommissionStatus.REQUIRES_3DS), any()))
                .thenReturn(List.of(bid));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("canceled");
            piStatic.when(() -> PaymentIntent.retrieve("pi_canceled")).thenReturn(pi);

            job.cleanup();

            verify(pi, never()).cancel();
        }

        assertThat(bid.getCommissionStatus()).isNull();
        assertThat(bid.getCommissionPaymentIntentId()).isNull();
        assertThat(bid.getCommissionRetryCount()).isEqualTo(1);
        verify(bidRepo).save(bid);
    }

    @Test
    void noOrphansIsNoOp() {
        when(bidRepo.findByCommissionStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());

        job.cleanup();

        verify(bidRepo, never()).save(any());
    }
}
