package com.yadony.api.admin.account;

import com.yadony.api.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le filtre d'authentification appelle {@link AdminAuthService#resolve} sur chaque requête,
 * y compris celles des utilisateurs mobiles, qui ne sont pas administrateurs. La réponse
 * « pas admin » doit donc être mise en cache comme les autres, sinon chaque appel de l'app
 * coûte un SELECT admin_users.
 */
@SpringJUnitConfig(classes = {CacheConfig.class, AdminAuthService.class})
@DisplayName("AdminAuthService — cache des résolutions")
class AdminAuthServiceCacheTest {

    @Autowired AdminAuthService adminAuthService;
    @Autowired CacheManager cacheManager;
    @MockitoBean AdminUserRepository adminUserRepository;

    @BeforeEach
    void resetCache() {
        cacheManager.getCache("adminAuthz").clear();
        reset(adminUserRepository);
    }

    @Test
    void resolve_metEnCacheLaReponsePasAdmin() {
        when(adminUserRepository.findByFirebaseUid("uid-mobile")).thenReturn(Optional.empty());

        Optional<AdminAuthorities> first = adminAuthService.resolve("uid-mobile");
        Optional<AdminAuthorities> second = adminAuthService.resolve("uid-mobile");

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        verify(adminUserRepository, times(1)).findByFirebaseUid("uid-mobile");
    }

    @Test
    void evictByFirebaseUid_rendVisibleUnCompteAdminCreeApresCoup() {
        when(adminUserRepository.findByFirebaseUid("uid-promu")).thenReturn(Optional.empty());
        assertThat(adminAuthService.resolve("uid-promu")).isEmpty();

        AdminUserEntity created = new AdminUserEntity("uid-promu", "promu@yadony", AdminRole.SUPPORT);
        created.setStatus(AdminStatus.ACTIVE);
        created.setMustChangePassword(false);
        when(adminUserRepository.findByFirebaseUid("uid-promu")).thenReturn(Optional.of(created));
        adminAuthService.evictByFirebaseUid("uid-promu");

        assertThat(adminAuthService.resolve("uid-promu")).isPresent();
    }
}
