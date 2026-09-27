package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldService;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat HTTP du gel des versements cote back-office : corps facultatif de derogation, forme des
 * 409 (RFC 7807, slug dans {@code code}), filtre {@code held=true} et champs exposes.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentHoldIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentRepository paymentRepository;
    @MockitoBean BidRepository bidRepository;
    @MockitoBean AnnouncementRepository announcementRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean MobileMoneyPayoutInitiator payoutInitiator;
    @MockitoBean PawapayOperationService operations;
    @MockitoBean EntityManager entityManager;
    @MockitoBean PayoutHoldService holds;

    private PaymentEntity payment;
    private BidEntity bid;
    private final UUID travelerId = UUID.randomUUID();
    private static final String OVERRIDE = """
            {"overrideHold": true, "overrideReason": "Enquete close, fraude non etablie"}
            """;

    @BeforeEach
    void setUp() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setTravelerId(travelerId);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
        when(holds.statusOf(travelerId)).thenReturn(
                new PayoutHoldStatus(LocalDateTime.now().minusDays(1), List.of(PayoutHoldReason.BANNED)));
    }

    private static UsernamePasswordAuthenticationToken admin(String... authorities) {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-hold");
        List<SimpleGrantedAuthority> granted = new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        for (String a : authorities) granted.add(new SimpleGrantedAuthority(a));
        return new UsernamePasswordAuthenticationToken(principal, null, granted);
    }

    private PawapayOperationEntity payout(PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, payment.getId(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void forceRelease_sansCorps_beneficiaireGele_409ProblemDetail() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId())
                        .with(authentication(admin("PAYMENT_RELEASE"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payout-beneficiary-held"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.blockers[0]").value("BENEFICIARY_HELD"))
                .andExpect(jsonPath("$.holdReasons[0]").value("BANNED"))
                .andExpect(jsonPath("$.travelerId").value(travelerId.toString()));
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void forceRelease_avecDerogationJson_verseAvecLaDerogation() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), any(), eq("admin-force-release"), eq(true)))
                .thenReturn(payout(PawapayOperationStatus.ACCEPTED));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(OVERRIDE)
                        .with(authentication(admin("PAYMENT_RELEASE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beneficiaryHeld").value(true))
                .andExpect(jsonPath("$.beneficiaryHoldReason").value("BANNED"))
                .andExpect(jsonPath("$.travelerId").value(travelerId.toString()));
        verify(payoutInitiator).release(any(), any(), any(), any(), eq("admin-force-release"), eq(true));
    }

    @Test
    void forceRelease_motifTropCourt_422() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideHold\": true, \"overrideReason\": \"court\"}")
                        .with(authentication(admin("PAYMENT_RELEASE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("override-reason-invalid"));
    }

    @Test
    void retryPayout_paiementDispute_409() throws Exception {
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setDisputed(true);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(payout(PawapayOperationStatus.FAILED)));

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId())
                        .with(authentication(admin("PAYMENT_RELEASE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payment-disputed"))
                .andExpect(jsonPath("$.blockers[0]").value("DISPUTED"))
                .andExpect(jsonPath("$.blockers[1]").value("BENEFICIARY_HELD"));
    }

    @Test
    void liste_heldTrue_exposeLesChampsDuGel() throws Exception {
        payment.setPayoutHeldAt(LocalDateTime.of(2026, 9, 27, 10, 0));
        when(paymentRepository.findAdminFiltered(any(), any(), any(), any(), any(), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment), PageRequest.of(0, 20), 1));
        when(paymentRepository.findBeneficiaries(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{payment.getId().toString(), travelerId.toString()}));
        when(holds.statusesOf(anyCollection())).thenReturn(Map.of(travelerId,
                new PayoutHoldStatus(LocalDateTime.now(), List.of(PayoutHoldReason.BANNED))));

        mockMvc.perform(get("/admin/payments").param("held", "true").with(authentication(admin("PAYMENT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payoutHeldAt").value("2026-09-27T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].beneficiaryHeld").value(true))
                .andExpect(jsonPath("$.content[0].beneficiaryHoldReason").value("BANNED"))
                .andExpect(jsonPath("$.content[0].travelerId").value(travelerId.toString()));
    }
}
