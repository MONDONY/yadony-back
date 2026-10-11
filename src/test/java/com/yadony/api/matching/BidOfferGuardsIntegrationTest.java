package com.yadony.api.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Réglages du voyageur appliqués par le serveur aux propositions de prix d'un
 * expéditeur sur son trajet (recette Sentry du 2026-10-11) : prix minimum par kilo
 * (422 {@code bid-below-min-price}) et profils vérifiés uniquement
 * (403 {@code contact-kyc-required}), de bout en bout via MockMvc.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Offres sur un trajet : prix minimum par kg et profils vérifiés")
class BidOfferGuardsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    /**
     * {@code findByIdForUpdate} compile en {@code FOR NO KEY UPDATE}, refusé par H2 : le
     * verrou est remplacé par une lecture simple, sans effet sur les gardes testées.
     */
    @MockitoSpyBean private AnnouncementRepository announcementRepository;
    @Autowired private UserBusinessPrefsRepository prefsRepository;

    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> announcementRepository.findById(inv.getArgument(0)))
                .when(announcementRepository).findByIdForUpdate(any());
        traveler = persistUser("Moussa", KycStatus.VERIFIED, true);
        sender = persistUser("Awa", KycStatus.VERIFIED, true);
        announcement = persistAnnouncement(traveler);
    }

    private UserEntity persistUser(String firstName, KycStatus kyc, boolean contactKycOnly) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-offer-guards-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(kyc);
        u.setContactKycOnly(contactKycOnly);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setNegotiable(true);
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.CASH)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private void travelerMinimum(int min) {
        UserBusinessPrefsEntity p = new UserBusinessPrefsEntity();
        p.setUserId(traveler.getId());
        p.setCurrencyCode("EUR");
        p.setMinBidPriceEur(min);
        prefsRepository.save(p);
    }

    /** Relit le voyageur avant d'écrire : sa version a pu avancer entre-temps. */
    private void travelerAcceptsOnlyVerified(boolean value) {
        UserEntity fresh = userRepository.findById(traveler.getId()).orElseThrow();
        fresh.setContactKycOnly(value);
        traveler = userRepository.save(fresh);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity u, String role) {
        return new UsernamePasswordAuthenticationToken(
                u.getFirebaseUid(), null, List.of(new SimpleGrantedAuthority(role)));
    }

    /** 5 kg pour un total brut de {@code gross} €. */
    private ResultActions propose(String gross) throws Exception {
        BidNegotiationStartRequest body = new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                "Fatou Sarr", "+221701234567", true,
                "CASH", null, null, null, new BigDecimal(gross), null, null);
        return mockMvc.perform(post("/announcements/" + announcement.getId() + "/bids/negotiation")
                .with(authentication(as(sender, "ROLE_SENDER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String proposeAndReturnBidId(String gross) throws Exception {
        String json = propose(gross).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("bidId").asText();
    }

    @Nested
    @DisplayName("Prix minimum par kg")
    class MinPrice {

        @Test
        @DisplayName("proposition sous le minimum : 422 bid-below-min-price avec minimum et devise")
        void belowMinimum_isRefused() throws Exception {
            travelerMinimum(6);

            // 20 € brut pour 5 kg : 4 €/kg brut, sous 6 €/kg net
            propose("20.00")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://yadony.app/errors/bid-below-min-price"))
                    .andExpect(jsonPath("$.code").value("bid-below-min-price"))
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.minPricePerKg").isNumber());
        }

        @Test
        @DisplayName("proposition au-dessus du minimum : 201")
        void aboveMinimum_isAccepted() throws Exception {
            travelerMinimum(6);

            propose("50.00").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("minimum à 0 : aucune restriction")
        void zeroMinimum_isIgnored() throws Exception {
            travelerMinimum(0);

            propose("5.00").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("aucun réglage enregistré : aucune restriction")
        void noPrefs_isIgnored() throws Exception {
            propose("5.00").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("contre-offre de l'expéditeur sous le minimum : 422 ; celle du voyageur reste libre")
        void senderCounterBelowMinimum_isRefused() throws Exception {
            String bidId = proposeAndReturnBidId("50.00");
            travelerMinimum(6);

            // Le voyageur répond plus bas que son propre minimum : c'est son choix.
            mockMvc.perform(post("/bids/" + bidId + "/negotiation/counter")
                            .with(authentication(as(traveler, "ROLE_TRAVELER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BidNegotiationCounterRequest(new BigDecimal("25.00"), null))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/bids/" + bidId + "/negotiation/counter")
                            .with(authentication(as(sender, "ROLE_SENDER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BidNegotiationCounterRequest(new BigDecimal("20.00"), null))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("bid-below-min-price"));
        }
    }

    @Nested
    @DisplayName("Profils vérifiés uniquement")
    class VerifiedOnly {

        @Test
        @DisplayName("expéditeur non vérifié, option active : 403 contact-kyc-required")
        void unverifiedSender_optionOn_isRefused() throws Exception {
            sender = persistUser("Awa", KycStatus.PENDING, true);

            propose("50.00")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://yadony.app/errors/contact-kyc-required"));
        }

        @Test
        @DisplayName("expéditeur non vérifié, option désactivée : 201")
        void unverifiedSender_optionOff_isAccepted() throws Exception {
            travelerAcceptsOnlyVerified(false);
            sender = persistUser("Awa", KycStatus.PENDING, true);

            propose("50.00").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("option activée en cours de discussion : la contre-offre de l'expéditeur non vérifié est refusée")
        void optionTurnedOnMidThread_blocksSenderCounter() throws Exception {
            travelerAcceptsOnlyVerified(false);
            sender = persistUser("Awa", KycStatus.PENDING, true);
            String bidId = proposeAndReturnBidId("50.00");

            mockMvc.perform(post("/bids/" + bidId + "/negotiation/counter")
                            .with(authentication(as(traveler, "ROLE_TRAVELER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BidNegotiationCounterRequest(new BigDecimal("60.00"), null))))
                    .andExpect(status().isOk());
            travelerAcceptsOnlyVerified(true);

            mockMvc.perform(post("/bids/" + bidId + "/negotiation/counter")
                            .with(authentication(as(sender, "ROLE_SENDER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BidNegotiationCounterRequest(new BigDecimal("55.00"), null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("contact-kyc-required"));
        }
    }
}
