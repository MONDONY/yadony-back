package com.yadony.api.calls;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yadony.calls.enabled=true",
        "yadony.calls.api-key=key",
        "yadony.calls.api-secret=s3cr3t-de-test-assez-long-pour-hs256-0123456789"})
class CallControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired CallRepository callRepository;
    @MockitoBean StreamClient streamClient;

    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
    }

    @Test
    void jetonPourLUtilisateurConnecte() throws Exception {
        mockMvc.perform(get("/calls/token").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("key"))
                .andExpect(jsonPath("$.userId").value(sender.getId().toString()))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void jetonSansAuthentificationRefuse() throws Exception {
        mockMvc.perform(get("/calls/token")).andExpect(status().is4xxClientError());
    }

    @Test
    void lExpediteurAppelleLeVoyageurPuisUnSecondAppelEstRefuse() throws Exception {
        ConversationEntity conv = persistConversation(BidStatus.ACCEPTED);

        mockMvc.perform(post("/conversations/{id}/calls", conv.getId()).with(authentication(as(sender))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callType").value("audio_call"))
                .andExpect(jsonPath("$.callId").isNotEmpty());
        assertThat(callRepository.findAll()).anySatisfy(c -> {
            assertThat(c.getConversationId()).isEqualTo(conv.getId());
            assertThat(c.getStatus()).isEqualTo(CallStatus.RINGING);
        });

        mockMvc.perform(post("/conversations/{id}/calls", conv.getId()).with(authentication(as(sender))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("call-already-in-progress"));
    }

    @Test
    void unTiersEstRefuseEn403() throws Exception {
        ConversationEntity conv = persistConversation(BidStatus.ACCEPTED);
        mockMvc.perform(post("/conversations/{id}/calls", conv.getId()).with(authentication(as(persistUser("Tiers")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("call-not-participant"));
    }

    @Test
    void negociationEnCoursRefuseeEn422() throws Exception {
        ConversationEntity conv = persistConversation(BidStatus.NEGOTIATING);
        mockMvc.perform(post("/conversations/{id}/calls", conv.getId()).with(authentication(as(sender))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("call-out-of-window"));
    }

    @Test
    void conversationInconnueEn404() throws Exception {
        mockMvc.perform(post("/conversations/{id}/calls", UUID.randomUUID()).with(authentication(as(sender))))
                .andExpect(status().isNotFound());
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-calls-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private ConversationEntity persistConversation(BidStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        b.setTrackingNumber("DON-" + suffix);
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        b = bidRepository.save(b);

        return conversationRepository.save(new ConversationEntity(b.getId(), sender.getId(), traveler.getId(),
                "conv_" + UUID.randomUUID()));
    }
}
