package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.PageResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.promo.PromoCodeEntity;
import com.yadony.api.promo.PromoService;
import com.yadony.api.referral.events.ReferralWalletRewardChosenEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import com.yadony.api.voucher.VoucherConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralService — filleuls et choix de la récompense")
class ReferralRewardChoiceServiceTest {

    @Mock private ReferralCodeRepository referralCodeRepository;
    @Mock private ReferralInvitationRepository invitationRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private CommissionVoucherService voucherService;
    @Mock private PromoService promoService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReferralService service;

    private static final String UID = "uid-parrain";
    private final UUID referrerId = UUID.randomUUID();
    private final UUID refereeId = UUID.randomUUID();
    private final UUID invitationId = UUID.randomUUID();
    private UserEntity referrer;

    @BeforeEach
    void setUp() {
        service = new ReferralService(referralCodeRepository, invitationRepository, userRepository,
                auditService, new ReferralConfig(), voucherService, new VoucherConfig(), promoService, eventPublisher);
        referrer = user(referrerId, "Aboubakar", "Diakité");
        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(referrer));
    }

    private static UserEntity user(UUID id, String first, String last) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private ReferralInvitationEntity invitation(UUID owner, ReferralRewardStatus rewardStatus) {
        ReferralInvitationEntity inv = new ReferralInvitationEntity();
        ReflectionTestUtils.setField(inv, "id", invitationId);
        ReflectionTestUtils.setField(inv, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
        inv.setReferrerUserId(owner);
        inv.setRefereeUserId(refereeId);
        inv.setStatus(rewardStatus == ReferralRewardStatus.NONE ? "SIGNED_UP" : "FIRST_DELIVERY");
        inv.setRewardStatus(rewardStatus);
        return inv;
    }

    private PromoCodeEntity personalPromo() {
        PromoCodeEntity p = new PromoCodeEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setCode("MERCI-ABOU-7K2F");
        p.setRate(new BigDecimal("0.050"));
        p.setValidTo(LocalDateTime.now(ZoneOffset.UTC).plusMonths(6));
        return p;
    }

    // ── chooseReward ─────────────────────────────────────────────────────────

    @Test
    void walletChoice_marksRewardedAndPublishesTheCreditEvent() {
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.AVAILABLE);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(userRepository.findById(refereeId)).thenReturn(Optional.of(user(refereeId, "Awa", "Diop")));

        ReferralRefereeResponse response = service.chooseReward(UID, invitationId, ReferralRewardChoice.WALLET_CREDIT);

        assertThat(inv.getRewardStatus()).isEqualTo(ReferralRewardStatus.CHOSEN);
        assertThat(inv.getRewardChoice()).isEqualTo(ReferralRewardChoice.WALLET_CREDIT);
        assertThat(inv.getStatus()).isEqualTo("REWARDED");
        assertThat(inv.getRewardChosenAt()).isNotNull();
        assertThat(inv.getCreditAmountCents()).isEqualTo(500);
        verify(invitationRepository).save(inv);

        ArgumentCaptor<ReferralWalletRewardChosenEvent> event =
                ArgumentCaptor.forClass(ReferralWalletRewardChosenEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().referrerUserId()).isEqualTo(referrerId);
        assertThat(event.getValue().amount()).isEqualByComparingTo("5.00");
        assertThat(event.getValue().currency()).isEqualTo("EUR");
        assertThat(event.getValue().idempotencyKey()).isEqualTo("referral-reward:" + invitationId);
        verifyNoInteractions(promoService);
        verify(auditService).log(eq("REFERRAL_INVITATION"), eq(invitationId), eq("REFERRAL_REWARD_CHOSEN"),
                eq(referrerId), anyMap());

        assertThat(response.displayName()).isEqualTo("Awa D.");
        assertThat(response.walletAmount()).isEqualByComparingTo("5.00");
        assertThat(response.walletCurrency()).isEqualTo("EUR");
        assertThat(response.discountCode()).isNull();
    }

    @Test
    void discountChoice_createsAPersonalCodeForTheReferrer() {
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.AVAILABLE);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        PromoCodeEntity promo = personalPromo();
        when(promoService.createPersonalCode(eq(referrerId), eq("MERCI-ABOU"), any(), any())).thenReturn(promo);
        when(userRepository.findById(refereeId)).thenReturn(Optional.empty());

        ReferralRefereeResponse response = service.chooseReward(UID, invitationId, ReferralRewardChoice.DISCOUNT_CODE);

        ArgumentCaptor<BigDecimal> percent = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<LocalDateTime> validTo = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(promoService).createPersonalCode(eq(referrerId), eq("MERCI-ABOU"), percent.capture(), validTo.capture());
        assertThat(percent.getValue()).isEqualByComparingTo("0.05");
        assertThat(validTo.getValue()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMonths(6).minusMinutes(1));
        assertThat(inv.getRewardPromoCodeId()).isEqualTo(promo.getId());
        assertThat(inv.getRewardChoice()).isEqualTo(ReferralRewardChoice.DISCOUNT_CODE);
        verify(eventPublisher, never()).publishEvent(any());

        assertThat(response.discountCode().code()).isEqualTo("MERCI-ABOU-7K2F");
        assertThat(response.discountCode().percent()).isEqualTo(5);
        assertThat(response.discountCode().used()).isFalse();
        assertThat(response.discountCode().expired()).isFalse();
        assertThat(response.walletAmount()).isNull();
    }

    @Test
    void discountChoice_prefixFallsBackWhenTheFirstNameHasNoLetter() {
        referrer.setFirstName("  ");
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.AVAILABLE);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(promoService.createPersonalCode(eq(referrerId), eq("MERCI-YADO"), any(), any())).thenReturn(personalPromo());
        when(userRepository.findById(refereeId)).thenReturn(Optional.empty());

        service.chooseReward(UID, invitationId, ReferralRewardChoice.DISCOUNT_CODE);

        verify(promoService).createPersonalCode(eq(referrerId), eq("MERCI-YADO"), any(), any());
    }

    @Test
    void alreadyChosen_is409() {
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.CHOSEN);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.chooseReward(UID, invitationId, ReferralRewardChoice.WALLET_CREDIT))
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("referral-reward-already-chosen");
                });
        verifyNoInteractions(eventPublisher, promoService);
    }

    @Test
    void notYetAvailable_is409() {
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.NONE);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.chooseReward(UID, invitationId, ReferralRewardChoice.DISCOUNT_CODE))
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("referral-reward-not-available"));
        verifyNoInteractions(eventPublisher, promoService);
    }

    @Test
    void someoneElsesInvitation_is404() {
        ReferralInvitationEntity inv = invitation(UUID.randomUUID(), ReferralRewardStatus.AVAILABLE);
        when(invitationRepository.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.chooseReward(UID, invitationId, ReferralRewardChoice.WALLET_CREDIT))
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("referral-invitation-not-found");
                });
        assertThat(inv.getRewardStatus()).isEqualTo(ReferralRewardStatus.AVAILABLE);
        verifyNoInteractions(eventPublisher, promoService);
    }

    @Test
    void historicalVoucherChoice_isNotSelectable() {
        assertThatThrownBy(() -> service.chooseReward(UID, invitationId, ReferralRewardChoice.COMMISSION_VOUCHER))
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("referral-reward-choice-invalid");
                });
        verify(invitationRepository, never()).findByIdForUpdate(any());
    }

    // ── getReferees ──────────────────────────────────────────────────────────

    @Test
    void referees_showFirstNameAndInitialOnly_andTheDiscountCodeState() {
        ReferralInvitationEntity inv = invitation(referrerId, ReferralRewardStatus.CHOSEN);
        inv.setStatus("REWARDED");
        inv.setRewardChoice(ReferralRewardChoice.DISCOUNT_CODE);
        PromoCodeEntity promo = personalPromo();
        promo.setRedeemedCount(1);
        inv.setRewardPromoCodeId(promo.getId());
        inv.setRefereePhone("+33600000000");
        Pageable pageable = PageRequest.of(0, 50);
        when(invitationRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId, pageable))
                .thenReturn(new PageImpl<>(List.of(inv), pageable, 1));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(refereeId, "Awa", "diop")));
        when(promoService.findAllByIds(any())).thenReturn(List.of(promo));

        // size au-delà du plafond : ramené à 50.
        PageResponse<ReferralRefereeResponse> page = service.getReferees(UID, 0, 500);

        assertThat(page.totalElements()).isEqualTo(1);
        ReferralRefereeResponse r = page.content().getFirst();
        assertThat(r.invitationId()).isEqualTo(invitationId);
        assertThat(r.displayName()).isEqualTo("Awa D.");
        assertThat(r.signedUpAt()).isEqualTo(inv.getCreatedAt());
        assertThat(r.status()).isEqualTo("REWARDED");
        assertThat(r.rewardStatus()).isEqualTo(ReferralRewardStatus.CHOSEN);
        assertThat(r.discountCode().code()).isEqualTo("MERCI-ABOU-7K2F");
        assertThat(r.discountCode().used()).isTrue();
        assertThat(r.toString()).doesNotContain("+33600000000");
    }

    @Test
    void referees_emptyPage_skipsLookups() {
        Pageable pageable = PageRequest.of(0, 20);
        when(invitationRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(promoService.findAllByIds(any())).thenReturn(List.of());

        assertThat(service.getReferees(UID, -1, 20).content()).isEmpty();
    }

    // ── RefereeNames ─────────────────────────────────────────────────────────

    @Test
    void refereeNames_variants() {
        assertThat(RefereeNames.displayName(null)).isNull();
        assertThat(RefereeNames.displayName(user(refereeId, null, "Diop"))).isNull();
        assertThat(RefereeNames.displayName(user(refereeId, " Awa ", null))).isEqualTo("Awa");
        assertThat(RefereeNames.displayName(user(refereeId, "Awa", "élodie"))).isEqualTo("Awa É.");
    }

    @Test
    void walletEvent_idempotencyKey_isPerInvitation() {
        UUID id = UUID.randomUUID();
        assertThat(new ReferralWalletRewardChosenEvent(id, referrerId, BigDecimal.ONE, "EUR").idempotencyKey())
                .isEqualTo("referral-reward:" + id);
    }
}
