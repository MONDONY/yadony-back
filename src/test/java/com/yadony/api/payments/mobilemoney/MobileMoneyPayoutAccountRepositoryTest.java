package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import com.yadony.api.common.EncryptionService;
import com.yadony.api.common.EncryptionSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Entité et requêtes dérivées sur le schéma H2 généré des entités (le profil test n'exécute pas Flyway). */
@DataJpaTest
@ActiveProfiles("test")
class MobileMoneyPayoutAccountRepositoryTest {

    @Autowired private MobileMoneyPayoutAccountRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void initEncryption() {
        // @DataJpaTest ne charge pas EncryptionSupport : même passphrase que application-test.yml.
        new EncryptionSupport(new EncryptionService("yadony-test-encryption-key-not-for-production-use-32-bytes"));
    }

    private MobileMoneyPayoutAccountEntity account(UUID userId, String provider) {
        return repository.saveAndFlush(new MobileMoneyPayoutAccountEntity(
                userId, provider, "CI", "XOF", "+2250700000001", "+225 ** ** 01", Instant.parse("2026-03-04T10:11:12Z")));
    }

    @Test
    void liveAccounts_areListedOldestFirst_andScopedToTheirOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity first = account(owner, "ORANGE_CIV");
        Thread.sleep(5);
        MobileMoneyPayoutAccountEntity second = account(owner, "MTN_MOMO_CIV");
        account(UUID.randomUUID(), "ORANGE_CIV");

        assertThat(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(owner))
                .extracting(MobileMoneyPayoutAccountEntity::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(first.getId(), owner)).isPresent();
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(first.getId(), UUID.randomUUID())).isEmpty();
        assertThat(repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "MTN_MOMO_CIV")).contains(second);
    }

    @Test
    void aSoftDeletedAccount_disappearsFromEveryQuery() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity removed = account(owner, "ORANGE_CIV");
        removed.softDelete();
        repository.saveAndFlush(removed);

        assertThat(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(owner)).isEmpty();
        assertThat(repository.findByIdAndUserIdAndDeletedAtIsNull(removed.getId(), owner)).isEmpty();
        assertThat(repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_CIV")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_money_payout_accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void msisdn_isStoredEncrypted_andReadBackInClear() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity saved = account(owner, "ORANGE_CIV");
        saved.setMigrated(true);
        saved.setLegacyAcceptedProviders("ORANGE_CIV,MTN_MOMO_CIV");
        repository.saveAndFlush(saved);

        String raw = jdbc.queryForObject("SELECT msisdn FROM mobile_money_payout_accounts WHERE id = ?", String.class, saved.getId());
        assertThat(raw).isNotEqualTo("+2250700000001").doesNotContain("0700000001");

        var reloaded = repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_CIV").orElseThrow();
        assertThat(reloaded.getMsisdn()).isEqualTo("+2250700000001");
        assertThat(reloaded.getMsisdnMasked()).isEqualTo("+225 ** ** 01");
        assertThat(reloaded.isMigrated()).isTrue();
        assertThat(reloaded.getLegacyAcceptedProviders()).isEqualTo("ORANGE_CIV,MTN_MOMO_CIV");
        assertThat(reloaded.getVerifiedAt()).isEqualTo(Instant.parse("2026-03-04T10:11:12Z"));
        assertThat(reloaded.getCountry()).isEqualTo("CI");
        assertThat(reloaded.getCurrency()).isEqualTo("XOF");
        assertThat(reloaded.getUserId()).isEqualTo(owner);
    }

    @Test
    void settersUpdateTheAccountInPlace() {
        UUID owner = UUID.randomUUID();
        MobileMoneyPayoutAccountEntity saved = account(owner, "ORANGE_CIV");
        saved.setProvider("ORANGE_SEN");
        saved.setCountry("SN");
        saved.setCurrency("XOF");
        saved.setMsisdn("+221770000002");
        saved.setMsisdnMasked("+221 ** ** 02");
        saved.setVerifiedAt(Instant.parse("2026-04-01T00:00:00Z"));
        repository.saveAndFlush(saved);

        var reloaded = repository.findByUserIdAndProviderAndDeletedAtIsNull(owner, "ORANGE_SEN").orElseThrow();
        assertThat(reloaded.getMsisdn()).isEqualTo("+221770000002");
        assertThat(reloaded.getCountry()).isEqualTo("SN");
    }
}
