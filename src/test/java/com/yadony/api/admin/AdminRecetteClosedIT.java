package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.FirebaseContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mode recette fermé (propriété par défaut, comme en prod) : statut faux, lot refusé en 409. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminRecetteClosedIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean FirebaseContactService firebaseContact;

    @Test
    void statutFerme_etLotRefuse409() throws Exception {
        mockMvc.perform(get("/admin/recette/status")
                        .with(authentication(AdminRecetteBulkIT.admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(AdminRecetteBulkIT.bulk(List.of(UUID.randomUUID()), true)
                        .with(authentication(AdminRecetteBulkIT.admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("recette-disabled"));
    }
}
