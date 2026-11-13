package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Règle de versement figée à l'acceptation")
class PayoutTriggerPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:30:00Z");

    @Mock PlatformSettingsService settings;
    @Mock UserRepository users;

    private PayoutTriggerPolicy policy;
    private UserEntity gpPro;
    private UserEntity proAccount;
    private UserEntity kycOnly;
    private AnnouncementEntity boatTrip;
    private AnnouncementEntity planeTrip;

    @BeforeEach
    void setUp() {
        policy = new PayoutTriggerPolicy(settings, new BoatCarrierPolicy(), users);
        policy.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        gpPro = user(true, false);
        proAccount = user(false, true);
        kycOnly = user(false, false);
        boatTrip = trip(TransportMode.BOAT, gpPro);
        planeTrip = trip(TransportMode.PLANE, gpPro);
        boatEnabled(true);
    }

    private static UserEntity user(boolean gpPro, boolean proAccount) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setGpPro(gpPro);
        u.setProAccount(proAccount);
        return u;
    }

    private static AnnouncementEntity trip(TransportMode mode, UserEntity owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setTransportMode(mode);
        a.setTravelerId(owner.getId());
        return a;
    }

    private void boatEnabled(boolean enabled) {
        lenient().when(settings.transportBoatEnabled()).thenReturn(enabled);
    }

    private static BidEntity pendingBid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        return b;
    }

    // ── decide ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bateau, interrupteur allumé, GP Pro : au dépôt")
    void boatEnabledVerified_deposit() {
        assertThat(policy.decide(boatTrip, gpPro)).isEqualTo(PayoutTrigger.DEPOSIT);
    }

    @Test
    @DisplayName("bateau, interrupteur allumé, compte PRO : au dépôt")
    void boatEnabledProAccount_deposit() {
        AnnouncementEntity trip = trip(TransportMode.BOAT, proAccount);
        assertThat(policy.decide(trip, proAccount)).isEqualTo(PayoutTrigger.DEPOSIT);
    }

    @Test
    @DisplayName("bateau, interrupteur éteint : à la livraison")
    void boatDisabled_delivery() {
        boatEnabled(false);
        assertThat(policy.decide(boatTrip, gpPro)).isEqualTo(PayoutTrigger.DELIVERY);
    }

    @Test
    @DisplayName("bateau, transporteur seulement vérifié par KYC : à la livraison")
    void boatUnverifiedTraveler_delivery() {
        AnnouncementEntity trip = trip(TransportMode.BOAT, kycOnly);
        assertThat(policy.decide(trip, kycOnly)).isEqualTo(PayoutTrigger.DELIVERY);
    }

    @Test
    @DisplayName("avion, même transporteur vérifié et interrupteur allumé : à la livraison")
    void plane_delivery() {
        assertThat(policy.decide(planeTrip, gpPro)).isEqualTo(PayoutTrigger.DELIVERY);
    }

    @ParameterizedTest(name = "{0} : à la livraison")
    @EnumSource(value = TransportMode.class, names = "BOAT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("tout mode autre que le bateau : à la livraison, interrupteur jamais lu")
    void everyOtherMode_delivery(TransportMode mode) {
        assertThat(policy.decide(trip(mode, gpPro), gpPro)).isEqualTo(PayoutTrigger.DELIVERY);
        verify(settings, never()).transportBoatEnabled();
    }

    @Test
    @DisplayName("mode inconnu (null), trajet ou transporteur absent : à la livraison")
    void missingFacts_delivery() {
        AnnouncementEntity noMode = trip(TransportMode.BOAT, gpPro);
        noMode.setTransportMode(null);
        assertThat(policy.decide(noMode, gpPro)).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(policy.decide(null, gpPro)).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(policy.decide(boatTrip, null)).isEqualTo(PayoutTrigger.DELIVERY);
    }

    @Test
    @DisplayName("transporteur qui n'est pas le propriétaire du trajet : à la livraison")
    void travelerIsNotTheTripOwner_delivery() {
        UserEntity otherGpPro = user(true, false);
        assertThat(policy.decide(boatTrip, otherGpPro)).isEqualTo(PayoutTrigger.DELIVERY);
    }

    // ── freeze ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("première acceptation : règle décidée et figée, avec son horodatage")
    void freeze_firstAcceptance_writesDecisionAndInstant() {
        BidEntity bid = pendingBid();

        policy.freeze(bid, boatTrip, gpPro);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DEPOSIT);
        assertThat(bid.isPayoutTriggerFrozen()).isTrue();
        assertThat(bid.getPayoutTriggerFrozenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("première acceptation à la livraison : figée aussi, une réacceptation ne passera pas au dépôt")
    void freeze_deliveryIsFrozenToo() {
        boatEnabled(false);
        BidEntity bid = pendingBid();

        policy.freeze(bid, boatTrip, gpPro);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isTrue();
    }

    @Test
    @DisplayName("colis réaccepté après une remise en attente : garde la règle de la première acceptation")
    void freeze_reacceptance_keepsFirstDecision() {
        boatEnabled(false);
        BidEntity bid = pendingBid();
        policy.freeze(bid, boatTrip, gpPro);
        LocalDateTime firstFrozenAt = bid.getPayoutTriggerFrozenAt();
        bid.setStatus(BidStatus.PAYMENT_ESCROWED);
        boatEnabled(true);
        policy.setClock(Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));

        policy.freeze(bid, boatTrip, gpPro);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.getPayoutTriggerFrozenAt()).isEqualTo(firstFrozenAt);
    }

    @Test
    @DisplayName("colis réaccepté au dépôt : reste au dépôt même si la règle dirait livraison")
    void freeze_reacceptance_keepsFirstDeposit() {
        BidEntity bid = pendingBid();
        policy.freeze(bid, boatTrip, gpPro);
        bid.setStatus(BidStatus.PAYMENT_ESCROWED);
        boatEnabled(false);

        policy.freeze(bid, boatTrip, gpPro);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DEPOSIT);
    }

    @Test
    @DisplayName("colis déjà accepté : aucune écriture (ce n'est pas un passage à ACCEPTED)")
    void freeze_alreadyAccepted_noWrite() {
        BidEntity bid = pendingBid();
        bid.setStatus(BidStatus.ACCEPTED);

        policy.freeze(bid, boatTrip, gpPro);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isFalse();
        verifyNoInteractions(settings);
    }

    @Test
    @DisplayName("trajet introuvable : figé à la livraison, sans exception (appelé après l'argent encaissé)")
    void freeze_withoutTrip_frozenDeliveryWithoutThrowing() {
        BidEntity bid = pendingBid();

        policy.freeze(bid, null);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isTrue();
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("sans transporteur fourni : relu en base, le propriétaire du trajet à cet instant")
    void freeze_resolvesTripOwnerFromDatabase() {
        when(users.findById(gpPro.getId())).thenReturn(Optional.of(gpPro));
        BidEntity bid = pendingBid();

        policy.freeze(bid, boatTrip);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DEPOSIT);
    }

    @Test
    @DisplayName("propriétaire du trajet introuvable : figé à la livraison")
    void freeze_ownerMissing_delivery() {
        when(users.findById(gpPro.getId())).thenReturn(Optional.empty());
        BidEntity bid = pendingBid();

        policy.freeze(bid, boatTrip);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isTrue();
    }

    @Test
    @DisplayName("relecture du transporteur : rien n'est lu pour un colis déjà accepté ou déjà figé")
    void freeze_resolvingOwner_skipsLookupWhenNothingToFreeze() {
        BidEntity accepted = pendingBid();
        accepted.setStatus(BidStatus.ACCEPTED);
        BidEntity frozen = pendingBid();
        policy.freeze(frozen, boatTrip, gpPro);
        frozen.setStatus(BidStatus.PENDING);

        policy.freeze(accepted, boatTrip);
        policy.freeze(frozen, boatTrip);

        assertThat(accepted.isPayoutTriggerFrozen()).isFalse();
        assertThat(frozen.getPayoutTrigger()).isEqualTo(PayoutTrigger.DEPOSIT);
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("trajet sans propriétaire connu : figé à la livraison sans lecture")
    void freeze_tripWithoutOwner_deliveryWithoutLookup() {
        AnnouncementEntity orphan = trip(TransportMode.BOAT, gpPro);
        orphan.setTravelerId(null);
        BidEntity bid = pendingBid();

        policy.freeze(bid, orphan);

        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isTrue();
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("un colis neuf naît à la livraison, non figé")
    void newBid_deliveryNotFrozen() {
        BidEntity bid = new BidEntity();
        assertThat(bid.getPayoutTrigger()).isEqualTo(PayoutTrigger.DELIVERY);
        assertThat(bid.isPayoutTriggerFrozen()).isFalse();
        assertThat(bid.getPayoutTriggerFrozenAt()).isNull();
    }
}
