package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import com.yadony.api.payments.supplement.dto.SupplementOptionsResponse;
import com.yadony.api.payments.supplement.events.BidSupplementCancelledEvent;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementExpiredEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementWithdrawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidSupplementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidSupplementRepository supplements;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock AnnouncementPriceGridItemRepository gridItems;
    @Mock UserRepository users;
    @Mock BidSupplementPricing pricing;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock StorageService storage;
    @Mock com.yadony.api.disputes.DisputeRepository disputes;

    private final BidSupplementRailHandler cardRail = mock(BidSupplementRailHandler.class);
    private final BidSupplementProperties properties = new BidSupplementProperties();

    private UserEntity traveler;
    private UserEntity sender;
    private BidEntity bid;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        traveler = user("uid-traveler");
        traveler.setStripeAccountId("acct_1");
        traveler.setStripeAccountStatus(com.yadony.api.auth.StripeAccountStatus.ONBOARDING_COMPLETE);
        sender = user("uid-sender");
        announcement = new AnnouncementEntity();
        setId(announcement, UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setDepartureAt(OffsetDateTime.ofInstant(NOW.plusSeconds(86_400), ZoneOffset.UTC));
        announcement.setPricePerKg(new BigDecimal("8.00"));
        bid = new BidEntity();
        setId(bid, UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setCurrency("EUR");
        bid.setPaymentMethod(PaymentMethod.STRIPE);

        lenient().when(cardRail.rail()).thenReturn(BidSupplementRail.STRIPE);
        lenient().when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(announcements.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        lenient().when(storage.generatePresignedUrl(any(), any())).thenReturn("https://signed/photo.jpg");
        lenient().when(supplements.saveAndFlush(any())).thenAnswer(inv -> {
            BidSupplementEntity s = inv.getArgument(0);
            setId(s, UUID.randomUUID());
            return s;
        });
        lenient().when(pricing.quote(any(), any(), any())).thenAnswer(inv -> {
            BigDecimal net = inv.getArgument(2);
            return new BidSupplementQuote(net, new BigDecimal("1.10"), net.add(new BigDecimal("1.10")),
                    new BigDecimal("0.055000"), UUID.randomUUID());
        });
    }

    private BidSupplementService service(BidSupplementRailHandler... handlers) {
        BidSupplementWindow window = new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        return new BidSupplementService(supplements, bids, announcements, gridItems, users, pricing, window,
                properties, List.of(handlers), auditService, events, storage, disputes);
    }

    private static CreateSupplementRequest request(String net) {
        return new CreateSupplementRequest(new BigDecimal(net), BidSupplementReason.GRID_ITEM, "Téléphone déclaré au kilo",
                "Téléphone", "tracking/%s/1_supplement.jpg");
    }

    private CreateSupplementRequest requestFor(String net) {
        CreateSupplementRequest r = request(net);
        return new CreateSupplementRequest(r.netAmount(), r.reasonCode(), r.reasonText(), r.gridItemLabel(),
                r.photoKey().formatted(bid.getId()));
    }

    // ── Création ─────────────────────────────────────────────────────────────

    @Test
    void create_colisAccepte_prixFigeEvenementEtAudit() {
        BidSupplementResponse r = service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20"));

        ArgumentCaptor<BidSupplementEntity> saved = ArgumentCaptor.forClass(BidSupplementEntity.class);
        verify(supplements).saveAndFlush(saved.capture());
        BidSupplementEntity s = saved.getValue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
        assertThat(s.getRail()).isEqualTo(BidSupplementRail.STRIPE);
        assertThat(s.getNetAmount()).isEqualByComparingTo("20.00");
        assertThat(s.getGrossAmount()).isEqualByComparingTo("21.10");
        assertThat(s.getTravelerId()).isEqualTo(traveler.getId());
        assertThat(s.getSenderId()).isEqualTo(sender.getId());
        // min(maintenant + 2 h, départ − 15 min) : le départ est dans 24 h.
        assertThat(s.getExpiresAt()).isEqualTo(NOW_UTC.plusHours(2));
        verify(bids).lockForUpdate(bid.getId());
        verify(cardRail).onCreated(eq(s), any(CreateSupplementRequest.class));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BidSupplementRequestedEvent.class, e -> {
            assertThat(e.senderId()).isEqualTo(sender.getId());
            assertThat(e.grossAmount()).isEqualByComparingTo("21.10");
            assertThat(e.expiresAt()).isEqualTo(NOW_UTC.plusHours(2));
        });
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_REQUESTED"),
                eq(traveler.getId()), anyMap());
        // Le voyageur voit son net, la commission et le brut.
        assertThat(r.netAmount()).isEqualByComparingTo("20.00");
        assertThat(r.commissionAmount()).isEqualByComparingTo("1.10");
        assertThat(r.blocksHandover()).isTrue();
        assertThat(r.photoUrl()).isEqualTo("https://signed/photo.jpg");
    }

    @Test
    void create_echeanceRaccourciePourFinir15MinAvantLeDepart() {
        announcement.setDepartureAt(OffsetDateTime.ofInstant(NOW.plusSeconds(3_600), ZoneOffset.UTC));

        service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20"));

        ArgumentCaptor<BidSupplementEntity> saved = ArgumentCaptor.forClass(BidSupplementEntity.class);
        verify(supplements).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW_UTC.plusMinutes(45));
    }

    @Test
    void create_colisRemisAvantLeDepart_autorise() {
        bid.setStatus(BidStatus.HANDED_OVER);
        service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20"));
        verify(supplements).saveAndFlush(any());
    }

    @Test
    void create_departPasseOuTropProche_409() {
        bid.setStatus(BidStatus.HANDED_OVER);
        announcement.setDepartureAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.CONFLICT, "supplement-locked");

        bid.setStatus(BidStatus.ACCEPTED);
        announcement.setDepartureAt(OffsetDateTime.ofInstant(NOW.plusSeconds(20 * 60), ZoneOffset.UTC));
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.CONFLICT, "supplement-locked");
        verify(supplements, never()).saveAndFlush(any());
    }

    @Test
    void create_colisHorsFenetre_422() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status");
    }

    @Test
    void create_interrupteurCoupe_422() {
        properties.setEnabled(false);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-disabled");
    }

    @Test
    void create_parUnAutreQueLeVoyageur_403() {
        assertError(() -> service(cardRail).create(bid.getId(), "uid-sender", requestFor("20")),
                HttpStatus.FORBIDDEN, "forbidden");
    }

    @Test
    void create_bornesDuNet_422AvecMinMaxDevise() {
        YadonyBusinessException low = assertError(
                () -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("0.99")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-amount-out-of-range");
        assertThat(low.getProperties()).containsEntry("currency", "EUR");
        assertThat((BigDecimal) low.getProperties().get("min")).isEqualByComparingTo("1.00");
        assertThat((BigDecimal) low.getProperties().get("max")).isEqualByComparingTo("300.00");
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("300.01")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-amount-out-of-range");
    }

    @Test
    void create_bornesConvertiesEnFrancsCfa() {
        bid.setCurrency("XOF");
        YadonyBusinessException e = assertError(
                () -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("500")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-amount-out-of-range");
        assertThat((BigDecimal) e.getProperties().get("min")).isEqualByComparingTo("656");
        assertThat((BigDecimal) e.getProperties().get("max")).isEqualByComparingTo("196787");
    }

    @Test
    void create_railHerite_422() {
        bid.setPaymentMethod(PaymentMethod.WAVE);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported");
    }

    @Test
    void create_railSansComposant_422() {
        assertError(() -> service().create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported");
    }

    @Test
    void create_carteSansCompteDeVersement_422() {
        traveler.setStripeAccountId(null);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported");
    }

    @Test
    void create_photoHorsDuColis_422() {
        CreateSupplementRequest other = new CreateSupplementRequest(new BigDecimal("20"), BidSupplementReason.OTHER,
                null, null, "tracking/" + UUID.randomUUID() + "/1.jpg");
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", other),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid-photo-url");
        CreateSupplementRequest url = new CreateSupplementRequest(new BigDecimal("20"), BidSupplementReason.OTHER,
                null, null, "https://evil/tracking/" + bid.getId() + "/1.jpg");
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", url),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid-photo-url");
    }

    @Test
    void create_demandeDejaActive_409() {
        when(supplements.existsByBidIdAndStatusIn(bid.getId(), BidSupplementStatus.ACTIVE)).thenReturn(true);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.CONFLICT, "supplement-already-active");
    }

    @Test
    void create_courseSurLIndexUnique_409() {
        doThrow(new DataIntegrityViolationException("uq_bid_supplements_one_active")).when(supplements).saveAndFlush(any());
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.CONFLICT, "supplement-already-active");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_autreViolationDeContrainte_relancee() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("chk_bid_supplements_amounts");
        doThrow(other).when(supplements).saveAndFlush(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20"))).isSameAs(other);
    }

    @Test
    void create_plafondAtteint_422() {
        when(supplements.countTowardLimit(eq(bid.getId()), any())).thenReturn(2L);
        assertError(() -> service(cardRail).create(bid.getId(), "uid-traveler", requestFor("20")),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-limit-reached");
    }

    // ── Devis et options ─────────────────────────────────────────────────────

    @Test
    void quote_lecturePure() {
        var q = service(cardRail).quote(bid.getId(), "uid-traveler", new BigDecimal("20"));
        assertThat(q.grossAmount()).isEqualByComparingTo("21.10");
        assertThat(q.currency()).isEqualTo("EUR");
        verify(supplements, never()).saveAndFlush(any());
    }

    @Test
    void options_grilleDuTrajetEtKiloEnPlus() {
        AnnouncementPriceGridItemEntity phone = new AnnouncementPriceGridItemEntity();
        phone.setLabel("Téléphone");
        phone.setUnitPriceNet(new BigDecimal("15.00"));
        when(gridItems.findByAnnouncementIdOrderByPositionAsc(announcement.getId())).thenReturn(List.of(phone));

        SupplementOptionsResponse o = service(cardRail).options(bid.getId(), "uid-traveler");

        assertThat(o.currency()).isEqualTo("EUR");
        assertThat(o.rail()).isEqualTo("STRIPE");
        assertThat(o.minNet()).isEqualByComparingTo("1.00");
        assertThat(o.maxNet()).isEqualByComparingTo("300.00");
        assertThat(o.suggestions()).extracting(SupplementOptionsResponse.Suggestion::kind).containsExactly("GRID_ITEM", "KG");
        assertThat(o.suggestions().get(1).unitNet()).isEqualByComparingTo("8.00");
    }

    // ── Retrait, refus, acceptation ──────────────────────────────────────────

    @Test
    void withdraw_parLeVoyageur_retireEtPrevientLExpediteur() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);

        BidSupplementResponse r = service(cardRail).withdraw(bid.getId(), s.getId(), "uid-traveler");

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.WITHDRAWN);
        assertThat(s.getRespondedAt()).isEqualTo(NOW_UTC);
        assertThat(r.status()).isEqualTo("WITHDRAWN");
        assertThat(r.blocksHandover()).isFalse();
        verify(events).publishEvent(new BidSupplementWithdrawnEvent(s.getId(), bid.getId(), sender.getId(),
                traveler.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_WITHDRAWN"),
                eq(traveler.getId()), anyMap());
    }

    @Test
    void withdraw_parLExpediteur_403_etDemandeCloseNonRetirable_409() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        assertError(() -> service(cardRail).withdraw(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.FORBIDDEN, "forbidden");
        s.setStatus(BidSupplementStatus.DECLINED);
        assertError(() -> service(cardRail).withdraw(bid.getId(), s.getId(), "uid-traveler"),
                HttpStatus.CONFLICT, "supplement-not-pending");
    }

    @Test
    void decline_parLExpediteur_refuseSansNiNetNiCommission() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);

        BidSupplementResponse r = service(cardRail).decline(bid.getId(), s.getId(), "uid-sender");

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.DECLINED);
        assertThat(r.netAmount()).isNull();
        assertThat(r.commissionAmount()).isNull();
        assertThat(r.grossAmount()).isEqualByComparingTo("21.10");
        verify(events).publishEvent(new BidSupplementDeclinedEvent(s.getId(), bid.getId(), sender.getId(),
                traveler.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
    }

    @Test
    void decline_parLeVoyageur_403_echue_409_inconnue_404() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        assertError(() -> service(cardRail).decline(bid.getId(), s.getId(), "uid-traveler"),
                HttpStatus.FORBIDDEN, "forbidden");
        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        assertError(() -> service(cardRail).decline(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.CONFLICT, "supplement-expired");
        UUID unknown = UUID.randomUUID();
        assertError(() -> service(cardRail).decline(bid.getId(), unknown, "uid-sender"),
                HttpStatus.NOT_FOUND, "supplement-not-found");
    }

    @Test
    void accept_sansComposantDuRail_422() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        assertError(() -> service().accept(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported");
    }

    @Test
    void accept_delegueAuRailDuColis() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        SupplementAcceptResponse expected = new SupplementAcceptResponse(null, "STRIPE", "pi_secret",
                List.of("card"), s.getGrossAmount(), "EUR");
        when(cardRail.accept(s, sender)).thenReturn(expected);

        assertThat(service(cardRail).accept(bid.getId(), s.getId(), "uid-sender")).isSameAs(expected);
    }

    @Test
    void accepterOuRefuser_surUnColisTermine_422EtRailJamaisAppele() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        bid.setStatus(BidStatus.CANCELLED);

        assertError(() -> service(cardRail).accept(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status");
        assertError(() -> service(cardRail).decline(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status");
        verify(cardRail, never()).accept(any(), any());
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PROPOSED);
    }

    @Test
    void accepter_surUnColisRemisAvantLeDepart_delegueAuRail() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        bid.setStatus(BidStatus.HANDED_OVER);
        service(cardRail).accept(bid.getId(), s.getId(), "uid-sender");
        verify(cardRail).accept(s, sender);
    }

    @Test
    void accept_demandeDejaRefusee_409() {
        BidSupplementEntity s = existing(BidSupplementStatus.DECLINED);
        assertError(() -> service(cardRail).accept(bid.getId(), s.getId(), "uid-sender"),
                HttpStatus.CONFLICT, "supplement-not-pending");
    }

    // ── Expiration et fin du colis ───────────────────────────────────────────

    @Test
    void expire_demandeEchue_expireeEtLesDeuxPartiesPrevenues() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));

        assertThat(service(cardRail).expire(s.getId())).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.EXPIRED);
        verify(events).publishEvent(new BidSupplementExpiredEvent(s.getId(), bid.getId(), sender.getId(),
                traveler.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED));
    }

    @Test
    void expire_nonEchueOuDejaClose_sansEffet() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));
        assertThat(service(cardRail).expire(s.getId())).isFalse();

        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        s.setStatus(BidSupplementStatus.DECLINED);
        assertThat(service(cardRail).expire(s.getId())).isFalse();
        verify(events, never()).publishEvent(any());
        assertThat(service(cardRail).expire(UUID.randomUUID())).isFalse();
    }

    @Test
    void expire_paiementEnCoursQueLeRailRetient_attend() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAYMENT_PENDING);
        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));
        when(cardRail.mayExpire(s)).thenReturn(false);

        assertThat(service(cardRail).expire(s.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAYMENT_PENDING);
    }

    @Test
    void expire_commissionJamaisConfirmee_annulee() {
        BidSupplementEntity s = existing(BidSupplementStatus.COMMISSION_PENDING);
        s.setRail(BidSupplementRail.CASH);
        s.setExpiresAt(NOW_UTC.minusMinutes(1));
        when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));

        assertThat(service(cardRail).expire(s.getId())).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.CANCELLED);
        verify(events).publishEvent(new BidSupplementCancelledEvent(s.getId(), bid.getId(), sender.getId(),
                traveler.getId(), BidSupplementRail.CASH, BidSupplementStatus.COMMISSION_PENDING,
                "COMMISSION_NOT_CONFIRMED"));
    }

    @Test
    void onBidEnded_colisRefuse_demandeOuverteAnnulee() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        bid.setStatus(BidStatus.PARCEL_REFUSED);
        when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementStatus.OPEN)).thenReturn(Optional.of(s));

        assertThat(service(cardRail).onBidEnded(bid.getId(), "PARCEL_REFUSED")).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.CANCELLED);
        assertThat(s.getClosedReason()).isEqualTo("PARCEL_REFUSED");
        verify(events).publishEvent(new BidSupplementCancelledEvent(s.getId(), bid.getId(), sender.getId(),
                traveler.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.PROPOSED, "PARCEL_REFUSED"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_CANCELLED_BID_ENDED"),
                eq(null), anyMap());
    }

    @Test
    void onBidEnded_colisToujoursEnCours_ouSansDemandeOuverte_sansEffet() {
        assertThat(service(cardRail).onBidEnded(bid.getId(), "CANCELLATION_CONFIRMED")).isFalse();
        verify(supplements, never()).findByBidIdAndStatusInForUpdate(any(), any());

        bid.setStatus(BidStatus.CANCELLED);
        assertThat(service(cardRail).onBidEnded(bid.getId(), "BID_REJECTED")).isFalse();
        assertThat(service(cardRail).onBidEnded(null, "BID_REJECTED")).isFalse();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void onBidEnded_supplementPaye_passeEnRemboursementEtLeDemandeAuRail() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAID);
        bid.setStatus(BidStatus.PARCEL_REFUSED);
        lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onBidEnded(bid.getId(), "PARCEL_REFUSED")).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        assertThat(s.getClosedReason()).isEqualTo("PARCEL_REFUSED");
        verify(events).publishEvent(new com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent(
                s.getId(), bid.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.REFUND_PENDING, "PARCEL_REFUSED"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_REFUND_REQUESTED"),
                eq(null), anyMap());
    }

    @Test
    void onBidEnded_dejaEnRemboursementOuVerse_redemandeSansChangerLeStatut() {
        bid.setStatus(BidStatus.CANCELLED);
        for (BidSupplementStatus status : List.of(BidSupplementStatus.REFUND_PENDING, BidSupplementStatus.PAID_OUT)) {
            BidSupplementEntity s = existing(status);
            lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                    .thenReturn(Optional.of(s));

            assertThat(service(cardRail).onBidEnded(bid.getId(), "BID_REJECTED")).isTrue();

            assertThat(s.getStatus()).isEqualTo(status);
            verify(events).publishEvent(new com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent(
                    s.getId(), bid.getId(), BidSupplementRail.STRIPE, status, "BID_REJECTED"));
        }
        verify(supplements, never()).save(any());
    }

    @Test
    void onBidEnded_supplementEspecesAccepte_rembourseToutDeSuite_etCommissionRendueParLeRail() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAID);
        s.setRail(BidSupplementRail.CASH);
        bid.setStatus(BidStatus.CANCELLED);
        lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onBidEnded(bid.getId(), "BID_REJECTED")).isTrue();

        // Plus de PAID sur un colis mort : le filet B1 ne le reprend plus à chaque passage.
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUNDED);
        assertThat(s.getRefundedAt()).isEqualTo(NOW_UTC);
        assertThat(s.getClosedReason()).isEqualTo("BID_REJECTED");
        verify(events).publishEvent(new com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent(
                s.getId(), bid.getId(), BidSupplementRail.CASH, BidSupplementStatus.PAID, "BID_REJECTED"));
        verify(auditService).log(eq("BID_SUPPLEMENT"), eq(s.getId()), eq("BID_SUPPLEMENT_CASH_CANCELLED_BID_ENDED"),
                eq(null), anyMap());
    }

    @Test
    void onBidEnded_supplementEspecesDejaRembourse_rienDeNeuf() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAID_OUT);
        s.setRail(BidSupplementRail.CASH);
        bid.setStatus(BidStatus.CANCELLED);
        lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onBidEnded(bid.getId(), "BID_REJECTED")).isFalse();
        verify(events, never()).publishEvent(any());
    }

    // ── Rail espèces : commission en attente ─────────────────────────────────

    @Test
    void create_commissionCarteEnAttente_expediteurPasEncorePrevenu() {
        BidSupplementRailHandler cash = mock(BidSupplementRailHandler.class);
        when(cash.rail()).thenReturn(BidSupplementRail.CASH);
        bid.setPaymentMethod(PaymentMethod.CASH);
        org.mockito.Mockito.doAnswer(inv -> {
            BidSupplementEntity created = inv.getArgument(0);
            created.setStatus(BidSupplementStatus.COMMISSION_PENDING);
            return null;
        }).when(cash).onCreated(any(), any());

        BidSupplementResponse r = service(cash).create(bid.getId(), "uid-traveler", requestFor("20"));

        assertThat(r.status()).isEqualTo("COMMISSION_PENDING");
        assertThat(r.blocksHandover()).isTrue();
        verify(events, never()).publishEvent(any(BidSupplementRequestedEvent.class));
        verify(auditService).log(eq("BID_SUPPLEMENT"), any(), eq("BID_SUPPLEMENT_REQUESTED"), eq(traveler.getId()),
                anyMap());
    }

    @Test
    void withdraw_avantLaCommission_motifDistinct() {
        BidSupplementEntity s = existing(BidSupplementStatus.COMMISSION_PENDING);
        s.setRail(BidSupplementRail.CASH);

        service(cardRail).withdraw(bid.getId(), s.getId(), "uid-traveler");

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.WITHDRAWN);
        assertThat(s.getClosedReason()).isEqualTo("WITHDRAWN_BEFORE_COMMISSION");
        assertThat(BidSupplementService.NEVER_PROPOSED_REASONS).contains("WITHDRAWN_BEFORE_COMMISSION",
                "COMMISSION_FAILED", "COMMISSION_NOT_CONFIRMED");
    }

    @Test
    void cancelUnconfirmedCommission_apres30Minutes_annulee() {
        BidSupplementEntity s = existing(BidSupplementStatus.COMMISSION_PENDING);
        s.setRail(BidSupplementRail.CASH);
        createdAt(s, NOW_UTC.minusMinutes(31));
        when(supplements.findBidIdById(s.getId())).thenReturn(Optional.of(bid.getId()));

        assertThat(service(cardRail).cancelUnconfirmedCommission(s.getId(), NOW_UTC.minusMinutes(30))).isTrue();

        verify(bids).lockForUpdate(bid.getId());
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.CANCELLED);
        assertThat(s.getClosedReason()).isEqualTo("COMMISSION_NOT_CONFIRMED");
        verify(events).publishEvent(any(BidSupplementCancelledEvent.class));
    }

    @Test
    void cancelUnconfirmedCommission_tropRecenteDejaProposeeOuInconnue_sansEffet() {
        BidSupplementEntity recent = existing(BidSupplementStatus.COMMISSION_PENDING);
        createdAt(recent, NOW_UTC.minusMinutes(5));
        BidSupplementEntity proposed = existing(BidSupplementStatus.PROPOSED);
        createdAt(proposed, NOW_UTC.minusHours(1));
        BidSupplementEntity noDate = existing(BidSupplementStatus.COMMISSION_PENDING);
        UUID unknown = UUID.randomUUID();
        when(supplements.findBidIdById(any())).thenReturn(Optional.of(bid.getId()));
        when(supplements.findBidIdById(unknown)).thenReturn(Optional.empty());
        LocalDateTime before = NOW_UTC.minusMinutes(30);

        assertThat(service(cardRail).cancelUnconfirmedCommission(recent.getId(), before)).isFalse();
        assertThat(service(cardRail).cancelUnconfirmedCommission(proposed.getId(), before)).isFalse();
        assertThat(service(cardRail).cancelUnconfirmedCommission(noDate.getId(), before)).isFalse();
        assertThat(service(cardRail).cancelUnconfirmedCommission(unknown, before)).isFalse();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void remboursementAdminDuPrincipal_colisEnCours_supplementPayeRembourse() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAID);
        bid.setStatus(BidStatus.COMPLETED);
        lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onPrincipalRefunded(bid.getId())).isTrue();

        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.REFUND_PENDING);
        assertThat(s.getClosedReason()).isEqualTo("ADMIN_REFUND");
        verify(events).publishEvent(new com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent(
                s.getId(), bid.getId(), BidSupplementRail.STRIPE, BidSupplementStatus.REFUND_PENDING, "ADMIN_REFUND"));
    }

    @Test
    void remboursementAdminDuPrincipal_litigeOuvert_supplementResteAPaye() {
        BidSupplementEntity s = existing(BidSupplementStatus.PAID);
        bid.setStatus(BidStatus.COMPLETED);
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(bid.getId(), "OPEN", "")).thenReturn(true);
        lenient().when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementService.SETTLING))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onPrincipalRefunded(bid.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.PAID);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void remboursementAdminDuPrincipal_colisTermine_memeRegleQueLaFinDuColis() {
        BidSupplementEntity s = existing(BidSupplementStatus.PROPOSED);
        bid.setStatus(BidStatus.CANCELLED);
        when(supplements.findByBidIdAndStatusInForUpdate(bid.getId(), BidSupplementStatus.OPEN))
                .thenReturn(Optional.of(s));

        assertThat(service(cardRail).onPrincipalRefunded(bid.getId())).isTrue();
        assertThat(s.getStatus()).isEqualTo(BidSupplementStatus.CANCELLED);
        assertThat(service(cardRail).onPrincipalRefunded(null)).isFalse();
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private BidSupplementEntity existing(BidSupplementStatus status) {
        BidSupplementEntity s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(bid.getId());
        s.setTravelerId(traveler.getId());
        s.setSenderId(sender.getId());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(status);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setReasonCode(BidSupplementReason.GRID_ITEM);
        s.setPhotoKey("tracking/" + bid.getId() + "/1.jpg");
        s.setExpiresAt(NOW_UTC.plusHours(1));
        lenient().when(supplements.findByIdAndBidIdForUpdate(s.getId(), bid.getId())).thenReturn(Optional.of(s));
        return s;
    }

    static void createdAt(BidSupplementEntity s, LocalDateTime at) {
        try {
            java.lang.reflect.Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(s, at);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        lenient().when(users.findByFirebaseUid(uid)).thenReturn(Optional.of(u));
        return u;
    }

    private static YadonyBusinessException assertError(Runnable call, HttpStatus status, String code) {
        try {
            call.run();
        } catch (YadonyBusinessException e) {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(code);
            return e;
        }
        throw new AssertionError("Exception " + code + " attendue");
    }
}
