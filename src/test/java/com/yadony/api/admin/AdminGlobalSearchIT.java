package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
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
import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recherches des listes admin utilisées par la barre de recherche globale : colis (n° de suivi
 * ou identifiant), trajets (ville, nom du voyageur), tickets support (sujet, nom, identifiant) et
 * remboursements wallet (nom, comptes supprimés compris). Chaque test isole ses données par un
 * marqueur unique dans les noms et villes.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminGlobalSearchIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired SupportTicketRepository ticketRepository;
    @Autowired WalletRefundRequestRepository refundRepository;

    private String tag;
    private UserEntity sender;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        tag = "q" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        sender = persistUser("Awa" + tag, "Diallo", null);
        traveler = persistUser("Moussa" + tag, "Keita", null);
    }

    @Test
    void colis_parNumeroDeSuiviOuIdentifiant() throws Exception {
        BidEntity bid = persistBid(persistAnnouncement("Paris", "Dakar").getId(), "DON-" + tag.substring(1).toUpperCase());

        mockMvc.perform(get("/admin/bids").param("query", "don-" + tag.substring(1)).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(bid.getId().toString())));
        mockMvc.perform(get("/admin/bids").param("query", bid.getId().toString()).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(bid.getId().toString()));
    }

    @Test
    void trajets_parVilleNomDuVoyageurOuIdentifiant() throws Exception {
        AnnouncementEntity ann = persistAnnouncement("Ville" + tag, "Dakar");
        AnnouncementEntity other = persistAnnouncement("Lyon", "Bamako");

        mockMvc.perform(get("/admin/announcements").param("query", "ville" + tag).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ann.getId().toString()));
        mockMvc.perform(get("/admin/announcements").param("query", "moussa" + tag).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(ann.getId().toString())))
                .andExpect(jsonPath("$.content[*].id", hasItem(other.getId().toString())));
        mockMvc.perform(get("/admin/announcements").param("query", other.getId().toString()).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(other.getId().toString()));
    }

    @Test
    void ticketsSupport_parSujetNomOuIdentifiant() throws Exception {
        SupportTicketEntity ticket = persistTicket(sender.getId(), "Colis bloqué " + tag);
        SupportTicketEntity unrelated = persistTicket(traveler.getId(), "Autre sujet");

        mockMvc.perform(get("/admin/support/tickets").param("query", "BLOQUÉ " + tag).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ticket.getId().toString()));
        mockMvc.perform(get("/admin/support/tickets").param("query", "awa" + tag).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(ticket.getId().toString())))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(unrelated.getId().toString()))));
        mockMvc.perform(get("/admin/support/tickets").param("query", unrelated.getId().toString()).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(unrelated.getId().toString()));
    }

    @Test
    void remboursementsWallet_parNom_compteSupprimeCompris_etParIdentifiant() throws Exception {
        UserEntity deleted = persistUser("Fatou" + tag, "Sow", LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        WalletRefundRequestEntity refunded = persistRefund(deleted.getId(), WalletRefundRequestStatus.REFUNDED);
        WalletRefundRequestEntity pending = persistRefund(sender.getId(), WalletRefundRequestStatus.PENDING);

        mockMvc.perform(get("/admin/wallet-refund-requests").param("query", "fatou" + tag).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(refunded.getId().toString()))
                .andExpect(jsonPath("$.content[0].userName").value("Fatou" + tag + " Sow"))
                .andExpect(jsonPath("$.content[0].origin").value("ACCOUNT_DELETION"));
        mockMvc.perform(get("/admin/wallet-refund-requests").param("query", "fatou" + tag)
                        .param("status", "PENDING").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/admin/wallet-refund-requests").param("query", sender.getId().toString())
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pending.getId().toString()));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-search");
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (AdminPermission p : AdminPermissions.effective(AdminRole.ADMIN, Map.of())) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private UserEntity persistUser(String firstName, String lastName, LocalDateTime deletedAt) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-search-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        u.setDeletedAt(deletedAt);
        return userRepository.saveAndFlush(u);
    }

    private AnnouncementEntity persistAnnouncement(String from, String to) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity(from);
        a.setArrivalCity(to);
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
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity persistBid(UUID announcementId, String trackingNumber) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.ACCEPTED);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber(trackingNumber);
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b);
    }

    private SupportTicketEntity persistTicket(UUID userId, String subject) {
        SupportTicketEntity t = new SupportTicketEntity();
        t.setUserId(userId);
        t.setCategory("OTHER");
        t.setSubject(subject);
        t.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
        return ticketRepository.saveAndFlush(t);
    }

    private WalletRefundRequestEntity persistRefund(UUID userId, WalletRefundRequestStatus status) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setUserId(userId);
        r.setCurrency("EUR");
        r.setAmount(new BigDecimal("9.05"));
        r.setStatus(status);
        r.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        r.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        return refundRepository.saveAndFlush(r);
    }
}
