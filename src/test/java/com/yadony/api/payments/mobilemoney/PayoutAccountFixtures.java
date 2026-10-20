package com.yadony.api.payments.mobilemoney;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pose sur un compte de test les champs que seule la migration V343 écrit en production (compte
 * repris, réseaux cochés sur l'ancien compte unique) : l'entité n'a pas de setter pour eux.
 */
public final class PayoutAccountFixtures {

    private PayoutAccountFixtures() {
    }

    public static MobileMoneyPayoutAccountEntity setMigrated(MobileMoneyPayoutAccountEntity account, boolean migrated) {
        ReflectionTestUtils.setField(account, "migrated", migrated);
        return account;
    }

    public static MobileMoneyPayoutAccountEntity setLegacyAcceptedProviders(MobileMoneyPayoutAccountEntity account,
                                                                            String legacyAcceptedProviders) {
        ReflectionTestUtils.setField(account, "legacyAcceptedProviders", legacyAcceptedProviders);
        return account;
    }
}
