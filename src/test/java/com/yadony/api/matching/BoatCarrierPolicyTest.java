package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoatCarrierPolicyTest {

    private final BoatCarrierPolicy policy = new BoatCarrierPolicy();

    private static UserEntity user(boolean gpPro, boolean pro) {
        UserEntity u = new UserEntity();
        u.setGpPro(gpPro);
        u.setProAccount(pro);
        return u;
    }

    @Test
    void gpPro_isVerified() {
        assertThat(policy.isVerifiedCarrier(user(true, false))).isTrue();
    }

    @Test
    void proAccount_isVerified() {
        assertThat(policy.isVerifiedCarrier(user(false, true))).isTrue();
    }

    @Test
    void kycOnly_isNotVerified() {
        assertThat(policy.isVerifiedCarrier(user(false, false))).isFalse();
    }

    @Test
    void nullUser_isNotVerified() {
        assertThat(policy.isVerifiedCarrier(null)).isFalse();
    }

    @Test
    void assertCanPublishBoat_verified_passes() {
        assertThatCode(() -> policy.assertCanPublishBoat(user(true, false))).doesNotThrowAnyException();
    }

    @Test
    void assertCanPublishBoat_notVerified_403() {
        assertThatThrownBy(() -> policy.assertCanPublishBoat(user(false, false)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getErrorCode()).isEqualTo("boat-carrier-not-verified");
                });
    }
}
