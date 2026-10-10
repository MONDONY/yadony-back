package com.yadony.api.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(SupportAttachmentReferenceSource.class)
class SupportAttachmentReferenceSourceTest {

    @Autowired SupportMessageAttachmentRepository attachments;
    @Autowired SupportAttachmentReferenceSource source;

    private void attachment(String key, boolean softDeleted) {
        SupportMessageAttachmentEntity a = new SupportMessageAttachmentEntity();
        a.setMessageId(UUID.randomUUID());
        a.setObjectKey(key);
        a.setContentType("image/jpeg");
        a.setSizeBytes(0L);
        if (softDeleted) a.softDelete();
        attachments.saveAndFlush(a);
    }

    @Test
    void attachments_userAndAdmin_softDeletedIncluded() {
        attachment("support/u1/a.jpg", false);
        attachment("support/admin/a1/b.jpg", false);
        attachment("support/u2/c.jpg", true);

        assertThat(source.prefixes()).containsExactly("support/");
        assertThat(source.referencedKeys("support/"))
                .containsExactlyInAnyOrder("support/u1/a.jpg", "support/admin/a1/b.jpg", "support/u2/c.jpg");
    }
}
