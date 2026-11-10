package com.yadony.api.travelerstatus;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.gppro.GpProApplicationEntity;
import com.yadony.api.gppro.GpProApplicationRepository;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.travelerstatus.dto.PublishingEligibilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishingEligibilityServiceTest {

    /** 15 novembre 2026 : le mois suivant commence le 1er décembre. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("UTC"));

    @Mock AnnouncementRepository announcements;
    @Mock GpProApplicationRepository applications;

    PublishingEligibilityService service;
    UserEntity user;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PublishingEligibilityService(announcements, applications, CLOCK);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        used(0);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.empty());
    }

    private void used(long n) {
        when(announcements.countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                eq(userId), any(), any(), eq(AnnouncementStatus.DRAFT))).thenReturn(n);
    }

    @Test
    void limiteNulle_toujoursOk_memeTresAuDessus() {
        used(500);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isTrue();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.OK);
        assertThat(r.limit()).isNull();
        assertThat(r.used()).isEqualTo(500);
    }

    @Test
    void gpProValide_neJamaisLimite_memeAvecUneLimiteSaisieEtAtteinte() {
        user.setGpPro(true);
        user.setMonthlyTripLimit(2);
        user.setTravelerStatus(TravelerStatus.PROFESSIONAL);
        used(7);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isTrue();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.OK);
        assertThat(r.limit()).isNull();
        assertThat(r.used()).isEqualTo(7);
    }

    @Test
    void sousLaLimite_ok_avecLaLimiteRenvoyee() {
        user.setMonthlyTripLimit(3);
        used(2);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isTrue();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.OK);
        assertThat(r.limit()).isEqualTo(3);
        assertThat(r.used()).isEqualTo(2);
    }

    @Test
    void limiteAtteinte_particulier_limitReached() {
        user.setMonthlyTripLimit(3);
        user.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        used(3);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.LIMIT_REACHED);
        assertThat(r.limit()).isEqualTo(3);
        assertThat(r.used()).isEqualTo(3);
        assertThat(r.travelerStatus()).isEqualTo(TravelerStatus.INDIVIDUAL);
    }

    @Test
    void limiteAtteinte_sansStatut_statusRequired_pasLimitReached() {
        user.setMonthlyTripLimit(1);
        used(4);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.STATUS_REQUIRED);
        assertThat(r.travelerStatus()).isNull();
    }

    @Test
    void limiteAtteinte_demandeGpProEnAttente_gpProPending_memeSansStatut() {
        user.setMonthlyTripLimit(1);
        used(1);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), any()))
                .thenReturn(Optional.of(new GpProApplicationEntity()));

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo(PublishingEligibilityReason.GP_PRO_PENDING);
    }

    @Test
    void demandeEnAttenteMaisSousLaLimite_ok() {
        user.setMonthlyTripLimit(5);
        used(1);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), any()))
                .thenReturn(Optional.of(new GpProApplicationEntity()));

        assertThat(service.evaluate(user).reason()).isEqualTo(PublishingEligibilityReason.OK);
    }

    @Test
    void resetsOn_premierJourDuMoisSuivant_etFenetreDuMoisCivilCourant() {
        user.setMonthlyTripLimit(2);

        PublishingEligibilityResponse r = service.evaluate(user);

        assertThat(r.resetsOn()).isEqualTo(LocalDate.of(2026, 12, 1));
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(announcements).countByTravelerIdAndCreatedAtBetweenAndStatusNot(
                eq(userId), from.capture(), to.capture(), eq(AnnouncementStatus.DRAFT));
        assertThat(from.getValue()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertThat(to.getValue()).isEqualTo(LocalDateTime.of(2026, 11, 30, 23, 59, 59));
    }

    @Test
    void resetsOn_findDAnnee_janvierSuivant() {
        PublishingEligibilityService decembre = new PublishingEligibilityService(announcements, applications,
                Clock.fixed(Instant.parse("2026-12-31T22:00:00Z"), ZoneId.of("UTC")));

        assertThat(decembre.evaluate(user).resetsOn()).isEqualTo(LocalDate.of(2027, 1, 1));
    }
}
