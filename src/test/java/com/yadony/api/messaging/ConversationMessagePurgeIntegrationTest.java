package com.yadony.api.messaging;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
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
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Purge des échanges 3 jours après la livraison : sélection SQL des conversations échues,
 * purge (Firestore et R2 simulés) et exposition de l'échéance à l'app.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationMessagePurgeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired ConversationMessagePurgeService purgeService;
    @MockitoBean FirestoreService firestoreService;
    @MockitoBean StorageService storageService;

    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
    }

    @Test
    void onlyConversationsDeliveredThreeDaysAgo_arePurged_andTheAppSeesIt() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConversationEntity due = conversationFor(persistBid(BidStatus.COMPLETED, now.minusDays(4)));
        ConversationEntity recent = conversationFor(persistBid(BidStatus.COMPLETED, now.minusDays(1)));
        ConversationEntity running = conversationFor(persistBid(BidStatus.IN_TRANSIT, null));
        when(firestoreService.purgeMessages(anyString())).thenReturn(true);

        List<UUID> selected = conversationRepository.findMessagesToPurge(now.minusDays(3)).stream()
                .map(ConversationEntity::getId).toList();
        assertThat(selected).contains(due.getId()).doesNotContain(recent.getId(), running.getId());

        purgeService.purgeDue();

        verify(firestoreService).purgeMessages(due.getFirestoreConversationId());
        verify(firestoreService, never()).purgeMessages(recent.getFirestoreConversationId());
        verify(storageService).deleteByPrefix("messaging/" + due.getFirestoreConversationId() + "/");
        assertThat(conversationRepository.findById(due.getId()).orElseThrow().isMessagesPurged()).isTrue();
        assertThat(conversationRepository.findMessagesToPurge(now.minusDays(3)).stream()
                .map(ConversationEntity::getId)).doesNotContain(due.getId());

        mockMvc.perform(get("/conversations/{id}", due.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messagesPurged").value(true))
                .andExpect(jsonPath("$.deliveredAt").exists())
                .andExpect(jsonPath("$.messagesPurgeAt").exists());

        mockMvc.perform(get("/conversations/{id}", recent.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messagesPurged").value(false))
                .andExpect(jsonPath("$.messagesPurgeAt").exists());

        mockMvc.perform(get("/conversations/{id}", running.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messagesPurged").value(false))
                .andExpect(jsonPath("$.deliveredAt").doesNotExist())
                .andExpect(jsonPath("$.messagesPurgeAt").doesNotExist());
    }

    private ConversationEntity conversationFor(BidEntity bid) {
        return conversationRepository.saveAndFlush(
                new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(), "conv_purge_" + bid.getId()));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-purge-" + UUID.randomUUID());
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

    private BidEntity persistBid(BidStatus status, LocalDateTime deliveredAt) {
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
        if (deliveredAt != null) {
            b.markDelivered(deliveredAt);
        }
        return bidRepository.saveAndFlush(b);
    }
}
