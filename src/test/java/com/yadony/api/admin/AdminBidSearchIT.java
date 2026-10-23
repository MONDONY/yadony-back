package com.yadony.api.admin;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requête de liste des colis de l'admin ({@link BidRepository#searchAdmin}) : recherche par jetons
 * repliés, identifiants exacts, filtres, vues rapides, tri et rang d'alerte, sur la base H2 de test.
 * La base est partagée entre tests : chaque assertion se restreint aux colis créés par le test
 * (filtre par annonce, ou appartenance).
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminBidSearchIT {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = NOW_UTC.toLocalDate();

    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        traveler = saveUser("Mamadou", "Kouyaté");
        sender = saveUser("Aïcha", "Diallo");
    }

    // ---------------------------------------------------------------- helpers

    private UserEntity saveUser(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return userRepository.save(u);
    }

    private AnnouncementEntity announcement(UserEntity owner, String from, String to,
                                            LocalDate departureDate, LocalDate arrivalDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity(from);
        a.setArrivalCity(to);
        a.setDepartureDate(departureDate);
        a.setArrivalDate(arrivalDate);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Départ");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Arrivée");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.CASH)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private AnnouncementEntity trip() {
        return announcement(traveler, "Paris", "Dakar", TODAY.plusDays(10), null);
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity from, BidStatus status) {
        return bid(a, from, status, b -> { });
    }

    private BidEntity bid(AnnouncementEntity a, UserEntity from, BidStatus status, Consumer<BidEntity> customize) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(from.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        customize.accept(b);
        return bidRepository.save(b);
    }

    private void setBidTime(BidEntity b, String column, Instant at) {
        jdbc.update("update bids set " + column + " = ? where id = ?", ts(at), b.getId());
    }

    private DisputeEntity dispute(BidEntity b, String status) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(b.getId());
        d.setStatus(status);
        return disputeRepository.save(d);
    }

    /** Même conversion que {@code AdminUserParcelsIT} : relu par Hibernate comme l'instant UTC. */
    private static LocalDateTime ts(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC).atZone(java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Paramètres de la liste avec leurs valeurs par défaut (vue et périmètre ALL, horloge fixée). */
    private static final class Q {
        AdminBidView view = AdminBidView.ALL;
        BidStatus status;
        UUID announcementId;
        String query;
        AdminBidScope scope = AdminBidScope.ALL;
        boolean phones;
        PaymentMethod paymentMethod;
        String content;
        AdminBidAlert alert;
        LocalDateTime from;
        LocalDateTime to;

        Q view(AdminBidView v) { view = v; return this; }
        Q status(BidStatus s) { status = s; return this; }
        Q on(AnnouncementEntity a) { announcementId = a.getId(); return this; }
        Q query(String q) { query = q; return this; }
        Q scope(AdminBidScope s) { scope = s; return this; }
        Q phones(boolean p) { phones = p; return this; }
        Q paymentMethod(PaymentMethod m) { paymentMethod = m; return this; }
        Q content(String c) { content = c; return this; }
        Q alert(AdminBidAlert a) { alert = a; return this; }
        Q from(LocalDateTime f) { from = f; return this; }
        Q to(LocalDateTime t) { to = t; return this; }

        AdminBidQuery build() {
            return AdminBidQuery.of(view, status, announcementId, query, scope, phones, paymentMethod,
                    content, alert, from, to, NOW_UTC);
        }
    }

    private static Q q() {
        return new Q();
    }

    private List<UUID> ids(Q q) {
        return bidRepository.searchAdmin(q.build(), PageRequest.of(0, 200))
                .map(BidEntity::getId).getContent();
    }

    private long total(Q q) {
        return bidRepository.searchAdmin(q.build(), PageRequest.of(0, 1)).getTotalElements();
    }

    private Map<UUID, Integer> ranks(BidEntity... bids) {
        return bidRepository.alertRanks(Stream.of(bids).map(BidEntity::getId).toList(), NOW_UTC,
                        AdminBidQuery.lateBefore(NOW_UTC))
                .stream().collect(Collectors.toMap(BidRepository.AlertRankRow::getBidId,
                        BidRepository.AlertRankRow::getRank));
    }

    // ---------------------------------------------------------------- recherche

    @Test
    void search_byTrackingNumber_partial_matches() {
        AnnouncementEntity a = trip();
        BidEntity wanted = bid(a, sender, BidStatus.PENDING);
        bid(a, sender, BidStatus.PENDING);
        String part = wanted.getTrackingNumber().substring(3, 9).toLowerCase(Locale.ROOT);

        assertThat(ids(q().on(a).query(part))).containsExactly(wanted.getId());
        assertThat(ids(q().on(a).query(part).scope(AdminBidScope.TRACKING))).containsExactly(wanted.getId());
    }

    @Test
    void search_bySenderName_ignoresCaseAndAccents() {
        AnnouncementEntity a = trip();
        BidEntity aicha = bid(a, sender, BidStatus.PENDING);
        bid(a, saveUser("Bintou", "Sarr"), BidStatus.PENDING);

        assertThat(ids(q().on(a).query("AICHA"))).containsExactly(aicha.getId());
        assertThat(ids(q().on(a).query("aïcha"))).containsExactly(aicha.getId());
        assertThat(ids(q().on(a).query("AÏCHA"))).containsExactly(aicha.getId());
        assertThat(ids(q().on(a).query("aicha diallo").scope(AdminBidScope.SENDER))).containsExactly(aicha.getId());
    }

    @Test
    void search_byTravelerName() {
        AnnouncementEntity mine = trip();
        AnnouncementEntity other = announcement(saveUser("Ousmane", "Traoré"), "Lyon", "Bamako",
                TODAY.plusDays(3), null);
        BidEntity wanted = bid(mine, sender, BidStatus.PENDING);
        BidEntity notWanted = bid(other, sender, BidStatus.PENDING);

        List<UUID> found = ids(q().query("KOUYATE").scope(AdminBidScope.TRAVELER));

        assertThat(found).contains(wanted.getId()).doesNotContain(notWanted.getId());
    }

    @Test
    void search_byRecipientName() {
        AnnouncementEntity a = trip();
        BidEntity wanted = bid(a, sender, BidStatus.PENDING, b -> b.setRecipientName("Ndèye Sow"));
        bid(a, sender, BidStatus.PENDING, b -> b.setRecipientName("Khady Ba"));

        assertThat(ids(q().on(a).query("ndeye"))).containsExactly(wanted.getId());
        assertThat(ids(q().on(a).query("NDEYE sow").scope(AdminBidScope.RECIPIENT))).containsExactly(wanted.getId());
    }

    @Test
    void search_byTripCities_twoWordsBothRequired() {
        AnnouncementEntity a = trip();
        BidEntity parcel = bid(a, sender, BidStatus.PENDING);

        assertThat(ids(q().on(a).query("paris dakar"))).containsExactly(parcel.getId());
        assertThat(ids(q().on(a).query("Paris Dakar").scope(AdminBidScope.TRIP))).containsExactly(parcel.getId());
        assertThat(ids(q().on(a).query("paris abidjan"))).isEmpty();
    }

    @Test
    void search_scopeSender_doesNotMatchTravelerName() {
        AnnouncementEntity a = trip();
        BidEntity parcel = bid(a, sender, BidStatus.PENDING);

        assertThat(ids(q().on(a).query("mamadou").scope(AdminBidScope.SENDER))).isEmpty();
        assertThat(ids(q().on(a).query("mamadou").scope(AdminBidScope.TRAVELER))).containsExactly(parcel.getId());
        assertThat(ids(q().on(a).query("mamadou"))).containsExactly(parcel.getId());
    }

    @Test
    void search_byBidId_exact() {
        AnnouncementEntity a = trip();
        BidEntity wanted = bid(a, sender, BidStatus.PENDING);
        bid(a, sender, BidStatus.PENDING);

        assertThat(ids(q().query(wanted.getId().toString().toUpperCase(Locale.ROOT))))
                .containsExactly(wanted.getId());
        // Un fragment d'identifiant n'est pas un identifiant.
        assertThat(ids(q().on(a).query(wanted.getId().toString().substring(0, 8)))).isEmpty();
    }

    @Test
    void search_byAnnouncementId_exact() {
        AnnouncementEntity a = trip();
        BidEntity first = bid(a, sender, BidStatus.PENDING);
        BidEntity second = bid(a, sender, BidStatus.ACCEPTED);

        assertThat(ids(q().query(a.getId().toString()))).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void search_byPaymentId_exact() {
        AnnouncementEntity a = trip();
        BidEntity paid = bid(a, sender, BidStatus.ACCEPTED);
        bid(a, sender, BidStatus.ACCEPTED);
        PaymentEntity p = new PaymentEntity();
        p.setBidId(paid.getId());
        p.setAmount(new BigDecimal("24.00"));
        p.setCommissionAmount(new BigDecimal("1.20"));
        p = paymentRepository.save(p);

        assertThat(ids(q().query(p.getId().toString()))).containsExactly(paid.getId());
    }

    @Test
    void search_byDisputeId_exact() {
        AnnouncementEntity a = trip();
        BidEntity disputed = bid(a, sender, BidStatus.COMPLETED);
        bid(a, sender, BidStatus.COMPLETED);
        DisputeEntity d = dispute(disputed, "RESOLVED");

        assertThat(ids(q().query(d.getId().toString()))).containsExactly(disputed.getId());
    }

    @Test
    void search_percentAndUnderscore_areLiteralText() {
        AnnouncementEntity a = trip();
        bid(a, sender, BidStatus.PENDING, b -> b.setRecipientName("xqzay"));
        BidEntity literal = bid(a, sender, BidStatus.PENDING, b -> b.setRecipientName("xqzy"));

        // En motif, « xqz_y » et « xqz%y » trouveraient aussi « xqzay » : ce sont des textes.
        assertThat(ids(q().on(a).query("xqz_y"))).containsExactly(literal.getId());
        assertThat(ids(q().on(a).query("xqz%y"))).containsExactly(literal.getId());
    }

    @Test
    void search_recipientPhone_onlyWhenPhonesAllowed() {
        AnnouncementEntity a = trip();
        BidEntity parcel = bid(a, sender, BidStatus.PENDING, b -> b.setRecipientPhone("+221 77 123-45.67"));

        assertThat(ids(q().on(a).query("771234567").scope(AdminBidScope.RECIPIENT).phones(false))).isEmpty();
        assertThat(ids(q().on(a).query("771234567").scope(AdminBidScope.RECIPIENT).phones(true)))
                .containsExactly(parcel.getId());
        assertThat(ids(q().on(a).query("+221771234567").phones(true))).containsExactly(parcel.getId());
        assertThat(ids(q().on(a).query("771234567").phones(false))).isEmpty();
    }

    // ---------------------------------------------------------------- filtres

    @Test
    void filter_paymentMethod() {
        AnnouncementEntity a = trip();
        BidEntity cash = bid(a, sender, BidStatus.PENDING, b -> b.setPaymentMethod(PaymentMethod.CASH));
        BidEntity card = bid(a, sender, BidStatus.PENDING, b -> b.setPaymentMethod(PaymentMethod.STRIPE));

        assertThat(ids(q().on(a).paymentMethod(PaymentMethod.CASH))).containsExactly(cash.getId());
        assertThat(ids(q().on(a).paymentMethod(PaymentMethod.STRIPE))).containsExactly(card.getId());
    }

    @Test
    void filter_content() {
        AnnouncementEntity a = trip();
        BidEntity clothes = bid(a, sender, BidStatus.PENDING, b -> b.setContentCategory("Vêtements enfants"));
        bid(a, sender, BidStatus.PENDING, b -> b.setContentCategory("Épices"));

        assertThat(ids(q().on(a).content("VETEMENT"))).containsExactly(clothes.getId());
        assertThat(ids(q().on(a).content("vêtements enf"))).containsExactly(clothes.getId());
    }

    @Test
    void filter_dateRange() {
        AnnouncementEntity a = trip();
        BidEntity old = bid(a, sender, BidStatus.PENDING);
        BidEntity recent = bid(a, sender, BidStatus.PENDING);
        setBidTime(old, "created_at", NOW.minus(Duration.ofDays(5)));
        setBidTime(recent, "created_at", NOW.minus(Duration.ofDays(1)));

        assertThat(ids(q().on(a).from(NOW_UTC.minusDays(2)))).containsExactly(recent.getId());
        assertThat(ids(q().on(a).to(NOW_UTC.minusDays(3)))).containsExactly(old.getId());
        assertThat(ids(q().on(a).from(NOW_UTC.minusDays(6)).to(NOW_UTC))).hasSize(2);
    }

    @Test
    void filter_status_negotiating_explicit_isReturned() {
        AnnouncementEntity a = trip();
        BidEntity thread = bid(a, sender, BidStatus.NEGOTIATING);
        BidEntity closed = bid(a, sender, BidStatus.NEGOTIATION_CLOSED);
        bid(a, sender, BidStatus.PENDING);

        assertThat(ids(q().on(a).status(BidStatus.NEGOTIATING))).containsExactly(thread.getId());
        assertThat(ids(q().on(a).status(BidStatus.NEGOTIATION_CLOSED))).containsExactly(closed.getId());
    }

    @Test
    void default_excludesNegotiationThreads() {
        AnnouncementEntity a = trip();
        BidEntity real = bid(a, sender, BidStatus.PENDING);
        bid(a, sender, BidStatus.NEGOTIATING);
        bid(a, sender, BidStatus.NEGOTIATION_CLOSED);

        assertThat(ids(q().on(a))).containsExactly(real.getId());
        assertThat(total(q().on(a))).isEqualTo(1);
    }

    @Test
    void deletedSender_parcelStillListed() {
        AnnouncementEntity a = trip();
        BidEntity parcel = bid(a, sender, BidStatus.PENDING);
        jdbc.update("update users set deleted_at = ? where id = ?", ts(NOW), sender.getId());

        assertThat(ids(q().on(a))).containsExactly(parcel.getId());
    }

    @Test
    void filter_alert_keepsOnlyThatRank() {
        AnnouncementEntity a = trip();
        BidEntity noShow = bid(a, sender, BidStatus.NO_SHOW);
        BidEntity disputedNoShow = bid(a, sender, BidStatus.NO_SHOW);
        dispute(disputedNoShow, "OPEN");

        assertThat(ids(q().on(a).alert(AdminBidAlert.NO_SHOW))).containsExactly(noShow.getId());
        assertThat(ids(q().on(a).alert(AdminBidAlert.DISPUTE_OPEN))).containsExactly(disputedNoShow.getId());
    }

    // ---------------------------------------------------------------- vues

    @Test
    void view_todo_includesOpenDisputeOnCompletedBid() {
        AnnouncementEntity a = trip();
        BidEntity open = bid(a, sender, BidStatus.COMPLETED);
        dispute(open, "OPEN");
        BidEntity resolved = bid(a, sender, BidStatus.COMPLETED);
        dispute(resolved, "RESOLVED");
        BidEntity deleted = bid(a, sender, BidStatus.COMPLETED);
        DisputeEntity gone = dispute(deleted, "OPEN");
        jdbc.update("update disputes set deleted_at = ? where id = ?", ts(NOW), gone.getId());
        bid(a, sender, BidStatus.COMPLETED);

        assertThat(ids(q().on(a).view(AdminBidView.TODO))).containsExactly(open.getId());
    }

    @Test
    void view_todo_includesNoShow() {
        AnnouncementEntity a = trip();
        BidEntity noShow = bid(a, sender, BidStatus.NO_SHOW);
        bid(a, sender, BidStatus.PENDING);

        assertThat(ids(q().on(a).view(AdminBidView.TODO))).containsExactly(noShow.getId());
    }

    @Test
    void view_todo_unpaidOver24h_notUnder24h() {
        AnnouncementEntity a = trip();
        BidEntity stale = bid(a, sender, BidStatus.AWAITING_PAYMENT);
        BidEntity fresh = bid(a, sender, BidStatus.AWAITING_PAYMENT);
        setBidTime(stale, "updated_at", NOW.minus(Duration.ofHours(25)));
        setBidTime(fresh, "updated_at", NOW.minus(Duration.ofHours(23)));

        assertThat(ids(q().on(a).view(AdminBidView.TODO))).containsExactly(stale.getId());
    }

    @Test
    void view_todo_late_onlyEnRouteStatusesPastArrivalDate() {
        // now = 23/10 : lateBefore = 22/10. Arrivée le 21 → en retard ; arrivée le jour même → non.
        AnnouncementEntity arrivedTwoDaysAgo = announcement(traveler, "Paris", "Dakar", TODAY.minusDays(3), TODAY.minusDays(2));
        AnnouncementEntity arrivesToday = announcement(traveler, "Paris", "Dakar", TODAY.minusDays(1), TODAY);
        AnnouncementEntity arrivedYesterday = announcement(traveler, "Paris", "Dakar", TODAY.minusDays(2), TODAY.minusDays(1));
        AnnouncementEntity noArrivalDate = announcement(traveler, "Paris", "Dakar", TODAY.minusDays(3), null);

        BidEntity lateInTransit = bid(arrivedTwoDaysAgo, sender, BidStatus.IN_TRANSIT);
        BidEntity lateHandedOver = bid(arrivedTwoDaysAgo, sender, BidStatus.HANDED_OVER);
        BidEntity lateByDeparture = bid(noArrivalDate, sender, BidStatus.ACCEPTED);
        BidEntity arrivedStatus = bid(arrivedTwoDaysAgo, sender, BidStatus.ARRIVED);
        BidEntity delivered = bid(arrivedTwoDaysAgo, sender, BidStatus.IN_TRANSIT);
        setBidTime(delivered, "delivered_at", NOW.minus(Duration.ofHours(30)));
        BidEntity sameDay = bid(arrivesToday, sender, BidStatus.IN_TRANSIT);
        BidEntity graceDay = bid(arrivedYesterday, sender, BidStatus.IN_TRANSIT);

        List<UUID> todo = ids(q().view(AdminBidView.TODO));

        assertThat(todo).contains(lateInTransit.getId(), lateHandedOver.getId(), lateByDeparture.getId())
                .doesNotContain(arrivedStatus.getId(), delivered.getId(), sameDay.getId(), graceDay.getId());
        assertThat(ranks(lateInTransit, sameDay)).containsExactly(Map.entry(lateInTransit.getId(), 4));
    }

    @Test
    void view_active() {
        AnnouncementEntity a = trip();
        List<UUID> active = Stream.of(BidStatus.AWAITING_PAYMENT, BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED,
                        BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED)
                .map(s -> bid(a, sender, s).getId()).toList();
        Stream.of(BidStatus.COMPLETED, BidStatus.NO_SHOW, BidStatus.REJECTED, BidStatus.NEGOTIATING)
                .forEach(s -> bid(a, sender, s));

        assertThat(ids(q().on(a).view(AdminBidView.ACTIVE))).containsExactlyInAnyOrderElementsOf(active);
    }

    @Test
    void view_recent_only72hDelivered() {
        AnnouncementEntity a = trip();
        BidEntity recent = bid(a, sender, BidStatus.COMPLETED);
        setBidTime(recent, "delivered_at", NOW.minus(Duration.ofHours(71)));
        BidEntity old = bid(a, sender, BidStatus.COMPLETED);
        setBidTime(old, "delivered_at", NOW.minus(Duration.ofHours(73)));
        bid(a, sender, BidStatus.COMPLETED);
        BidEntity notCompleted = bid(a, sender, BidStatus.ARRIVED);
        setBidTime(notCompleted, "delivered_at", NOW.minus(Duration.ofHours(1)));

        assertThat(ids(q().on(a).view(AdminBidView.RECENT))).containsExactly(recent.getId());
    }

    @Test
    void view_completed() {
        AnnouncementEntity a = trip();
        BidEntity delivered = bid(a, sender, BidStatus.COMPLETED);
        setBidTime(delivered, "delivered_at", NOW.minus(Duration.ofDays(30)));
        BidEntity undated = bid(a, sender, BidStatus.COMPLETED);
        bid(a, sender, BidStatus.ARRIVED);

        assertThat(ids(q().on(a).view(AdminBidView.COMPLETED)))
                .containsExactlyInAnyOrder(delivered.getId(), undated.getId());
    }

    @Test
    void view_closed() {
        AnnouncementEntity a = trip();
        List<UUID> closed = Stream.of(BidStatus.REJECTED, BidStatus.CANCELLED, BidStatus.EXPIRED,
                        BidStatus.PARCEL_REFUSED, BidStatus.NO_SHOW)
                .map(s -> bid(a, sender, s).getId()).toList();
        Stream.of(BidStatus.COMPLETED, BidStatus.PENDING, BidStatus.NEGOTIATION_CLOSED)
                .forEach(s -> bid(a, sender, s));

        assertThat(ids(q().on(a).view(AdminBidView.CLOSED))).containsExactlyInAnyOrderElementsOf(closed);
    }

    @Test
    void view_all() {
        AnnouncementEntity a = trip();
        List<UUID> all = Stream.of(BidStatus.values())
                .filter(s -> !BidStatus.NEGOTIATION_STATUSES.contains(s))
                .map(s -> bid(a, sender, s).getId()).toList();
        bid(a, sender, BidStatus.NEGOTIATING);
        bid(a, sender, BidStatus.NEGOTIATION_CLOSED);

        assertThat(ids(q().on(a).view(AdminBidView.ALL))).containsExactlyInAnyOrderElementsOf(all);
        assertThat(total(q().on(a))).isEqualTo(all.size());
    }

    // ---------------------------------------------------------------- tri

    @Test
    void order_todo_byAlertRankThenOldestFirst() {
        AnnouncementEntity a = announcement(traveler, "Paris", "Dakar", TODAY.minusDays(5), TODAY.minusDays(4));
        BidEntity late = bid(a, sender, BidStatus.IN_TRANSIT);
        BidEntity unpaid = bid(a, sender, BidStatus.AWAITING_PAYMENT);
        BidEntity noShowNewer = bid(a, sender, BidStatus.NO_SHOW);
        BidEntity noShowOlder = bid(a, sender, BidStatus.NO_SHOW);
        BidEntity disputed = bid(a, sender, BidStatus.COMPLETED);
        dispute(disputed, "OPEN");
        setBidTime(late, "created_at", NOW.minus(Duration.ofDays(10)));
        setBidTime(unpaid, "created_at", NOW.minus(Duration.ofDays(9)));
        setBidTime(unpaid, "updated_at", NOW.minus(Duration.ofDays(2)));
        setBidTime(noShowNewer, "created_at", NOW.minus(Duration.ofDays(2)));
        setBidTime(noShowOlder, "created_at", NOW.minus(Duration.ofDays(3)));
        setBidTime(disputed, "created_at", NOW.minus(Duration.ofDays(1)));

        assertThat(ids(q().on(a).view(AdminBidView.TODO))).containsExactly(
                disputed.getId(), noShowOlder.getId(), noShowNewer.getId(), unpaid.getId(), late.getId());
    }

    @Test
    void order_default_newestFirstWithIdTieBreak() {
        AnnouncementEntity a = trip();
        BidEntity oldest = bid(a, sender, BidStatus.PENDING);
        BidEntity tieOne = bid(a, sender, BidStatus.PENDING);
        BidEntity tieTwo = bid(a, sender, BidStatus.PENDING);
        setBidTime(oldest, "created_at", NOW.minus(Duration.ofDays(2)));
        setBidTime(tieOne, "created_at", NOW.minus(Duration.ofHours(1)));
        setBidTime(tieTwo, "created_at", NOW.minus(Duration.ofHours(1)));
        // PostgreSQL et H2 comparent les UUID octet par octet : l'ordre de leur forme texte.
        List<UUID> ties = Stream.of(tieOne.getId(), tieTwo.getId())
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        assertThat(ids(q().on(a))).containsExactly(ties.get(0), ties.get(1), oldest.getId());
        assertThat(ids(q().on(a).view(AdminBidView.ACTIVE))).containsExactly(ties.get(0), ties.get(1), oldest.getId());
    }

    // ---------------------------------------------------------------- alertes

    @Test
    void alertRanks_returnsOneRankPerBid_mostUrgent() {
        AnnouncementEntity a = trip();
        BidEntity both = bid(a, sender, BidStatus.NO_SHOW);
        dispute(both, "OPEN");
        dispute(both, "OPEN");
        BidEntity noShow = bid(a, sender, BidStatus.NO_SHOW);
        BidEntity unpaid = bid(a, sender, BidStatus.AWAITING_PAYMENT);
        setBidTime(unpaid, "updated_at", NOW.minus(Duration.ofHours(48)));

        assertThat(ranks(both, noShow, unpaid)).containsOnly(
                Map.entry(both.getId(), AdminBidAlert.DISPUTE_OPEN.rank()),
                Map.entry(noShow.getId(), AdminBidAlert.NO_SHOW.rank()),
                Map.entry(unpaid.getId(), AdminBidAlert.PAYMENT_PENDING.rank()));
        // Un seul passage dans « À traiter » malgré deux litiges et le no-show.
        assertThat(ids(q().on(a).view(AdminBidView.TODO))).containsOnlyOnce(both.getId());
        assertThat(total(q().on(a).view(AdminBidView.TODO))).isEqualTo(3);
    }

    /** Choix documenté : un colis sans alerte n'a pas de ligne (pas de rang 0 renvoyé). */
    @Test
    void alertRanks_noAlert_returnsZeroOrAbsent() {
        AnnouncementEntity a = trip();
        BidEntity calm = bid(a, sender, BidStatus.PENDING);
        BidEntity noShow = bid(a, sender, BidStatus.NO_SHOW);

        assertThat(ranks(calm, noShow)).containsOnlyKeys(noShow.getId());
        assertThat(ranks(calm)).isEmpty();
        assertThat(AdminBidAlert.ofRank(0)).isEmpty();
        assertThat(AdminBidAlert.ofRank(4)).contains(AdminBidAlert.LATE);
    }
}
