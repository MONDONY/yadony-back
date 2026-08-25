package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserListItemResponseTest {

    @Test
    @DisplayName("l'email du contact Firebase est repris dans le DTO de liste")
    void from_carriesEmailFromContact() {
        UserEntity user = new UserEntity();
        user.setFirstName("Jean");
        var contact = new FirebaseContactService.Contact("+33600000000", "jean@example.com");

        AdminUserListItemResponse dto = AdminUserListItemResponse.from(user, contact);

        assertThat(dto.email()).isEqualTo("jean@example.com");
        assertThat(dto.phoneNumber()).isEqualTo("+33600000000");
    }

    @Test
    @DisplayName("un contact vide donne un email nul plutôt qu'une exception")
    void from_toleratesEmptyContact() {
        UserEntity user = new UserEntity();

        AdminUserListItemResponse dto =
                AdminUserListItemResponse.from(user, FirebaseContactService.Contact.EMPTY);

        assertThat(dto.email()).isNull();
    }
}
