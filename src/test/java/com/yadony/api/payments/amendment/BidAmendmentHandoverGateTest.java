package com.yadony.api.payments.amendment;

import com.yadony.api.common.BaseEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Portail de la remise : bloque tant que l'étape en cours n'est pas échue, sinon clôt la modification lui-même. */
class BidAmendmentHandoverGateTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BidAmendmentRepository amendments = mock(BidAmendmentRepository.class);
    private final BidAmendmentService service = mock(BidAmendmentService.class);
    private final BidAmendmentHandoverGate gate = new BidAmendmentHandoverGate(amendments, service,
            new BidAmendmentWindow(new BidAmendmentProperties(), Clock.fixed(NOW, ZoneOffset.UTC)));
    private final UUID bidId = UUID.randomUUID();

    @Test
    void aucuneModificationOuverte_rienNeBloque() {
        when(amendments.findFirstByBidIdAndStatusIn(bidId, BidAmendmentStatus.OPEN)).thenReturn(Optional.empty());

        assertThat(gate.blockingCode(bidId)).isEmpty();
        verify(service, never()).expireForHandover(any(), any());
    }

    @Test
    void etapeEnCoursNonEchue_bloqueLaRemise() {
        open(BidAmendmentStatus.PROPOSED, a -> a.setExpiresAt(NOW_UTC.plusMinutes(5)));
        assertThat(gate.blockingCode(bidId)).contains(BidAmendmentHandoverGate.CODE);

        open(BidAmendmentStatus.AUTHORIZATION_PENDING, a -> setCreatedAt(a, NOW_UTC.minusMinutes(29)));
        assertThat(gate.blockingCode(bidId)).contains(BidAmendmentHandoverGate.CODE);

        open(BidAmendmentStatus.COMMISSION_PENDING, a -> a.setRespondedAt(NOW_UTC.minusMinutes(29)));
        assertThat(gate.blockingCode(bidId)).contains(BidAmendmentHandoverGate.CODE);

        open(BidAmendmentStatus.PAYMENT_PENDING, a -> a.setPaymentExpiresAt(NOW_UTC.plusMinutes(1)));
        assertThat(gate.blockingCode(bidId)).contains(BidAmendmentHandoverGate.CODE);

        verify(service, never()).expireForHandover(any(), any());
    }

    @Test
    void etapeEchue_neBloquePlus_etEstCloseSansAttendreLeFilet() {
        // L'échéance de réponse (48 h) n'est pas passée, mais la 3DS de la commission n'a pas été confirmée à temps.
        BidAmendmentEntity a = open(BidAmendmentStatus.COMMISSION_PENDING, x -> {
            x.setExpiresAt(NOW_UTC.plusHours(40));
            x.setRespondedAt(NOW_UTC.minusMinutes(31));
        });
        when(service.expireForHandover(bidId, a.getId())).thenReturn(true);

        assertThat(gate.blockingCode(bidId)).isEmpty();
        verify(service).expireForHandover(bidId, a.getId());
    }

    @Test
    void depotEnVol_echuMaisNonClos_bloqueJusquaSonIssue() {
        BidAmendmentEntity a = open(BidAmendmentStatus.PAYMENT_PENDING, x -> x.setPaymentExpiresAt(NOW_UTC.minusMinutes(1)));
        when(service.expireForHandover(bidId, a.getId())).thenReturn(false);

        assertThat(gate.blockingCode(bidId)).contains(BidAmendmentHandoverGate.CODE);
    }

    private BidAmendmentEntity open(BidAmendmentStatus status, java.util.function.Consumer<BidAmendmentEntity> setup) {
        BidAmendmentEntity a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(bidId);
        a.setStatus(status);
        a.setExpiresAt(NOW_UTC.plusHours(48));
        setCreatedAt(a, NOW_UTC.minusMinutes(1));
        setup.accept(a);
        when(amendments.findFirstByBidIdAndStatusIn(bidId, BidAmendmentStatus.OPEN)).thenReturn(Optional.of(a));
        return a;
    }

    private static void setCreatedAt(BaseEntity entity, LocalDateTime createdAt) {
        try {
            Field f = BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
