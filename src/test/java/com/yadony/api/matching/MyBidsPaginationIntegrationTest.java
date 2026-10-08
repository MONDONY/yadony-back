package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /bids/me} paginé (test de charge du 08/10/2026 : 741 colis, 1,47 Mo, une
 * dizaine de requêtes par colis). Sans {@code page}, l'ancien contrat (tableau complet)
 * reste servi aux versions de l'app déjà installées.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /bids/me : pagination, filtres, ancien contrat")
class MyBidsPaginationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;

    private UserEntity sender;
    private AnnouncementEntity trip;
    private AnnouncementEntity otherTrip;
    private BidEntity accepted;
    private BidEntity completed;
    private BidEntity cancelled;
    private BidEntity onOtherTrip;
    private BidEntity negotiating;

    @BeforeEach
    void seed() {
        UserEntity traveler = persistUser("Moussa");
        sender = persistUser("Awa");
        trip = persistAnnouncement(traveler, 0);
        otherTrip = persistAnnouncement(traveler, 1);

        // Créés dans l'ordre : le plus récent sort en premier.
        completed = persistBid(trip, sender, BidStatus.COMPLETED, false);
        cancelled = persistBid(trip, sender, BidStatus.CANCELLED, false);
        onOtherTrip = persistBid(otherTrip, sender, BidStatus.IN_TRANSIT, false);
        accepted = persistBid(trip, sender, BidStatus.ACCEPTED, false);
        // Jamais listés : discussions de prix, colis retiré par l'expéditeur, colis d'un autre.
        negotiating = persistBid(trip, sender, BidStatus.NEGOTIATING, false);
        persistBid(trip, sender, BidStatus.NEGOTIATION_CLOSED, false);
        persistBid(otherTrip, sender, BidStatus.REJECTED, true);
        persistBid(trip, persistUser("Tiers"), BidStatus.ACCEPTED, false);
    }

    @Test
    void sansPage_ancienContrat_tableauComplet() throws Exception {
        mockMvc.perform(get("/bids/me").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(
                        ids(accepted, onOtherTrip, cancelled, completed))));
    }

    @Test
    void pages_duPlusRecentAuPlusAncien_avecMetadonnees() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0").param("size", "3")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(accepted.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/bids/me").param("page", "1").param("size", "3")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(completed.getId().toString()))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void filtreParStatuts_listeSepareeParDesVirgules() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0").param("status", "ACCEPTED,COMPLETED")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(ids(accepted, completed))))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void statutsDeNegociationDemandes_restentExclus() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0")
                        .param("status", "NEGOTIATING,NEGOTIATION_CLOSED")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void includeNegotiating_ajouteLesOffresOuvertes_pasLesFilsClos() throws Exception {
        // FLUTTER-GC : l'accueil demande les colis en cours ET les offres envoyées.
        mockMvc.perform(get("/bids/me").param("page", "0").param("status", "ACCEPTED")
                        .param("includeNegotiating", "true")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(ids(accepted, negotiating))))
                .andExpect(jsonPath("$.content[?(@.status == 'NEGOTIATING')].announcementId")
                        .value(containsInAnyOrder(trip.getId().toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void sansIncludeNegotiating_contratInchange() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0").param("status", "ACCEPTED")
                        .param("includeNegotiating", "false")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(ids(accepted))));
    }

    @Test
    void filtreParTrajet_pourLeControleDejaUneDemande() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0")
                        .param("announcementId", otherTrip.getId().toString())
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(ids(onOtherTrip))));
    }

    @Test
    void tailleDemandeeTropGrande_plafonnee() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0").param("size", "500")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(BidService.MY_BIDS_MAX_PAGE_SIZE));
    }

    @Test
    void statutInconnu_400() throws Exception {
        mockMvc.perform(get("/bids/me").param("page", "0").param("status", "NIMPORTE")
                        .with(authentication(as(sender))))
                .andExpect(status().isBadRequest());
    }

    private static String[] ids(BidEntity... bids) {
        return java.util.Arrays.stream(bids).map(b -> b.getId().toString()).toArray(String[]::new);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler, int offsetDays) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureCountryCode("FR");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(7 + offsetDays));
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

    private BidEntity persistBid(AnnouncementEntity a, UserEntity owner, BidStatus status, boolean deletedBySender) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(owner.getId());
        b.setWeightKg(new BigDecimal("2.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        b.setDeletedBySender(deletedBySender);
        BidEntity saved = bidRepository.saveAndFlush(b);
        // createdAt à la milliseconde : un écart garantit l'ordre du plus récent au plus ancien.
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return saved;
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-bidsme-" + UUID.randomUUID());
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
}
