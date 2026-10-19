package com.yadony.api.payments.supplement;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidSupplementCashLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock WalletService walletService;
    @Mock WalletTransactionRepository walletTransactions;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;

    private BidSupplementCashLedger ledger;
    private BidSupplementEntity s;

    @BeforeEach
    void setUp() {
        ledger = new BidSupplementCashLedger(supplements, bids, announcements, walletService, walletTransactions,
                auditService, events, new BidSupplementWindow(new BidSupplementProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC)));
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setTravelerId(UUID.randomUUID());
        s.setSenderId(UUID.randomUUID());
        s.setRail(BidSupplementRail.CASH);
        s.setStatus(BidSupplementStatus.COMMISSION_PENDING);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setReasonCode(BidSupplementReason.OTHER);
        s.setPhotoKey("tracking/x/1.jpg");
        s.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(2));
        lenient().when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(s.getBidId()));
        lenient().when(supplements.findByIdAndBidIdForUpdate(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
    }

    // ── Acceptation espèces ──────────────────────────────────────────────────

    @Test
    void acceptationEspeces_payeeSansVersement_voyageurPrevenu() {
        s.setStatus(BidSupplementStatus.PROPOSED);

        ledger.acceptCash(s);

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        assertThat(s.getPaidAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(auditService).log(eq(BidSupplementService.ENTITY), eq(s.getId()), eq("BID_SUPPLEMENT_CASH_ACCEPTED"),
                eq(s.getSenderId()), anyMap());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BidSupplementPaidEvent.class, e -> {
            assertThat(e.rail()).isEqualTo(BidSupplementRail.CASH);
            assertThat(e.grossAmount()).isEqualByComparingTo("21.10");
        });
    }

    @Test
    void acceptationEspeces_horsProposee_sansEffet() {
        s.setStatus(BidSupplementStatus.PAID);
        ledger.acceptCash(s);
        verifyNoInteractions(events, auditService);
    }

    // ── Commission par carte ─────────────────────────────────────────────────

    @Test
    void troisDs_enregistreLaPaymentIntentUneFois() {
        assertThat(ledger.commissionRequiresAction(s.getId(), "pi_c").getCommissionStatus())
                .isEqualTo(CommissionStatus.REQUIRES_3DS);
        assertThat(s.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        ledger.commissionRequiresAction(s.getId(), "pi_other");
        assertThat(s.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        verify(auditService).log(any(), any(), eq("BID_SUPPLEMENT_COMMISSION_3DS_REQUIRED"), any(), anyMap());
    }

    @Test
    void commissionPrelevee_demandeProposeeEtExpediteurPrevenu() {
        BidEntity bid = new BidEntity();
        AnnouncementEntity announcement = new AnnouncementEntity();
        setId(announcement, UUID.randomUUID());
        announcement.setTimezone("Africa/Dakar");
        bid.setAnnouncementId(announcement.getId());
        when(bids.findById(s.getBidId())).thenReturn(Optional.of(bid));
        when(announcements.findById(announcement.getId())).thenReturn(Optional.of(announcement));

        assertThat(ledger.commissionCharged(s.getId(), "pi_c", "webhook"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.PROPOSED);

        verify(bids).lockForUpdate(s.getBidId());
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        assertThat(s.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.CARD);
        assertThat(s.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BidSupplementRequestedEvent.class, e -> {
            assertThat(e.senderId()).isEqualTo(s.getSenderId());
            assertThat(e.timezone()).isEqualTo("Africa/Dakar");
        });
        // Rejeu (webhook après la confirmation de l'app) : aucun second effet.
        assertThat(ledger.commissionCharged(s.getId(), "pi_c", "confirm"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.ALREADY);
        verify(events).publishEvent(any(BidSupplementRequestedEvent.class));
    }

    @Test
    void commissionPrelevee_colisIntrouvable_annonceSansFuseau() {
        when(bids.findById(s.getBidId())).thenReturn(Optional.empty());

        ledger.commissionCharged(s.getId(), "pi_c", "webhook");

        verify(events).publishEvent(any(BidSupplementRequestedEvent.class));
    }

    @Test
    void commissionPrelevee_surDemandeClose_aRendre() {
        s.setStatus(BidSupplementStatus.WITHDRAWN);
        s.setCommissionPaymentIntentId("pi_c");
        s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

        assertThat(ledger.commissionCharged(s.getId(), "pi_c", "webhook"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.LATE_REFUND);

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.WITHDRAWN);
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
        verify(events).publishEvent(any(BidSupplementRefundRequestedEvent.class));
    }

    @Test
    void commissionPrelevee_casIgnores() {
        UUID unknown = UUID.randomUUID();
        when(supplements.findBidIdById(unknown)).thenReturn(Optional.empty());
        assertThat(ledger.commissionCharged(unknown, "pi_c", "webhook"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.IGNORED);

        s.setCommissionPaymentIntentId("pi_mine");
        assertThat(ledger.commissionCharged(s.getId(), "pi_other", "webhook"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.IGNORED);

        s.setRail(BidSupplementRail.STRIPE);
        assertThat(ledger.commissionCharged(s.getId(), "pi_mine", "webhook"))
                .isEqualTo(BidSupplementCashLedger.ChargeOutcome.IGNORED);
        verifyNoInteractions(events);
    }

    @Test
    void commissionEchouee_demandeEnAttente_annuleeSansJamaisAtteindreLExpediteur() {
        assertThat(ledger.commissionFailed(s.getId(), "pi_c", "card-declined")).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.CANCELLED);
        assertThat(s.getClosedReason()).isEqualTo(BidSupplementCashLedger.COMMISSION_FAILED);
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        assertThat(s.getCommissionPaymentIntentId()).isEqualTo("pi_c");
        verifyNoInteractions(events);
        assertThat(ledger.commissionFailed(s.getId(), "pi_c", "again")).as("rejeu").isFalse();
    }

    @Test
    void commissionEchouee_demandeDejaClose_statutConserve() {
        s.setStatus(BidSupplementStatus.EXPIRED);
        s.setCommissionStatus(CommissionStatus.REQUIRES_3DS);

        assertThat(ledger.commissionFailed(s.getId(), null, "abandoned")).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.EXPIRED);
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
    }

    @Test
    void commissionEchouee_jamaisSurUneCommissionPrelevee() {
        s.setCommissionStatus(CommissionStatus.CHARGED);
        assertThat(ledger.commissionFailed(s.getId(), "pi_c", "late")).isFalse();
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);

        s.setCommissionStatus(null);
        s.setRail(BidSupplementRail.PAWAPAY);
        assertThat(ledger.commissionFailed(s.getId(), "pi_c", "late")).isFalse();
    }

    // ── Remboursement de la commission ───────────────────────────────────────

    @Test
    void recreditSolde_uneLigneParDevise_refHorsPrefixeRefund() {
        chargedViaWallet(BidSupplementStatus.DECLINED);
        when(walletTransactions.findAllByUserIdAndPaymentRefAndType(s.getTravelerId(), "supplement-" + s.getId(),
                WalletTransactionType.SUPPLEMENT_COMMISSION_DEDUCTED))
                .thenReturn(List.of(line("EUR", "-0.40"), line("XOF", "-460"), line("CAD", "0")));

        assertThat(ledger.refundWallet(s.getId())).isTrue();

        String ref = "supplement-refund-" + s.getId();
        verify(walletService).credit(s.getTravelerId(), "EUR", new BigDecimal("0.40"), WalletTransactionType.REFUND,
                ref, ref + "-EUR", s.getBidId());
        verify(walletService).credit(s.getTravelerId(), "XOF", new BigDecimal("460"), WalletTransactionType.REFUND,
                ref, ref + "-XOF", s.getBidId());
        verify(walletService, never()).credit(any(), eq("CAD"), any(), any(), anyString(), anyString(), any());
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BidSupplementRefundedEvent.class, e -> {
            assertThat(e.commissionOnly()).isTrue();
            assertThat(e.recipientId()).isEqualTo(s.getTravelerId());
            assertThat(e.amount()).isEqualByComparingTo("1.10");
        });
        // Rejeu : la commission est déjà rendue.
        assertThat(ledger.refundWallet(s.getId())).isFalse();
    }

    @Test
    void recreditSolde_uneSeuleLigne_cleSimple() {
        chargedViaWallet(BidSupplementStatus.REFUNDED);
        when(walletTransactions.findAllByUserIdAndPaymentRefAndType(any(), anyString(), any()))
                .thenReturn(List.of(line("EUR", "-1.10")));

        ledger.refundWallet(s.getId());

        String ref = "supplement-refund-" + s.getId();
        verify(walletService).credit(s.getTravelerId(), "EUR", new BigDecimal("1.10"), WalletTransactionType.REFUND,
                ref, ref, s.getBidId());
    }

    @Test
    void recreditSolde_sansLigne_commissionMarqueeRendue() {
        chargedViaWallet(BidSupplementStatus.EXPIRED);
        when(walletTransactions.findAllByUserIdAndPaymentRefAndType(any(), anyString(), any())).thenReturn(List.of());

        assertThat(ledger.refundWallet(s.getId())).isTrue();
        verifyNoInteractions(walletService);
    }

    @Test
    void recreditSolde_demandeEncoreVivanteOuAutreCanal_rien() {
        chargedViaWallet(BidSupplementStatus.PROPOSED);
        assertThat(ledger.refundWallet(s.getId())).isFalse();
        s.setStatus(BidSupplementStatus.DECLINED);
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        assertThat(ledger.refundWallet(s.getId())).isFalse();
        verifyNoInteractions(walletService, walletTransactions);
    }

    @Test
    void carte_reservationRemboursementPuisRembourseeEtEchecApresCoup() {
        s.setStatus(BidSupplementStatus.CANCELLED);
        s.setCommissionChargedVia(CommissionChargedVia.CARD);
        s.setCommissionStatus(CommissionStatus.CHARGED);
        s.setCommissionPaymentIntentId("pi_c");
        when(supplements.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(ledger.cardRefundClaim(s.getId())).get().satisfies(c -> {
            assertThat(c.paymentIntentId()).isEqualTo("pi_c");
            assertThat(c.travelerId()).isEqualTo(s.getTravelerId());
        });
        assertThat(ledger.cardRefunded(s.getId(), "re_1", "refund")).isTrue();
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        assertThat(ledger.cardRefunded(s.getId(), "re_1", "webhook")).as("rejeu").isFalse();
        assertThat(ledger.cardRefundClaim(s.getId())).isEmpty();
        verify(events).publishEvent(any(BidSupplementRefundedEvent.class));

        assertThat(ledger.commissionRefundFailed(s.getId(), "re_1")).isTrue();
        assertThat(s.getCommissionStatus()).isEqualTo(CommissionStatus.REFUND_FAILED);
        assertThat(ledger.commissionRefundFailed(s.getId(), "re_1")).isFalse();
    }

    @Test
    void auditSeul() {
        when(supplements.findById(s.getId())).thenReturn(Optional.of(s));
        ledger.auditOnly(s.getId(), "X", Map.of("k", "v"));
        verify(auditService).log(eq(BidSupplementService.ENTITY), eq(s.getId()), eq("X"), any(), anyMap());
    }

    private void chargedViaWallet(BidSupplementStatus status) {
        s.setStatus(status);
        s.setCommissionChargedVia(CommissionChargedVia.WALLET);
        s.setCommissionStatus(CommissionStatus.CHARGED);
    }

    private static WalletTransactionEntity line(String currency, String amount) {
        WalletTransactionEntity t = new WalletTransactionEntity();
        t.setCurrency(currency);
        t.setAmount(new BigDecimal(amount));
        return t;
    }
}
