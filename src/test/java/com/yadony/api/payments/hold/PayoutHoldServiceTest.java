package com.yadony.api.payments.hold;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayoutHoldServiceTest {

    @Mock PayoutHoldRepository repository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;

    PayoutHoldService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PayoutHoldService(repository, paymentRepository, auditService, adminAlert);
        lenient().when(repository.save(any(PayoutHoldEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private PayoutHoldEntity active(PayoutHoldReason reason, LocalDateTime since) {
        return new PayoutHoldEntity(userId, reason, since, adminId);
    }

    @Test
    void hold_creeUnGelActif_etAudite() {
        when(repository.findActive(userId, PayoutHoldReason.BANNED)).thenReturn(Optional.empty());

        boolean created = service.hold(userId, PayoutHoldReason.BANNED, adminId);

        assertThat(created).isTrue();
        ArgumentCaptor<PayoutHoldEntity> saved = ArgumentCaptor.forClass(PayoutHoldEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getReason()).isEqualTo(PayoutHoldReason.BANNED);
        assertThat(saved.getValue().getHeldSince()).isNotNull();
        assertThat(saved.getValue().getHeldBy()).isEqualTo(adminId);
        assertThat(saved.getValue().isActive()).isTrue();
        verify(auditService).log("USER", userId, "PAYOUTS_HELD", adminId, Map.of("reason", "BANNED"));
    }

    @Test
    void hold_estIdempotent_pourUnMotifDejaActif() {
        when(repository.findActive(userId, PayoutHoldReason.KYC_REVOKED))
                .thenReturn(Optional.of(active(PayoutHoldReason.KYC_REVOKED, LocalDateTime.now())));

        boolean created = service.hold(userId, PayoutHoldReason.KYC_REVOKED, adminId);

        assertThat(created).isFalse();
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void hold_sansUtilisateur_neFaitRien() {
        assertThat(service.hold(null, PayoutHoldReason.BANNED, adminId)).isFalse();
        verifyNoInteractions(repository, auditService);
    }

    @Test
    void release_leveLeDernierGel_auditeEtAlerteAvecLeNombreDePaiementsRetenus() {
        PayoutHoldEntity banned = active(PayoutHoldReason.BANNED, LocalDateTime.now().minusDays(2));
        when(repository.findActive(userId, PayoutHoldReason.BANNED)).thenReturn(Optional.of(banned));
        when(repository.findActiveByUserId(userId)).thenReturn(List.of());
        when(paymentRepository.countHeldEscrowForTraveler(userId)).thenReturn(2L);

        boolean released = service.release(userId, PayoutHoldReason.BANNED, adminId);

        assertThat(released).isTrue();
        assertThat(banned.isActive()).isFalse();
        assertThat(banned.getReleasedBy()).isEqualTo(adminId);
        verify(repository).save(banned);
        verify(auditService).log("USER", userId, "PAYOUTS_RELEASED_HOLD", adminId,
                Map.of("reason", "BANNED", "remainingReasons", "", "heldPaymentsCount", 2L));
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.captor();
        verify(adminAlert).raise(eq("PAYOUT_HOLD_LIFTED"), anyString(), ctx.capture());
        assertThat(ctx.getValue()).containsEntry("userId", userId.toString())
                .containsEntry("heldPaymentsCount", "2");
    }

    @Test
    void release_unMotifSurDeux_gardeLeGel_sansAlerte() {
        PayoutHoldEntity banned = active(PayoutHoldReason.BANNED, LocalDateTime.now().minusDays(2));
        PayoutHoldEntity kyc = active(PayoutHoldReason.KYC_REVOKED, LocalDateTime.now().minusDays(1));
        when(repository.findActive(userId, PayoutHoldReason.BANNED)).thenReturn(Optional.of(banned));
        when(repository.findActiveByUserId(userId)).thenReturn(List.of(kyc));
        when(paymentRepository.countHeldEscrowForTraveler(userId)).thenReturn(3L);

        service.release(userId, PayoutHoldReason.BANNED, adminId);

        verify(auditService).log("USER", userId, "PAYOUTS_RELEASED_HOLD", adminId,
                Map.of("reason", "BANNED", "remainingReasons", "KYC_REVOKED", "heldPaymentsCount", 3L));
        verify(adminAlert, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void release_dernierGelSansPaiementRetenu_nAlertePas() {
        when(repository.findActive(userId, PayoutHoldReason.KYC_REVOKED))
                .thenReturn(Optional.of(active(PayoutHoldReason.KYC_REVOKED, LocalDateTime.now())));
        when(repository.findActiveByUserId(userId)).thenReturn(List.of());
        when(paymentRepository.countHeldEscrowForTraveler(userId)).thenReturn(0L);

        service.release(userId, PayoutHoldReason.KYC_REVOKED, null);

        verify(auditService).log(eq("USER"), eq(userId), eq("PAYOUTS_RELEASED_HOLD"), eq(null), anyMap());
        verify(adminAlert, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void release_sansGelActif_neFaitRien() {
        when(repository.findActive(userId, PayoutHoldReason.BANNED)).thenReturn(Optional.empty());

        assertThat(service.release(userId, PayoutHoldReason.BANNED, adminId)).isFalse();

        verify(repository, never()).save(any());
        verifyNoInteractions(auditService, adminAlert, paymentRepository);
    }

    @Test
    void statusOf_ordonneLesMotifsEtRendLePlusAncienGel() {
        LocalDateTime older = LocalDateTime.now().minusDays(5);
        when(repository.findActiveByUserId(userId)).thenReturn(List.of(
                active(PayoutHoldReason.KYC_REVOKED, older),
                active(PayoutHoldReason.BANNED, LocalDateTime.now())));

        PayoutHoldStatus status = service.statusOf(userId);

        assertThat(status.held()).isTrue();
        assertThat(status.reasons()).containsExactly(PayoutHoldReason.BANNED, PayoutHoldReason.KYC_REVOKED);
        assertThat(status.primaryReason()).isEqualTo(PayoutHoldReason.BANNED);
        assertThat(status.heldSince()).isEqualTo(older);
        assertThat(service.isHeld(userId)).isTrue();
    }

    @Test
    void statusOf_nonGeleOuNul_rendNone() {
        when(repository.findActiveByUserId(userId)).thenReturn(List.of());

        assertThat(service.statusOf(userId)).isEqualTo(PayoutHoldStatus.NONE);
        assertThat(service.statusOf(null)).isEqualTo(PayoutHoldStatus.NONE);
        assertThat(PayoutHoldStatus.NONE.primaryReason()).isNull();
        assertThat(service.isHeld(userId)).isFalse();
    }

    @Test
    void statusesOf_regroupeParVoyageur_etIgnoreLesNonGeles() {
        UUID other = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        LocalDateTime since = LocalDateTime.now().minusHours(3);
        when(repository.findActiveByUserIds(List.of(userId, other, free))).thenReturn(List.of(
                active(PayoutHoldReason.BANNED, since),
                new PayoutHoldEntity(other, PayoutHoldReason.KYC_REVOKED, since, null)));

        Map<UUID, PayoutHoldStatus> statuses = service.statusesOf(List.of(userId, other, free));

        assertThat(statuses).containsOnlyKeys(userId, other);
        assertThat(statuses.get(other).reasons()).containsExactly(PayoutHoldReason.KYC_REVOKED);
        assertThat(service.statusesOf(List.of())).isEmpty();
        assertThat(service.statusesOf(null)).isEmpty();
    }

    @Test
    void summaryOf_combineEtatEtNombreDePaiementsRetenus() {
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        when(repository.findActiveByUserId(userId)).thenReturn(List.of(active(PayoutHoldReason.BANNED, since)));
        when(paymentRepository.countHeldEscrowForTraveler(userId)).thenReturn(4L);

        PayoutHoldSummary summary = service.summaryOf(userId);

        assertThat(summary.heldSince()).isEqualTo(since);
        assertThat(summary.primaryReason()).isEqualTo(PayoutHoldReason.BANNED);
        assertThat(summary.reasons()).containsExactly(PayoutHoldReason.BANNED);
        assertThat(summary.heldPaymentsCount()).isEqualTo(4L);
        assertThat(service.summaryOf(null)).isEqualTo(PayoutHoldSummary.NONE);
        assertThat(PayoutHoldSummary.NONE.primaryReason()).isNull();
    }
}
