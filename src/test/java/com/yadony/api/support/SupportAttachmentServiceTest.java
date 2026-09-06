package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SupportAttachmentServiceTest {

    @Mock private StorageService storageService;
    @Mock private SupportMessageAttachmentRepository attachmentRepository;

    @InjectMocks private SupportAttachmentService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void acceptsKeysThatBelongToTheCaller() {
        List<String> keys = List.of(
                "support/" + userId + "/1_a.jpg",
                "support/" + userId + "/2_b.jpg");

        assertThat(service.requireOwnedKeys(keys, service.userPrefix(userId))).isEqualTo(keys);
    }

    /**
     * Sans ce controle, un utilisateur pourrait joindre a son message le fichier
     * d'un autre en devinant sa cle.
     */
    @Test
    void rejectsAKeyThatBelongsToSomeoneElse() {
        List<String> keys = List.of("support/" + UUID.randomUUID() + "/1_a.jpg");

        assertThatThrownBy(() -> service.requireOwnedKeys(keys, service.userPrefix(userId)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("piece jointe");
    }

    @Test
    void rejectsMoreThanFourAttachments() {
        List<String> keys = List.of(
                "support/" + userId + "/1.jpg", "support/" + userId + "/2.jpg",
                "support/" + userId + "/3.jpg", "support/" + userId + "/4.jpg",
                "support/" + userId + "/5.jpg");

        assertThatThrownBy(() -> service.requireOwnedKeys(keys, service.userPrefix(userId)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("4");
    }

    @Test
    void acceptsAnEmptyList() {
        assertThat(service.requireOwnedKeys(null, service.userPrefix(userId))).isEmpty();
        assertThat(service.requireOwnedKeys(List.of(), service.userPrefix(userId))).isEmpty();
    }

    @Test
    void buildsPrefixesFromIdentifiersNotFromRawInput() {
        UUID adminId = UUID.randomUUID();
        assertThat(service.userPrefix(userId)).isEqualTo("support/" + userId + "/");
        assertThat(service.adminPrefix(adminId)).isEqualTo("support/admin/" + adminId + "/");
    }

    @Test
    void attachIgnoresANullOrEmptyKeyList() {
        UUID messageId = UUID.randomUUID();

        // Test null keys
        service.attach(messageId, null, "image/jpeg");
        verifyNoInteractions(attachmentRepository);

        // Test empty list
        service.attach(messageId, List.of(), "image/jpeg");
        verifyNoInteractions(attachmentRepository);
    }
}
