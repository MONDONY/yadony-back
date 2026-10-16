package com.yadony.api.payments.wallet;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FLUTTER-QP : la requête en lot sur une vraie base (H2), colis supprimés exclus. */
@DataJpaTest
@ActiveProfiles("test")
@Import(WalletTransactionBidLinks.class)
class WalletTransactionBidLinksRepositoryTest {

    @Autowired BidRepository bidRepository;
    @Autowired WalletTransactionBidLinks links;
    @Autowired TestEntityManager em;

    @Test
    void resoutFilEtRemboursement_ignoreLesColisSupprimes() {
        UUID announcementId = announcement();
        UUID thread = UUID.randomUUID();
        BidEntity negotiated = bid(announcementId, thread, false);
        BidEntity classic = bid(announcementId, null, false);
        BidEntity deleted = bid(announcementId, null, true);

        List<BidRepository.BidThreadLink> found = bidRepository.findBidThreadLinks(
                Set.of(classic.getId(), deleted.getId()), Set.of(thread));
        assertThat(found).extracting(BidRepository.BidThreadLink::getId)
                .containsExactlyInAnyOrder(negotiated.getId(), classic.getId());

        WalletTransactionEntity negoDebit = ledger(WalletTransactionType.COMMISSION_DEDUCTED, thread.toString());
        WalletTransactionEntity refund = ledger(WalletTransactionType.REFUND, "refund-" + classic.getId());
        WalletTransactionEntity refundOfDeleted = ledger(WalletTransactionType.REFUND, "refund-" + deleted.getId());

        assertThat(links.resolve(List.of(negoDebit, refund, refundOfDeleted))).containsOnly(
                Map.entry(negoDebit.getId(), negotiated.getId()),
                Map.entry(refund.getId(), classic.getId()));
    }

    private WalletTransactionEntity ledger(WalletTransactionType type, String paymentRef) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(UUID.randomUUID());
        tx.setCurrency("EUR");
        tx.setType(type);
        tx.setAmount(type == WalletTransactionType.REFUND ? new BigDecimal("1.00") : new BigDecimal("-1.00"));
        tx.setBalanceAfter(BigDecimal.ZERO);
        tx.setPaymentRef(paymentRef);
        return em.persistAndFlush(tx);
    }

    private UUID announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(2));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return em.persistAndFlush(a).getId();
    }

    private BidEntity bid(UUID announcementId, UUID thread, boolean deleted) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("5.0"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(thread);
        if (deleted) {
            bid.setDeletedAt(LocalDateTime.now());
        }
        return bidRepository.saveAndFlush(bid);
    }
}
