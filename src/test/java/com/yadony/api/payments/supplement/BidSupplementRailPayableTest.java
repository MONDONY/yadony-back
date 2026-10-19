package com.yadony.api.payments.supplement;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rail carte : même exigence de compte Connect que le paiement principal ({@code ONBOARDING_COMPLETE}). */
class BidSupplementRailPayableTest {

    private static UserEntity traveler(String account, StripeAccountStatus status) {
        UserEntity u = new UserEntity();
        u.setStripeAccountId(account);
        u.setStripeAccountStatus(status);
        return u;
    }

    @Test
    void carte_compteConnectComplet_payable() {
        assertThat(BidSupplementRail.STRIPE.payableBy(traveler("acct_1", StripeAccountStatus.ONBOARDING_COMPLETE),
                "EUR")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = StripeAccountStatus.class, names = "ONBOARDING_COMPLETE", mode = EnumSource.Mode.EXCLUDE)
    void carte_compteConnectIncompletOuDesactive_nonPayable(StripeAccountStatus status) {
        assertThat(BidSupplementRail.STRIPE.payableBy(traveler("acct_1", status), "EUR")).isFalse();
    }

    @Test
    void carte_sansIdentifiantDeCompte_nonPayable() {
        assertThat(BidSupplementRail.STRIPE.payableBy(traveler(" ", StripeAccountStatus.ONBOARDING_COMPLETE), "EUR"))
                .isFalse();
        assertThat(BidSupplementRail.STRIPE.payableBy(traveler(null, StripeAccountStatus.ONBOARDING_COMPLETE), "EUR"))
                .isFalse();
    }
}
