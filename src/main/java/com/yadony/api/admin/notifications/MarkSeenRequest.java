package com.yadony.api.admin.notifications;

import java.time.OffsetDateTime;

/** Corps optionnel de {@code POST /admin/notifications/mark-seen} ; {@code upTo} absent = maintenant. */
public record MarkSeenRequest(OffsetDateTime upTo) {}
