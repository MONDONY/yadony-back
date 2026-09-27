package com.yadony.api.kyc;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.dto.KycQueueItemResponse;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * File de revue KYC sur une vraie base (H2, mode PostgreSQL) : classement des lignes par
 * état métier, tri, filtres. La base est partagée entre les tests d'intégration : chaque
 * assertion ne regarde que les lignes créées ici.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("KycAdminReviewService.queue — file de revue KYC")
class KycAdminQueueIT {

    @Autowired KycAdminReviewService service;
    @Autowired UserRepository userRepository;
    @Autowired KycRepository kycRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean AdminEmailDirectory adminDirectory;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    private final String tag = "Zq" + UUID.randomUUID().toString().substring(0, 8);

    private UUID oldestReview;
    private UUID recentReview;
    private UUID inProgress;
    private UUID notStarted;
    private UUID rejected;
    private UUID verifiedOld;
    private UUID verifiedRecent;

    @BeforeEach
    void seed() {
        oldestReview = row("Awa", KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(30), NOW.minusHours(30), null);
        recentReview = row("Binta", KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.STRIPE,
                NOW.minusHours(2), NOW.minusHours(2), null);
        inProgress = row("Coumba", KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(1), null, null);
        notStarted = row("Dieynaba", KycStatus.NOT_STARTED, KycVerificationStatus.PENDING,
                VerificationProviderKind.DIDIT, NOW.minusHours(5), null, null);
        rejected = row("Fatou", KycStatus.REJECTED, KycVerificationStatus.REJECTED, VerificationProviderKind.DIDIT,
                NOW.minusDays(1), NOW.minusDays(2), KycDecisionKind.REJECTED);
        verifiedOld = row("Gnima", KycStatus.VERIFIED, KycVerificationStatus.VERIFIED, VerificationProviderKind.DIDIT,
                LocalDateTime.of(2026, 9, 1, 12, 0), null, null);
        verifiedRecent = row("Hawa", KycStatus.VERIFIED, KycVerificationStatus.VERIFIED,
                VerificationProviderKind.DIDIT, LocalDateTime.of(2026, 9, 10, 12, 0), null, KycDecisionKind.APPROVED);
        em.flush();
        em.clear();

        when(firebaseContact.getContacts(any())).thenAnswer(inv -> Map.of());
        when(adminDirectory.emailsOf(any())).thenReturn(Map.of(ADMIN_ID, "admin@yadony.test"));
    }

    private UUID row(String firstName, KycStatus userStatus, KycVerificationStatus rowStatus,
                     VerificationProviderKind provider, LocalDateTime updatedAt, LocalDateTime submittedAt,
                     KycDecisionKind decision) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setFirstName(firstName);
        user.setLastName(tag);
        user.setKycStatus(userStatus);
        user = userRepository.save(user);

        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setUserId(user.getId());
        kyc.setVerificationSessionId("sess-" + UUID.randomUUID());
        kyc.setProvider(provider);
        kyc.setStatus(rowStatus);
        // Par l'entité, pas par JDBC : Hibernate convertit les dates en UTC, un UPDATE JDBC
        // brut décalerait l'attente du fuseau de la machine.
        kyc.setSubmittedAt(submittedAt);
        if (decision != null) {
            kyc.setDecisionKind(decision);
            kyc.setDecidedByAdminId(ADMIN_ID);
            kyc.setDecidedAt(updatedAt);
            kyc.setDecisionReason("motif interne");
        }
        kyc = kycRepository.save(kyc);
        em.flush();
        // updated_at n'a pas de setter (@PreUpdate) : seul le tri relatif et le jour comptent ici.
        jdbc.update("UPDATE kyc_schema.kyc_verifications SET updated_at = ? WHERE id = ?", updatedAt, kyc.getId());
        return user.getId();
    }

    private List<UUID> ours(Page<KycQueueItemResponse> page) {
        Set<UUID> mine = Set.of(oldestReview, recentReview, inProgress, notStarted, rejected, verifiedOld,
                verifiedRecent);
        return page.getContent().stream().map(KycQueueItemResponse::userId).filter(mine::contains).toList();
    }

    private Page<KycQueueItemResponse> queue(String status, VerificationProviderKind provider, String query,
                                             LocalDate from, LocalDate to) {
        return service.queue(status, provider, query, from, to, 0, 100);
    }

    @Test
    void parDefaut_enRevue_duPlusAncienAuPlusRecent() {
        Page<KycQueueItemResponse> page = queue(null, null, tag, null, null);

        assertThat(ours(page)).containsExactly(oldestReview, recentReview);
        KycQueueItemResponse first = page.getContent().get(0);
        assertThat(first.queueStatus()).isEqualTo("IN_REVIEW");
        assertThat(first.recordStatus()).isEqualTo("PENDING");
        assertThat(first.kycStatus()).isEqualTo("PENDING");
        assertThat(first.provider()).isEqualTo("DIDIT");
        assertThat(first.userName()).isEqualTo("Awa " + tag);
        assertThat(first.waitingHours()).isBetween(29L, 31L);
        assertThat(first.submittedAt()).isNotNull();
    }

    @Test
    void enCours_etNonDemarre_sontDistingues() {
        assertThat(ours(queue("IN_PROGRESS", null, tag, null, null))).containsExactly(inProgress);
        assertThat(ours(queue("NOT_STARTED", null, tag, null, null))).containsExactly(notStarted);
    }

    @Test
    void verifies_duPlusRecentAuPlusAncien_avecLAuteurDeLaDecision() {
        Page<KycQueueItemResponse> page = queue("VERIFIED", null, tag, null, null);

        assertThat(ours(page)).containsExactly(verifiedRecent, verifiedOld);
        KycQueueItemResponse recent = page.getContent().get(0);
        assertThat(recent.decisionKind()).isEqualTo("APPROVED");
        assertThat(recent.decidedByAdminEmail()).isEqualTo("admin@yadony.test");
        assertThat(recent.waitingHours()).as("seule une demande en revue attend").isNull();
    }

    @Test
    void refuses() {
        Page<KycQueueItemResponse> page = queue("REJECTED", null, tag, null, null);

        assertThat(ours(page)).containsExactly(rejected);
        assertThat(page.getContent().get(0).decisionKind()).isEqualTo("REJECTED");
    }

    @Test
    void filtreParFournisseur() {
        assertThat(ours(queue("IN_REVIEW", VerificationProviderKind.STRIPE, tag, null, null)))
                .containsExactly(recentReview);
    }

    @Test
    void rechercheParNom_insensibleALaCasse_etParUuid() {
        assertThat(ours(queue("IN_REVIEW", null, "awa " + tag.toLowerCase(), null, null)))
                .containsExactly(oldestReview);
        assertThat(ours(queue("VERIFIED", null, verifiedOld.toString(), null, null))).containsExactly(verifiedOld);
    }

    @Test
    void rechercheParTelephone_resoluParFirebase_etMasque() {
        UserEntity user = userRepository.findById(recentReview).orElseThrow();
        when(firebaseContact.findUidByPhone("+33612345678")).thenReturn(Optional.of(user.getFirebaseUid()));
        when(firebaseContact.getContacts(any())).thenReturn(Map.of(user.getFirebaseUid(),
                new FirebaseContactService.Contact("+33612345678", "x@y.z")));

        Page<KycQueueItemResponse> page = queue("IN_REVIEW", null, "+33612345678", null, null);

        assertThat(page.getContent()).extracting(KycQueueItemResponse::userId).containsExactly(recentReview);
        assertThat(page.getContent().get(0).userPhone()).isEqualTo("•••• 5678");
    }

    @Test
    void telephoneInconnu_pageVide() {
        when(firebaseContact.findUidByPhone("+33600000000")).thenReturn(Optional.empty());

        assertThat(queue("IN_REVIEW", null, "+33600000000", null, null).getContent()).isEmpty();
    }

    @Test
    void filtreParDates_bornesIncluses() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        assertThat(ours(queue("VERIFIED", null, tag, day, day))).containsExactly(verifiedRecent);
        assertThat(ours(queue("VERIFIED", null, tag, null, LocalDate.of(2026, 9, 9)))).containsExactly(verifiedOld);
    }

    @Test
    void statutInconnu_400() {
        assertThatThrownBy(() -> queue("PERDU", null, null, null, null))
                .isInstanceOfSatisfying(com.yadony.api.common.YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("kyc-queue-status-invalid"));
    }

    @Test
    void tailleDePagePlafonneeA100() {
        assertThat(service.queue(null, null, null, null, null, 0, 500).getSize()).isEqualTo(100);
        assertThat(service.queue(null, null, null, null, null, -1, 0).getSize()).isEqualTo(20);
    }

}
