package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecetteMode;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminRecetteTesterServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private AdminRecetteTesterService service(boolean property, String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return new AdminRecetteTesterService(userRepository, auditService,
                new RecetteMode(property, env, auditService));
    }

    private static UserEntity user(boolean tester) {
        UserEntity u = new UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setRecetteTester(tester);
        return u;
    }

    @SuppressWarnings("unchecked")
    private void repositoryReturns(UserEntity... users) {
        when(userRepository.findAllById(any(Iterable.class))).thenReturn(List.of(users));
    }

    @Test
    void isAvailable_suitLeMode() {
        assertThat(service(true, "staging").isAvailable()).isTrue();
        assertThat(service(true, "prod").isAvailable()).isFalse();
        assertThat(service(false, "staging").isAvailable()).isFalse();
    }

    @Test
    void modeFerme_prod_refuse409_sansToucherAuxComptes() {
        Throwable thrown = catchThrowable(() -> service(true, "prod")
                .setBulk(List.of(UUID.randomUUID()), true, ADMIN_ID));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException ex = (YadonyBusinessException) thrown;
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("recette-disabled");
        verifyNoInteractions(userRepository, auditService);
    }

    @Test
    void lotVideOuNulOuTropGrand_refuse422() {
        AdminRecetteTesterService service = service(true, "staging");
        List<UUID> tooMany = IntStream.range(0, AdminRecetteTesterService.MAX_BULK + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> onlyNull = new ArrayList<>();
        onlyNull.add(null);

        for (Collection<UUID> ids : List.of(List.<UUID>of(), tooMany, onlyNull)) {
            YadonyBusinessException ex = (YadonyBusinessException) catchThrowable(
                    () -> service.setBulk(ids, true, ADMIN_ID));
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(ex.getErrorCode()).isEqualTo("recette-bulk-size");
        }
        YadonyBusinessException ex = (YadonyBusinessException) catchThrowable(
                () -> service.setBulk(null, true, ADMIN_ID));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(userRepository);
    }

    @Test
    void active_compteChaqueCas_etAuditeChaqueChangementReel() {
        UserEntity toGrant = user(false);
        UserEntity already = user(true);
        UUID missing = UUID.randomUUID();
        repositoryReturns(toGrant, already);

        var result = service(true, "staging").setBulk(
                List.of(toGrant.getId(), already.getId(), missing, toGrant.getId()), true, ADMIN_ID);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.notFound()).containsExactly(missing);
        assertThat(toGrant.isRecetteTester()).isTrue();
        verify(userRepository).saveAll(List.of(toGrant));
        verify(auditService).log("USER", toGrant.getId(), "RECETTE_TESTER_GRANTED", ADMIN_ID,
                Map.of("recetteTester", true, "bulk", true));
        verify(auditService, times(1)).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void desactive_auditeRevoked() {
        UserEntity a = user(true);
        UserEntity b = user(true);
        repositoryReturns(a, b);

        var result = service(true, "staging").setBulk(List.of(a.getId(), b.getId()), false, ADMIN_ID);

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.notFound()).isEmpty();
        assertThat(a.isRecetteTester()).isFalse();
        assertThat(b.isRecetteTester()).isFalse();
        verify(auditService, times(2)).log(eq("USER"), any(), eq("RECETTE_TESTER_REVOKED"), eq(ADMIN_ID), anyMap());
    }

    @Test
    void aucunChangement_niSauvegardeNiAudit() {
        UserEntity a = user(false);
        repositoryReturns(a);

        var result = service(true, "staging").setBulk(List.of(a.getId()), false, ADMIN_ID);

        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        verify(userRepository, never()).saveAll(any());
        verifyNoInteractions(auditService);
    }
}
