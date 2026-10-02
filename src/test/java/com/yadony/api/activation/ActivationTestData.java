package com.yadony.api.activation;

import com.yadony.api.alerts.AlertDirection;
import com.yadony.api.alerts.AlertNotifyMode;
import com.yadony.api.alerts.CorridorAlertEntity;
import com.yadony.api.alerts.CorridorAlertRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.auth.UserBlockJpaRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.city.CityEntity;
import com.yadony.api.city.CityRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Jeux de données des tests du package activation (même socle que TripsSummaryRepositoryIT). */
final class ActivationTestData {

    private static final AtomicLong CITY_IDS = new AtomicLong(990_000_000L);

    private final UserRepository users;
    private final AnnouncementRepository announcements;
    private final PackageRequestRepository packageRequests;
    private final BidRepository bids;
    private final CorridorAlertRepository alerts;
    private final NegotiationThreadRepository threads;
    private final CityRepository cities;
    private UserBlockJpaRepository blocks;

    ActivationTestData(UserRepository users, AnnouncementRepository announcements,
                       PackageRequestRepository packageRequests, BidRepository bids,
                       CorridorAlertRepository alerts, NegotiationThreadRepository threads,
                       CityRepository cities) {
        this.users = users;
        this.announcements = announcements;
        this.packageRequests = packageRequests;
        this.bids = bids;
        this.alerts = alerts;
        this.threads = threads;
        this.cities = cities;
    }

    UUID user(KycStatus kycStatus, Instant kycVerifiedAt, int reminderCount) {
        UserEntity u = ActivationTestUsers.newUser("uid-" + UUID.randomUUID());
        u.setKycStatus(kycStatus);
        u.setKycVerifiedAt(kycVerifiedAt);
        u.setFirstActionReminderCount(reminderCount);
        return users.saveAndFlush(u).getId();
    }

    UUID announcement(UUID travelerId, String dep, String arr, String arrCountry, LocalDate date,
                      AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity(dep);
        a.setArrivalCity(arr);
        a.setArrivalCountryCode(arrCountry);
        a.setDepartureDate(date);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel(dep);
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel(arr);
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(status);
        return announcements.saveAndFlush(a).getId();
    }

    void softDeleteAnnouncement(UUID id) {
        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.softDelete();
        announcements.saveAndFlush(a);
    }

    UUID packageRequest(UUID senderId, String dep, String arr, LocalDate date, PackageRequestStatus status) {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(senderId);
        colis.setDepartureCity(dep);
        colis.setArrivalCity(arr);
        colis.setDesiredDate(date);
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(new BigDecimal("2.00"));
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setStatus(status);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        return packageRequests.saveAndFlush(colis).getId();
    }

    void bid(UUID senderId, UUID announcementId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.PENDING);
        bids.saveAndFlush(bid);
    }

    void corridorAlert(UUID ownerId) {
        CorridorAlertEntity alert = new CorridorAlertEntity();
        alert.setOwnerId(ownerId);
        alert.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        alert.setDepartureCity("Paris");
        alert.setArrivalCity("Dakar");
        alert.setActive(true);
        alert.setNotifyMode(AlertNotifyMode.INSTANT);
        alerts.saveAndFlush(alert);
    }

    void negotiationThread(UUID travelerId, UUID packageRequestId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(travelerId);
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        threads.saveAndFlush(t);
    }

    ActivationTestData withBlocks(UserBlockJpaRepository blocks) {
        this.blocks = blocks;
        return this;
    }

    void block(UUID blocker, UUID blocked) {
        UserBlockEntity b = new UserBlockEntity();
        b.setBlockerId(blocker);
        b.setBlockedId(blocked);
        b.setCreatedAt(java.time.OffsetDateTime.now());
        blocks.saveAndFlush(b);
    }

    void reminded(UUID userId, int count, Instant lastAt) {
        UserEntity u = users.findById(userId).orElseThrow();
        u.setFirstActionReminderCount(count);
        u.setFirstActionReminderLastAt(lastAt);
        users.saveAndFlush(u);
    }

    void city(String name, String countryCode) {
        CityEntity c = new CityEntity();
        c.setId(CITY_IDS.incrementAndGet());
        c.setName(name);
        c.setCountryCode(countryCode);
        c.setCountryName(countryCode);
        c.setPopulation(1L);
        c.setLatitude(BigDecimal.ZERO);
        c.setLongitude(BigDecimal.ZERO);
        cities.saveAndFlush(c);
    }
}
