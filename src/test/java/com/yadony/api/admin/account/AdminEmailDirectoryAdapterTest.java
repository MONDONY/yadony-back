package com.yadony.api.admin.account;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminEmailDirectoryAdapterTest {

    private final AdminUserRepository repository = mock(AdminUserRepository.class);
    private final AdminEmailDirectoryAdapter adapter = new AdminEmailDirectoryAdapter(repository);

    @Test
    void rendLEmailDesAdministrateursConnus_etOmetLesAutres() {
        UUID known = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        AdminUserEntity admin = new AdminUserEntity("uid", "a@yadony.test", AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", known);
        when(repository.findAllById(Set.of(known, deleted))).thenReturn(List.of(admin));

        assertThat(adapter.emailsOf(Set.of(known, deleted)))
                .containsExactlyEntriesOf(java.util.Map.of(known, "a@yadony.test"));
    }

    @Test
    void sansIdentifiant_aucuneRequete() {
        assertThat(adapter.emailsOf(Set.of())).isEmpty();
        assertThat(adapter.emailsOf(null)).isEmpty();
        verifyNoInteractions(repository);
    }
}
