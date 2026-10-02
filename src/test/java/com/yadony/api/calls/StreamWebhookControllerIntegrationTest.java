package com.yadony.api.calls;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yadony.calls.enabled=true",
        "yadony.calls.api-key=key",
        "yadony.calls.api-secret=" + StreamWebhookVerifierTest.SECRET})
class StreamWebhookControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired CallRepository callRepository;
    @Autowired UserRepository userRepository;

    @Test
    void webhookSigneSansAuthentificationFirebaseMetAJourLAppel() throws Exception {
        CallEntity call = persistCall();
        byte[] body = """
                {"type":"call.missed","call_cid":"audio_call:%s"}""".formatted(call.getStreamCallId())
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/calls/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Signature", StreamWebhookVerifierTest.sign(body))
                        .header("X-Api-Key", "key"))
                .andExpect(status().isOk());

        assertThat(callRepository.findByStreamCallId(call.getStreamCallId()).orElseThrow().getStatus())
                .isEqualTo(CallStatus.MISSED);
    }

    @Test
    void webhookCompresseGzipSigneSurLeJsonEstAccepte() throws Exception {
        // Ce qu'envoie Stream (vu en staging) : corps gzip, Content-Encoding: gzip,
        // signature HMAC calculée sur le JSON décompressé.
        CallEntity call = persistCall();
        byte[] json = """
                {"type":"call.missed","call_cid":"audio_call:%s"}""".formatted(call.getStreamCallId())
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/calls/webhook").contentType(MediaType.APPLICATION_JSON).content(gzip(json))
                        .header("Content-Encoding", "gzip")
                        .header("X-Signature", StreamWebhookVerifierTest.sign(json))
                        .header("X-Api-Key", "key"))
                .andExpect(status().isOk());

        assertThat(callRepository.findByStreamCallId(call.getStreamCallId()).orElseThrow().getStatus())
                .isEqualTo(CallStatus.MISSED);
    }

    private static byte[] gzip(byte[] raw) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    void gzipIllisibleEn401() throws Exception {
        byte[] notGzip = {0x1f, (byte) 0x8b, 1, 2, 3};
        mockMvc.perform(post("/calls/webhook").contentType(MediaType.APPLICATION_JSON).content(notGzip)
                        .header("Content-Encoding", "gzip")
                        .header("X-Signature", StreamWebhookVerifierTest.sign(notGzip)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signatureFausseEn401() throws Exception {
        mockMvc.perform(post("/calls/webhook").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Signature", "00"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sansSignatureEn401() throws Exception {
        mockMvc.perform(post("/calls/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private CallEntity persistCall() {
        UserEntity a = persistUser();
        UserEntity b = persistUser();
        // conversation_id / bid_id : FK absentes en H2 (ddl-auto), identifiants libres suffisants ici.
        return callRepository.save(new CallEntity(UUID.randomUUID(), UUID.randomUUID(), a.getId(), b.getId(),
                UUID.randomUUID().toString()));
    }

    private UserEntity persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-webhook-" + UUID.randomUUID());
        u.setFirstName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
