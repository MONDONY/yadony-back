package com.yadony.api.support;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Pièces jointes du support (utilisateur et admin) : {@code support_message_attachments.object_key}. */
@Component
public class SupportAttachmentReferenceSource implements MediaKeyReferenceSource {

    private final SupportMessageAttachmentRepository attachments;

    public SupportAttachmentReferenceSource(SupportMessageAttachmentRepository attachments) {
        this.attachments = attachments;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("support/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, attachments.findObjectKeyValuesMatchingIncludingDeleted(prefix));
    }
}
