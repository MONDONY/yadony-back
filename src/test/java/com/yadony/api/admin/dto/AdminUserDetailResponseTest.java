package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.billing.BillingCycle;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.ProSubscriptionSource;
import com.yadony.api.billing.ProSubscriptionStatus;
import com.yadony.api.billing.dto.AdminProSubscriptionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        // Valeurs toutes distinctes et reconnaissables : une inversion positionnelle dans
        // AdminProSubscriptionView.from(...) (ex. stripeSubscriptionId <-> adminGrantReason,
        // deux String adjacents) doit faire échouer ce test, pas passer inaperçue derrière
        // une couverture JaCoCo satisfaite.
        UUID adminId = UUID.randomUUID();
        Instant currentPeriodEnd = Instant.parse("2027-01-01T00:00:00Z");
        Instant graceExpiresAt = Instant.parse("2027-06-15T00:00:00Z");

        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.ADMIN_GRANT);
        sub.setBillingCycle(BillingCycle.YEARLY);
        sub.setCurrentPeriodEnd(currentPeriodEnd);
        sub.setCancelAtPeriodEnd(true);
        sub.setGraceExpiresAt(graceExpiresAt);
        sub.setStripeSubscriptionId("sub_distinct_stripe_id");
        sub.setGrantedByAdminId(adminId);
        sub.setAdminGrantReason("Partenariat presse");

        AdminProSubscriptionView view =
                AdminUserDetailResponse.from(sampleUser(), sampleContact(), sub).proSubscription();

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.source()).isEqualTo("ADMIN_GRANT");
        assertThat(view.billingCycle()).isEqualTo("YEARLY");
        assertThat(view.currentPeriodEnd()).isEqualTo(currentPeriodEnd);
        assertThat(view.cancelAtPeriodEnd()).isTrue();
        assertThat(view.graceExpiresAt()).isEqualTo(graceExpiresAt);
        assertThat(view.stripeSubscriptionId()).isEqualTo("sub_distinct_stripe_id");
        assertThat(view.grantedByAdminId()).isEqualTo(adminId);
        assertThat(view.adminGrantReason()).isEqualTo("Partenariat presse");
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
