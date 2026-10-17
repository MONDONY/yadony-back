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

    private void verifyGp(boolean expected) {
        // 9e paramètre du service : gpProOnly (même valeur, donc même clé de cache, pour les deux noms).
        org.mockito.ArgumentCaptor<Boolean> gp = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        verify(announcementService).searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), gp.capture(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(gp.getValue()).isEqualTo(expected);
    }

    @Test
    void kiloProOnlyAliasFiltersGpPro() throws Exception {
        call("?kiloProOnly=true");
        verifyGp(true);
    }

    @Test
    void gpProOnlyAndAliasShareCacheKey() throws Exception {
        // Les deux noms aboutissent au même booléen transmis au service, donc à la même clé.
        call("?gpProOnly=true");
        verifyGp(true);
    }

    @Test
    void absentParamsMeanNoFilter() throws Exception {
        call("");
        verifyGp(false);
    }
}
