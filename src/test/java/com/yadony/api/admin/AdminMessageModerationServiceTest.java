package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.FirestoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AdminMessageModerationServiceTest {

    @Mock FirestoreService firestoreService;
    @Mock AuditService auditService;

    @Test
    void deleteMessage_suppressionDouceFirestorePuisAuditAvecLAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "conv_x");
        ReflectionTestUtils.setField(conv, "id", convId);

        new AdminMessageModerationService(firestoreService, auditService).deleteMessage(conv, "msg_1", adminId);

        InOrder order = inOrder(firestoreService, auditService);
        order.verify(firestoreService).softDeleteMessage("conv_x", "msg_1");
        order.verify(auditService).log("message", convId, "MESSAGE_ADMIN_DELETED", adminId,
                Map.of("conversationId", "conv_x", "messageId", "msg_1"));
    }
}
