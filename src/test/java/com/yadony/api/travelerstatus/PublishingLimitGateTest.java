package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishingLimitGateTest {

    @Mock PublishingEligibilityService eligibility;

    private final UserEntity user = new UserEntity();

    private PublishingLimitGate gate(PublishingEligibilityReason reason, Integer limit) {
        when(eligibility.evaluateForPublishing(user)).thenReturn(new PublishingEligibilityResponse(
                reason == PublishingEligibilityReason.OK, reason, limit, limit == null ? 0 : limit,
                LocalDate.of(2026, 12, 1), null));
        return new PublishingLimitGate(eligibility);
    }

    @Test
    void ok_neLevePas() {
        assertThatCode(() -> gate(PublishingEligibilityReason.OK, null).assertCanPublish(user))
                .doesNotThrowAnyException();
    }

    @Test
    void statusRequired_403_traveler_status_required() {
        assertThatThrownBy(() -> gate(PublishingEligibilityReason.STATUS_REQUIRED, 2).assertCanPublish(user))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("traveler-status-required");
                });
    }

    @Test
    void limitReached_403_monthly_limit_reached_avecLaDateDeReprise() {
        assertThatThrownBy(() -> gate(PublishingEligibilityReason.LIMIT_REACHED, 2).assertCanPublish(user))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("monthly-limit-reached");
                    assertThat(e.getMessage()).contains("2 trajets").contains("2026-12-01");
                });
    }

    @Test
    void limitReached_singulierPourUneLimiteDeUn() {
        assertThatThrownBy(() -> gate(PublishingEligibilityReason.LIMIT_REACHED, 1).assertCanPublish(user))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getMessage()).contains("1 trajet ").doesNotContain("1 trajets"));
    }

    @Test
    void gpProPending_403_gp_pro_pending() {
        assertThatThrownBy(() -> gate(PublishingEligibilityReason.GP_PRO_PENDING, 2).assertCanPublish(user))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getErrorCode()).isEqualTo("gp-pro-pending");
                });
    }
}
