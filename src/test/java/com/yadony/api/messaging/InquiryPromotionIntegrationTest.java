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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Promotion d'une conversation de questions, sur le vrai contexte (H2, Firestore simulé). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InquiryPromotionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired ConversationService conversationService;
    @MockitoBean FirestoreService firestoreService;

    UserEntity asker;
    UserEntity owner;
    AnnouncementEntity announcement;
    BidEntity bid;

    @BeforeEach
    void seed() {
        asker = persistUser("Fatou");
        owner = persistUser("Moussa");
        announcement = persistAnnouncement(owner);
        bid = persistBid(asker, announcement);
    }

    @Test
    void promotion_thenAcceptance_yieldsTheSameConversation_andTheCreatorStillSeesIt() throws Exception {
        ConversationEntity inquiry = conversationService.getOrCreateInquiry(asker.getId(), announcement.getId());
        String firestoreId = inquiry.getFirestoreConversationId();

        assertThat(conversationService.promoteInquiryToBid(asker.getId(), announcement.getId(), bid.getId())).isTrue();

        ConversationEntity promoted = conversationRepository.findById(inquiry.getId()).orElseThrow();
        assertThat(promoted.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(promoted.getBidId()).isEqualTo(bid.getId());
        assertThat(promoted.isQuotaLifted()).isTrue();
        assertThat(promoted.getFirestoreConversationId()).isEqualTo(firestoreId);
        verify(firestoreService).updateConversation(firestoreId,
                Map.of("kind", "SENDER_TRAVELER", "quotaLifted", true, "bidId", bid.getId().toString()));

        // Acceptation : createConversationForBid retrouve le fil promu, sans en créer un second.
        ConversationEntity atAcceptance = conversationService
                .createConversationForBid(bid.getId(), asker.getId(), owner.getId());
        assertThat(atAcceptance.getId()).isEqualTo(inquiry.getId());
        assertThat(conversationRepository.findByAskerAndAnnouncement(asker.getId(), announcement.getId()))
                .get().extracting(ConversationEntity::getId).isEqualTo(inquiry.getId());

        // Le créateur voit le fil promu sans que le demandeur ait écrit (le filtre ne vise que INQUIRY).
        mockMvc.perform(get("/conversations").header("X-Dony-Capabilities", "inquiry")
                        .with(authentication(as(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(inquiry.getId().toString())));
    }

    @Test
    void aQuestionDeletedByTheCreator_isNotPromoted_andAcceptanceOpensAFreshThread() {
        ConversationEntity inquiry = conversationService.getOrCreateInquiry(asker.getId(), announcement.getId());
        conversationService.deleteConversation(inquiry.getId(), owner.getId());

        assertThat(conversationService.promoteInquiryToBid(asker.getId(), announcement.getId(), bid.getId())).isFalse();

        ConversationEntity untouched = conversationRepository.findById(inquiry.getId()).orElseThrow();
        assertThat(untouched.getKind()).isEqualTo(ConversationKind.INQUIRY);
        assertThat(untouched.getBidId()).isNull();
        ConversationEntity atAcceptance = conversationService
                .createConversationForBid(bid.getId(), asker.getId(), owner.getId());
        assertThat(atAcceptance.getId()).isNotEqualTo(inquiry.getId());
        assertThat(atAcceptance.getBidId()).isEqualTo(bid.getId());
        assertThat(atAcceptance.getTravelerDeletedAt()).isNull();
    }

    @Test
    void withoutAnInquiry_nothingHappens() {
        assertThat(conversationService.promoteInquiryToBid(asker.getId(), announcement.getId(), bid.getId())).isFalse();
        assertThat(conversationRepository.findByAskerAndAnnouncement(asker.getId(), announcement.getId())).isEmpty();
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-promo-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler) {
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
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UserEntity sender, AnnouncementEntity a) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.PENDING);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b);
    }
}
