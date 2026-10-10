package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Staging, 07/10/2026 : 5 paiements carte ESCROW jamais capturés ({@code captured_at} NULL), colis
 * HANDED_OVER ou ACCEPTED, autorisation qui expire à J+7. La passe « séquestres non capturés » du
 * job les capture avec le vrai {@link EscrowCaptureService} (Stripe simulé) ; les séquestres dont
 * la capture n'est pas due ou interdite ne sont jamais touchés.
 */
@SpringBootTest
@ActiveProfiles("test")
class UncapturedEscrowAutoCaptureIT {

    @Autowired PaymentRepository paymentRepository;
    @Autowired BidRepository bidRepository;
    @Autowired CancellationRepository cancellationRepository;
    @Autowired EscrowCaptureService escrowCapture;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AdminAlertEscalator alertEscalator;

    private final List<UUID> payments = new ArrayList<>();
    private final List<UUID> bids = new ArrayList<>();
    private final List<UUID> cancellations = new ArrayList<>();
    private final Map<String, PaymentIntent> intents = new HashMap<>();
    private final List<String> captures = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        cancellations.forEach(id -> jdbc.update("DELETE FROM cancellations WHERE id = ?", id));
        payments.forEach(id -> jdbc.update("DELETE FROM payments WHERE id = ?", id));
        bids.forEach(id -> jdbc.update("DELETE FROM bids WHERE id = ?", id));
    }

    private PendingCardPaymentAutoHealJob job() {
        return new PendingCardPaymentAutoHealJob(paymentRepository, mock(PaymentStripeResyncService.class),
                mock(DeliveredEscrowReleaser.class), escrowCapture, true, Duration.ofMinutes(10), Duration.ofDays(7),
                Duration.ofHours(24), 20, Clock.systemUTC());
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(UUID.randomUUID());
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        b = bidRepository.saveAndFlush(b);
        bids.add(b.getId());
        return b;
    }

    /** Séquestre carte créé il y a 3 jours, jamais capturé, PaymentIntent autorisé ({@code requires_capture}). */
    private PaymentEntity escrow(BidEntity bid, String piStatus) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("40.00"));
        p.setCommissionAmount(new BigDecimal("4.80"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p = paymentRepository.saveAndFlush(p);
        payments.add(p.getId());
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(3)), p.getId());
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(piStatus);
        lenient().when(pi.getAmount()).thenReturn(4000L);
        lenient().when(pi.getAmountCapturable()).thenReturn("requires_capture".equals(piStatus) ? 4000L : 0L);
        lenient().when(pi.getCurrency()).thenReturn("eur");
        lenient().when(pi.getLatestCharge()).thenReturn("ch_" + p.getId());
        try {
            lenient().when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
                captures.add(((RequestOptions) inv.getArgument(1)).getIdempotencyKey());
                return pi;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        intents.put(p.getStripePaymentIntentId(), pi);
        return p;
    }

    private int runWithStripe(PendingCardPaymentAutoHealJob job) {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(anyString(), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenAnswer(inv -> intents.get((String) inv.getArgument(0)));
            return job.captureDueEscrows();
        }
    }

    private List<UUID> candidates() {
        return paymentRepository.findUncapturedDueEscrowIds(LocalDateTime.now(ZoneOffset.UTC).minusHours(2),
                LocalDateTime.now(ZoneOffset.UTC).minusDays(7),
                EscrowCaptureService.ENGAGED_BID_STATUSES, PendingCardPaymentAutoHealJob.DEAD_THREADS,
                PageRequest.of(0, 500)).stream().filter(payments::contains).toList();
    }

    @Test
    void cinqSequestresNonCaptures_sontCaptures_uneSeuleFois() {
        List<PaymentEntity> staging = List.of(
                escrow(bid(BidStatus.HANDED_OVER), "requires_capture"),
                escrow(bid(BidStatus.HANDED_OVER), "requires_capture"),
                escrow(bid(BidStatus.HANDED_OVER), "requires_capture"),
                escrow(bid(BidStatus.HANDED_OVER), "requires_capture"),
                escrow(bid(BidStatus.ACCEPTED), "requires_capture"));

        assertThat(runWithStripe(job())).isEqualTo(5);

        assertThat(captures).containsExactlyInAnyOrderElementsOf(
                staging.stream().map(p -> "capture-" + p.getId()).toList());
        for (PaymentEntity p : staging) {
            PaymentEntity after = paymentRepository.findById(p.getId()).orElseThrow();
            assertThat(after.getCapturedAt()).isNotNull();
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
            assertThat(after.getStripeChargeId()).isEqualTo("ch_" + p.getId());
        }
        // Capturés : ils sortent de la file, un second passage ne rappelle pas Stripe.
        assertThat(candidates()).isEmpty();
        assertThat(runWithStripe(job())).isZero();
        assertThat(captures).hasSize(5);
    }

    @Test
    void captureNonDueOuInterdite_jamaisTentee() {
        PaymentEntity due = escrow(bid(BidStatus.ACCEPTED), "requires_capture");
        escrow(bid(BidStatus.CANCELLED), "requires_capture");             // colis annulé
        escrow(bid(BidStatus.PAYMENT_ESCROWED), "requires_capture");      // pas encore accepté
        PaymentEntity recent = escrow(bid(BidStatus.ACCEPTED), "requires_capture");
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30)), recent.getId());
        PaymentEntity legacy = escrow(bid(BidStatus.ACCEPTED), "requires_capture");
        jdbc.update("UPDATE payments SET legacy_destination_charge = true WHERE id = ?", legacy.getId());
        PaymentEntity tooOld = escrow(bid(BidStatus.ACCEPTED), "requires_capture");   // autorisation expirée (> 7 j)
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(8)), tooOld.getId());
        PaymentEntity disputed = escrow(bid(BidStatus.ACCEPTED), "requires_capture");
        jdbc.update("UPDATE payments SET disputed = true WHERE id = ?", disputed.getId());
        BidEntity inCancellation = bid(BidStatus.HANDED_OVER);
        escrow(inCancellation, "requires_capture");
        CancellationEntity c = new CancellationEntity();
        c.setBidId(inCancellation.getId());
        c.setCancelledBy(inCancellation.getSenderId());
        c.setReason("SENDER_NO_SHOW");
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        cancellations.add(cancellationRepository.saveAndFlush(c).getId());

        assertThat(candidates()).containsExactly(due.getId());
    }

    @Test
    void autorisationExpiree_aucuneCapture_uneAlerte_plusDeNouvelEssai() {
        PaymentEntity expired = escrow(bid(BidStatus.HANDED_OVER), "canceled");
        PendingCardPaymentAutoHealJob job = job();

        assertThat(runWithStripe(job)).isEqualTo(1);
        assertThat(runWithStripe(job)).isZero();

        assertThat(captures).isEmpty();
        verify(alertEscalator, times(1)).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + expired.getId()),
                org.mockito.ArgumentMatchers.contains("autorisation carte expirée ou annulée"), anyMap());
        assertThat(paymentRepository.findById(expired.getId()).orElseThrow().getCapturedAt()).isNull();
    }
}
