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

    private UUID trip(AnnouncementStatus status) {
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
        return announcements.saveAndFlush(a).getId();
    }

    /** Antidate la création et la publication (colonnes posées par la base, pas modifiables via l'entité). */
    private void backdate(UUID id, LocalDateTime createdAt, LocalDateTime publishedAt) {
        jdbc.update("UPDATE announcements SET created_at = ?, updated_at = ?, published_at = ? WHERE id = ?",
                createdAt, createdAt, publishedAt, id);
    }

    private int used() {
        return service.evaluate(users.findById(user.getId()).orElseThrow()).used();
    }

    @Test
    void brouillonCreeLeMoisPrecedentEtPublieCeMoisCi_compteCeMoisCi() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID id = trip(AnnouncementStatus.DRAFT);
        backdate(id, now.minusMonths(1).withDayOfMonth(15), null);
        assertThat(used()).isZero();

        // Ce que fait publishAnnouncement : DRAFT vers ACTIVE, date de publication posée.
        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setPublishedAt(now);
        announcements.saveAndFlush(a);

        assertThat(used()).isEqualTo(1);
    }

    @Test
    void trajetPublieLeMoisPrecedent_puisModifieAccepteOuComplet_neCompteJamaisCeMoisCi() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime lastMonth = now.minusMonths(1).withDayOfMonth(15);
        UUID id = trip(AnnouncementStatus.ACTIVE);
        backdate(id, lastMonth, lastMonth);

        // Modification, puis passage FULL, puis COMPLETED : updated_at est ce mois-ci à chaque fois.
        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.setPricePerKg(new BigDecimal("6.00"));
        announcements.saveAndFlush(a);
        a = announcements.findById(id).orElseThrow();
        a.setStatus(AnnouncementStatus.FULL);
        announcements.saveAndFlush(a);
        a = announcements.findById(id).orElseThrow();
        a.setStatus(AnnouncementStatus.COMPLETED);
        announcements.saveAndFlush(a);

        assertThat(announcements.findById(id).orElseThrow().getUpdatedAt().getMonth()).isEqualTo(now.getMonth());
        // Date inchangée : toujours le mois précédent (le JDBC brut écrit en fuseau local, d'où la comparaison au mois).
        assertThat(announcements.findById(id).orElseThrow().getPublishedAt().getMonth()).isEqualTo(lastMonth.getMonth());
        assertThat(used()).isZero();
    }

    @Test
    void trajetCreeNonBrouillonCeMoisCi_estDatePuisCompteUneSeuleFois_quelQueSoitSonStatutEnsuite() {
        UUID id = trip(AnnouncementStatus.ACTIVE);
        AnnouncementEntity created = announcements.findById(id).orElseThrow();
        assertThat(created.getPublishedAt()).isNotNull();
        LocalDateTime stamp = created.getPublishedAt();
        assertThat(used()).isEqualTo(1);

        // Un changement de statut ne change ni la date ni le compte.
        created.setStatus(AnnouncementStatus.FULL);
        announcements.saveAndFlush(created);
        assertThat(announcements.findById(id).orElseThrow().getPublishedAt()).isEqualTo(stamp);
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void brouillonNonPublie_pasDeDate_neCompteJamais() {
        UUID id = trip(AnnouncementStatus.DRAFT);

        assertThat(announcements.findById(id).orElseThrow().getPublishedAt()).isNull();
        assertThat(used()).isZero();
    }

    @Test
    void plusieursTrajetsDuMois_brouillonsExclus() {
        trip(AnnouncementStatus.ACTIVE);
        trip(AnnouncementStatus.ACTIVE);
        trip(AnnouncementStatus.DRAFT);

        assertThat(used()).isEqualTo(2);
    }

    @Test
    void trajetPublieEtSupprimeEnDouceur_resteCompte_pasDeContournementParSuppression() {
        UUID id = trip(AnnouncementStatus.ACTIVE);
        assertThat(used()).isEqualTo(1);

        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.softDelete();
        announcements.saveAndFlush(a);

        assertThat(announcements.findById(id)).isEmpty();
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void trajetPublieLaDerniereSecondeDuMois_estCompte_intervalleSemiOuvert() {
        UUID id = trip(AnnouncementStatus.ACTIVE);
        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.setPublishedAt(java.time.YearMonth.now(ZoneOffset.UTC).atEndOfMonth().atTime(23, 59, 59, 500_000_000));
        announcements.saveAndFlush(a);

        assertThat(used()).isEqualTo(1);
    }

    @Test
    void trajetPublieLeTouteDebutDuMoisSuivant_nEstPasCompteCeMois() {
        UUID id = trip(AnnouncementStatus.ACTIVE);
        AnnouncementEntity a = announcements.findById(id).orElseThrow();
        a.setPublishedAt(java.time.YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(1).atStartOfDay());
        announcements.saveAndFlush(a);

        assertThat(used()).isZero();
    }
}
