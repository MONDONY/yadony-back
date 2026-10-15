package com.yadony.api.payments.wallet.topupcode;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTopupCodeEntityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    private static WalletTopupCodeEntity code(int max, int used, LocalDateTime expiresAt) {
        WalletTopupCodeEntity c = new WalletTopupCodeEntity();
        c.setCode("DAKAR2000");
        c.setAmount(new BigDecimal("2000"));
        c.setCurrency("XOF");
        c.setMaxRedemptions(max);
        c.setRedeemedCount(used);
        c.setExpiresAt(expiresAt);
        return c;
    }

    @Test
    void actif_tantQueNonExpireNonEpuiseNonRevoque() {
        assertThat(code(100, 37, NOW.plusDays(1)).displayStatus(NOW)).isEqualTo(WalletTopupCodeDisplayStatus.ACTIVE);
        assertThat(code(100, 37, NOW.plusDays(1)).remaining()).isEqualTo(63);
    }

    @Test
    void epuise_quandToutesLesUnitesSontPrises() {
        assertThat(code(2, 2, NOW.plusDays(1)).displayStatus(NOW)).isEqualTo(WalletTopupCodeDisplayStatus.EXHAUSTED);
    }

    @Test
    void expire_prioritaireSurEpuise_etExpireALaSecondeExacte() {
        assertThat(code(2, 2, NOW).displayStatus(NOW)).isEqualTo(WalletTopupCodeDisplayStatus.EXPIRED);
    }

    @Test
    void revoque_prioritaireSurTout() {
        WalletTopupCodeEntity c = code(2, 2, NOW.minusDays(1));
        c.setStatus(WalletTopupCodeStatus.REVOKED);
        assertThat(c.displayStatus(NOW)).isEqualTo(WalletTopupCodeDisplayStatus.REVOKED);
    }

    @Test
    void setCode_normaliseEnMajuscules() {
        WalletTopupCodeEntity c = new WalletTopupCodeEntity();
        c.setCode("yd-ab12");
        assertThat(c.getCode()).isEqualTo("YD-AB12");
    }
}
