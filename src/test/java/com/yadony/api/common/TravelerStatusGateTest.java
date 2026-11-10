package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.travelerstatus.TravelerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TravelerStatusGateTest {

    @Mock PlatformSettingsService settings;

    private TravelerStatusGate gate() {
        return new TravelerStatusGate(settings);
    }

    private static UserEntity user(TravelerStatus status) {
        UserEntity u = new UserEntity();
        u.setTravelerStatus(status);
        return u;
    }

    @Test
    void interrupteurInactif_neLeveJamais() {
        when(settings.travelerStatusRequired()).thenReturn(false);

        assertThatCode(() -> gate().assertDeclaredIfRequired(user(null))).doesNotThrowAnyException();
        assertThatCode(() -> gate().assertDeclaredIfRequired(user(TravelerStatus.INDIVIDUAL))).doesNotThrowAnyException();
    }

    @Test
    void interrupteurActif_statutNonDeclare_403() {
        when(settings.travelerStatusRequired()).thenReturn(true);

        assertThatThrownBy(() -> gate().assertDeclaredIfRequired(user(null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("traveler-status-required");
                    assertThat(e.getTitle()).isEqualTo("Traveler Status Required");
                    assertThat(e.getMessage())
                            .isEqualTo("Indiquez si vous voyagez en tant que particulier ou professionnel.");
                });
    }

    @Test
    void interrupteurActif_statutDeclare_neLevePas() {
        when(settings.travelerStatusRequired()).thenReturn(true);

        assertThatCode(() -> gate().assertDeclaredIfRequired(user(TravelerStatus.INDIVIDUAL))).doesNotThrowAnyException();
        assertThatCode(() -> gate().assertDeclaredIfRequired(user(TravelerStatus.PROFESSIONAL))).doesNotThrowAnyException();
    }
}
