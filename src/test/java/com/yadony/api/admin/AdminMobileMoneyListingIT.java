package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminMobileMoneyCountsResponse;
import com.yadony.api.admin.dto.AdminMobileMoneyResponse;
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
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.payments.wallet.WalletRefundItemStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liste mobile money de l'admin ({@link AdminFinanceListings#mobileMoney}) : vues, nombres, personne et colis
 * résolus, relance possible, recherche sans téléphone. Chaque test pose ses opérations sous un fournisseur qui
 * lui est propre et filtre dessus : la base H2 partagée ne mélange pas les jeux de données.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminMobileMoneyListingIT {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 8, 0);

    @Autowired AdminFinanceListings listings;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired PawapayOperationRepository operationRepository;
    @Autowired WalletRefundRequestRepository walletRefundRequestRepository;
    @Autowired WalletRefundRequestItemRepository walletRefundRequestItemRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired JdbcTemplate jdbc;

    /** Fournisseur propre au test : seule clé d'isolement sur la base partagée. */
    private String provider;
    private int minutes;
    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity walletUser;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        provider = "MM" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        minutes = 0;
        sender = persistUser("Aïcha", "N'Diaye");
        traveler = persistUser("Awa", "Diallo");
        walletUser = persistUser("Moussa", "Koné");
        trip = persistAnnouncement("Paris", "Dakar");
    }

    // ── Vues et nombres ─────────────────────────────────────────────────────────

    @Test
    void vues_failedOpenDone_etNombres() {
        Map<PawapayOperationStatus, UUID> byStatus = new java.util.EnumMap<>(PawapayOperationStatus.class);
        for (PawapayOperationStatus s : PawapayOperationStatus.values()) {
            UUID payment = payment(bid(trip, null).getId(), null, "ESCROW");
            byStatus.put(s, op(PawapayOperationKind.DEPOSIT, payment, s).getId());
        }

        assertThat(ids(AdminMobileMoneyView.FAILED, null, null)).containsExactlyInAnyOrder(
                byStatus.get(PawapayOperationStatus.FAILED), byStatus.get(PawapayOperationStatus.SUBMIT_REJECTED));
        assertThat(ids(AdminMobileMoneyView.OPEN, null, null)).containsExactlyInAnyOrder(
                byStatus.get(PawapayOperationStatus.CREATED), byStatus.get(PawapayOperationStatus.ACCEPTED),
                byStatus.get(PawapayOperationStatus.PROCESSING), byStatus.get(PawapayOperationStatus.ENQUEUED),
                byStatus.get(PawapayOperationStatus.IN_RECONCILIATION));
        assertThat(ids(AdminMobileMoneyView.DONE, null, null))
                .containsExactly(byStatus.get(PawapayOperationStatus.COMPLETED));
        assertThat(ids(AdminMobileMoneyView.ALL, null, null)).hasSize(8);
        assertThat(ids(null, null, null)).hasSize(8);

        AdminMobileMoneyCountsResponse counts = listings.mobileMoneyCounts(null, provider, null);
        assertThat(counts.failed()).isEqualTo(2);
        assertThat(counts.open()).isEqualTo(5);
        assertThat(counts.done()).isEqualTo(1);
        assertThat(counts.all()).isEqualTo(8);
        assertThat(counts.attention()).isZero();
    }

    @Test
    void plusRecentesDAbord_etFiltreKind() {
        UUID payment = payment(bid(trip, null).getId(), null, "RELEASED");
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, payment, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity payout = op(PawapayOperationKind.PAYOUT, payment, PawapayOperationStatus.COMPLETED);

        assertThat(ids(null, null, null)).containsExactly(payout.getId(), deposit.getId());
        assertThat(listings.mobileMoney(null, PawapayOperationKind.DEPOSIT, provider, null, PageRequest.of(0, 50))
                .getContent()).extracting(AdminMobileMoneyResponse::id).containsExactly(deposit.getId());
        assertThat(listings.mobileMoneyCounts(PawapayOperationKind.PAYOUT, provider, null).all()).isEqualTo(1);
        // Fournisseur insensible à la casse.
        assertThat(listings.mobileMoney(null, null, provider.toLowerCase(Locale.ROOT), null, PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(2);
        // Pagination : total juste, page bornée.
        var first = listings.mobileMoney(null, null, provider, null, PageRequest.of(0, 1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(AdminMobileMoneyResponse::id).containsExactly(payout.getId());
    }

    @Test
    void attention_echecsVersementEtRemboursementNonRemplaces_memeRegleQueMmFailed() {
        // Échec remplacé par un versement abouti : plus d'alerte.
        UUID replaced = payment(bid(trip, null).getId(), null, "RELEASED");
        op(PawapayOperationKind.PAYOUT, replaced, PawapayOperationStatus.FAILED);
        op(PawapayOperationKind.PAYOUT, replaced, PawapayOperationStatus.COMPLETED);
        // Deux échecs successifs : aucun n'est remplacé par une opération non échouée.
        UUID twice = payment(bid(trip, null).getId(), null, "RELEASED");
        op(PawapayOperationKind.PAYOUT, twice, PawapayOperationStatus.FAILED);
        op(PawapayOperationKind.PAYOUT, twice, PawapayOperationStatus.SUBMIT_REJECTED);
        // Remboursement échoué.
        UUID refund = payment(bid(trip, null).getId(), null, "REFUNDED");
        op(PawapayOperationKind.REFUND, refund, PawapayOperationStatus.FAILED);
        // Un dépôt échoué n'est pas une alerte.
        UUID deposit = payment(bid(trip, null).getId(), null, "PENDING");
        op(PawapayOperationKind.DEPOSIT, deposit, PawapayOperationStatus.FAILED);
        // Un remboursement de solde échoué n'a pas de paiement : hors règle MM_FAILED.
        walletOp(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND, PawapayOperationStatus.FAILED);

        assertThat(listings.mobileMoneyCounts(null, provider, null).attention()).isEqualTo(3);
        assertThat(listings.mobileMoneyCounts(null, provider, null).failed()).isEqualTo(6);
        // Même règle, par paiement, que l'alerte MM_FAILED de la liste des paiements.
        List<String> flagged = jdbc.queryForList("SELECT CAST(p.id AS VARCHAR(36)) FROM payments p WHERE p.id IN (?, ?, ?, ?)"
                + " AND " + AdminPaymentSql.MM_FAILED_PREDICATE, String.class, replaced, twice, refund, deposit);
        assertThat(flagged).containsExactlyInAnyOrder(twice.toString(), refund.toString());
    }

    // ── Personne, colis, demande de remboursement ───────────────────────────────

    @Test
    void personne_versementVoyageur_depotEtRemboursementExpediteur_colisEtTrajet() {
        BidEntity bid = bid(trip, "YD-MM" + provider.substring(2, 8));
        UUID payment = payment(bid.getId(), null, "REFUNDED");
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, payment, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity payout = op(PawapayOperationKind.PAYOUT, payment, PawapayOperationStatus.FAILED);
        PawapayOperationEntity refund = op(PawapayOperationKind.REFUND, payment, PawapayOperationStatus.COMPLETED);

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        assertThat(rows.get(payout.getId())).satisfies(r -> {
            assertThat(r.personId()).isEqualTo(traveler.getId());
            assertThat(r.personName()).isEqualTo("Awa Diallo");
            assertThat(r.role()).isEqualTo("TRAVELER");
        });
        for (PawapayOperationEntity o : List.of(deposit, refund)) {
            assertThat(rows.get(o.getId())).satisfies(r -> {
                assertThat(r.personId()).isEqualTo(sender.getId());
                assertThat(r.personName()).isEqualTo("Aïcha N'Diaye");
                assertThat(r.role()).isEqualTo("SENDER");
            });
        }
        for (AdminMobileMoneyResponse r : rows.values()) {
            assertThat(r.bidId()).isEqualTo(bid.getId());
            assertThat(r.trackingNumber()).isEqualTo(bid.getTrackingNumber());
            assertThat(r.corridor()).isEqualTo("Paris → Dakar");
            assertThat(r.purpose()).isEqualTo("BID_PAYMENT");
            assertThat(r.paymentId()).isEqualTo(payment);
            assertThat(r.walletRefundRequestId()).isNull();
        }
    }

    @Test
    void personne_colisLieAuFil_etDemandeSansColis() {
        NegotiationThreadEntity linkedThread = persistThread();
        AnnouncementEntity other = persistAnnouncement("Marseille", "Bamako");
        BidEntity linked = bidOn(other, "YD-LK" + provider.substring(2, 8), linkedThread.getId());
        PawapayOperationEntity viaLinked = op(PawapayOperationKind.PAYOUT,
                payment(null, linkedThread.getId(), "RELEASED"), PawapayOperationStatus.COMPLETED);
        NegotiationThreadEntity bare = persistThread();
        PawapayOperationEntity viaRequest = op(PawapayOperationKind.DEPOSIT,
                payment(null, bare.getId(), "ESCROW"), PawapayOperationStatus.COMPLETED);

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(viaLinked.getId())).satisfies(r -> {
            assertThat(r.bidId()).isEqualTo(linked.getId());
            assertThat(r.trackingNumber()).isEqualTo(linked.getTrackingNumber());
            assertThat(r.corridor()).isEqualTo("Marseille → Bamako");
            assertThat(r.personId()).isEqualTo(traveler.getId());
            assertThat(r.role()).isEqualTo("TRAVELER");
        });
        assertThat(rows.get(viaRequest.getId())).satisfies(r -> {
            assertThat(r.bidId()).isNull();
            assertThat(r.trackingNumber()).isNull();
            assertThat(r.corridor()).isEqualTo("Lyon → Abidjan");
            assertThat(r.personId()).isEqualTo(sender.getId());
            assertThat(r.role()).isEqualTo("SENDER");
        });
    }

    @Test
    void personne_soldeUtilisateur_avecDemandeDeRemboursementRetrouvee() {
        PawapayOperationEntity topup = walletOp(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP,
                PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity refundPayout = walletOp(PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.WALLET_REFUND, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity refundRefund = walletOp(PawapayOperationKind.REFUND,
                PawapayOperationPurpose.WALLET_REFUND, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity orphan = walletOp(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND,
                PawapayOperationStatus.FAILED);
        WalletRefundRequestEntity request = refundRequest();
        refundItem(request, null, refundPayout.getId());
        refundItem(request, refundRefund.getId(), null);

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        for (PawapayOperationEntity o : List.of(topup, refundPayout, refundRefund, orphan)) {
            assertThat(rows.get(o.getId())).satisfies(r -> {
                assertThat(r.personId()).isEqualTo(walletUser.getId());
                assertThat(r.personName()).isEqualTo("Moussa Koné");
                assertThat(r.role()).isEqualTo("USER");
                assertThat(r.bidId()).isNull();
                assertThat(r.corridor()).isNull();
                assertThat(r.retryable()).isFalse();
            });
        }
        assertThat(rows.get(topup.getId()).purpose()).isEqualTo("WALLET_TOPUP");
        assertThat(rows.get(topup.getId()).walletRefundRequestId()).isNull();
        assertThat(rows.get(refundPayout.getId()).walletRefundRequestId()).isEqualTo(request.getId());
        assertThat(rows.get(refundRefund.getId()).walletRefundRequestId()).isEqualTo(request.getId());
        assertThat(rows.get(orphan.getId()).walletRefundRequestId()).isNull();
    }

    @Test
    void personne_supplement_expediteurAuDepot_voyageurAuVersement_avecColis() {
        BidEntity bid = bid(trip, "YD-SP" + provider.substring(2, 8));
        UUID supplement = supplement(bid.getId());
        PawapayOperationEntity deposit = supplementOp(PawapayOperationKind.DEPOSIT, supplement, sender.getId());
        PawapayOperationEntity payout = supplementOp(PawapayOperationKind.PAYOUT, supplement, traveler.getId());

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        assertThat(rows.get(deposit.getId())).satisfies(r -> {
            assertThat(r.purpose()).isEqualTo("BID_SUPPLEMENT");
            assertThat(r.personId()).isEqualTo(sender.getId());
            assertThat(r.role()).isEqualTo("SENDER");
        });
        assertThat(rows.get(payout.getId())).satisfies(r -> {
            assertThat(r.personId()).isEqualTo(traveler.getId());
            assertThat(r.role()).isEqualTo("TRAVELER");
        });
        for (AdminMobileMoneyResponse r : rows.values()) {
            assertThat(r.bidId()).isEqualTo(bid.getId());
            assertThat(r.trackingNumber()).isEqualTo(bid.getTrackingNumber());
            assertThat(r.corridor()).isEqualTo("Paris → Dakar");
            assertThat(r.paymentId()).isNull();
            assertThat(r.retryable()).isFalse();
        }
    }

    // ── Relance ────────────────────────────────────────────────────────────────

    @Test
    void retryable_seulementLeDernierVersementMortDUnPaiementVerse() {
        UUID released = payment(bid(trip, null).getId(), null, "RELEASED");
        PawapayOperationEntity older = op(PawapayOperationKind.PAYOUT, released, PawapayOperationStatus.FAILED);
        PawapayOperationEntity latest = op(PawapayOperationKind.PAYOUT, released, PawapayOperationStatus.SUBMIT_REJECTED);
        UUID escrow = payment(bid(trip, null).getId(), null, "ESCROW");
        PawapayOperationEntity notReleased = op(PawapayOperationKind.PAYOUT, escrow, PawapayOperationStatus.FAILED);
        UUID done = payment(bid(trip, null).getId(), null, "RELEASED");
        PawapayOperationEntity superseded = op(PawapayOperationKind.PAYOUT, done, PawapayOperationStatus.FAILED);
        op(PawapayOperationKind.PAYOUT, done, PawapayOperationStatus.COMPLETED);
        UUID stripe = paymentOnRail(bid(trip, null).getId(), "STRIPE", "RELEASED");
        PawapayOperationEntity stripeRail = op(PawapayOperationKind.PAYOUT, stripe, PawapayOperationStatus.FAILED);

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        assertThat(rows.get(latest.getId()).retryable()).isTrue();
        assertThat(rows.get(older.getId()).retryable()).isFalse();
        assertThat(rows.get(notReleased.getId()).retryable()).isFalse();
        assertThat(rows.get(superseded.getId()).retryable()).isFalse();
        assertThat(rows.get(stripeRail.getId()).retryable()).isFalse();
    }

    @Test
    void retryable_dernierRemboursementMortDUnPaiementRembourseOuAnnule_avecDepotAbouti() {
        UUID refunded = payment(bid(trip, null).getId(), null, "REFUNDED");
        op(PawapayOperationKind.DEPOSIT, refunded, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity refundedDead = op(PawapayOperationKind.REFUND, refunded, PawapayOperationStatus.FAILED);
        UUID cancelled = payment(bid(trip, null).getId(), null, "CANCELLED");
        op(PawapayOperationKind.DEPOSIT, cancelled, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity cancelledDead = op(PawapayOperationKind.REFUND, cancelled, PawapayOperationStatus.FAILED);
        UUID noDeposit = payment(bid(trip, null).getId(), null, "REFUNDED");
        op(PawapayOperationKind.DEPOSIT, noDeposit, PawapayOperationStatus.FAILED);
        PawapayOperationEntity withoutDeposit = op(PawapayOperationKind.REFUND, noDeposit, PawapayOperationStatus.FAILED);
        UUID escrow = payment(bid(trip, null).getId(), null, "ESCROW");
        op(PawapayOperationKind.DEPOSIT, escrow, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity escrowDead = op(PawapayOperationKind.REFUND, escrow, PawapayOperationStatus.FAILED);

        Map<UUID, AdminMobileMoneyResponse> rows = rows();
        assertThat(rows.get(refundedDead.getId()).retryable()).isTrue();
        assertThat(rows.get(cancelledDead.getId()).retryable()).isTrue();
        assertThat(rows.get(withoutDeposit.getId()).retryable()).isFalse();
        assertThat(rows.get(escrowDead.getId()).retryable()).isFalse();
        assertThat(rows.values()).filteredOn(r -> !"REFUND".equals(r.kind()))
                .allSatisfy(r -> assertThat(r.retryable()).isFalse());
    }

    // ── Recherche ──────────────────────────────────────────────────────────────

    @Test
    void recherche_identifiantsExactsEtReferenceFournisseur() {
        BidEntity bid = bid(trip, null);
        UUID payment = payment(bid.getId(), null, "ESCROW");
        PawapayOperationEntity target = op(PawapayOperationKind.DEPOSIT, payment, PawapayOperationStatus.COMPLETED);
        String reference = "Ref-" + provider;
        jdbc.update("UPDATE pawapay_operations SET provider_transaction_id = ? WHERE id = ?", reference, target.getId());
        BidEntity supplementBid = bid(trip, null);
        PawapayOperationEntity supplementOp = supplementOp(PawapayOperationKind.DEPOSIT,
                supplement(supplementBid.getId()), sender.getId());
        PawapayOperationEntity other = op(PawapayOperationKind.DEPOSIT, payment(bid(trip, null).getId(), null, "ESCROW"),
                PawapayOperationStatus.COMPLETED);

        assertThat(search(target.getId().toString())).containsExactly(target.getId());
        assertThat(search(target.getId().toString().toUpperCase(Locale.ROOT))).containsExactly(target.getId());
        assertThat(search(payment.toString())).containsExactly(target.getId());
        assertThat(search(bid.getId().toString())).containsExactly(target.getId());
        assertThat(search(supplementBid.getId().toString())).containsExactly(supplementOp.getId());
        assertThat(search(reference)).containsExactly(target.getId());
        assertThat(search(reference.toLowerCase(Locale.ROOT))).containsExactly(target.getId());
        assertThat(search(reference.substring(0, 8))).doesNotContain(target.getId());
        assertThat(search(UUID.randomUUID().toString())).isEmpty();
        assertThat(search(other.getId().toString())).containsExactly(other.getId());
    }

    @Test
    void recherche_suiviNomTrajet_accentsReplies_enNimporteQuelOrdre() {
        BidEntity bid = bid(trip, "YD-QX" + provider.substring(2, 8));
        PawapayOperationEntity payout = op(PawapayOperationKind.PAYOUT, payment(bid.getId(), null, "RELEASED"),
                PawapayOperationStatus.COMPLETED);
        AnnouncementEntity thies = persistAnnouncement("Lyon", "THIÈS");
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT,
                payment(bid(thies, null).getId(), null, "ESCROW"), PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity topup = walletOp(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP,
                PawapayOperationStatus.COMPLETED);

        assertThat(search("yd-qx" + provider.substring(2, 8).toLowerCase(Locale.ROOT))).containsExactly(payout.getId());
        assertThat(search("qx" + provider.substring(2, 5))).containsExactly(payout.getId());
        // Personne résolue : le versement est au voyageur, le dépôt à l'expéditeur.
        assertThat(search("awa diallo")).containsExactly(payout.getId());
        assertThat(search("diallo AWA")).containsExactly(payout.getId());
        assertThat(search("aicha")).containsExactly(deposit.getId());
        assertThat(search("N’DIAYE")).containsExactly(deposit.getId());
        assertThat(search("moussa kone")).containsExactly(topup.getId());
        assertThat(search("dakar paris")).containsExactly(payout.getId());
        assertThat(search("thies")).containsExactly(deposit.getId());
        assertThat(search("Thiès lyon")).containsExactly(deposit.getId());
        assertThat(search("bamako")).isEmpty();
        assertThat(listings.mobileMoneyCounts(null, provider, "paris").all()).isEqualTo(1);
    }

    @Test
    void recherche_parFournisseur_etSaisieSansJeton_neTrouveRien() {
        PawapayOperationEntity o = op(PawapayOperationKind.DEPOSIT, payment(bid(trip, null).getId(), null, "ESCROW"),
                PawapayOperationStatus.COMPLETED);
        jdbc.update("UPDATE pawapay_operations SET provider = ? WHERE id = ?", "ORANGE_" + provider, o.getId());
        provider = "ORANGE_" + provider;

        assertThat(search("orange")).containsExactly(o.getId());
        assertThat(search(provider)).containsExactly(o.getId());
        for (String q : List.of("%", "_", "'", "@", "@@", "% _", "' @")) {
            assertThat(search(q)).as(q).isEmpty();
            assertThat(listings.mobileMoneyCounts(null, provider, q).all()).as(q).isZero();
        }
    }

    @Test
    void recherche_parTelephone_neTrouveJamaisRien() {
        op(PawapayOperationKind.DEPOSIT, payment(bid(trip, null).getId(), null, "ESCROW"), PawapayOperationStatus.COMPLETED);
        walletOp(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP, PawapayOperationStatus.COMPLETED);

        assertThat(ids(null, null, null)).hasSize(2);
        for (String q : List.of("221771234567", "+221771234567", "771234567", "+221 77 123 45 67", "67",
                "+221 •••• 67", "•••• 67", "+221")) {
            assertThat(search(q)).as(q).isEmpty();
            assertThat(listings.mobileMoneyCounts(null, provider, q).all()).as(q).isZero();
        }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    @Test
    void http_listeEtNombres_numeroMasque_champsAjoutes() throws Exception {
        BidEntity bid = bid(trip, "YD-HT" + provider.substring(2, 8));
        UUID payment = payment(bid.getId(), null, "RELEASED");
        PawapayOperationEntity dead = op(PawapayOperationKind.PAYOUT, payment, PawapayOperationStatus.FAILED);
        op(PawapayOperationKind.DEPOSIT, payment(bid(trip, null).getId(), null, "ESCROW"), PawapayOperationStatus.PROCESSING);

        String body = mockMvc.perform(get("/admin/mobile-money-payments")
                        .param("view", "FAILED").param("provider", provider).with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(dead.getId().toString()))
                .andExpect(jsonPath("$.content[0].phoneNumber").value("+221 •••• 67"))
                .andExpect(jsonPath("$.content[0].amountCents").value(660000))
                .andExpect(jsonPath("$.content[0].purpose").value("BID_PAYMENT"))
                .andExpect(jsonPath("$.content[0].personId").value(traveler.getId().toString()))
                .andExpect(jsonPath("$.content[0].personName").value("Awa Diallo"))
                .andExpect(jsonPath("$.content[0].role").value("TRAVELER"))
                .andExpect(jsonPath("$.content[0].bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.content[0].trackingNumber").value(bid.getTrackingNumber()))
                .andExpect(jsonPath("$.content[0].corridor").value("Paris → Dakar"))
                .andExpect(jsonPath("$.content[0].retryable").value(true))
                .andExpect(jsonPath("$.content[0].walletRefundRequestId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("221771234567").doesNotContain("771234567");

        mockMvc.perform(get("/admin/mobile-money-payments/counts").param("provider", provider)
                        .param("view", "FAILED").with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.done").value(0))
                .andExpect(jsonPath("$.all").value(2))
                .andExpect(jsonPath("$.attention").value(1));

        mockMvc.perform(get("/admin/mobile-money-payments").param("provider", provider).param("kind", "DEPOSIT")
                        .with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void http_valeurInconnue_400_etSansPaymentView_403() throws Exception {
        mockMvc.perform(get("/admin/mobile-money-payments").param("view", "NOPE").with(authentication(support())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/mobile-money-payments").param("kind", "NOPE").with(authentication(support())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/mobile-money-payments").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/mobile-money-payments/counts").with(authentication(withoutPaymentView())))
                .andExpect(status().isForbidden());
    }

    // ── Outils ──────────────────────────────────────────────────────────────────

    private List<UUID> ids(AdminMobileMoneyView view, PawapayOperationKind kind, String q) {
        return listings.mobileMoney(view, kind, provider, q, PageRequest.of(0, 100)).getContent().stream()
                .map(AdminMobileMoneyResponse::id).toList();
    }

    private List<UUID> search(String q) {
        return ids(null, null, q);
    }

    private Map<UUID, AdminMobileMoneyResponse> rows() {
        return listings.mobileMoney(null, null, provider, null, PageRequest.of(0, 100)).getContent().stream()
                .collect(Collectors.toMap(AdminMobileMoneyResponse::id, Function.identity()));
    }

    /** Opération d'un paiement de colis, créée une minute après la précédente du test. */
    private PawapayOperationEntity op(PawapayOperationKind kind, UUID paymentId, PawapayOperationStatus status) {
        return finish(operationRepository.save(new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                new BigDecimal("6600.00"), "XOF", provider, "SN", "221771234567")), status);
    }

    private PawapayOperationEntity walletOp(PawapayOperationKind kind, PawapayOperationPurpose purpose,
                                            PawapayOperationStatus status) {
        return finish(operationRepository.save(new PawapayOperationEntity(UUID.randomUUID(), kind, purpose,
                walletUser.getId(), null, null, new BigDecimal("5000.00"), "XOF", provider, "SN", "221771234567")),
                status);
    }

    private PawapayOperationEntity supplementOp(PawapayOperationKind kind, UUID supplementId, UUID userId) {
        return finish(operationRepository.save(PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, userId,
                supplementId, null, new BigDecimal("1100.00"), "XOF", provider, "SN", "221771234567")),
                PawapayOperationStatus.COMPLETED);
    }

    private PawapayOperationEntity finish(PawapayOperationEntity op, PawapayOperationStatus status) {
        jdbc.update("UPDATE pawapay_operations SET status = ?, created_at = ? WHERE id = ?", status.name(),
                Timestamp.valueOf(T0.plusMinutes(++minutes)), op.getId());
        return op;
    }

    private UUID payment(UUID bidId, UUID threadId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, negotiation_thread_id, rail, amount, currency, commission_amount, "
                + "status, legacy_destination_charge, disputed, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'PAWAPAY', 6600, 'XOF', 600, ?, false, false, ?, ?)",
                id, bidId, threadId, status, Timestamp.valueOf(T0), Timestamp.valueOf(T0));
        return id;
    }

    private UUID paymentOnRail(UUID bidId, String rail, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, bid_id, rail, stripe_payment_intent_id, amount, currency, "
                + "commission_amount, status, legacy_destination_charge, disputed, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 66, 'EUR', 6, ?, false, false, ?, ?)",
                id, bidId, rail, "pi_" + id.toString().replace("-", ""), status, Timestamp.valueOf(T0),
                Timestamp.valueOf(T0));
        return id;
    }

    private UUID supplement(UUID bidId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, net_amount, "
                + "commission_amount, gross_amount, reason_code, photo_key, expires_at, stripe_pi_attempt, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, 'PAWAPAY', 'PAID', 'XOF', 1000, 100, 1100, 'EXTRA_WEIGHT', 'k', ?, 0, "
                + "?, ?)", id, bidId, traveler.getId(), sender.getId(), Timestamp.valueOf(T0.plusDays(1)),
                Timestamp.valueOf(T0), Timestamp.valueOf(T0));
        return id;
    }

    private WalletRefundRequestEntity refundRequest() {
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(walletUser.getId());
        request.setCurrency("XOF");
        request.setAmount(new BigDecimal("5000.00"));
        request.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(T0);
        return walletRefundRequestRepository.saveAndFlush(request);
    }

    private void refundItem(WalletRefundRequestEntity request, UUID pawapayRefundId, UUID pawapayPayoutId) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(walletUser.getId());
        tx.setCurrency("XOF");
        tx.setType(WalletTransactionType.TOP_UP);
        tx.setAmount(new BigDecimal("2500.00"));
        tx.setBalanceAfter(new BigDecimal("2500.00"));
        tx = walletTransactionRepository.saveAndFlush(tx);
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(request.getId());
        item.setWalletTransactionId(tx.getId());
        item.setPaymentIntentId("mm_" + UUID.randomUUID());
        item.setAmount(new BigDecimal("2500.00"));
        item.setStatus(WalletRefundItemStatus.REFUNDED);
        item.setPawapayRefundId(pawapayRefundId);
        item.setPawapayPayoutId(pawapayPayoutId);
        walletRefundRequestItemRepository.saveAndFlush(item);
    }

    private BidEntity bid(AnnouncementEntity announcement, String tracking) {
        return bidOn(announcement, tracking, null);
    }

    private BidEntity bidOn(AnnouncementEntity announcement, String tracking, UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setTrackingNumber(tracking);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        return bidRepository.save(bid);
    }

    private UserEntity persistUser(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername("mm" + UUID.randomUUID().toString().substring(0, 8));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(String departure, String arrival) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
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

    private NegotiationThreadEntity persistThread() {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(sender.getId());
        colis.setDepartureCity("Lyon");
        colis.setArrivalCity("Abidjan");
        colis.setDesiredDate(LocalDate.now().plusDays(10));
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(new BigDecimal("2.50"));
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setStatus(PackageRequestStatus.OPEN);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        packageRequestRepository.save(colis);

        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(colis.getId());
        t.setTravelerId(traveler.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setCurrency("EUR");
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }

    private static UsernamePasswordAuthenticationToken support() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support-mm@yadony.test", AdminRole.SUPPORT, false, "uid-support-mm");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private static UsernamePasswordAuthenticationToken withoutPaymentView() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "bride-mm@yadony.test", AdminRole.ADMIN, false, "uid-bride-mm");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
