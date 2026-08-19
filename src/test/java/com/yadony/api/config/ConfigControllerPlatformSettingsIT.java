package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot D — le {@code ConfigController} public est consomme EN PRODUCTION par l'application
 * mobile deja installee chez les utilisateurs. Ce lot change sa SOURCE de donnees (la table
 * {@code platform_settings} au lieu des properties), jamais la FORME de ses reponses.
 *
 * <p>Ces tests verrouillent donc deux choses distinctes :
 * <ol>
 *   <li>la forme exacte du JSON de chacune des quatre routes — elle doit rester rouge si
 *       quelqu'un renomme un champ ou change son type, car une application deja deployee
 *       ne peut pas etre mise a jour a la demande ;</li>
 *   <li>le fait que ces routes suivent desormais reellement la table : une modification
 *       administrateur doit etre visible immediatement, sans redeploiement.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerPlatformSettingsIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformSettingsService settings;
    @MockBean private FirebaseAuth firebaseAuth;

    @Test
    void commissionRateKeepsItsResponseShape() throws Exception {
        mockMvc.perform(get("/config/commission-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").isNumber());
    }

    @Test
    void urgencyThresholdKeepsItsResponseShape() throws Exception {
        // Le seuil est exprime en JOURS : un renommage en heures multiplierait la valeur
        // par 24 chez tous les clients deja installes.
        mockMvc.perform(get("/config/urgency-threshold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholdDays").isNumber());
    }

    @Test
    void reimbursementCapKeepsItsResponseShape() throws Exception {
        mockMvc.perform(get("/config/reimbursement-cap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAmountEur").isNumber());
    }

    @Test
    void smsEnabledKeepsItsResponseShape() throws Exception {
        mockMvc.perform(get("/config/sms-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").isBoolean());
    }

    @Test
    void adminChangeIsVisibleImmediatelyOnThePublicEndpoint() throws Exception {
        BigDecimal before = settings.commissionRate();
        BigDecimal changed = before.add(new BigDecimal("0.01"));

        settings.update(Map.of(PlatformSettingKey.COMMISSION_RATE, changed.toPlainString()),
                UUID.randomUUID());
        try {
            mockMvc.perform(get("/config/commission-rate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rate").value(changed.doubleValue()));
        } finally {
            settings.update(Map.of(PlatformSettingKey.COMMISSION_RATE, before.toPlainString()),
                    UUID.randomUUID());
        }
    }

    @Test
    void urgencyThresholdFollowsTheTableToo() throws Exception {
        int before = settings.urgencyThresholdDays();
        int changed = before + 1;

        settings.update(Map.of(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, String.valueOf(changed)),
                UUID.randomUUID());
        try {
            mockMvc.perform(get("/config/urgency-threshold"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.thresholdDays").value(changed));
        } finally {
            settings.update(Map.of(PlatformSettingKey.URGENCY_THRESHOLD_DAYS, String.valueOf(before)),
                    UUID.randomUUID());
        }
    }

    @Test
    void smsEnabledFollowsTheTableToo() throws Exception {
        boolean before = settings.smsEnabled();

        settings.update(Map.of(PlatformSettingKey.SMS_ENABLED, String.valueOf(!before)),
                UUID.randomUUID());
        try {
            mockMvc.perform(get("/config/sms-enabled"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(!before));
        } finally {
            settings.update(Map.of(PlatformSettingKey.SMS_ENABLED, String.valueOf(before)),
                    UUID.randomUUID());
        }

        assertThat(settings.smsEnabled()).isEqualTo(before);
    }
}
