package com.yadony.api.voucher;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionVoucherServiceTest {

    @Mock CommissionVoucherRepository repository;
    @Mock AuditService auditService;

    CommissionVoucherService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID invitationId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        VoucherConfig config = new VoucherConfig();
        config.setFactor(new BigDecimal("0.50"));
        config.setValidityMonths(6);
        service = new CommissionVoucherService(repository, auditService, config);
    }

    // ── grant ────────────────────────────────────────────────────────────────

    @Test
    void grant_newInvitation_createsVoucherWithConfiguredFactorAndExpiry() {
        when(repository.findBySourceInvitationId(invitationId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommissionVoucherEntity result = service.grant(userId, invitationId);

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getFactor()).isEqualByComparingTo("0.50");
        assertThat(result.getSourceInvitationId()).isEqualTo(invitationId);
        assertThat(result.getConsumedAt()).isNull();
        assertThat(result.getGrantedAt()).isNotNull();
        assertThat(result.getExpiresAt()).isAfter(result.getGrantedAt().plusMonths(5));
        assertThat(result.getExpiresAt()).isBefore(result.getGrantedAt().plusMonths(7));
    }

    @Test
    void grant_sameInvitationTwice_isIdempotent() {
        CommissionVoucherEntity existing = voucher(userId, invitationId, false, false);
        when(repository.findBySourceInvitationId(invitationId)).thenReturn(Optional.of(existing));

        CommissionVoucherEntity result = service.grant(userId, invitationId);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    // ── peekActive ───────────────────────────────────────────────────────────

    @Test
    void peekActive_noVoucher_returnsEmpty() {
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of());

        assertThat(service.peekActive(userId)).isEmpty();
    }

    @Test
    void peekActive_oneVoucher_returnsIt() {
        CommissionVoucherEntity v = voucher(userId, invitationId, false, false);
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of(v));

        assertThat(service.peekActive(userId)).contains(v);
    }

    // ── consume ──────────────────────────────────────────────────────────────

    @Test
    void consume_activeVoucherExists_marksConsumedAndAudits() {
        CommissionVoucherEntity v = voucher(userId, invitationId, false, false);
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of(v));
        when(repository.findByIdForUpdate(v.getId())).thenReturn(Optional.of(v));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CommissionVoucherEntity> result = service.consume(userId, bidId);

        assertThat(result).isPresent();
        assertThat(result.get().getConsumedAt()).isNotNull();
        assertThat(result.get().getConsumedOnBidId()).isEqualTo(bidId);
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void consume_noActiveVoucher_returnsEmptyWithoutTouchingRepository() {
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of());

        Optional<CommissionVoucherEntity> result = service.consume(userId, bidId);

        assertThat(result).isEmpty();
        verify(repository, never()).findByIdForUpdate(any());
        verify(repository, never()).save(any());
    }

    @Test
    void consume_calledTwiceForSameBid_isIdempotent_secondCallReturnsAlreadyConsumed() {
        CommissionVoucherEntity consumedByThisBid = voucher(userId, invitationId, false, false);
        consumedByThisBid.setConsumedAt(LocalDateTime.now(ZoneOffset.UTC));
        consumedByThisBid.setConsumedOnBidId(bidId);
        when(repository.findByConsumedOnBidIdAndUserId(bidId, userId)).thenReturn(Optional.of(consumedByThisBid));

        Optional<CommissionVoucherEntity> result = service.consume(userId, bidId);

        assertThat(result).contains(consumedByThisBid);
        verify(repository, never()).findActiveByUserId(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void consume_raceWhereLockedVoucherAlreadyConsumedMeanwhile_returnsEmpty() {
        // Un autre thread a consommé le même bon entre le peek et le verrou.
        CommissionVoucherEntity v = voucher(userId, invitationId, false, false);
        CommissionVoucherEntity lockedButAlreadyConsumed = voucher(userId, invitationId, false, false);
        lockedButAlreadyConsumed.setConsumedAt(LocalDateTime.now(ZoneOffset.UTC));
        lockedButAlreadyConsumed.setConsumedOnBidId(UUID.randomUUID());
        when(repository.findByConsumedOnBidIdAndUserId(bidId, userId)).thenReturn(Optional.empty());
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of(v));
        when(repository.findByIdForUpdate(v.getId())).thenReturn(Optional.of(lockedButAlreadyConsumed));

        Optional<CommissionVoucherEntity> result = service.consume(userId, bidId);

        assertThat(result).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void consume_senderAndTravelerOnSameBid_areIndependentConsumptions() {
        // Un même bid peut porter deux bons distincts : celui de l'expéditeur et celui
        // du voyageur. La consommation de l'un ne doit jamais être vue comme "déjà fait"
        // pour l'autre — sans le scope userId, findByConsumedOnBidId(bidId) confondrait
        // les deux détenteurs.
        UUID travelerId = UUID.randomUUID();
        CommissionVoucherEntity senderVoucher = voucher(userId, invitationId, false, false);
        when(repository.findByConsumedOnBidIdAndUserId(bidId, userId)).thenReturn(Optional.empty());
        when(repository.findActiveByUserId(eq(userId), any())).thenReturn(List.of(senderVoucher));
        when(repository.findByIdForUpdate(senderVoucher.getId())).thenReturn(Optional.of(senderVoucher));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CommissionVoucherEntity> senderResult = service.consume(userId, bidId);
        assertThat(senderResult).isPresent();

        UUID travelerInvitationId = UUID.randomUUID();
        CommissionVoucherEntity travelerVoucher = voucher(travelerId, travelerInvitationId, false, false);
        when(repository.findByConsumedOnBidIdAndUserId(bidId, travelerId)).thenReturn(Optional.empty());
        when(repository.findActiveByUserId(eq(travelerId), any())).thenReturn(List.of(travelerVoucher));
        when(repository.findByIdForUpdate(travelerVoucher.getId())).thenReturn(Optional.of(travelerVoucher));

        Optional<CommissionVoucherEntity> travelerResult = service.consume(travelerId, bidId);
        assertThat(travelerResult).isPresent();
        assertThat(travelerResult.get()).isNotSameAs(senderResult.get());
    }

    @Test
    void peekActive_expiredVoucher_notReturnedByRepositoryQuery() {
        // La borne d'expiration est de la responsabilité de la requête repository
        // (WHERE expires_at > :now) — ce test documente juste que le service ne
        // filtre rien lui-même en plus (pas de double logique de validité).
        when(repository.findActiveByUserId(any(), any())).thenReturn(List.of());

        assertThat(service.peekActive(userId)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CommissionVoucherEntity voucher(UUID userId, UUID invitationId, boolean expired, boolean consumed) {
        CommissionVoucherEntity v = new CommissionVoucherEntity();
        setId(v, UUID.randomUUID());
        v.setUserId(userId);
        v.setFactor(new BigDecimal("0.50"));
        v.setSourceInvitationId(invitationId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        v.setGrantedAt(now.minusDays(1));
        v.setExpiresAt(expired ? now.minusDays(1) : now.plusMonths(6));
        if (consumed) {
            v.setConsumedAt(now);
            v.setConsumedOnBidId(UUID.randomUUID());
        }
        return v;
    }

    private void setId(CommissionVoucherEntity entity, UUID id) {
        try {
            var f = CommissionVoucherEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
