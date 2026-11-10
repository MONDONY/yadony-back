package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.TravelerStatusRequest;
import com.yadony.api.travelerstatus.dto.TravelerStatusResponse;
import com.yadony.api.travelerstatus.events.TravelerStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TravelerStatusServiceTest {

    private static final String UID = "uid-ts-1";
    private static final String NUMBER = "SIREN 123 456 789";
    private static final String NAME = "Acme Express SARL";

    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    TravelerStatusService service;
    UserEntity user;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TravelerStatusService(users, audit, events);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    }

    private static TravelerStatusRequest individual(Boolean confirmed) {
        return new TravelerStatusRequest(TravelerStatus.INDIVIDUAL, confirmed);
    }

    private static TravelerStatusRequest professional() {
        return new TravelerStatusRequest(TravelerStatus.PROFESSIONAL, null);
    }

    private void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("traveler-status-invalid");
                });
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertThat(user.getTravelerStatus()).isNull();
        assertThat(user.getBusinessName()).isNull();
        assertThat(user.getBusinessRegistrationNumber()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
        assertThat(user.getTravelerStatusDeclaredAt()).isNull();
        verify(users, never()).save(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), anyMap());
        verifyNoInteractions(events);
    }

    @Test
    void particulierConfirme_poseLeStatutSansChampsProfessionnels() {
        TravelerStatusResponse r = service.declare(UID, individual(true));

        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
        assertThat(user.getTravelerStatusDeclaredAt()).isNotNull();
        assertThat(user.getTravelerStatusTermsVersion()).isEqualTo(TravelerStatusService.TERMS_VERSION);
        assertThat(user.getBusinessName()).isNull();
        assertThat(user.getBusinessRegistrationNumber()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
        assertThat(r.status()).isEqualTo(TravelerStatus.INDIVIDUAL);
        assertThat(r.declaredAt()).isEqualTo(user.getTravelerStatusDeclaredAt());
        assertThat(r.businessName()).isNull();
        assertThat(r.registrationNumber()).isNull();
        assertThat(r.country()).isNull();
        verify(users).save(user);
    }

    @Test
    void particulierSansConfirmation_422() {
        assertInvalid(() -> service.declare(UID, individual(false)));
        assertInvalid(() -> service.declare(UID, individual(null)));
    }

    @Test
    void professionnel_422_utilisezLaDemandeGpPro_rienEcrit() {
        assertThatThrownBy(() -> service.declare(UID, professional()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("traveler-status-invalid");
                    assertThat(e.getMessage()).contains("GP Pro");
                });
        assertNothingWritten();
    }

    @Test
    void ancienProfessionnelDeclareSansGpPro_peutRedevenirParticulier_champsEffaces() {
        user.setTravelerStatus(TravelerStatus.PROFESSIONAL);
        user.setBusinessName(NAME);
        user.setBusinessRegistrationNumber(NUMBER);
        user.setBusinessCountry("FR");

        service.declare(UID, individual(true));

        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
        assertThat(user.getBusinessName()).isNull();
        assertThat(user.getBusinessRegistrationNumber()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
        verify(audit, times(1)).log(anyString(), any(), anyString(), any(), anyMap());
        verify(events, times(1)).publishEvent(any(TravelerStatusChangedEvent.class));
    }

    @Test
    void gpProValideAvecStatutProfessionnel_409_ilFautRedevenirParticulierParLaSortieGpPro() {
        user.setGpPro(true);
        user.setTravelerStatus(TravelerStatus.PROFESSIONAL);
        user.setBusinessName(NAME);
        user.setBusinessRegistrationNumber(NUMBER);
        user.setBusinessCountry("FR");

        assertThatThrownBy(() -> service.declare(UID, individual(true)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("gp-pro-active");
                });
        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.PROFESSIONAL);
        assertThat(user.getBusinessName()).isEqualTo(NAME);
        verify(users, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void gpProAncienBadgeSansStatut_peutDeclarerParticulier() {
        user.setGpPro(true);

        service.declare(UID, individual(true));

        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
        assertThat(user.isGpPro()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void audit_particulier_neContientQueLeStatut() {
        service.declare(UID, individual(true));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(userId), eq("TRAVELER_STATUS_DECLARED"), eq(userId), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("status");
        assertThat(payload.getValue()).containsEntry("status", "INDIVIDUAL");
    }

    @Test
    void evenementPublieUneSeuleFois() {
        service.declare(UID, individual(true));

        ArgumentCaptor<TravelerStatusChangedEvent> ev = ArgumentCaptor.forClass(TravelerStatusChangedEvent.class);
        verify(events, times(1)).publishEvent(ev.capture());
        assertThat(ev.getValue().userId()).isEqualTo(userId);
        assertThat(ev.getValue().status()).isEqualTo(TravelerStatus.INDIVIDUAL);
    }

    @Test
    void utilisateurInconnu_404() {
        when(users.findByFirebaseUid("inconnu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.declare("inconnu", individual(true)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test
    void lApresVerrouRelitLeCompte_pasLEntiteChargeeAvant() {
        UserEntity fresh = new UserEntity();
        ReflectionTestUtils.setField(fresh, "id", userId);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(fresh));

        service.declare(UID, individual(true));

        assertThat(fresh.getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
        verify(users).save(fresh);
    }
}
