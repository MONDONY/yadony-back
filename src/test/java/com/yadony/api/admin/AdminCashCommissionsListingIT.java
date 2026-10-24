package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminCashCommissionCountsResponse;
import com.yadony.api.admin.dto.AdminCashCommissionResponse;
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
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liste des commissions sur règlement en espèces ({@link AdminFinanceListings#cashCommissions}) : vue « à
 * traiter », statut, recherche (suivi, voyageur, trajet), voyageur, nombres, colis hors périmètre. Chaque test
 * a son voyageur ({@code partyId}) ou son étiquette : la base H2 partagée ne mélange pas les jeux de données.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminCashCommissionsListingIT {

    @Autowired AdminFinanceListings listings;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private int minutes;
    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        tag = "cc" + letters(10);
        minutes = 0;
        sender = persistUser("Aïcha", "Sender" + tag, "s" + tag);
        traveler = persistUser(tag, "Diallo", "t" + tag);
        trip = persistAnnouncement(traveler, "Paris", "Dakar");
    }

    @Test
    void ligne_suiviCorridorVoyageurEtMontants() {
        BidEntity bid = cashBid(trip, "YD" + tag.substring(2, 10), CommissionStatus.CHARGED);
        bid.setNegotiatedGrossEur(new BigDecimal("100.00"));
        bid.setNegotiatedNetEur(new BigDecimal("88.00"));
        bid.setCommissionChargedVia(CommissionChargedVia.WALLET);
        bid.setCommissionRetryCount(2);
        bidRepository.save(bid);

        AdminCashCommissionResponse r = only(null, null, null, traveler.getId());

        assertThat(r.bidId()).isEqualTo(bid.getId());
        assertThat(r.trackingNumber()).isEqualTo("YD" + tag.substring(2, 10));
        assertThat(r.corridor()).isEqualTo("Paris → Dakar");
        assertThat(r.travelerId()).isEqualTo(traveler.getId());
        assertThat(r.travelerName()).isEqualTo(tag + " Diallo (@t" + tag + ")");
        assertThat(r.amountCents()).isEqualTo(10000L);
        assertThat(r.commissionCents()).isEqualTo(1200L);
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.status()).isEqualTo(CommissionStatus.CHARGED);
        assertThat(r.chargedVia()).isEqualTo(CommissionChargedVia.WALLET);
        assertThat(r.retryCount()).isEqualTo(2);
        assertThat(r.createdAt()).isNotNull();
    }

    @Test
    void montantsInconnus_absentsEtNonZero() {
        cashBid(trip, null, CommissionStatus.PENDING);

        AdminCashCommissionResponse r = only(null, null, null, traveler.getId());

        assertThat(r.amountCents()).isNull();
        assertThat(r.commissionCents()).isNull();
        assertThat(r.chargedVia()).isNull();
        assertThat(r.trackingNumber()).isNull();
    }

    @Test
    void vueAttention_failedRequires3dsRefundFailed_etNombres() {
        Map<CommissionStatus, UUID> byStatus = new java.util.EnumMap<>(CommissionStatus.class);
        for (CommissionStatus s : CommissionStatus.values()) {
            byStatus.put(s, cashBid(trip, null, s).getId());
        }

        assertThat(ids(AdminCashCommissionView.ATTENTION, null, null, traveler.getId())).containsExactlyInAnyOrder(
                byStatus.get(CommissionStatus.FAILED), byStatus.get(CommissionStatus.REQUIRES_3DS),
                byStatus.get(CommissionStatus.REFUND_FAILED));
        assertThat(ids(AdminCashCommissionView.ALL, null, null, traveler.getId()))
                .hasSize(CommissionStatus.values().length);
        assertThat(ids(null, null, null, traveler.getId())).hasSize(CommissionStatus.values().length);

        AdminCashCommissionCountsResponse counts = listings.cashCommissionCounts(null, traveler.getId());
        assertThat(counts.attention()).isEqualTo(3);
        assertThat(counts.all()).isEqualTo(CommissionStatus.values().length);
        assertThat(counts.attention()).isEqualTo(
                ids(AdminCashCommissionView.ATTENTION, null, null, traveler.getId()).size());
    }

    @Test
    void statutExact_etSeulementEspecesAvecStatut() {
        UUID charged = cashBid(trip, null, CommissionStatus.CHARGED).getId();
        cashBid(trip, null, CommissionStatus.PENDING);
        // Hors périmètre : espèces sans statut de commission, et colis payé en ligne avec un statut.
        BidEntity noStatus = cashBid(trip, null, CommissionStatus.PENDING);
        jdbc.update("UPDATE bids SET commission_status = NULL WHERE id = ?", noStatus.getId());
        BidEntity online = cashBid(trip, null, CommissionStatus.CHARGED);
        online.setPaymentMethod(PaymentMethod.STRIPE);
        bidRepository.save(online);

        assertThat(ids(null, CommissionStatus.CHARGED, null, traveler.getId())).containsExactly(charged);
        assertThat(ids(null, null, null, traveler.getId())).hasSize(2);
        assertThat(listings.cashCommissionCounts(null, traveler.getId()).all()).isEqualTo(2);
        // Le statut se combine avec la vue.
        assertThat(ids(AdminCashCommissionView.ATTENTION, CommissionStatus.CHARGED, null, traveler.getId())).isEmpty();
    }

    @Test
    void ordre_creationDecroissante() {
        UUID first = cashBid(trip, null, CommissionStatus.PENDING).getId();
        UUID second = cashBid(trip, null, CommissionStatus.PENDING).getId();
        UUID third = cashBid(trip, null, CommissionStatus.PENDING).getId();

        assertThat(ids(null, null, null, traveler.getId())).containsExactly(third, second, first);
        var page = listings.cashCommissions(null, null, null, traveler.getId(), PageRequest.of(1, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AdminCashCommissionResponse::bidId).containsExactly(first);
    }

    @Test
    void q_suiviVoyageurTrajet_accentsEtJetons() {
        AnnouncementEntity abidjan = persistAnnouncement(traveler, "Marseille", "Abidjan");
        UUID dakar = cashBid(trip, "YD" + tag.substring(2, 10), CommissionStatus.PENDING).getId();
        UUID other = cashBid(abidjan, "YE" + tag.substring(2, 10), CommissionStatus.PENDING).getId();
        UserEntity otherTraveler = persistUser("Bintou" + tag, "Koné", "k" + tag);
        UUID elsewhere = cashBid(persistAnnouncement(otherTraveler, "Lyon", "Bamako"), null, CommissionStatus.PENDING)
                .getId();

        assertThat(ids(null, null, "YD" + tag.substring(2, 10).toLowerCase(), null)).containsExactly(dakar);
        assertThat(ids(null, null, "diallo " + tag, null)).containsExactlyInAnyOrder(dakar, other);
        assertThat(ids(null, null, "@t" + tag, null)).containsExactlyInAnyOrder(dakar, other);
        assertThat(ids(null, null, "marseille " + tag, null)).containsExactly(other);
        assertThat(ids(null, null, "bintou" + tag + " KONE", null)).containsExactly(elsewhere);
        assertThat(ids(null, null, "zzz" + tag, null)).isEmpty();
        for (String q : List.of("%", "_", "'", "@")) {
            assertThat(listings.cashCommissions(null, null, q, null, PageRequest.of(0, 50)).getTotalElements())
                    .as("q=%s", q).isZero();
        }
        // Les nombres suivent q et partyId, pas la vue.
        assertThat(listings.cashCommissionCounts(tag, null).all()).isEqualTo(3);
        assertThat(listings.cashCommissionCounts(null, otherTraveler.getId()).all()).isEqualTo(1);
    }

    @Test
    void voyageurSupprime_idPresent_nomAbsent() throws Exception {
        UUID id = cashBid(trip, null, CommissionStatus.FAILED).getId();
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", traveler.getId());

        AdminCashCommissionResponse r = only(null, null, null, traveler.getId());
        assertThat(r.bidId()).isEqualTo(id);
        assertThat(r.travelerId()).isEqualTo(traveler.getId());
        assertThat(r.travelerName()).isNull();

        mockMvc.perform(get("/admin/cash-commissions").param("partyId", traveler.getId().toString())
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].travelerId").value(traveler.getId().toString()))
                .andExpect(jsonPath("$.content[0].travelerName").doesNotExist())
                .andExpect(jsonPath("$.content[0].corridor").value("Paris → Dakar"));
    }

    @Test
    void http_vueStatutEtNombres() throws Exception {
        cashBid(trip, null, CommissionStatus.FAILED);
        cashBid(trip, null, CommissionStatus.CHARGED);
        String party = traveler.getId().toString();

        mockMvc.perform(get("/admin/cash-commissions").param("partyId", party).param("view", "ATTENTION")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
        mockMvc.perform(get("/admin/cash-commissions").param("partyId", party).param("status", "CHARGED")
                        .with(authentication(admin())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/admin/cash-commissions/counts").param("partyId", party).param("view", "ATTENTION")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attention").value(1))
                .andExpect(jsonPath("$.all").value(2));
        mockMvc.perform(get("/admin/cash-commissions").param("view", "BIZARRE").with(authentication(admin())))
                .andExpect(status().isBadRequest());
    }

    // ── Aides ───────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT,
                false, "uid-support-cash");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private List<AdminCashCommissionResponse> rows(AdminCashCommissionView view, CommissionStatus status, String q,
                                                   UUID partyId) {
        return listings.cashCommissions(view, status, q, partyId, PageRequest.of(0, 100)).getContent();
    }

    private List<UUID> ids(AdminCashCommissionView view, CommissionStatus status, String q, UUID partyId) {
        return rows(view, status, q, partyId).stream().map(AdminCashCommissionResponse::bidId).toList();
    }

    private AdminCashCommissionResponse only(AdminCashCommissionView view, CommissionStatus status, String q,
                                             UUID partyId) {
        List<AdminCashCommissionResponse> rows = rows(view, status, q, partyId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** Colis en espèces, créé une minute après le précédent du test. */
    private BidEntity cashBid(AnnouncementEntity announcement, String tracking, CommissionStatus commission) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setTrackingNumber(tracking);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(commission);
        BidEntity saved = bidRepository.save(bid);
        jdbc.update("UPDATE bids SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 20, 8, 0).plusMinutes(minutes++)), saved.getId());
        return saved;
    }

    private UserEntity persistUser(String firstName, String lastName, String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity owner, String departure, String arrival) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity(departure);
        a.setArrivalCity(arrival);
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel(departure);
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel(arrival);
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    /** Lettres seules, minuscules. */
    private static String letters(int length) {
        StringBuilder out = new StringBuilder(length);
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, length).toCharArray()) {
            out.append((char) ('g' + Character.digit(c, 16)));
        }
        return out.toString();
    }
}
