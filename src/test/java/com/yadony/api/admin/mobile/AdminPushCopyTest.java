package com.yadony.api.admin.mobile;

import com.yadony.api.admin.notifications.AdminNotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPushCopyTest {

    @Test
    void copy_titles_neverContainPersonalData() {
        for (AdminNotificationType type : AdminNotificationType.values()) {
            String title = AdminPushCopy.titleFor(type);
            assertThat(title).as(type.name()).isNotBlank().doesNotContainPattern("\\d");
            assertThat(title).doesNotContain("@");
        }
        assertThat(AdminPushCopy.titleFor(AdminNotificationType.ADMIN_ALERT)).isEqualTo("Nouvelle alerte");
        assertThat(AdminPushCopy.titleFor(AdminNotificationType.DISPUTE_OPENED)).isEqualTo("Nouveau litige");
    }
}
