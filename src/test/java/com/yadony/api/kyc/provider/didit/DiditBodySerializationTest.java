package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'API Didit attend des cles en snake_case ({@code workflow_id}, {@code vendor_data}).
 * L'ObjectMapper de l'application est configure en {@code LOWER_CAMEL_CASE}
 * (application.yml) : ce test verifie que cette strategie ne renomme PAS les cles d'une
 * Map, faute de quoi le corps envoye a Didit serait invalide en production alors qu'il est
 * correct dans un test utilisant un mapper nu.
 */
class DiditBodySerializationTest {

    @Test
    void lesClesDeMapNeSontPasRenommeesParLaStrategieCamelCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

        String json = mapper.writeValueAsString(Map.of(
                "workflow_id", "wf",
                "vendor_data", "vd"));

        assertThat(json).contains("workflow_id").contains("vendor_data");
        assertThat(json).doesNotContain("workflowId").doesNotContain("vendorData");
    }
}
