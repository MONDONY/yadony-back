package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.yadony.api.promo.PromoService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralService — unit tests")
class ReferralServiceTest {

    @Mock private ReferralCodeRepository referralCodeRepository;
    @Mock private ReferralInvitationRepository referralInvitationRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;
    @Mock private PromoService promoService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReferralConfig config;

    private ReferralService referralService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-referral-test-001";
    private static final String REFERRER_UID = "uid-referral-referrer-001";
    private static final String CODE = "JEAN1234";

    @BeforeEach
    void setUp() {
        config = new ReferralConfig();
        config.setMaxInvitationsPerUser(50);
        config.setCodeRegenerationCooldownDays(30);
        com.yadony.api.voucher.VoucherConfig voucherConfig = new com.yadony.api.voucher.VoucherConfig();
        voucherConfig.setFactor(new java.math.BigDecimal("0.50"));
        referralService = new ReferralService(
                referralCodeRepository, referralInvitationRepository,
                userRepository, auditService, config, voucherService, voucherConfig,
                promoService, eventPublisher);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity userWithName(UUID id, String uid, String firstName, String lastName) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirebaseUid(uid);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return u;
    }

    private ReferralCodeEntity codeEntity(UUID userId, String code) {
        ReferralCodeEntity e = new ReferralCodeEntity();
        e.setUserId(userId);
        e.setCode(code);
        return e;
    }

    // ── 1. generateCode_createsUniqueCode ─────────────────────────────────────

    @Test
    @DisplayName("generateCode_createsUniqueCode — code is saved with correct prefix")
    void generateCode_createsUniqueCode() {
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Jean", "Dupont");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(referralCodeRepository.existsByCode(anyString())).thenReturn(false);
        when(referralCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReferralCodeEntity result = referralService.generateCodeForUser(USER_ID);

        ArgumentCaptor<ReferralCodeEntity> captor = ArgumentCaptor.forClass(ReferralCodeEntity.class);
        verify(referralCodeRepository).save(captor.capture());
        ReferralCodeEntity saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCode()).startsWith("JEAN");
        assertThat(saved.getCode()).hasSize(8); // 4 prefix + 4 digits
    }

    // ── 2. generateCode_retryOnDuplicate ─────────────────────────────────────

    @Test
    @DisplayName("generateCode_retryOnDuplicate — retries until unique code found")
    void generateCode_retryOnDuplicate() {
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Jean", "Dupont");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // First 3 attempts are duplicates, 4th is unique
        when(referralCodeRepository.existsByCode(anyString()))
                .thenReturn(true).thenReturn(true).thenReturn(true).thenReturn(false);
        when(referralCodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        referralService.generateCodeForUser(USER_ID);

        // save should have been called exactly once with the unique code
        verify(referralCodeRepository, times(1)).save(any());
        verify(referralCodeRepository, atLeast(4)).existsByCode(anyString());
    }

    // ── 3. getMyReferral_createsCodeIfAbsent ─────────────────────────────────

    @Test
    @DisplayName("getMyReferral_createsCodeIfAbsent — lazily generates code and returns stats")
    void getMyReferral_createsCodeIfAbsent() {
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Jean", "Dupont");
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(referralCodeRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(referralCodeRepository.existsByCode(anyString())).thenReturn(false);
        ReferralCodeEntity generated = codeEntity(USER_ID, "JEAN9999");
        when(referralCodeRepository.save(any())).thenReturn(generated);
        when(referralInvitationRepository.countByReferrerUserId(USER_ID)).thenReturn(3L);
        when(referralInvitationRepository.countByReferrerUserIdAndRefereeUserIdIsNotNull(USER_ID)).thenReturn(3L);
        when(referralInvitationRepository.countByReferrerUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(2L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.CHOSEN)).thenReturn(1L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.AVAILABLE)).thenReturn(1L);
        when(voucherService.listActive(USER_ID)).thenReturn(java.util.List.of());
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(false);

        MyReferralResponse response = referralService.getMyReferral(FIREBASE_UID);

        assertThat(response.code()).isEqualTo("JEAN9999");
        assertThat(response.shareUrl()).isEqualTo("https://yadony.com/r/JEAN9999");
        // Compteurs cumulatifs : les 3 invités sont inscrits, 2 ont envoyé un premier
        // colis, dont 1 récompense choisie et 1 en attente de choix.
        assertThat(response.totalInvited()).isEqualTo(3);
        assertThat(response.signedUp()).isEqualTo(3);
        assertThat(response.firstDelivery()).isEqualTo(2);
        assertThat(response.rewarded()).isEqualTo(1);
        assertThat(response.pendingRewardCount()).isEqualTo(1);
        assertThat(response.rewardWalletAmount()).isEqualByComparingTo("5.00");
        assertThat(response.rewardWalletCurrency()).isEqualTo("EUR");
        assertThat(response.rewardDiscountPercent()).isEqualTo(5);
        assertThat(response.activeVoucherCount()).isZero();
        // Le facteur reste annoncé même sans bon actif : c'est le barème courant.
        assertThat(response.voucherFactor()).isEqualByComparingTo("0.50");
        assertThat(response.hasBeenReferred()).isFalse();
    }

    @Test
    @DisplayName("getMyReferral_reportsActiveVoucher — count/expiry from the voucher, factor from the current barème")
    void getMyReferral_reportsActiveVoucher() {
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Jean", "Dupont");
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        ReferralCodeEntity code = codeEntity(USER_ID, "JEAN9999");
        when(referralCodeRepository.findByUserId(USER_ID)).thenReturn(Optional.of(code));
        when(referralInvitationRepository.countByReferrerUserId(USER_ID)).thenReturn(1L);
        when(referralInvitationRepository.countByReferrerUserIdAndRefereeUserIdIsNotNull(USER_ID)).thenReturn(1L);
        when(referralInvitationRepository.countByReferrerUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(1L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.CHOSEN)).thenReturn(1L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.AVAILABLE)).thenReturn(0L);
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(false);
        // Le bon a été octroyé sous un ancien barème (0.30) : la réponse doit annoncer
        // le barème COURANT (0.50, celui de VoucherConfig dans setUp), pas celui figé
        // sur ce bon précis — c'est la promesse pour le PROCHAIN parrainage.
        com.yadony.api.voucher.CommissionVoucherEntity voucher =
                mock(com.yadony.api.voucher.CommissionVoucherEntity.class);
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusMonths(5);
        lenient().when(voucher.getFactor()).thenReturn(new java.math.BigDecimal("0.30"));
        lenient().when(voucher.getExpiresAt()).thenReturn(expiresAt);
        when(voucherService.listActive(USER_ID)).thenReturn(java.util.List.of(voucher));

        MyReferralResponse response = referralService.getMyReferral(FIREBASE_UID);

        assertThat(response.activeVoucherCount()).isEqualTo(1);
        assertThat(response.voucherFactor()).isEqualByComparingTo("0.50");
        assertThat(response.nextVoucherExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("getMyReferral_hasBeenReferred_true — hasBeenReferred is true when user has any invitation as referee")
    void getMyReferral_hasBeenReferred_true() {
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Ali", "Diallo");
        ReferralCodeEntity code = codeEntity(USER_ID, "ALID1234");
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        when(referralCodeRepository.findByUserId(USER_ID)).thenReturn(Optional.of(code));
        when(referralInvitationRepository.countByReferrerUserId(USER_ID)).thenReturn(0L);
        when(referralInvitationRepository.countByReferrerUserIdAndRefereeUserIdIsNotNull(USER_ID)).thenReturn(0L);
        when(referralInvitationRepository.countByReferrerUserIdAndStatusIn(eq(USER_ID), any())).thenReturn(0L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.CHOSEN)).thenReturn(0L);
        when(referralInvitationRepository.countByReferrerUserIdAndRewardStatus(USER_ID, ReferralRewardStatus.AVAILABLE)).thenReturn(0L);
        when(voucherService.listActive(USER_ID)).thenReturn(java.util.List.of());
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(true);

        MyReferralResponse response = referralService.getMyReferral(FIREBASE_UID);

        assertThat(response.hasBeenReferred()).isTrue();
    }

    // ── 4. redeemCode_success ─────────────────────────────────────────────────

    @Test
    @DisplayName("redeemCode_success — creates SIGNED_UP invitation")
    void redeemCode_success() {
        UserEntity referee = userWithName(USER_ID, FIREBASE_UID, "Ali", "Diallo");
        ReferralCodeEntity codeEntity = codeEntity(REFERRER_ID, CODE);

        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(referee));
        when(referralCodeRepository.findByCode(CODE)).thenReturn(Optional.of(codeEntity));
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(false);
        when(referralInvitationRepository.save(any())).thenAnswer(inv -> {
            ReferralInvitationEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        referralService.redeemCode(FIREBASE_UID, CODE);

        // Le filleul est verrouillé AVANT le test « déjà parrainé » : deux saisies simultanées
        // ne créent plus deux invitations.
        var order = inOrder(userRepository, referralInvitationRepository);
        order.verify(userRepository).lockForUpdate(USER_ID);
        order.verify(referralInvitationRepository).existsByRefereeUserId(USER_ID);

        ArgumentCaptor<ReferralInvitationEntity> captor =
                ArgumentCaptor.forClass(ReferralInvitationEntity.class);
        verify(referralInvitationRepository).save(captor.capture());
        ReferralInvitationEntity saved = captor.getValue();

        assertThat(saved.getReferrerUserId()).isEqualTo(REFERRER_ID);
        assertThat(saved.getRefereeUserId()).isEqualTo(USER_ID);
        assertThat(saved.getStatus()).isEqualTo("SIGNED_UP");
        assertThat(saved.getCodeUsed()).isEqualTo(CODE);
    }

    // ── 5. redeemCode_selfReferral_throws409 ─────────────────────────────────

    @Test
    @DisplayName("redeemCode_selfReferral_throws409 — cannot use own code")
    void redeemCode_selfReferral_throws409() {
        // The referee and the code owner are the same user
        UserEntity user = userWithName(USER_ID, FIREBASE_UID, "Ali", "Diallo");
        ReferralCodeEntity ownCode = codeEntity(USER_ID, CODE); // same USER_ID

        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
        when(referralCodeRepository.findByCode(CODE)).thenReturn(Optional.of(ownCode));

        assertThatThrownBy(() -> referralService.redeemCode(FIREBASE_UID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(dbe.getErrorCode()).isEqualTo("self-referral");
                });
    }

    // ── 6. redeemCode_alreadyReferred_throws409 ───────────────────────────────

    @Test
    @DisplayName("redeemCode_alreadyReferred_throws409 — user already referred (invitation past SIGNED_UP)")
    void redeemCode_alreadyReferred_throws409() {
        UserEntity referee = userWithName(USER_ID, FIREBASE_UID, "Ali", "Diallo");
        ReferralCodeEntity codeEntity = codeEntity(REFERRER_ID, CODE);

        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(referee));
        when(referralCodeRepository.findByCode(CODE)).thenReturn(Optional.of(codeEntity));
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> referralService.redeemCode(FIREBASE_UID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(dbe.getErrorCode()).isEqualTo("already-referred");
                });
    }

    // ── 7. redeemCode_alreadySignedUp_throws409 ───────────────────────────────

    @Test
    @DisplayName("redeemCode_alreadySignedUp_throws409 — user already has SIGNED_UP invitation")
    void redeemCode_alreadySignedUp_throws409() {
        UserEntity referee = userWithName(USER_ID, FIREBASE_UID, "Ali", "Diallo");
        ReferralCodeEntity codeEntity = codeEntity(REFERRER_ID, CODE);

        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(referee));
        when(referralCodeRepository.findByCode(CODE)).thenReturn(Optional.of(codeEntity));
        when(referralInvitationRepository.existsByRefereeUserId(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> referralService.redeemCode(FIREBASE_UID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(dbe.getErrorCode()).isEqualTo("already-referred");
                });
    }

}
