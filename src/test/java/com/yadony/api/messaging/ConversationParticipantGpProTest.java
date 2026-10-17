package com.yadony.api.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.messaging.dto.ParticipantDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.invokeMethod;

/** Le participant d'une conversation porte le badge GP Pro (clé JSON {@code gpPro}). */
class ConversationParticipantGpProTest {

    @Test
    void participantExposesGpPro() {
        ConversationService service = new ConversationService(mock(ConversationRepository.class),
                mock(FirestoreService.class), mock(UserRepository.class), mock(AuditService.class),
                mock(BidRepository.class), mock(AnnouncementRepository.class), mock(StorageService.class),
                mock(com.yadony.api.common.BlockVisibility.class), mock(com.yadony.api.common.CallAvailability.class),
                mock(ConversationMediaPolicy.class), mock(MessagingImageRetentionService.class));
        UserEntity user = new UserEntity();
        user.setUsername("user1");
        user.setGpPro(true);

        ParticipantDTO dto = invokeMethod(service, "buildParticipant", UUID.randomUUID(), user, false, "Voyageur");
        JsonNode json = new ObjectMapper().valueToTree(dto);

        assertThat(json.get("gpPro").asBoolean()).isTrue();
    }
}
