package com.yadony.api.messaging;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.BatchConversationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationBatchServiceTest {

    @Mock ConversationService conversationService;
    @InjectMocks ConversationBatchService batchService;

    private final UUID user = UUID.randomUUID();

    @Test
    void archive_appliesEachDistinctIdOnce_inOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        BatchConversationResponse res = batchService.archive(List.of(a, b, a), user);

        assertThat(res.results()).extracting(BatchConversationResponse.Item::id).containsExactly(a, b);
        assertThat(res.succeeded()).isEqualTo(2);
        assertThat(res.failed()).isZero();
        verify(conversationService, times(1)).archiveConversation(a, user);
        verify(conversationService, times(1)).archiveConversation(b, user);
    }

    @Test
    void unarchive_reportsForbiddenPerId_withoutStoppingTheOthers() {
        UUID mine = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(conversationService).unarchiveConversation(foreign, user);

        BatchConversationResponse res = batchService.unarchive(List.of(foreign, mine), user);

        assertThat(res.succeeded()).isEqualTo(1);
        assertThat(res.failed()).isEqualTo(1);
        assertThat(res.results().get(0))
                .isEqualTo(new BatchConversationResponse.Item(foreign, false, 403, "access-denied"));
        assertThat(res.results().get(1)).isEqualTo(new BatchConversationResponse.Item(mine, true, 204, null));
        verify(conversationService).unarchiveConversation(mine, user);
    }

    @Test
    void delete_mapsBusinessAndUnexpectedErrors() {
        UUID notFound = UUID.randomUUID();
        UUID business = UUID.randomUUID();
        UUID boom = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(conversationService).deleteConversation(notFound, user);
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "conversation-locked", "Locked", "x"))
                .when(conversationService).deleteConversation(business, user);
        doThrow(new IllegalStateException("db down"))
                .when(conversationService).deleteConversation(boom, user);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST))
                .when(conversationService).deleteConversation(other, user);

        BatchConversationResponse res = batchService.delete(List.of(notFound, business, boom, other), user);

        assertThat(res.failed()).isEqualTo(4);
        assertThat(res.results()).extracting(BatchConversationResponse.Item::error)
                .containsExactly("not-found", "conversation-locked", "unexpected-error", "request-failed");
        assertThat(res.results()).extracting(BatchConversationResponse.Item::status)
                .containsExactly(404, 409, 500, 400);
    }
}
