package com.yadony.api.admin.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.dto.PaymentVolumeRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Premier test de la vue d'ensemble admin. Recette du 2026-09-09 : les volumes additionnaient
 * EUR, XOF et XAF dans un seul SUM et s'affichaient en euros. Le contrat sert désormais une
 * ventilation par devise en centièmes, et garde {@code gmv} (ligne EUR seule, en unités) pour
 * les back-offices pas encore mis à jour.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminMetricsControllerIT — /admin/metrics/overview")
class AdminMetricsControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentRepository paymentRepository;

    private static UsernamePasswordAuthenticationToken auth(String... permissions) {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-metrics");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void overview_ventileLesVolumesParDevise_etGardeGmvEnEurosSeuls() throws Exception {
        when(paymentRepository.sumVolumesByCurrencyAndStatus(any())).thenReturn(List.of(
                new PaymentVolumeRow("EUR", PaymentStatus.RELEASED,
                        new BigDecimal("100.00"), new BigDecimal("12.00"), BigDecimal.ZERO),
                new PaymentVolumeRow("XOF", PaymentStatus.ESCROW,
                        new BigDecimal("9900.00"), new BigDecimal("900.00"), BigDecimal.ZERO),
                new PaymentVolumeRow("XOF", PaymentStatus.RELEASED,
                        new BigDecimal("6600.00"), new BigDecimal("600.00"), BigDecimal.ZERO)));

        mockMvc.perform(get("/admin/metrics/overview").with(authentication(auth("METRICS_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gmvByCurrency.length()").value(2))
                .andExpect(jsonPath("$.gmvByCurrency[0].currency").value("EUR"))
                .andExpect(jsonPath("$.gmvByCurrency[0].releasedCents").value(10000))
                .andExpect(jsonPath("$.gmvByCurrency[0].commissionCents").value(1200))
                .andExpect(jsonPath("$.gmvByCurrency[1].currency").value("XOF"))
                .andExpect(jsonPath("$.gmvByCurrency[1].escrowHeldCents").value(990000))
                .andExpect(jsonPath("$.gmvByCurrency[1].releasedCents").value(660000))
                .andExpect(jsonPath("$.gmvByCurrency[1].commissionCents").value(60000))
                .andExpect(jsonPath("$.gmvByCurrency[1].refundedCents").value(0))
                // Ancien contrat : la seule ligne EUR, en unités, jamais un mélange avec le XOF.
                .andExpect(jsonPath("$.gmv.released").value(100.0))
                .andExpect(jsonPath("$.gmv.commission").value(12.0))
                .andExpect(jsonPath("$.gmv.escrowHeld").value(0.0));
    }

    @Test
    void overview_sansMetricsView_est403_etNeLitAucunVolume() throws Exception {
        mockMvc.perform(get("/admin/metrics/overview").with(authentication(auth())))
                .andExpect(status().isForbidden());

        verify(paymentRepository, never()).sumVolumesByCurrencyAndStatus(any());
    }
}
