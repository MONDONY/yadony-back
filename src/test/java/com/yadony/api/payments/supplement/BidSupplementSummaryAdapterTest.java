package com.yadony.api.payments.supplement;

import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.BidSupplementSummaryPort;
import com.yadony.api.matching.BidSupplementView;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidSupplementSummaryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final BidSupplementRepository repository = mock(BidSupplementRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final BidSupplementProperties properties = new BidSupplementProperties();
    private final BidSupplementRailHandler cardRail = mock(BidSupplementRailHandler.class);
    private final com.yadony.api.auth.UserRepository users = mock(com.yadony.api.auth.UserRepository.class);
    private final com.yadony.api.auth.UserEntity traveler = new com.yadony.api.auth.UserEntity();

    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private final List<BidSupplementEntity> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(cardRail.rail()).thenReturn(BidSupplementRail.STRIPE);
        when(storage.generatePresignedUrl(any(), any())).thenReturn("https://signed");
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureAt(OffsetDateTime.ofInstant(NOW.plusSeconds(86_400), ZoneOffset.UTC));
        when(repository.findByBidIdOrderByCreatedAtDesc(bid.getId())).thenReturn(stored);
        traveler.setStripeAccountId("acct_1");
        when(users.findById(travelerId)).thenReturn(java.util.Optional.of(traveler));
    }

    private BidSupplementSummaryAdapter adapter(BidSupplementRailHandler... handlers) {
        return new BidSupplementSummaryAdapter(repository,
                new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties, List.of(handlers), storage,
                users);
    }

    private BidSupplementEntity add(BidSupplementStatus status, int minutesAgo) {
        BidSupplementEntity s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        ReflectionTestUtils.setField(s, "createdAt", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(minutesAgo));
        s.setBidId(bid.getId());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(status);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("10.00"));
        s.setCommissionAmount(new BigDecimal("1.00"));
        s.setGrossAmount(new BigDecimal("11.00"));
        s.setReasonCode(BidSupplementReason.EXTRA_WEIGHT);
        s.setPhotoKey("tracking/" + bid.getId() + "/1.jpg");
        s.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(1));
        stored.add(s);
        return s;
    }

    @Test
    void voyageur_sansDemande_peutDemander() {
        BidSupplementView v = adapter(cardRail).view(bid, announcement, travelerId, null);
        assertThat(v.supplement()).isNull();
        assertThat(v.requestAllowed()).isTrue();
        assertThat(v.paidGross()).isNull();
    }

    @Test
    void droitDeDemander_faux_horsConditions() {
        assertThat(adapter(cardRail).view(bid, announcement, senderId, null).requestAllowed())
                .as("l'expéditeur").isFalse();
        assertThat(adapter(cardRail).view(bid, announcement, null, null).requestAllowed())
                .as("appelant inconnu").isFalse();
        assertThat(adapter().view(bid, announcement, travelerId, null).requestAllowed())
                .as("rail sans composant").isFalse();

        bid.setPaymentMethod(PaymentMethod.WAVE);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed())
                .as("rail hérité").isFalse();
        bid.setPaymentMethod(PaymentMethod.STRIPE);

        bid.setStatus(BidStatus.IN_TRANSIT);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed())
                .as("colis parti").isFalse();
        bid.setStatus(BidStatus.ACCEPTED);

        properties.setEnabled(false);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed())
                .as("interrupteur coupé").isFalse();
    }

    @Test
    void voyageurSansCompteDeVersementSurLeRail_pasDeBouton() {
        traveler.setStripeAccountId(null);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed()).isFalse();
    }

    @Test
    void demandeActive_ouPlafondAtteint_plusDeDemande() {
        add(BidSupplementStatus.PROPOSED, 5);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed()).isFalse();

        stored.clear();
        add(BidSupplementStatus.DECLINED, 30);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed()).isTrue();
        add(BidSupplementStatus.EXPIRED, 10);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).requestAllowed()).isFalse();
    }

    @Test
    void afficheLActif_sinonLeDernierClos() {
        add(BidSupplementStatus.DECLINED, 30);
        BidSupplementEntity active = add(BidSupplementStatus.PROPOSED, 40);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).supplement().id()).isEqualTo(active.getId());

        stored.clear();
        add(BidSupplementStatus.DECLINED, 30);
        BidSupplementEntity latest = add(BidSupplementStatus.EXPIRED, 10);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).supplement().id()).isEqualTo(latest.getId());
    }

    @Test
    void expediteur_neVoitQueLeBrut() {
        add(BidSupplementStatus.PROPOSED, 5);

        BidSupplementView v = adapter(cardRail).view(bid, announcement, senderId, null);

        assertThat(v.supplement().grossAmount()).isEqualByComparingTo("11.00");
        assertThat(v.supplement().netAmount()).isNull();
        assertThat(v.supplement().commissionAmount()).isNull();
        assertThat(v.supplement().blocksHandover()).isTrue();
    }

    @Test
    void demandeEchueNonEncoreFermee_neBloquePlusLaRemise() {
        BidSupplementEntity s = add(BidSupplementStatus.PROPOSED, 130);
        s.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1));
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).supplement().blocksHandover()).isFalse();

        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).supplement().blocksHandover())
                .as("paiement en vol").isTrue();
    }

    @Test
    void montantsPayes_seulementPourUnSupplementPayeNonRembourse() {
        BidSupplementEntity s = add(BidSupplementStatus.PROPOSED, 5);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).paidGross()).isNull();

        for (BidSupplementStatus paid : List.of(BidSupplementStatus.PAID, BidSupplementStatus.PAYOUT_PENDING,
                BidSupplementStatus.PAID_OUT)) {
            s.setStatus(paid);
            BidSupplementView v = adapter(cardRail).view(bid, announcement, travelerId, null);
            assertThat(v.paidGross()).as(paid.name()).isEqualByComparingTo("11.00");
            assertThat(v.paidNet()).as(paid.name()).isEqualByComparingTo("10.00");
        }
        s.setStatus(BidSupplementStatus.REFUND_PENDING);
        assertThat(adapter(cardRail).view(bid, announcement, travelerId, null).paidGross()).isNull();
    }

    @Test
    void liste_unePrechargeEtAucuneLectureParColis() {
        BidSupplementEntity s = add(BidSupplementStatus.PAID, 5);
        when(repository.findByBidIdIn(anyList())).thenReturn(List.of(s));
        BidSupplementSummaryAdapter adapter = adapter(cardRail);

        BidSupplementSummaryPort.Prefetch prefetch = adapter.prefetch(List.of(bid.getId(), UUID.randomUUID()));
        BidSupplementView v = adapter.view(bid, announcement, travelerId, prefetch);

        assertThat(v.supplement().id()).isEqualTo(s.getId());
        verify(repository, never()).findByBidIdOrderByCreatedAtDesc(any());
    }

    @Test
    void refusDuColis_excuseSeulementApresUnSupplementRefuseOuExpire() {
        BidSupplementSummaryAdapter adapter = adapter(cardRail);
        assertThat(adapter.excusesParcelRefusal(bid.getId())).isFalse();
        add(BidSupplementStatus.DECLINED, 10);
        assertThat(adapter.excusesParcelRefusal(bid.getId())).isTrue();
        stored.get(0).setStatus(BidSupplementStatus.EXPIRED);
        assertThat(adapter.excusesParcelRefusal(bid.getId())).isTrue();
        stored.get(0).setStatus(BidSupplementStatus.WITHDRAWN);
        assertThat(adapter.excusesParcelRefusal(bid.getId())).isFalse();
        add(BidSupplementStatus.PROPOSED, 1);
        stored.get(0).setStatus(BidSupplementStatus.DECLINED);
        assertThat(adapter.excusesParcelRefusal(bid.getId())).as("la dernière demande compte").isFalse();
    }
}
