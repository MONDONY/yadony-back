package com.yadony.api.matching;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-FT : un voyageur dont Stripe Connect est actif peut publier sans la carte ; au moins
 * un moyen de paiement reste obligatoire.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementCardOptionalIntegrationTest {

    private static final String UID = "uid-card-optional";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void seed() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var user = new com.yadony.api.auth.UserEntity();
        user.setFirebaseUid(UID);
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.VERIFIED);
        user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        user.setRoles(new java.util.HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        userRepository.save(user);
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static String body(String methodsJson) {
        String date = LocalDate.now().plusDays(10).toString();
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "currency": "EUR",
              "acceptedPaymentMethods": %s,
              "pickupAddress": {"label": "CDG", "lat": 49.009, "lng": 2.547},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, methodsJson, date);
    }

    @Test
    void publishWithoutCard_isAccepted_andCardIsNotOffered() throws Exception {
        mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("[\"CASH\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptedPaymentMethods.length()").value(1))
                .andExpect(jsonPath("$.acceptedPaymentMethods[0]").value("CASH"))
                .andExpect(jsonPath("$.availablePaymentMethods.length()").value(1))
                .andExpect(jsonPath("$.availablePaymentMethods[0]").value("CASH"));

        assertThat(announcementRepository.findAll()).singleElement()
                .satisfies(a -> assertThat(a.isCardDeclined()).isTrue());
    }

    @Test
    void publishWithNoPaymentMethod_isRefusedWith422() throws Exception {
        mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("[]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("payment-method-required"));
        assertThat(announcementRepository.count()).isZero();
    }
}
