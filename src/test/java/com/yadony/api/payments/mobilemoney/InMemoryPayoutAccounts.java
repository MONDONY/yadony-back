package com.yadony.api.payments.mobilemoney;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link MobileMoneyPayoutAccounts} réel (accepts, activeIn, libellés, refus) sur des comptes tenus en
 * mémoire : les tests de service y posent les comptes du voyageur sans mocker la logique d'acceptation.
 */
public class InMemoryPayoutAccounts extends MobileMoneyPayoutAccounts {

    private static final Map<String, String> ALPHA2 = Map.of("SEN", "SN", "CIV", "CI", "CMR", "CM", "BEN", "BJ",
            "BFA", "BF", "MLI", "ML", "TGO", "TG");

    private final Map<UUID, List<MobileMoneyPayoutAccountEntity>> byUser = new HashMap<>();

    public InMemoryPayoutAccounts() {
        super(null);
    }

    /** Remplace les comptes de l'utilisateur : un compte par code, le premier est le plus ancien. */
    public InMemoryPayoutAccounts give(UUID userId, String currency, String... providerCodes) {
        List<MobileMoneyPayoutAccountEntity> accounts = new ArrayList<>();
        for (String code : providerCodes) {
            String alpha3 = code.substring(code.lastIndexOf('_') + 1);
            MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(userId, code,
                    ALPHA2.getOrDefault(alpha3, alpha3.substring(0, 2)), currency, "+2210000000" + accounts.size(),
                    "+221 •••• 0" + accounts.size(), Instant.parse("2026-01-01T00:00:00Z"));
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            accounts.add(a);
        }
        byUser.put(userId, accounts);
        return this;
    }

    @Override
    public List<MobileMoneyPayoutAccountEntity> active(UUID userId) {
        return byUser.getOrDefault(userId, List.of());
    }
}
