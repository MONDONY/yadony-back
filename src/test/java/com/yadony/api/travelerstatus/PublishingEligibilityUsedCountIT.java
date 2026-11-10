package com.yadony.api.travelerstatus;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le compteur du mois porte sur la PUBLICATION, pas sur la création : un brouillon créé le mois précédent
 * puis publié ce mois-ci compte ce mois-ci (aucune date de publication n'existe : on s'appuie sur la dernière
 * mise à jour des trajets non brouillons).
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishingEligibilityUsedCountIT {

    @Autowired PublishingEligibilityService service;
    @Autowired UserRepository users;
    @Autowired AnnouncementRepository announcements;
    @Autowired JdbcTemplate jdbc;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        announcements.deleteAll();
        users.deleteAll();
        user = new UserEntity();
        user.setFirebaseUid("uid-used-count");
        user.setUsername("used-count");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setKycStatus(KycStatus.VERIFIED);
        user.setMonthlyTripLimit(10);
        user = users.save(user);
    }

    private UUID trip(AnnouncementStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(user.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(20));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(status);
        UUID id = announcements.saveAndFlush(a).getId();
        jdbc.update("UPDATE announcements SET created_at = ?, updated_at = ? WHERE id = ?",
                createdAt, updatedAt, id);
        return id;
    }

    @Test
    void brouillonCreeLeMoisPrecedentEtPublieCeMoisCi_compteDansUsed() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime lastMonth = now.minusMonths(1).withDayOfMonth(15);
        // Brouillon d'avant, publié maintenant : created_at ancien, updated_at ce mois-ci, statut ACTIVE.
        trip(AnnouncementStatus.ACTIVE, lastMonth, now);

        assertThat(service.evaluate(users.findById(user.getId()).orElseThrow()).used()).isEqualTo(1);
    }

    @Test
    void compteLesTrajetsPublies_pasLesBrouillons_pasLesTrajetsDuMoisPrecedent() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime lastMonth = now.minusMonths(1).withDayOfMonth(15);
        trip(AnnouncementStatus.ACTIVE, now, now);                  // publié directement ce mois-ci : compte
        trip(AnnouncementStatus.DRAFT, now, now);                   // brouillon de ce mois-ci : ne compte pas
        trip(AnnouncementStatus.DRAFT, lastMonth, now);             // brouillon retravaillé : ne compte pas
        trip(AnnouncementStatus.ACTIVE, lastMonth, lastMonth);      // publié le mois dernier : ne compte pas
        trip(AnnouncementStatus.ACTIVE, lastMonth, now);            // brouillon publié ce mois-ci : compte

        assertThat(service.evaluate(users.findById(user.getId()).orElseThrow()).used()).isEqualTo(2);
    }

    @Test
    void unTrajetCompteUneSeuleFois_creeEtMisAJourCeMois() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        trip(AnnouncementStatus.FULL, now, now);

        assertThat(service.evaluate(users.findById(user.getId()).orElseThrow()).used()).isEqualTo(1);
    }
}
