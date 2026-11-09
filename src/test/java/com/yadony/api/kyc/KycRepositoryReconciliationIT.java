package com.yadony.api.kyc;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requêtes du rattrapage KYC sur une vraie base (H2, mode PostgreSQL). La base est partagée entre
 * les tests d'intégration : chaque assertion ne regarde que les lignes créées ici.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("KycRepository — requêtes de rattrapage")
class KycRepositoryReconciliationIT {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);
    private static final PageRequest ALL = PageRequest.of(0, 500);

    @Autowired UserRepository userRepository;
    @Autowired KycRepository kycRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @MockitoBean FirebaseContactService firebaseContact;
    @MockitoBean AdminEmailDirectory adminDirectory;

    private UUID row(KycStatus userStatus, KycVerificationStatus rowStatus, VerificationProviderKind provider,
                     LocalDateTime updatedAt, LocalDateTime submittedAt, KycDecisionKind decision) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setKycStatus(userStatus);
        user = userRepository.save(user);

        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setUserId(user.getId());
        kyc.setVerificationSessionId("sess-" + UUID.randomUUID());
        kyc.setProvider(provider);
        kyc.setStatus(rowStatus);
        kyc.setSubmittedAt(submittedAt);
        if (decision != null) {
            kyc.setDecisionKind(decision);
            kyc.setDecidedByAdminId(UUID.randomUUID());
            kyc.setDecidedAt(updatedAt);
            kyc.setDecisionReason("motif interne");
        }
        kycRepository.save(kyc);
        em.flush();
        jdbc.update("UPDATE kyc_schema.kyc_verifications SET updated_at = ? WHERE user_id = ?",
                updatedAt, user.getId());
        return user.getId();
    }

    @Test
    void findStalePending_garde_seulement_les_sessions_Didit_dont_l_utilisateur_attend() {
        UUID stale = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(2), null, null);
        UUID inReview = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(3), NOW.minusHours(3), null);
        UUID tooRecent = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusMinutes(10), null, null);
        UUID tooOld = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusDays(5), null, null);
        UUID abandoned = row(KycStatus.NOT_STARTED, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(2), null, null);
        UUID stripe = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.STRIPE,
                NOW.minusHours(2), null, null);
        UUID lockedByAdmin = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(2), null, KycDecisionKind.REJECTED);
        UUID verified = row(KycStatus.VERIFIED, KycVerificationStatus.VERIFIED, VerificationProviderKind.DIDIT,
                NOW.minusHours(2), null, null);
        em.clear();

        Set<UUID> mine = Set.of(stale, inReview, tooRecent, tooOld, abandoned, stripe, lockedByAdmin, verified);
        List<UUID> found = kycRepository.findStalePending(VerificationProviderKind.DIDIT,
                        NOW.minusMinutes(30), NOW.minusDays(3), ALL).stream()
                .map(KycVerificationEntity::getUserId).filter(mine::contains).toList();

        // Les plus recentes d'abord : la revue de 3 h passe apres la session de 2 h.
        assertThat(found).containsExactly(stale, inReview);
    }

    @Test
    void findInReviewSince_garde_les_revues_Didit_anciennes_sans_decision_d_admin() {
        UUID old = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(6), NOW.minusHours(6), null);
        UUID older = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(9), NOW.minusHours(9), null);
        UUID fresh = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusMinutes(20), NOW.minusMinutes(20), null);
        UUID notSubmitted = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(6), null, null);
        UUID stripe = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.STRIPE,
                NOW.minusHours(6), NOW.minusHours(6), null);
        UUID decided = row(KycStatus.PENDING, KycVerificationStatus.PENDING, VerificationProviderKind.DIDIT,
                NOW.minusHours(6), NOW.minusHours(6), KycDecisionKind.REJECTED);
        em.clear();

        Set<UUID> mine = Set.of(old, older, fresh, notSubmitted, stripe, decided);
        List<UUID> found = kycRepository.findInReviewSince(VerificationProviderKind.DIDIT,
                        NOW.minusHours(2), ALL).stream()
                .map(KycVerificationEntity::getUserId).filter(mine::contains).toList();

        // La plus ancienne d'abord.
        assertThat(found).containsExactly(older, old);
    }
}
