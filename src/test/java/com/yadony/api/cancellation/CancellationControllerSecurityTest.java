package com.yadony.api.cancellation;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou RBAC : confirm-noshow est un geste de résolution de litige.
 * Un compte SUPPORT (ROLE_ADMIN sans DISPUTE_RESOLVE) ne doit pas pouvoir le déclencher.
 */
class CancellationControllerSecurityTest {

    @Test
    void confirmNoShow_requiresDisputeResolveAuthority() throws NoSuchMethodException {
        PreAuthorize annotation = CancellationController.class
                .getMethod("confirmNoShow", UUID.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains("hasRole('ADMIN')");
        assertThat(annotation.value()).contains("hasAuthority('DISPUTE_RESOLVE')");
    }
}
