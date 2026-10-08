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
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * État du colis dans la liste des conversations (FLUTTER-EZ) : {@code parcelStatus} porte le
 * statut brut du bid et {@code returnPending} le retour en cours, à côté du {@code bidStatus}
 * dérivé que l'app utilise déjà pour ses filtres. Firestore est simulé.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationParcelStatusIntegrationTest {

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
    void list_exposesParcelStatus_andReturnPending_perConversation() throws Exception {
        BidEntity handedOver = persistBid(BidStatus.HANDED_OVER, null);
        BidEntity returning = persistBid(BidStatus.CANCELLED, LocalDateTime.now().plusDays(3));
        conversationFor(handedOver);
        conversationFor(returning);

        mockMvc.perform(get("/conversations").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.bidId == '%s')].parcelStatus", handedOver.getId())
                        .value(hasItem("HANDED_OVER")))
                .andExpect(jsonPath("$.content[?(@.bidId == '%s')].bidStatus", handedOver.getId())
                        .value(hasItem("IN_TRANSIT")))
                .andExpect(jsonPath("$.content[?(@.bidId == '%s')].returnPending", handedOver.getId())
                        .value(hasItem(false)))
                .andExpect(jsonPath("$.content[?(@.bidId == '%s')].parcelStatus", returning.getId())
                        .value(hasItem("CANCELLED")))
                .andExpect(jsonPath("$.content[?(@.bidId == '%s')].returnPending", returning.getId())
                        .value(hasItem(true)));
    }

    @Test
    void returnedParcel_isNoLongerPending_andSingleConversationCarriesTheSameState() throws Exception {
        BidEntity returned = persistBid(BidStatus.CANCELLED, LocalDateTime.now().plusDays(3));
        returned.setReturnedAt(LocalDateTime.now());
        bidRepository.saveAndFlush(returned);
        ConversationEntity conv = conversationFor(returned);

        mockMvc.perform(get("/conversations/{id}", conv.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.returnPending").value(false));
    }

    private ConversationEntity conversationFor(BidEntity bid) {
        return conversationRepository.saveAndFlush(
                new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(), "conv_parcel_" + bid.getId()));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-parcel-" + UUID.randomUUID());
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

    private BidEntity persistBid(BidStatus status, LocalDateTime returnDeadline) {
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
        b.setReturnDeadline(returnDeadline);
        return bidRepository.saveAndFlush(b);
    }
}
