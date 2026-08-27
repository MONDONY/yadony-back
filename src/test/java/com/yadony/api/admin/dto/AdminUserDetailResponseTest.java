package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.ProSubscriptionSource;
import com.yadony.api.billing.ProSubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserDetailResponseTest {

    private static UserEntity sampleUser() {
        UserEntity user = new UserEntity();
        user.setFirstName("Jean");
        user.setLastName("Dupont");
        return user;
    }

    private static FirebaseContactService.Contact sampleContact() {
        return new FirebaseContactService.Contact("+33600000000", "jean@example.com");
    }

    @Test
    @DisplayName("sans abonnement, le champ proSubscription est nul")
    void withoutSubscription() {
        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact(), null);

        assertThat(response.proSubscription()).isNull();
    }

    @Test
    @DisplayName("un accès offert expose l'administrateur et le motif")
    void adminGrantExposesGranter() {
        UUID adminId = UUID.randomUUID();
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.ADMIN_GRANT);
        sub.setGrantedByAdminId(adminId);
        sub.setAdminGrantReason("Partenariat presse");

        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact(), sub);

        assertThat(response.proSubscription().source()).isEqualTo("ADMIN_GRANT");
        assertThat(response.proSubscription().grantedByAdminId()).isEqualTo(adminId);
        assertThat(response.proSubscription().adminGrantReason()).isEqualTo("Partenariat presse");
    }

    @Test
    @DisplayName("la surcharge à deux arguments reste disponible et laisse le champ nul")
    void twoArgOverloadStillWorks() {
        // Neuf appels du contrôleur l'utilisent encore : elle ne doit pas disparaître.
        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact());

        assertThat(response.proSubscription()).isNull();
    }
}
