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
        return new TravelerStatusRequest(TravelerStatus.INDIVIDUAL, confirmed, null, null, null);
    }

    private static TravelerStatusRequest professional(String name, String number, String country) {
        return new TravelerStatusRequest(TravelerStatus.PROFESSIONAL, null, name, number, country);
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
    void professionnelIncomplet_422() {
        assertInvalid(() -> service.declare(UID, professional(null, NUMBER, "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, null, "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, NUMBER, null)));
        assertInvalid(() -> service.declare(UID, professional("  ", NUMBER, "FR")));
    }

    @Test
    void professionnelAvecNumeroTropCourt_ouCaracteresDeControle_422_etRienDEcrit() {
        assertInvalid(() -> service.declare(UID, professional(NAME, "AB1", "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, "AB12\n3456", "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, "AB12\u00003456", "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, "AB12<script>", "FR")));
        assertInvalid(() -> service.declare(UID, professional("Ac\tme", NUMBER, "FR")));
        assertInvalid(() -> service.declare(UID, professional(NAME, NUMBER, "fr")));
        assertInvalid(() -> service.declare(UID, professional(NAME, NUMBER, "FRA")));
    }

    @Test
    void professionnelComplet_poseLesChamps() {
        TravelerStatusResponse r = service.declare(UID, professional("  " + NAME + " ", NUMBER, "FR"));

        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.PROFESSIONAL);
        assertThat(user.getBusinessName()).isEqualTo(NAME);
        assertThat(user.getBusinessRegistrationNumber()).isEqualTo(NUMBER);
        assertThat(user.getBusinessCountry()).isEqualTo("FR");
        assertThat(user.getTravelerStatusTermsVersion()).isEqualTo(TravelerStatusService.TERMS_VERSION);
        assertThat(r.status()).isEqualTo(TravelerStatus.PROFESSIONAL);
        assertThat(r.businessName()).isEqualTo(NAME);
        assertThat(r.registrationNumber()).isEqualTo(NUMBER);
        assertThat(r.country()).isEqualTo("FR");
    }

    @Test
    void professionnelVersParticulier_effaceRaisonSocialeNumeroEtPays_unSeulAudit() {
        service.declare(UID, professional(NAME, NUMBER, "FR"));
        org.mockito.Mockito.clearInvocations(audit, events);

        service.declare(UID, individual(true));

        assertThat(user.getTravelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
        assertThat(user.getBusinessName()).isNull();
        assertThat(user.getBusinessRegistrationNumber()).isNull();
        assertThat(user.getBusinessCountry()).isNull();
        verify(audit, times(1)).log(anyString(), any(), anyString(), any(), anyMap());
        verify(events, times(1)).publishEvent(any(TravelerStatusChangedEvent.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void audit_neContientQueLeStatutEtLePays_jamaisLeNumeroNiLeNom() {
        service.declare(UID, professional(NAME, NUMBER, "FR"));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("USER"), eq(userId), eq("TRAVELER_STATUS_DECLARED"), eq(userId), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("status", "country");
        assertThat(payload.getValue()).containsEntry("status", "PROFESSIONAL").containsEntry("country", "FR");
        assertThat(payload.getValue().values()).doesNotContain(NUMBER, NAME);
        assertThat(payload.getValue().toString()).doesNotContain(NUMBER).doesNotContain(NAME);
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
        service.declare(UID, professional(NAME, NUMBER, "FR"));

        ArgumentCaptor<TravelerStatusChangedEvent> ev = ArgumentCaptor.forClass(TravelerStatusChangedEvent.class);
        verify(events, times(1)).publishEvent(ev.capture());
        assertThat(ev.getValue().userId()).isEqualTo(userId);
        assertThat(ev.getValue().status()).isEqualTo(TravelerStatus.PROFESSIONAL);
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
