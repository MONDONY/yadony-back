package com.yadony.api.requests.repository;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Candidats au rappel automatique, en SQL réel (H2, profil test) : les bornes de temps et les
 * garde-fous vivent dans la chaîne JPQL, un test sur la seule liste Java ne les prouverait pas.
 */
@DataJpaTest
@ActiveProfiles("test")
class NegotiationThreadRepositoryAutoReminderTest {

    @Autowired private NegotiationThreadRepository threadRepo;
    @Autowired private PackageRequestRepository requestRepo;

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    private LocalDateTime silentSince;
    private LocalDateTime notOlderThan;
    private UUID requestId;

    @BeforeEach
    void setUp() {
        silentSince = now.minusHours(24);
        notOlderThan = now.minusHours(48);
        PackageRequestEntity r = new PackageRequestEntity();
        r.setSenderId(UUID.randomUUID());
        r.setDepartureCity("Paris");
        r.setArrivalCity("Dakar");
        r.setDesiredDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        r.setDateToleranceDays((short) 2);
        r.setWeightKg(new BigDecimal("5"));
        r.setParcelSize(ParcelSize.SMALL);
        r.setTransportMode(TransportMode.PLANE);
        r.setContentCategory("vetements");
        r.setStatus(PackageRequestStatus.NEGOTIATING);
        requestId = requestRepo.saveAndFlush(r).getId();
    }

    private NegotiationThreadEntity thread(NegotiationThreadStatus status, LocalDateTime lastActivityAt) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(requestId);
        t.setTravelerId(UUID.randomUUID());
        t.setTravelerTravelDate(LocalDate.now(ZoneOffset.UTC).plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("10"));
        t.setStatus(status);
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("35"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(lastActivityAt);
        return t;
    }

    private List<UUID> candidates() {
        return threadRepo.findAutoReminderCandidates(silentSince, notOlderThan);
    }

    @Test
    @DisplayName("fil OPEN silencieux depuis 30 h : candidat")
    void openAndSilent_isCandidate() {
        NegotiationThreadEntity t = threadRepo.saveAndFlush(thread(NegotiationThreadStatus.OPEN, now.minusHours(30)));

        assertThat(candidates()).containsExactly(t.getId());
    }

    @Test
    @DisplayName("fil trop récent (10 h) : pas encore de rappel")
    void tooRecent_isNotCandidate() {
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.OPEN, now.minusHours(10)));

        assertThat(candidates()).isEmpty();
    }

    @Test
    @DisplayName("fil trop ancien (60 h) : on ne réveille pas un fil mort")
    void tooOld_isNotCandidate() {
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.OPEN, now.minusHours(60)));

        assertThat(candidates()).isEmpty();
    }

    @Test
    @DisplayName("seul un fil OPEN est rappelé")
    void otherStatuses_areNotCandidates() {
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.AWAITING_TRIP, now.minusHours(30)));
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.AWAITING_PAYMENT, now.minusHours(30)));
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.CANCELLED, now.minusHours(30)));
        threadRepo.saveAndFlush(thread(NegotiationThreadStatus.EXPIRED, now.minusHours(30)));

        assertThat(candidates()).isEmpty();
    }

    @Test
    @DisplayName("déjà rappelé depuis la dernière activité : exclu ; rappel plus ancien que l'activité : de nouveau candidat")
    void oneReminderPerWaitingPeriod() {
        NegotiationThreadEntity reminded = thread(NegotiationThreadStatus.OPEN, now.minusHours(30));
        reminded.setAutoReminderAt(now.minusHours(2));
        threadRepo.saveAndFlush(reminded);
        NegotiationThreadEntity staleReminder = thread(NegotiationThreadStatus.OPEN, now.minusHours(30));
        staleReminder.setAutoReminderAt(now.minusHours(40));
        // L'entité porte un @Version non nul : save() fusionne et rend une copie, c'est elle qui a l'id.
        staleReminder = threadRepo.saveAndFlush(staleReminder);

        assertThat(candidates()).containsExactly(staleReminder.getId());
    }

    @Test
    @DisplayName("relance manuelle récente : exclu ; relance manuelle ancienne : candidat")
    void recentManualNudge_excludes() {
        NegotiationThreadEntity recentNudge = thread(NegotiationThreadStatus.OPEN, now.minusHours(30));
        recentNudge.setLastNudgeAt(now.minusHours(3));
        threadRepo.saveAndFlush(recentNudge);
        NegotiationThreadEntity oldNudge = thread(NegotiationThreadStatus.OPEN, now.minusHours(30));
        oldNudge.setLastNudgeAt(now.minusHours(28));
        oldNudge = threadRepo.saveAndFlush(oldNudge);

        assertThat(candidates()).containsExactly(oldNudge.getId());
    }

    @Test
    @DisplayName("les candidats sont triés du plus ancien silence au plus récent")
    void orderedByOldestActivityFirst() {
        NegotiationThreadEntity newer = threadRepo.saveAndFlush(thread(NegotiationThreadStatus.OPEN, now.minusHours(26)));
        NegotiationThreadEntity older = threadRepo.saveAndFlush(thread(NegotiationThreadStatus.OPEN, now.minusHours(40)));

        assertThat(candidates()).containsExactly(older.getId(), newer.getId());
    }
}
