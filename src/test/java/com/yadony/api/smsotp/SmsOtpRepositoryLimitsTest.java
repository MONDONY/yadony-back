package com.yadony.api.smsotp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sémantique en base des deux requêtes qui portent les limites : l'invalidation d'un
 * code l'expire sans le marquer utilisé, donc seul un code consommé compte comme
 * connexion réussie.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("SmsOtpRepository — invalidation et connexions réussies")
class SmsOtpRepositoryLimitsTest {

    @Autowired private SmsOtpRepository repository;
    @Autowired private TestEntityManager em;

    private static final String PHONE = "+221700000001";

    private SmsOtpEntity token(String phone, LocalDateTime expiresAt, LocalDateTime usedAt) {
        SmsOtpEntity t = new SmsOtpEntity();
        t.setPhoneNumber(phone);
        t.setCodeHash("$2a$10$hash");
        t.setExpiresAt(expiresAt);
        t.setUsedAt(usedAt);
        return em.persistAndFlush(t);
    }

    @Test
    @DisplayName("invalidateActive expire les codes actifs du numéro, sans toucher usedAt ni les autres numéros")
    void invalidateExpiresWithoutMarkingUsed() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SmsOtpEntity active = token(PHONE, now.plusMinutes(8), null);
        SmsOtpEntity alreadyUsed = token(PHONE, now.plusMinutes(8), now.minusMinutes(1));
        SmsOtpEntity otherPhone = token("+221700000002", now.plusMinutes(8), null);

        int updated = repository.invalidateActive(PHONE, now);
        em.clear();

        assertThat(updated).isEqualTo(1);
        SmsOtpEntity reloaded = em.find(SmsOtpEntity.class, active.getId());
        assertThat(reloaded.getUsedAt()).isNull();
        assertThat(reloaded.getExpiresAt()).isEqualToIgnoringNanos(now);
        assertThat(em.find(SmsOtpEntity.class, alreadyUsed.getId()).getExpiresAt()).isAfter(now);
        assertThat(em.find(SmsOtpEntity.class, otherPhone.getId()).getExpiresAt()).isAfter(now);
    }

    @Test
    @DisplayName("countSuccessfulSince : seuls les codes consommés dans la fenêtre comptent, pas les invalidés")
    void countsOnlyConsumedCodes() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        token(PHONE, now.plusMinutes(5), now.minusMinutes(2));
        token(PHONE, now.plusMinutes(5), now.minusMinutes(4));
        token(PHONE, now.plusMinutes(5), now.minusMinutes(30));
        token(PHONE, now.plusMinutes(5), null);
        repository.invalidateActive(PHONE, now);

        assertThat(repository.countSuccessfulSince(PHONE, now.minusMinutes(10))).isEqualTo(2);
        assertThat(repository.countSuccessfulSince("+221700000009", now.minusMinutes(10))).isZero();
    }
}
