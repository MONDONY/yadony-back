package com.yadony.api.messaging;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-QQ : contrat de la conversation après l'absence CONFIRMÉE de l'expéditeur. Le bid
 * annulé porte le motif {@code SENDER_NO_SHOW} : la conversation est en lecture seule pour les
 * deux parties et l'appel est coupé, sans la fenêtre de 48 h des autres annulations. Une autre
 * annulation récente garde l'écriture. Firestore est simulé.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationSenderNoShowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @MockitoBean FirestoreService firestoreService;

    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
    }

    @Test
    void absenceExpediteurConfirmee_lectureSeuleEtAppelCoupe_pourLesDeux() throws Exception {
        BidEntity bid = persistBid(BidEntity.REJECTION_SENDER_NO_SHOW);
        ConversationEntity conv = conversationFor(bid);

        for (UserEntity viewer : List.of(sender, traveler)) {
            mockMvc.perform(get("/conversations/{id}", conv.getId()).with(authentication(as(viewer))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.readOnly").value(true))
                    .andExpect(jsonPath("$.callAvailable").value(false))
                    .andExpect(jsonPath("$.parcelStatus").value("CANCELLED"));
        }
        // La conversation reste listée pour l'expéditeur (aucun closedAt).
        mockMvc.perform(get("/conversations").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].readOnly").value(true));
    }

    @Test
    void autreAnnulationRecente_resteOuverteEnEcriture() throws Exception {
        BidEntity bid = persistBid("CANCELLED_BY_SENDER");
        ConversationEntity conv = conversationFor(bid);

        mockMvc.perform(get("/conversations/{id}", conv.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(false));
    }

    private ConversationEntity conversationFor(BidEntity bid) {
        return conversationRepository.saveAndFlush(
                new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(), "conv_noshow_" + bid.getId()));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-noshow-" + UUID.randomUUID());
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

    /** Commande en cours (ACCEPTED) annulée il y a quelques minutes, avec le motif donné. */
    private BidEntity persistBid(String rejectionReason) {
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
        b.setStatus(BidStatus.ACCEPTED);
        b.setStatus(BidStatus.CANCELLED);
        b.setRejectionReason(rejectionReason);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        b.setTrackingNumber("DON-" + suffix);
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b);
    }
}
