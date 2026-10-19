package com.yadony.api.payments.supplement;

import org.junit.jupiter.api.Test;

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

class BidSupplementHandoverGateTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BidSupplementRepository supplements = mock(BidSupplementRepository.class);
    private final BidSupplementService service = mock(BidSupplementService.class);
    private final BidSupplementHandoverGate gate = new BidSupplementHandoverGate(supplements, service,
            new BidSupplementWindow(new BidSupplementProperties(), Clock.fixed(NOW, ZoneOffset.UTC)));
    private final UUID bidId = UUID.randomUUID();

    private BidSupplementEntity open(BidSupplementStatus status, LocalDateTime expiresAt) {
        BidSupplementEntity s = new BidSupplementEntity();
        BidSupplementPricingTest.setId(s, UUID.randomUUID());
        s.setBidId(bidId);
        s.setStatus(status);
        s.setExpiresAt(expiresAt);
        when(supplements.findByBidIdAndStatusInForUpdate(bidId, BidSupplementStatus.OPEN)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void demandeEnAttenteNonEchue_bloqueLaRemise() {
        open(BidSupplementStatus.PROPOSED, NOW_UTC.plusMinutes(5));
        assertThat(gate.blockingCode(bidId)).contains("handover-supplement-pending");
        verify(service, never()).expireUnderBidLock(any());
    }

    @Test
    void demandeEchue_neBloquePlus_etEstExpireeSousLeVerrouDuColis() {
        BidSupplementEntity s = open(BidSupplementStatus.PROPOSED, NOW_UTC.minusMinutes(1));
        assertThat(gate.blockingCode(bidId)).isEmpty();
        verify(service).expireUnderBidLock(s);

        BidSupplementEntity pending = open(BidSupplementStatus.COMMISSION_PENDING, NOW_UTC);
        assertThat(gate.blockingCode(bidId)).isEmpty();
        verify(service).expireUnderBidLock(pending);
    }

    @Test
    void commissionEn3dsDepuisMoinsDe30Min_bloqueLaRemise() {
        BidSupplementEntity s = open(BidSupplementStatus.COMMISSION_PENDING, NOW_UTC.plusMinutes(90));
        setCreatedAt(s, NOW_UTC.minusMinutes(29));
        assertThat(gate.blockingCode(bidId)).contains("handover-supplement-pending");
        verify(service, never()).expireUnderBidLock(any());
    }

    @Test
    void commissionEn3dsDepuisPlusDe30Min_neBloquePlus_etEstAnnuleeSansAttendreLeFilet() {
        // Relecture B4 : l'échéance (2 h) n'est pas passée, mais la 3DS n'a jamais été confirmée dans
        // le délai de 30 min ; le filet l'annulerait à son prochain passage, la remise n'attend pas.
        BidSupplementEntity s = open(BidSupplementStatus.COMMISSION_PENDING, NOW_UTC.plusMinutes(90));
        setCreatedAt(s, NOW_UTC.minusMinutes(30));
        assertThat(gate.blockingCode(bidId)).isEmpty();
        verify(service).expireUnderBidLock(s);
    }

    @Test
    void lesTrenteMinutesNeConcernentQueLaCommission_uneDemandeProposeeAncienneBloqueToujours() {
        BidSupplementEntity s = open(BidSupplementStatus.PROPOSED, NOW_UTC.plusMinutes(10));
        setCreatedAt(s, NOW_UTC.minusMinutes(110));
        assertThat(gate.blockingCode(bidId)).contains("handover-supplement-pending");
    }

    @Test
    void paiementEnVol_bloqueMemeEchu() {
        open(BidSupplementStatus.PAYMENT_PENDING, NOW_UTC.minusMinutes(30));
        assertThat(gate.blockingCode(bidId)).contains("handover-supplement-pending");
        verify(service, never()).expireUnderBidLock(any());
    }

    @Test
    void aucuneDemandeOuverte_laisseLaRemise() {
        when(supplements.findByBidIdAndStatusInForUpdate(bidId, BidSupplementStatus.OPEN)).thenReturn(Optional.empty());
        assertThat(gate.blockingCode(bidId)).isEmpty();
    }

    private static void setCreatedAt(BidSupplementEntity s, LocalDateTime createdAt) {
        try {
            java.lang.reflect.Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(s, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
