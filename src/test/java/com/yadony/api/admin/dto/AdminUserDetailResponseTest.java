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
    @DisplayName("kiloPro est figé à false et gpPro suit le compte")
    void kiloProIsDeprecatedAndGpProFollowsAccount() {
        UserEntity user = sampleUser();
        user.setKiloPro(true);
        user.setGpPro(true);

        AdminUserDetailResponse response = AdminUserDetailResponse.from(user, sampleContact(), null);

        assertThat(response.kiloPro()).isFalse();
        assertThat(response.gpPro()).isTrue();
    }

    @Test
    @DisplayName("voyageur professionnel : statut, date, raison sociale, numéro et pays visibles de l'admin")
    void professionalTravelerStatusIsExposedToAdmin() {
        UserEntity user = sampleUser();
        Instant declaredAt = Instant.parse("2026-11-10T09:00:00Z");
        user.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.PROFESSIONAL);
        user.setTravelerStatusDeclaredAt(declaredAt);
        user.setBusinessName("Acme Express");
        user.setBusinessRegistrationNumber("SIREN 123 456 789");
        user.setBusinessCountry("FR");

        AdminUserDetailResponse response = AdminUserDetailResponse.from(user, sampleContact(), null);

        assertThat(response.travelerStatus()).isEqualTo("PROFESSIONAL");
        assertThat(response.travelerStatusDeclaredAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 10, 9, 0));
        assertThat(response.businessName()).isEqualTo("Acme Express");
        assertThat(response.businessRegistrationNumber()).isEqualTo("SIREN 123 456 789");
        assertThat(response.businessCountry()).isEqualTo("FR");
    }

    @Test
    @DisplayName("voyageur particulier : statut et date seulement ; non déclaré : tout est nul")
    void individualAndUndeclaredTravelerStatus() {
        UserEntity individual = sampleUser();
        individual.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL);
        individual.setTravelerStatusDeclaredAt(Instant.parse("2026-11-10T09:00:00Z"));

        AdminUserDetailResponse ind = AdminUserDetailResponse.from(individual, sampleContact(), null);
        assertThat(ind.travelerStatus()).isEqualTo("INDIVIDUAL");
        assertThat(ind.travelerStatusDeclaredAt()).isNotNull();
        assertThat(ind.businessName()).isNull();
        assertThat(ind.businessRegistrationNumber()).isNull();
        assertThat(ind.businessCountry()).isNull();

        AdminUserDetailResponse none = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null);
        assertThat(none.travelerStatus()).isNull();
        assertThat(none.travelerStatusDeclaredAt()).isNull();
        assertThat(none.businessName()).isNull();
        assertThat(none.businessRegistrationNumber()).isNull();
        assertThat(none.businessCountry()).isNull();
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
        Instant grantedAt = Instant.parse("2026-08-01T00:00:00Z");

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
        sub.setGrantedAt(grantedAt);

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
        assertThat(view.grantedAt()).isEqualTo(grantedAt);
    }

    @Test
    @DisplayName("gel des versements : date, motif principal, motifs et paiements retenus")
    void payoutHoldFields() {
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(2);
        AdminUserDetailResponse response = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null,
                new com.yadony.api.payments.hold.PayoutHoldSummary(since, java.util.List.of(
                        com.yadony.api.payments.hold.PayoutHoldReason.BANNED,
                        com.yadony.api.payments.hold.PayoutHoldReason.KYC_REVOKED), 3L));

        assertThat(response.payoutsHeldSince()).isEqualTo(since);
        assertThat(response.payoutsHeldReason()).isEqualTo("BANNED");
        assertThat(response.payoutsHeldReasons()).containsExactly("BANNED", "KYC_REVOKED");
        assertThat(response.heldPaymentsCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("sans gel : champs nuls, liste vide, compteur a zero")
    void withoutPayoutHold() {
        AdminUserDetailResponse response = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null);

        assertThat(response.payoutsHeldSince()).isNull();
        assertThat(response.payoutsHeldReason()).isNull();
        assertThat(response.payoutsHeldReasons()).isEmpty();
        assertThat(response.heldPaymentsCount()).isZero();
        assertThat(AdminUserDetailResponse.from(sampleUser(), sampleContact(), null, null).heldPaymentsCount()).isZero();
    }

    @Test
    @DisplayName("limite mensuelle et trajets utilisés ce mois-ci exposés ; sans limite : null")
    void monthlyTripLimitAndUsed() {
        UserEntity limited = sampleUser();
        limited.setMonthlyTripLimit(4);

        AdminUserDetailResponse r = AdminUserDetailResponse.from(
                limited, sampleContact(), null, com.yadony.api.payments.hold.PayoutHoldSummary.NONE, 3);
        assertThat(r.monthlyTripLimit()).isEqualTo(4);
        assertThat(r.monthlyTripsUsed()).isEqualTo(3);

        AdminUserDetailResponse none = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null);
        assertThat(none.monthlyTripLimit()).isNull();
        assertThat(none.monthlyTripsUsed()).isZero();
    }

    @Test
    @DisplayName("alerte « particulier à confirmer » : présente avec un drapeau ouvert, nulle sinon")
    void individualFrequencyAlert() {
        var alert = new com.yadony.api.travelerstatus.dto.IndividualFrequencyAlert(
                UUID.randomUUID(), 6, 30, 4, java.time.LocalDateTime.of(2026, 11, 10, 4, 30), false, "2026-11");

        AdminUserDetailResponse flagged = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null,
                com.yadony.api.payments.hold.PayoutHoldSummary.NONE, 2, alert);
        assertThat(flagged.individualFrequencyAlert()).isEqualTo(alert);
        assertThat(flagged.monthlyTripsUsed()).isEqualTo(2);

        AdminUserDetailResponse none = AdminUserDetailResponse.from(sampleUser(), sampleContact(), null,
                com.yadony.api.payments.hold.PayoutHoldSummary.NONE, 2);
        assertThat(none.individualFrequencyAlert()).isNull();
    }
}
