package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecetteMode;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Environnement où le mode recette est fermé (prod, ou propriété fausse) : aucun drapeau posé. */
class AdminRecetteTesterControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private AdminRecetteTesterController controller(boolean property, String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return new AdminRecetteTesterController(userRepository, auditService,
                new RecetteMode(property, env, auditService));
    }

    private static UsernamePasswordAuthenticationToken superAdmin() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.test", AdminRole.SUPER_ADMIN, false, "uid-a"),
                null, List.of());
    }

    @Test
    void profilProd_refuse409_sansToucherAuCompte() {
        Throwable thrown = catchThrowable(() -> controller(true, "prod").set(UUID.randomUUID(),
                new AdminRecetteTesterController.RecetteTesterRequest(true), superAdmin()));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException ex = (YadonyBusinessException) thrown;
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("recette-disabled");
        verifyNoInteractions(userRepository, auditService);
    }

    @Test
    void proprieteFausse_refuse409() {
        Throwable thrown = catchThrowable(() -> controller(false, "staging").set(UUID.randomUUID(),
                new AdminRecetteTesterController.RecetteTesterRequest(true), superAdmin()));

        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("recette-disabled");
    }

    @Test
    void sansPrincipalAdmin_refuse403() {
        Throwable thrown = catchThrowable(() -> controller(true, "staging").set(UUID.randomUUID(),
                new AdminRecetteTesterController.RecetteTesterRequest(true),
                new UsernamePasswordAuthenticationToken("uid-x", null, List.of())));

        assertThat(((YadonyBusinessException) thrown).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
