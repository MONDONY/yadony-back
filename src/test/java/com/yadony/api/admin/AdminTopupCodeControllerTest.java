package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminTopupCodeResponse;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeAdminService;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeEntity;
import com.yadony.api.payments.wallet.topupcode.WalletTopupCodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La révocation verrouille la ligne (FOR NO KEY UPDATE), inexécutable sous H2 : test unitaire. */
@DisplayName("AdminTopupCodeController — révocation")
class AdminTopupCodeControllerTest {

    @Test
    void revoke_deleguePuisRenvoieLeCodeRevoque() {
        WalletTopupCodeAdminService service = mock(WalletTopupCodeAdminService.class);
        AdminUserRepository admins = mock(AdminUserRepository.class);
        UserRepository users = mock(UserRepository.class);
        UUID adminId = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        WalletTopupCodeEntity code = new WalletTopupCodeEntity();
        code.setCode("DAKAR2000");
        code.setAmount(BigDecimal.TEN);
        code.setCurrency("EUR");
        code.setMaxRedemptions(5);
        code.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        code.setCreatedBy(adminId);
        code.setStatus(WalletTopupCodeStatus.REVOKED);
        code.setRevokedBy(adminId);
        code.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(service.revoke(id, adminId)).thenReturn(code);
        when(admins.findAllById(List.of(adminId))).thenReturn(List.of());

        var auth = new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(adminId, "a@yadony.test", AdminRole.ADMIN, false, "uid"), null);
        AdminTopupCodeResponse response = new AdminTopupCodeController(service, admins, users).revoke(id, auth);

        verify(service).revoke(id, adminId);
        assertThat(response.displayStatus()).isEqualTo("REVOKED");
        assertThat(response.code()).isEqualTo("DAKAR2000");
        assertThat(response.revokedAt()).isNotNull();
        assertThat(response.remaining()).isEqualTo(5);
    }
}
