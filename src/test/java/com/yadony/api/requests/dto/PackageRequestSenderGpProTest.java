package com.yadony.api.requests.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackageRequestSenderGpProTest {

    @Test
    void packageRequestSenderExposesGpPro() {
        var profile = new PackageRequestSearchResponse.SenderPublicProfile(
                UUID.randomUUID(), "Fatou S.", 4.9, 12, true, true, null);
        JsonNode json = new ObjectMapper().valueToTree(profile);

        assertThat(json.get("gpPro").asBoolean()).isTrue();
        assertThat(new ObjectMapper().valueToTree(new PackageRequestSearchResponse.SenderPublicProfile(
                UUID.randomUUID(), "X", 0, 0, false, false, null)).get("gpPro").asBoolean()).isFalse();
    }
}
