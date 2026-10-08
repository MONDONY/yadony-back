package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filtre de recherche par moyen de paiement (FLUTTER-G0) : appliqué en requête, en accord
 * exact avec {@code availablePaymentMethods} (AnnouncementPaymentRails#offerable).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementPaymentMethodFilterIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CacheManager cacheManager;
    @Autowired com.yadony.api.payments.currency.ExchangeRateRepository exchangeRateRepository;

    private UUID cardAndCash;
    private UUID cardWithoutConnect;
    private UUID cashOnly;
    private UUID cfaCashAndMobileMoney;
    private UUID cfaMobileMoneyInactive;

    @BeforeEach
    void seed() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
        // Les trajets XOF sont convertis dans la devise du lecteur : taux requis.
        for (String[] rate : new String[][] {{"EUR", "1"}, {"XOF", "655.957"}}) {
            if (exchangeRateRepository.findById(rate[0]).isEmpty()) {
                exchangeRateRepository.save(new com.yadony.api.payments.currency.ExchangeRateEntity(
                        rate[0], new BigDecimal(rate[1])));
            }
        }
        UserEntity connect = traveler("pm-connect", StripeAccountStatus.ONBOARDING_COMPLETE,
                MobileMoneyPayoutStatus.NOT_CONFIGURED);
        UserEntity plain = traveler("pm-plain", StripeAccountStatus.NOT_CREATED,
                MobileMoneyPayoutStatus.NOT_CONFIGURED);
        UserEntity mobileMoney = traveler("pm-mm", StripeAccountStatus.NOT_CREATED,
                MobileMoneyPayoutStatus.ACTIVE);

        cardAndCash = trip(connect, "EUR", EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        cardWithoutConnect = trip(plain, "EUR", EnumSet.of(PaymentMethod.STRIPE));
        cashOnly = trip(connect, "EUR", EnumSet.of(PaymentMethod.CASH));
        cfaCashAndMobileMoney = trip(mobileMoney, "XOF", EnumSet.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY));
        cfaMobileMoneyInactive = trip(plain, "XOF", EnumSet.of(PaymentMethod.MOBILE_MONEY));
    }

    @Test
    void card_keepsOnlyTripsReallyPayableByCard() throws Exception {
        assertThat(search("CARD")).containsExactlyInAnyOrder(cardAndCash);
        assertThat(search("STRIPE")).containsExactlyInAnyOrder(cardAndCash);
    }

    @Test
    void cash_keepsTripsAcceptingCash() throws Exception {
        assertThat(search("CASH")).containsExactlyInAnyOrder(cardAndCash, cashOnly, cfaCashAndMobileMoney);
    }

    @Test
    void mobileMoney_requiresCfaCurrencyAndActivePayoutAccount() throws Exception {
        assertThat(search("MOBILE_MONEY")).containsExactlyInAnyOrder(cfaCashAndMobileMoney);
    }

    @Test
    void severalMethods_areOrCombined() throws Exception {
        assertThat(search("CARD,MOBILE_MONEY")).containsExactlyInAnyOrder(cardAndCash, cfaCashAndMobileMoney);
    }

    @Test
    void unknownOrAbsent_appliesNoFilter() throws Exception {
        assertThat(search("WAVE")).hasSize(5);
        assertThat(search(null)).hasSize(5);
    }

    @Test
    void filter_agreesWithAvailablePaymentMethodsOfTheResponse() throws Exception {
        JsonNode all = objectMapper.readTree(mockMvc.perform(get("/announcements").param("size", "50").with(reader()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (String wanted : List.of("STRIPE", "CASH", "MOBILE_MONEY")) {
            Set<UUID> expected = new HashSet<>();
            for (JsonNode trip : all.get("content")) {
                for (JsonNode method : trip.get("availablePaymentMethods")) {
                    if (method.asText().equals(wanted)) {
                        expected.add(UUID.fromString(trip.get("id").asText()));
                    }
                }
            }
            assertThat(search(wanted)).as(wanted).containsExactlyInAnyOrderElementsOf(expected);
        }
        assertThat(cardWithoutConnect).isNotNull();
        assertThat(cfaMobileMoneyInactive).isNotNull();
    }

    @Test
    void filter_isAppliedBeforePagination() throws Exception {
        mockMvc.perform(get("/announcements").param("paymentMethods", "CASH").param("size", "1").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /** Lecteur sans compte en base : aucun blocage, devise d'affichage par défaut. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader() {
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "pm-reader", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SENDER"))));
    }

    private List<UUID> search(String methods) throws Exception {
        var request = get("/announcements").param("size", "50").with(reader());
        if (methods != null) {
            request = request.param("paymentMethods", methods);
        }
        JsonNode page = objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<UUID> ids = new java.util.ArrayList<>();
        page.get("content").forEach(n -> ids.add(UUID.fromString(n.get("id").asText())));
        return ids;
    }

    private UserEntity traveler(String uid, StripeAccountStatus stripe, MobileMoneyPayoutStatus mobileMoney) {
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        user.setStripeAccountStatus(stripe);
        user.setMobileMoneyStatus(mobileMoney);
        return userRepository.save(user);
    }

    private UUID trip(UserEntity traveler, String currency, Set<PaymentMethod> accepted) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(traveler.getId());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setCurrency(currency);
        e.setAcceptedPaymentMethods(EnumSet.copyOf(accepted));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e).getId();
    }
}
