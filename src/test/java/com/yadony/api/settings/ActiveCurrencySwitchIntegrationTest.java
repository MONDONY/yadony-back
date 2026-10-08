package com.yadony.api.settings;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.PriceGridItemEntity;
import com.yadony.api.matching.PriceGridItemRepository;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-8F, de bout en bout : un utilisateur qui détient de l'argent dans plusieurs
 * devises passe d'un portefeuille à l'autre. Aucun solde ne bouge, le changement est
 * tracé, la grille tarifaire suit la nouvelle devise, et un taux manquant annule tout.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ActiveCurrencySwitchIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired UserBusinessPrefsRepository prefsRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired PriceGridItemRepository priceGridItemRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ExchangeRateRepository exchangeRateRepository;
    @Autowired CacheManager cacheManager;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        exchangeRateRepository.save(new ExchangeRateEntity("EUR", new BigDecimal("1")));
        exchangeRateRepository.save(new ExchangeRateEntity("CAD", new BigDecimal("1.47")));
        exchangeRateRepository.findByCurrency("XAF").ifPresent(exchangeRateRepository::delete);
        if (cacheManager.getCache("exchange-rates") != null) {
            cacheManager.getCache("exchange-rates").clear();
        }

        user = new UserEntity();
        user.setFirebaseUid("uid-switch-" + UUID.randomUUID());
        user.setFirstName("Awa");
        user.setLastName("Tester");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        user = userRepository.save(user);

        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(user.getId());
        prefs.setWeightUnit("kg");
        prefs.setCurrencyCode("EUR");
        prefs.setPickupRadiusKm(10);
        prefs.setDefaultPackageWeightKg(23);
        prefs.setMinBidPriceEur(0);
        prefsRepository.save(prefs);

        wallet("EUR", "50.00");
        wallet("CAD", "30.00");

        PriceGridItemEntity item = new PriceGridItemEntity();
        item.setTravelerId(user.getId());
        item.setLabel("Valise");
        item.setUnitPriceNet(new BigDecimal("10.00"));
        item.setPosition(0);
        priceGridItemRepository.save(item);
    }

    @Test
    @DisplayName("Changer de devise active avec des soldes non nuls : 200, soldes intacts, audit, grille convertie")
    void switch_withNonZeroBalances_succeedsWithoutTouchingBalances() throws Exception {
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CAD"))
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("CAD"))
                .andExpect(jsonPath("$.currencyLocked").value(false));

        assertThat(prefsRepository.findById(user.getId()).orElseThrow().getCurrencyCode())
                .isEqualTo("CAD");
        assertThat(balance("EUR")).isEqualByComparingTo("50.00");
        assertThat(balance("CAD")).isEqualByComparingTo("30.00");
        assertThat(priceGridItemRepository.findByTravelerIdOrderByPositionAsc(user.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getUnitPriceNet()).isEqualByComparingTo("14.70"));
        assertThat(userActions()).contains("ACTIVE_CURRENCY_CHANGED", "PRICE_GRID_CURRENCY_CONVERTED");

        // Réversible : retour sur l'euro, les soldes ne bougent toujours pas.
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EUR"))
                        .with(authentication(asUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("EUR"));
        assertThat(balance("EUR")).isEqualByComparingTo("50.00");
        assertThat(balance("CAD")).isEqualByComparingTo("30.00");
        assertThat(priceGridItemRepository.findByTravelerIdOrderByPositionAsc(user.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getUnitPriceNet()).isEqualByComparingTo("10.00"));
    }

    @Test
    @DisplayName("Taux manquant pour convertir la grille : 422 RFC 7807 et rien n'est change")
    void switch_rateMissing_rollsBackEverything() throws Exception {
        mockMvc.perform(put("/users/me/business-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("XAF"))
                        .with(authentication(asUser())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(prefsRepository.findById(user.getId()).orElseThrow().getCurrencyCode())
                .isEqualTo("EUR");
        assertThat(priceGridItemRepository.findByTravelerIdOrderByPositionAsc(user.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getUnitPriceNet()).isEqualByComparingTo("10.00"));
        assertThat(userActions()).doesNotContain("ACTIVE_CURRENCY_CHANGED");
    }

    private void wallet(String currency, String amount) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setUserId(user.getId());
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(amount));
        walletAccountRepository.save(w);
    }

    private BigDecimal balance(String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(user.getId(), currency)
                .orElseThrow().getBalance();
    }

    private List<String> userActions() {
        // Lecture de la seule colonne action : le payload JSONB ne se relit pas sous H2.
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'USER' AND entity_id = ?",
                String.class, user.getId());
    }

    private UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static String body(String currency) {
        return "{\"weightUnit\":\"kg\",\"currencyCode\":\"" + currency + "\",\"pickupRadiusKm\":10,"
                + "\"defaultPackageWeightKg\":23,\"minBidPriceEur\":0}";
    }
}
