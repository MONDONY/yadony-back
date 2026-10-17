package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Paramètres {@code gpProOnly} et alias déprécié {@code kiloProOnly} de {@code GET /announcements}. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementGpProSearchControllerIT {

    @Autowired MockMvc mockMvc;
    @MockBean AnnouncementService announcementService;

    private void call(String query) throws Exception {
        when(announcementService.searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class), any(), any(), any()))
                .thenReturn(Page.empty());
        mockMvc.perform(get("/announcements" + query)
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "viewer-uid", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isOk());
    }

    private Boolean lastGp() {
        org.mockito.ArgumentCaptor<Boolean> gp = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        verify(announcementService, org.mockito.Mockito.atLeastOnce()).searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), gp.capture(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class), any(), any(), any());
        return gp.getValue();
    }

    @Test
    void kiloProOnlyAliasFiltersGpPro() throws Exception {
        call("?kiloProOnly=true");
        org.assertj.core.api.Assertions.assertThat(lastGp()).isTrue();
    }

    @Test
    void gpProOnlyAndAliasShareCacheKey() throws Exception {
        java.util.List<Boolean> received = new java.util.ArrayList<>();
        for (String q : new String[] {"?gpProOnly=true", "?kiloProOnly=true", "?gpProOnly=false&kiloProOnly=true"}) {
            org.mockito.Mockito.clearInvocations(announcementService);
            call(q);
            received.add(lastGp());
        }
        // Même valeur transmise au service pour les trois formes, donc même clé de cache.
        org.assertj.core.api.Assertions.assertThat(received).containsExactly(true, true, true);
    }

    @Test
    void absentParamsPassNullLikeInternalCallers() throws Exception {
        call("");
        // null : identique à l'appel interne sans filtre (une seule entrée de cache).
        org.assertj.core.api.Assertions.assertThat(lastGp()).isNull();
    }
}
