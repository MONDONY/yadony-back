package com.yadony.api.referral;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoCodeRepository;
import com.yadony.api.promo.PromoDiscountKind;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choix de la récompense de parrainage, de bout en bout sur PostgreSQL (migrations Flyway
 * jouées, dont V321 ; schéma validé contre les entités) : compteurs, liste des filleuls,
 * crédit de solde unique (le verrou du portefeuille exige PostgreSQL), code personnel
 * réservé au parrain et appliqué en pourcentage du prix plafonné à la commission.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@DisplayName("Parrainage — choix de la récompense (PostgreSQL)")
class ReferralRewardChoiceIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ReferralInvitationRepository invitationRepository;
    @Autowired private WalletService walletService;
    @Autowired private WalletTransactionRepository walletTransactionRepository;
    @Autowired private PromoCodeRepository promoCodeRepository;
    @Autowired private CommissionRateResolver commissionRateResolver;

    private UserEntity persistUser(String firstName, String lastName) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("referral-choice-it-" + UUID.randomUUID());
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private UUID invitation(UserEntity referrer, UserEntity referee, ReferralRewardStatus rewardStatus) {
        ReferralInvitationEntity inv = new ReferralInvitationEntity();
        inv.setReferrerUserId(referrer.getId());
        inv.setRefereeUserId(referee.getId());
        inv.setRefereePhone("+33611223344");
        inv.setCodeUsed("JEAN1234");
        inv.setStatus(rewardStatus == ReferralRewardStatus.AVAILABLE ? "FIRST_DELIVERY" : "SIGNED_UP");
        inv.setRewardStatus(rewardStatus);
        if (rewardStatus == ReferralRewardStatus.AVAILABLE) {
            inv.setRewardAvailableAt(java.time.LocalDateTime.now());
        }
        return invitationRepository.saveAndFlush(inv).getId();
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private ResultActions choose(UserEntity caller, UUID invitationId, String choice) throws Exception {
        return mockMvc.perform(post("/me/referral/rewards/{id}/choose", invitationId)
                .with(authentication(as(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"" + choice + "\"}"));
    }

    @Test
    void myReferral_countsEveryStageAndAnnouncesTheRewardOptions() throws Exception {
        UserEntity referrer = persistUser("Jean", "Dupont");
        invitation(referrer, persistUser("Awa", "Diop"), ReferralRewardStatus.AVAILABLE);
        invitation(referrer, persistUser("Moussa", "Traoré"), ReferralRewardStatus.NONE);

        mockMvc.perform(get("/me/referral").with(authentication(as(referrer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareUrl", matchesPattern("https://yadony\\.com/r/[A-Z0-9]+")))
                .andExpect(jsonPath("$.totalInvited").value(2))
                .andExpect(jsonPath("$.signedUp").value(2))
                .andExpect(jsonPath("$.firstDelivery").value(1))
                .andExpect(jsonPath("$.rewarded").value(0))
                .andExpect(jsonPath("$.pendingRewardCount").value(1))
                .andExpect(jsonPath("$.rewardWalletAmount").value(5.00))
                .andExpect(jsonPath("$.rewardWalletCurrency").value("EUR"))
                .andExpect(jsonPath("$.rewardDiscountPercent").value(5))
                .andExpect(jsonPath("$.hasBeenReferred").value(false));
    }

    @Test
    void referees_listOnlyTheCallersReferees_withoutPhoneNumbers() throws Exception {
        UserEntity referrer = persistUser("Jean", "Dupont");
        UUID mine = invitation(referrer, persistUser("Awa", "Diop"), ReferralRewardStatus.AVAILABLE);
        invitation(persistUser("Autre", "Parrain"), persistUser("Fatou", "Sow"), ReferralRewardStatus.NONE);

        mockMvc.perform(get("/me/referral/referees").with(authentication(as(referrer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].invitationId").value(mine.toString()))
                .andExpect(jsonPath("$.content[0].displayName").value("Awa D."))
                .andExpect(jsonPath("$.content[0].status").value("FIRST_DELIVERY"))
                .andExpect(jsonPath("$.content[0].rewardStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.content[0].signedUpAt").exists())
                .andExpect(content().string(not(containsString("+3361122"))))
                .andExpect(content().string(not(containsString("Diop"))));
    }

    @Test
    void walletChoice_creditsFiveEurosExactlyOnce() throws Exception {
        UserEntity referrer = persistUser("Jean", "Dupont");
        UUID invitationId = invitation(referrer, persistUser("Awa", "Diop"), ReferralRewardStatus.AVAILABLE);

        choose(referrer, invitationId, "WALLET_CREDIT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardStatus").value("CHOSEN"))
                .andExpect(jsonPath("$.rewardChoice").value("WALLET_CREDIT"))
                .andExpect(jsonPath("$.status").value("REWARDED"))
                .andExpect(jsonPath("$.walletAmount").value(5.00))
                .andExpect(jsonPath("$.walletCurrency").value("EUR"));

        // Crédit AFTER_COMMIT, synchrone : il est passé quand la requête rend la main.
        assertThat(walletService.getBalance(referrer.getId(), "EUR")).isEqualByComparingTo("5.00");
        assertThat(walletTransactionRepository.findByIdempotencyKey("referral-reward:" + invitationId)).isPresent();

        choose(referrer, invitationId, "WALLET_CREDIT")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("referral-reward-already-chosen"));
        choose(referrer, invitationId, "DISCOUNT_CODE").andExpect(status().isConflict());

        assertThat(walletService.getBalance(referrer.getId(), "EUR")).isEqualByComparingTo("5.00");
    }

    @Test
    void someoneElsesInvitation_is404_andUnavailableRewardIs409() throws Exception {
        UserEntity referrer = persistUser("Jean", "Dupont");
        UUID available = invitation(referrer, persistUser("Awa", "Diop"), ReferralRewardStatus.AVAILABLE);
        UUID notYet = invitation(referrer, persistUser("Moussa", "Traoré"), ReferralRewardStatus.NONE);
        UserEntity intruder = persistUser("Intrus", "X");

        choose(intruder, available, "WALLET_CREDIT")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("referral-invitation-not-found"));
        choose(referrer, UUID.randomUUID(), "WALLET_CREDIT").andExpect(status().isNotFound());
        choose(referrer, notYet, "DISCOUNT_CODE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("referral-reward-not-available"));
        choose(referrer, available, "COMMISSION_VOUCHER")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("referral-reward-choice-invalid"));

        assertThat(walletService.getBalance(intruder.getId(), "EUR")).isEqualByComparingTo("0");
        assertThat(invitationRepository.findById(available).orElseThrow().getRewardStatus())
                .isEqualTo(ReferralRewardStatus.AVAILABLE);
    }

    @Test
    void discountChoice_createsAPersonalSingleUseCodeOnlyItsOwnerCanUse() throws Exception {
        UserEntity referrer = persistUser("Aboubakar", "Diakité");
        UUID invitationId = invitation(referrer, persistUser("Awa", "Diop"), ReferralRewardStatus.AVAILABLE);

        choose(referrer, invitationId, "DISCOUNT_CODE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardChoice").value("DISCOUNT_CODE"))
                .andExpect(jsonPath("$.discountCode.code", matchesPattern("MERCI-ABOU-[A-Z2-9]{4}")))
                .andExpect(jsonPath("$.discountCode.percent").value(5))
                .andExpect(jsonPath("$.discountCode.used").value(false))
                .andExpect(jsonPath("$.discountCode.expired").value(false))
                .andExpect(jsonPath("$.discountCode.expiresAt").exists());

        ReferralInvitationEntity inv = invitationRepository.findById(invitationId).orElseThrow();
        PromoCodeEntity promo = promoCodeRepository.findById(inv.getRewardPromoCodeId()).orElseThrow();
        assertThat(promo.getOwnerUserId()).isEqualTo(referrer.getId());
        assertThat(promo.getDiscountKind()).isEqualTo(PromoDiscountKind.PRICE_PERCENT);
        assertThat(promo.getMaxRedemptions()).isEqualTo(1);
        assertThat(walletService.getBalance(referrer.getId(), "EUR")).isEqualByComparingTo("0");

        // Un autre expéditeur ne peut pas l'utiliser.
        UserEntity other = persistUser("Fatou", "Sow");
        UUID traveler = UUID.randomUUID();
        assertThatThrownBy(() -> commissionRateResolver.resolve(traveler, other.getId(), promo.getCode()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("promo-not-owner"));

        // Le propriétaire obtient 5 % du prix payé, financés sur la commission et plafonnés à elle.
        BigDecimal base = commissionRateResolver.resolve(traveler, referrer.getId());
        BigDecimal expected = base.subtract(new BigDecimal("0.05").multiply(BigDecimal.ONE.add(base))
                .setScale(3, RoundingMode.HALF_UP)).max(BigDecimal.ZERO);
        assertThat(commissionRateResolver.resolve(traveler, referrer.getId(), promo.getCode()))
                .isEqualByComparingTo(expected)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
