package com.yadony.api.activation;

import com.yadony.api.activation.dto.DeclareIntentRequest;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivationServiceTest {

    @Mock UserRepository userRepository;
    @Mock ActivationRepository activationRepository;
    @Mock AuditService auditService;
    @InjectMocks ActivationService service;

    private UserEntity user(String intent, String destination) {
        UserEntity u = ActivationTestUsers.withId();
        u.setIntent(intent);
        u.setIntentDestinationCountry(destination);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        return u;
    }

    private static ActivationRepository.TripRow trip(UUID id, LocalDate date) {
        return new ActivationRepository.TripRow() {
            public String getId() { return id.toString(); }
            public String getDepartureCity() { return "Paris"; }
            public String getArrivalCity() { return "Abidjan"; }
            public LocalDate getDepartureDate() { return date; }
            public Number getAvailableKg() { return new BigDecimal("12.50"); }
            public Number getPricePerKg() { return null; }
            public String getCurrency() { return "EUR"; }
        };
    }

    private static ActivationRepository.PackageRow pkg(UUID id) {
        return new ActivationRepository.PackageRow() {
            public String getId() { return id.toString(); }
            public String getDepartureCity() { return "Paris"; }
            public String getArrivalCity() { return "Dakar"; }
            public LocalDate getDesiredDate() { return LocalDate.of(2026, 10, 12); }
            public Number getWeightKg() { return 3; }
        };
    }

    @Test
    void sender_getsTripsTowardsDestination() {
        UserEntity u = user("SENDER", "CI");
        UUID tripId = UUID.randomUUID();
        when(activationRepository.hasFirstAction(u.getId())).thenReturn(0);
        when(activationRepository.countTripsTowards(eq("CI"), any(), any(), eq(u.getId()))).thenReturn(3L);
        when(activationRepository.findTripsTowards(eq("CI"), any(), any(), eq(u.getId()), eq(3)))
                .thenReturn(List.of(trip(tripId, LocalDate.of(2026, 10, 10))));

        var r = service.getActivation("uid");

        assertThat(r.intent()).isEqualTo("SENDER");
        assertThat(r.destinationCountry()).isEqualTo("CI");
        assertThat(r.kycVerified()).isTrue();
        assertThat(r.firstActionDone()).isFalse();
        assertThat(r.opportunities().kind()).isEqualTo("TRIPS");
        assertThat(r.opportunities().total()).isEqualTo(3);
        var item = r.opportunities().trips().get(0);
        assertThat(item.id()).isEqualTo(tripId);
        assertThat(item.availableKg()).isEqualTo(12.5);
        assertThat(item.pricePerKg()).isNull();
        assertThat(r.opportunities().packages()).isEmpty();
        verify(activationRepository, never()).countPackagesTowards(any(), any(), any(), any());
    }

    @Test
    void firstActionDone_isTrue_whenRepositorySaysOne() {
        UserEntity u = user(null, null);
        when(activationRepository.hasFirstAction(u.getId())).thenReturn(1);
        assertThat(service.getActivation("uid").firstActionDone()).isTrue();
    }

    @Test
    void both_isTreatedAsSender() {
        UserEntity u = user("BOTH", "SN");
        when(activationRepository.countTripsTowards(eq("SN"), any(), any(), eq(u.getId()))).thenReturn(0L);
        when(activationRepository.findTripsTowards(eq("SN"), any(), any(), eq(u.getId()), eq(3))).thenReturn(List.of());
        assertThat(service.getActivation("uid").opportunities().kind()).isEqualTo("TRIPS");
    }

    @Test
    void traveler_getsPackagesWithin15Days() {
        UserEntity u = user("TRAVELER", "SN");
        UUID packageId = UUID.randomUUID();
        when(activationRepository.countPackagesTowards(eq("SN"), any(), any(), eq(u.getId()))).thenReturn(2L);
        when(activationRepository.findPackagesTowards(eq("SN"), any(), any(), eq(u.getId()), eq(3)))
                .thenReturn(List.of(pkg(packageId)));

        var r = service.getActivation("uid");

        assertThat(r.opportunities().kind()).isEqualTo("PACKAGES");
        assertThat(r.opportunities().packages().get(0).id()).isEqualTo(packageId);
        assertThat(r.opportunities().packages().get(0).weightKg()).isEqualTo(3.0);
        assertThat(r.opportunities().trips()).isEmpty();
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(activationRepository).countPackagesTowards(eq("SN"), from.capture(), to.capture(), eq(u.getId()));
        assertThat(to.getValue()).isEqualTo(from.getValue().plusDays(15));
    }

    @Test
    void nullDestination_givesNoOpportunities() {
        user("SENDER", null);
        var r = service.getActivation("uid");
        assertThat(r.opportunities().kind()).isEqualTo("NONE");
        assertThat(r.opportunities().total()).isZero();
        verify(activationRepository, never()).countTripsTowards(any(), any(), any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void unknownIntent_givesNoOpportunities() {
        user(null, "CI");
        assertThat(service.getActivation("uid").opportunities().kind()).isEqualTo("NONE");
    }

    @Test
    void declareIntent_storesFields_andAudits() {
        UserEntity u = user(null, null);
        var r = service.declareIntent("uid", new DeclareIntentRequest(UserIntent.TRAVELER, "sn", IntentSource.SIGNUP));

        assertThat(u.getIntent()).isEqualTo("TRAVELER");
        assertThat(u.getIntentDestinationCountry()).isEqualTo("SN");
        assertThat(u.getIntentSource()).isEqualTo("SIGNUP");
        assertThat(u.getIntentDeclaredAt()).isNotNull();
        assertThat(r.intent()).isEqualTo("TRAVELER");
        verify(userRepository).save(u);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(u.getId()), eq("INTENT_DECLARED"), eq(u.getId()), payload.capture());
        assertThat(payload.getValue()).containsEntry("intent", "TRAVELER").containsEntry("source", "SIGNUP")
                .containsEntry("destinationCountry", "SN");
    }

    @Test
    void declareIntent_acceptsNullDestination() {
        UserEntity u = user(null, null);
        service.declareIntent("uid", new DeclareIntentRequest(UserIntent.SENDER, null, IntentSource.PROMPT));
        assertThat(u.getIntentDestinationCountry()).isNull();
        assertThat(u.getIntentSource()).isEqualTo("PROMPT");
    }

    @Test
    void declareIntent_rejectsUnsupportedCountry() {
        assertThatThrownBy(() -> service.declareIntent("uid",
                new DeclareIntentRequest(UserIntent.SENDER, "ZZ", IntentSource.PROMPT)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("pays");
        verify(userRepository, never()).save(any());
    }

    @Test
    void declareIntent_rejectsInferredSourceFromClient() {
        assertThatThrownBy(() -> service.declareIntent("uid",
                new DeclareIntentRequest(UserIntent.SENDER, "SN", IntentSource.INFERRED)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("source");
        verify(userRepository, never()).save(any());
    }

    @Test
    void unknownUser_is404() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getActivation("ghost")).hasMessageContaining("introuvable");
    }
}
