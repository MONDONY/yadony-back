package com.yadony.api.matching.reception;

import com.yadony.api.auth.FirebaseContactService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Colis désigné par l'identifiant d'un compte : rattachement sans recherche Firebase, confirmation qui pose le nom
 * public et copie le numéro seulement s'il n'est pas masqué, aucune fuite du numéro avant la confirmation.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReceptionByUsernameIT {

    private static final String ACCOUNT_PHONE = "+221781112233";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired ReceptionLinker linker;
    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean com.yadony.api.common.RecipientTrust recipientTrust;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity account;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        account = persistUser("Binta", Role.SENDER);
        announcement = persistAnnouncement();
        when(firebaseContact.getContact(account.getFirebaseUid()))
                .thenReturn(new FirebaseContactService.Contact(ACCOUNT_PHONE, null));
    }

    @Test
    void designated_linksPending_noFirebaseLookup_thenConfirmSetsPublicNameAndCopiesPhone() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER, account.getId());

        Optional<BidRecipientLinkEntity> link = linker.linkIfPossible(bid.getId());

        assertThat(link).isPresent();
        assertThat(link.get().getStatus()).isEqualTo(ReceptionLinkStatus.PENDING);
        assertThat(link.get().getRecipientUserId()).isEqualTo(account.getId());
        verify(firebaseContact, never()).findUidByPhone(anyString());
        verify(firebaseContact, never()).findPhoneOwner(anyString());

        // Avant confirmation : ni numéro en base, ni fuite dans les vues expéditeur et voyageur.
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("@binta.sow"))
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.recipientAppStatus").value("PENDING"))
                .andExpect(content().string(not(containsString("781112233"))));
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(content().string(not(containsString("781112233"))));
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getRecipientPhone()).isNull();

        mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(account))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.recipientName").value(account.publicDisplayName()));

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo(account.publicDisplayName());
        assertThat(saved.getRecipientPhone()).isEqualTo(ACCOUNT_PHONE);
        assertThat(saved.getRecipientUserId()).isEqualTo(account.getId());

        // Après confirmation et retrait du colis, l'expéditeur voit le nom public et le numéro copié.
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value(account.publicDisplayName()))
                .andExpect(jsonPath("$.recipientPhone").value(ACCOUNT_PHONE))
                .andExpect(jsonPath("$.recipientAppStatus").value("CONFIRMED"));
    }

    @Test
    void designated_hiddenPhone_confirmDoesNotCopyNumber() throws Exception {
        account.setHidePhoneNumber(true);
        userRepository.save(account);
        BidEntity bid = persistBid(BidStatus.HANDED_OVER, account.getId());
        linker.linkIfPossible(bid.getId());

        mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(account))))
                .andExpect(status().isOk());

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo(account.publicDisplayName());
        assertThat(saved.getRecipientPhone()).isNull();
        verify(firebaseContact, never()).getContact(account.getFirebaseUid());
    }

    @Test
    void designated_hidesNumberAfterConfirmation_copiedNumberStays() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER, account.getId());
        linker.linkIfPossible(bid.getId());
        mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(account))))
                .andExpect(status().isOk());

        account.setHidePhoneNumber(true);
        userRepository.save(account);

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getRecipientPhone()).isEqualTo(ACCOUNT_PHONE);
    }

    @Test
    void designated_trustedAccount_linkedConfirmed_namedAndPhoneCopiedAtLink() {
        when(recipientTrust.isTrusted(sender.getId(), account.getId())).thenReturn(true);
        BidEntity bid = persistBid(BidStatus.ACCEPTED, account.getId());

        Optional<BidRecipientLinkEntity> link = linker.linkIfPossible(bid.getId());

        assertThat(link).get().extracting(BidRecipientLinkEntity::getStatus).isEqualTo(ReceptionLinkStatus.CONFIRMED);
        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo(account.publicDisplayName());
        assertThat(saved.getRecipientPhone()).isEqualTo(ACCOUNT_PHONE);
    }

    @Test
    void designated_trustedAccountHidingPhone_namedButPhoneNull() {
        account.setHidePhoneNumber(true);
        userRepository.save(account);
        when(recipientTrust.isTrusted(sender.getId(), account.getId())).thenReturn(true);
        BidEntity bid = persistBid(BidStatus.ACCEPTED, account.getId());

        linker.linkIfPossible(bid.getId());

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo(account.publicDisplayName());
        assertThat(saved.getRecipientPhone()).isNull();
    }

    @Test
    void designated_ineligibleAccount_noLink() {
        account.setStatus(UserStatus.SUSPENDED);
        userRepository.save(account);
        BidEntity bid = persistBid(BidStatus.ACCEPTED, account.getId());

        assertThat(linker.linkIfPossible(bid.getId())).isEmpty();
        assertThat(linkRepository.findByBidId(bid.getId())).isEmpty();
    }

    @Test
    void designated_deletedAccount_noLink() {
        BidEntity bid = persistBid(BidStatus.ACCEPTED, account.getId());
        account.softDelete();
        userRepository.save(account);

        assertThat(linker.linkIfPossible(bid.getId())).isEmpty();
        assertThat(linkRepository.findByBidId(bid.getId())).isEmpty();
    }

    @Test
    void byNumber_nonRegression_usesFirebaseLookupAndKeepsNameAndPhone() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER, null);
        bid.setRecipientName("Binta Sow");
        bid.setRecipientPhone("+221 78 111 22 33");
        bidRepository.save(bid);
        // Chemin par numéro : propriétaire vérifié par SMS lu par PhoneLookupService (findPhoneOwner), seul un
        // numéro vérifié par SMS ou validé par Yadony rattache le colis.
        when(firebaseContact.findPhoneOwner(ACCOUNT_PHONE))
                .thenReturn(FirebaseContactService.PhoneOwnerLookup.found(account.getFirebaseUid()));

        Optional<BidRecipientLinkEntity> link = linker.linkIfPossible(bid.getId());

        assertThat(link).isPresent();
        assertThat(link.get().getRecipientUserId()).isEqualTo(account.getId());
        mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(account))))
                .andExpect(status().isOk());
        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientName()).isEqualTo("Binta Sow");
        assertThat(saved.getRecipientPhone()).isEqualTo("+221 78 111 22 33");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-reception-username-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement() {
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

    private BidEntity persistBid(BidStatus status, UUID recipientUserId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(status);
        bid.setRecipientUserId(recipientUserId);
        bid.setRecipientName("@binta.sow");
        bid.setRecipientPhone(null);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bid.setTrackingNumber("DON-" + suffix);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setConfirmationCode("123456");
        return bidRepository.save(bid);
    }
}
