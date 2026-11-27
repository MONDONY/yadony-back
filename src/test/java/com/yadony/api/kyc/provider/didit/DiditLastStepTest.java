package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiditLastStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode decision(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void aucune_etape_atteinte_quand_les_tableaux_sont_nuls() throws Exception {
        JsonNode d = decision("{\"id_verifications\":null,\"liveness_checks\":null,\"face_matches\":null}");
        assertThat(DiditIdentityProvider.lastStep(d)).isNull();
        assertThat(DiditIdentityProvider.lastStepFinished(d)).isNull();
    }

    @Test
    void piece_commencee_mais_non_terminee() throws Exception {
        JsonNode d = decision("{\"id_verifications\":[{\"status\":\"Not Finished\"}]}");
        assertThat(DiditIdentityProvider.lastStep(d)).isEqualTo("ID_DOCUMENT");
        assertThat(DiditIdentityProvider.lastStepFinished(d)).isFalse();
    }

    @Test
    void retient_la_derniere_etape_ouverte() throws Exception {
        JsonNode d = decision("{\"id_verifications\":[{\"status\":\"Approved\"}],"
                + "\"liveness_checks\":[{\"status\":\"Not Finished\"}],\"face_matches\":null}");
        assertThat(DiditIdentityProvider.lastStep(d)).isEqualTo("LIVENESS");
        assertThat(DiditIdentityProvider.lastStepFinished(d)).isFalse();
    }

    @Test
    void parcours_complet_termine_a_la_comparaison_du_visage() throws Exception {
        JsonNode d = decision("{\"id_verifications\":[{\"status\":\"Approved\"}],"
                + "\"liveness_checks\":[{\"status\":\"Approved\"}],\"face_matches\":[{\"status\":\"Approved\"}]}");
        assertThat(DiditIdentityProvider.lastStep(d)).isEqualTo("FACE_MATCH");
        assertThat(DiditIdentityProvider.lastStepFinished(d)).isTrue();
    }
}
