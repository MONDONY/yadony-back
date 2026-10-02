package com.yadony.api.calls;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StreamClientTest {

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final StreamTokenService tokens = mock(StreamTokenService.class);
    final StreamProperties props = new StreamProperties(true, "https://video.stream-io-api.com", "key", "secret", "audio_call", 3);
    final StreamClient client = new StreamClient(builder.build(), props, tokens);

    @Test
    void upsertUsers() {
        when(tokens.serverToken()).thenReturn("jwt");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        server.expect(requestTo("https://video.stream-io-api.com/api/v2/users?api_key=key"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(header("Authorization", "jwt"))
              .andExpect(header("stream-auth-type", "jwt"))
              .andExpect(jsonPath("$.users['" + id + "'].name").value("Awa D."))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.upsertUsers(List.of(new StreamClient.StreamUser(id, "Awa D.", null)));
        server.verify();
    }

    @Test
    void creeUnAppelQuiSonne() {
        when(tokens.serverToken()).thenReturn("jwt");
        UUID caller = UUID.randomUUID();
        UUID callee = UUID.randomUUID();
        server.expect(requestTo("https://video.stream-io-api.com/api/v2/video/call/audio_call/c1?api_key=key"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.ring").value(true))
              .andExpect(jsonPath("$.video").value(false))
              .andExpect(jsonPath("$.data.created_by_id").value(caller.toString()))
              .andExpect(jsonPath("$.data.members.length()").value(2))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.createRingingCall("c1", caller, List.of(caller, callee));
        server.verify();
    }

    @Test
    void erreurStreamDevientStreamUnavailable() {
        when(tokens.serverToken()).thenReturn("jwt");
        server.expect(requestTo("https://video.stream-io-api.com/api/v2/video/call/audio_call/c1?api_key=key"))
              .andRespond(withServerError());

        assertThatThrownBy(() -> client.createRingingCall("c1", UUID.randomUUID(), List.of()))
                .isInstanceOf(StreamClient.StreamUnavailableException.class);
    }
}
