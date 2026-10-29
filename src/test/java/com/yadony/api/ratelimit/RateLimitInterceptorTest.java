package com.yadony.api.ratelimit;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.common.i18n.TestMessages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RateLimitInterceptor")
class RateLimitInterceptorTest {

    private RateLimitProperties properties;
    private SimpleMeterRegistry meters;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        meters = new SimpleMeterRegistry();
        interceptor = new RateLimitInterceptor(properties,
                new SlidingWindowRateLimiter(Clock.systemUTC(), Duration.ofHours(1)),
                meters, TestMessages.resolver());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TestMessages.clearRequest();
    }

    private static void limit(RateLimitProperties p, RateLimitRule rule, int limit) {
        RateLimitProperties.Override o = new RateLimitProperties.Override();
        o.setLimit(limit);
        p.getRules().put(rule.propertyName(), o);
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/v1" + path);
        r.setContextPath("/api/v1");
        r.setRemoteAddr(ip);
        return r;
    }

    private static void loggedInAs(String uid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                uid, null, AuthorityUtils.createAuthorityList("ROLE_SENDER")));
    }

    private boolean pass(MockHttpServletRequest r) {
        return interceptor.preHandle(r, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("limite par compte : le 4e appel du même uid est refusé, un autre uid passe")
    void accountLimitPerUid() {
        limit(properties, RateLimitRule.ACCOUNT, 3);
        loggedInAs("uid-a");
        for (int i = 0; i < 3; i++) {
            assertThat(pass(request("GET", "/announcements", "1.1.1.1"))).isTrue();
        }
        assertThatThrownBy(() -> pass(request("GET", "/announcements", "2.2.2.2")))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("too-many-requests");
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                    assertThat(e.getProperties()).containsKey("retryAfterSeconds");
                    assertThat(e.getMessage()).isNotBlank();
                });
        assertThat(meters.counter("yadony.rate_limit.rejected", "rule", "account").count()).isEqualTo(1);

        loggedInAs("uid-b");
        assertThat(pass(request("GET", "/announcements", "1.1.1.1"))).isTrue();
    }

    @Test
    @DisplayName("même IP, comptes différents : aucune limite par compte partagée (cas CGNAT)")
    void sameIpDifferentAccounts() {
        limit(properties, RateLimitRule.ACCOUNT, 1);
        for (int i = 0; i < 5; i++) {
            loggedInAs("uid-" + i);
            assertThat(pass(request("GET", "/bids/me", "41.82.0.1"))).isTrue();
        }
    }

    @Test
    @DisplayName("paiement : seules les écritures comptent, le GET de statut reste libre")
    void paymentWritesOnly() {
        limit(properties, RateLimitRule.PAYMENT_MINUTE, 2);
        loggedInAs("uid-pay");
        assertThat(pass(request("POST", "/payments", "1.1.1.1"))).isTrue();
        assertThat(pass(request("POST", "/wallet/topup", "1.1.1.1"))).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(pass(request("GET", "/bids/" + UUID.randomUUID() + "/mobile-money/status", "1.1.1.1")))
                    .isTrue();
        }
        assertThatThrownBy(() -> pass(request("POST", "/payments/connect/onboarding-link", "1.1.1.1")))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(meters.counter("yadony.rate_limit.rejected", "rule", "payment-minute").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("KYC : création de session limitée par compte")
    void kycSession() {
        limit(properties, RateLimitRule.KYC_SESSION, 1);
        loggedInAs("uid-kyc");
        assertThat(pass(request("POST", "/kyc/session", "1.1.1.1"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/kyc/session", "1.1.1.1")))
                .isInstanceOf(RateLimitExceededException.class);
        // La lecture du statut n'est pas concernée.
        assertThat(pass(request("GET", "/kyc/status", "1.1.1.1"))).isTrue();
    }

    @Test
    @DisplayName("OTP : compté par IP sans authentification, une autre IP passe")
    void otpPerIp() {
        limit(properties, RateLimitRule.OTP_SEND_IP, 2);
        assertThat(pass(request("POST", "/auth/email-otp/send", "9.9.9.9"))).isTrue();
        assertThat(pass(request("POST", "/auth/sms-otp/send", "9.9.9.9"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/auth/email-otp/send", "9.9.9.9")))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(pass(request("POST", "/auth/email-otp/send", "8.8.8.8"))).isTrue();
    }

    @Test
    @DisplayName("vérification OTP et rattachement partagent le budget par IP")
    void otpVerifyPerIp() {
        limit(properties, RateLimitRule.OTP_VERIFY_IP, 2);
        assertThat(pass(request("POST", "/auth/email-otp/verify", "7.7.7.7"))).isTrue();
        assertThat(pass(request("POST", "/auth/sms-otp/attach", "7.7.7.7"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/auth/sms-otp/verify", "7.7.7.7")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("codes promo et parrainage : oracles bornés par compte")
    void promoAndReferral() {
        limit(properties, RateLimitRule.PROMO_QUOTE, 1);
        limit(properties, RateLimitRule.REFERRAL_REDEEM, 1);
        loggedInAs("uid-promo");
        assertThat(pass(request("POST", "/bids/quote", "1.1.1.1"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/bids/quote", "1.1.1.1")))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(pass(request("POST", "/referral/redeem", "1.1.1.1"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/referral/redeem", "1.1.1.1")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("code de recharge : borné par compte et par IP, tous comptes confondus")
    void topupCodeRedeem() {
        limit(properties, RateLimitRule.TOPUP_CODE_REDEEM, 1);
        limit(properties, RateLimitRule.TOPUP_CODE_REDEEM_IP, 2);
        loggedInAs("uid-code-1");
        assertThat(pass(request("POST", "/wallet/topup-codes/redeem", "5.5.5.5"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/wallet/topup-codes/redeem", "6.6.6.6")))
                .isInstanceOf(RateLimitExceededException.class);
        loggedInAs("uid-code-2");
        assertThat(pass(request("POST", "/wallet/topup-codes/redeem", "5.5.5.5"))).isTrue();
        loggedInAs("uid-code-3");
        assertThatThrownBy(() -> pass(request("POST", "/wallet/topup-codes/redeem", "5.5.5.5")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("supplément et avenant : création et paiement comptés, devis et options libres")
    void supplementAndAmendmentWrites() {
        limit(properties, RateLimitRule.PAYMENT_MINUTE, 3);
        loggedInAs("uid-suppl");
        String bid = "/bids/" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThat(pass(request("POST", bid + "/supplements/quote", "1.1.1.1"))).isTrue();
            assertThat(pass(request("POST", bid + "/amendments/quote", "1.1.1.1"))).isTrue();
        }
        assertThat(pass(request("POST", bid + "/supplements", "1.1.1.1"))).isTrue();
        assertThat(pass(request("POST", bid + "/supplements/" + UUID.randomUUID() + "/mobile-money/initiate",
                "1.1.1.1"))).isTrue();
        assertThat(pass(request("POST", bid + "/amendments/" + UUID.randomUUID() + "/confirm-authorization",
                "1.1.1.1"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", bid + "/amendments", "1.1.1.1")))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("administrateur, anonyme ou principal inattendu : pas de limite par compte")
    void exemptions() {
        limit(properties, RateLimitRule.ACCOUNT, 1);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.com", null, false, "admin-uid"),
                null, List.of()));
        for (int i = 0; i < 3; i++) {
            assertThat(pass(request("GET", "/admin/users", "1.1.1.1"))).isTrue();
        }

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(pass(request("GET", "/config/app", "1.1.1.1"))).isTrue();
        assertThat(pass(request("GET", "/config/app", "1.1.1.1"))).isTrue();

        SecurityContextHolder.clearContext();
        assertThat(pass(request("GET", "/config/app", "1.1.1.1"))).isTrue();
        assertThat(pass(request("GET", "/config/app", "1.1.1.1"))).isTrue();

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("not", "a uid"), null, AuthorityUtils.createAuthorityList("ROLE_SENDER")));
        assertThat(pass(request("GET", "/announcements", "1.1.1.1"))).isTrue();
        assertThat(pass(request("GET", "/announcements", "1.1.1.1"))).isTrue();
    }

    @Test
    @DisplayName("IPv6 : compté par préfixe /64, une rotation d'adresses dans le bloc ne contourne rien")
    void ipv6CountedPerSlash64() {
        limit(properties, RateLimitRule.OTP_SEND_IP, 2);
        assertThat(pass(request("POST", "/auth/email-otp/send", "2001:db8:1:2::1"))).isTrue();
        assertThat(pass(request("POST", "/auth/email-otp/send", "2001:db8:1:2::ffff"))).isTrue();
        assertThatThrownBy(() -> pass(request("POST", "/auth/email-otp/send", "2001:db8:1:2:abcd::9")))
                .isInstanceOf(RateLimitExceededException.class);
        // Un autre /64 garde son propre budget.
        assertThat(pass(request("POST", "/auth/email-otp/send", "2001:db8:1:3::1"))).isTrue();

        assertThat(RateLimitInterceptor.ipKey("2001:db8:1:2::1")).isEqualTo("v6:20010db800010002");
        assertThat(RateLimitInterceptor.ipKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(RateLimitInterceptor.ipKey(null)).isNull();
        // IPv4 mappée : Java la rend en IPv4 (4 octets), l'adresse brute sert de clé.
        assertThat(RateLimitInterceptor.ipKey("::ffff:203.0.113.7")).isEqualTo("::ffff:203.0.113.7");
        assertThat(RateLimitInterceptor.ipKey("not:an:ip:zz")).isEqualTo("not:an:ip:zz");
    }

    @Test
    @DisplayName("désactivé : rien n'est compté")
    void disabled() {
        properties.setEnabled(false);
        limit(properties, RateLimitRule.ACCOUNT, 1);
        loggedInAs("uid-off");
        for (int i = 0; i < 3; i++) {
            assertThat(pass(request("GET", "/announcements", "1.1.1.1"))).isTrue();
        }
    }

    @Test
    @DisplayName("propriétés : surcharge de limite et de fenêtre, défaut sinon")
    void propertiesOverride() {
        assertThat(properties.limitOf(RateLimitRule.PAYMENT_HOUR)).isEqualTo(100);
        assertThat(properties.windowOf(RateLimitRule.PAYMENT_HOUR)).isEqualTo(Duration.ofHours(1));

        RateLimitProperties.Override o = new RateLimitProperties.Override();
        o.setWindow(Duration.ofMinutes(30));
        properties.getRules().put("payment-hour", o);
        assertThat(properties.limitOf(RateLimitRule.PAYMENT_HOUR)).isEqualTo(100);
        assertThat(properties.windowOf(RateLimitRule.PAYMENT_HOUR)).isEqualTo(Duration.ofMinutes(30));
        assertThat(RateLimitRule.OTP_SEND_IP.propertyName()).isEqualTo("otp-send-ip");
    }
}
