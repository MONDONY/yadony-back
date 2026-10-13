package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublishCommitmentsResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishCommitmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final String CURRENT = PublishCommitmentService.CURRENT_VERSION;

    private UserRepository userRepository;
    private AuditService auditService;
    private PublishCommitmentService service;
    private UserEntity user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        service = new PublishCommitmentService(userRepository, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = new UserEntity();
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void get_jamaisAccepte_lesDeuxRolesRequis() {
        PublishCommitmentsResponse r = service.get(userId);

        assertThat(r.currentVersion()).isEqualTo(CURRENT);
        assertThat(r.traveler().required()).isTrue();
        assertThat(r.traveler().acceptedVersion()).isNull();
        assertThat(r.traveler().acceptedAt()).isNull();
        assertThat(r.sender().required()).isTrue();
    }

    @Test
    void get_versionCouranteAcceptee_nonRequis() {
        user.setTravelerCommitmentVersion(CURRENT);
        user.setTravelerCommitmentAt(NOW);

        PublishCommitmentsResponse r = service.get(userId);

        assertThat(r.traveler().required()).isFalse();
        assertThat(r.traveler().acceptedAt()).isEqualTo(NOW.toString());
        assertThat(r.sender().required()).isTrue();
    }

    @Test
    void get_ancienneVersionAcceptee_requisDeNouveau() {
        user.setSenderCommitmentVersion("2020-01");
        user.setSenderCommitmentAt(NOW);

        assertThat(service.get(userId).sender().required()).isTrue();
    }

    @Test
    void get_utilisateurIntrouvable_404() {
        UUID other = UUID.randomUUID();
        when(userRepository.findById(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(other))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void accept_voyageur_enregistreEtAudite() {
        service.accept(userId, PublishCommitmentRole.TRAVELER, CURRENT);

        assertThat(user.getTravelerCommitmentVersion()).isEqualTo(CURRENT);
        assertThat(user.getTravelerCommitmentAt()).isEqualTo(NOW);
        assertThat(user.getSenderCommitmentVersion()).isNull();
        verify(userRepository).save(user);
        verify(auditService).log("USER", userId, "PUBLISH_COMMITMENT_ACCEPTED", userId,
                Map.of("role", "TRAVELER", "version", CURRENT));
    }

    @Test
    void accept_expediteur_enregistreEtAudite() {
        service.accept(userId, PublishCommitmentRole.SENDER, CURRENT);

        assertThat(user.getSenderCommitmentVersion()).isEqualTo(CURRENT);
        assertThat(user.getSenderCommitmentAt()).isEqualTo(NOW);
        assertThat(user.getTravelerCommitmentVersion()).isNull();
        verify(auditService).log("USER", userId, "PUBLISH_COMMITMENT_ACCEPTED", userId,
                Map.of("role", "SENDER", "version", CURRENT));
    }

    @Test
    void accept_dejaAccepte_idempotentSansAudit() {
        Instant before = Instant.parse("2026-10-01T00:00:00Z");
        user.setTravelerCommitmentVersion(CURRENT);
        user.setTravelerCommitmentAt(before);
        user.setSenderCommitmentVersion(CURRENT);
        user.setSenderCommitmentAt(before);

        service.accept(userId, PublishCommitmentRole.TRAVELER, CURRENT);
        service.accept(userId, PublishCommitmentRole.SENDER, CURRENT);

        assertThat(user.getTravelerCommitmentAt()).isEqualTo(before);
        assertThat(user.getSenderCommitmentAt()).isEqualTo(before);
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void accept_versionPerimee_409SansEcriture() {
        assertThatThrownBy(() -> service.accept(userId, PublishCommitmentRole.TRAVELER, "2020-01"))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("relisez");

        assertThat(user.getTravelerCommitmentVersion()).isNull();
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), eq("PUBLISH_COMMITMENT_ACCEPTED"), any(), anyMap());
    }
}
