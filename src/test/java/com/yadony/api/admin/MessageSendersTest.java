package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageSendersTest {

    @Mock UserRepository userRepo;

    private static UserEntity user(UUID id, String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        return u;
    }

    @Test
    void asUserId_uuidSeulement() {
        UUID id = UUID.randomUUID();
        assertThat(MessageSenders.asUserId(id.toString())).isEqualTo(id);
        assertThat(MessageSenders.asUserId("fbUid123")).isNull();
        assertThat(MessageSenders.asUserId("SYSTEM")).isNull();
        assertThat(MessageSenders.asUserId(null)).isNull();
    }

    @Test
    void asFirebaseUid_niUuidNiSystemeNiVide() {
        assertThat(MessageSenders.asFirebaseUid("fbUid123")).isEqualTo("fbUid123");
        assertThat(MessageSenders.asFirebaseUid(UUID.randomUUID().toString())).isNull();
        assertThat(MessageSenders.asFirebaseUid("SYSTEM")).isNull();
        assertThat(MessageSenders.asFirebaseUid("  ")).isNull();
        assertThat(MessageSenders.asFirebaseUid(null)).isNull();
    }

    @Test
    void resolve_melangeUidEtUuid_deuxRequetesIndexeesParSenderId() {
        UUID legacyId = UUID.randomUUID();
        UserEntity legacy = user(legacyId, "uidLegacy");
        UserEntity current = user(UUID.randomUUID(), "uidCourant");
        when(userRepo.findAllById(any())).thenReturn(List.of(legacy));
        when(userRepo.findAllByFirebaseUidIn(any())).thenReturn(List.of(current));

        Map<String, UserEntity> map = MessageSenders.resolve(userRepo,
                List.of(legacyId.toString(), "uidCourant", "uidCourant", "SYSTEM", "uidInconnu"));

        assertThat(map).containsOnlyKeys(legacyId.toString(), "uidCourant");
        assertThat(map.get(legacyId.toString())).isSameAs(legacy);
        assertThat(map.get("uidCourant")).isSameAs(current);
        verify(userRepo).findAllByFirebaseUidIn(argThat((Collection<String> uids) ->
                uids.equals(Set.of("uidCourant", "uidInconnu"))));
    }

    @Test
    void resolve_uniquementUid_pasDeRequeteParId() {
        when(userRepo.findAllByFirebaseUidIn(any())).thenReturn(List.of(user(UUID.randomUUID(), "uidA")));

        assertThat(MessageSenders.resolve(userRepo, List.of("uidA"))).containsOnlyKeys("uidA");
        verify(userRepo, never()).findAllById(any());
    }

    @Test
    void resolve_systemeSeulement_aucuneRequete() {
        assertThat(MessageSenders.resolve(userRepo, List.of("SYSTEM"))).isEmpty();
        verifyNoInteractions(userRepo);
    }

    @Test
    void resolve_ignoreLesComptesSansIdentifiant() {
        when(userRepo.findAllById(any())).thenReturn(List.of(user(null, "x")));
        when(userRepo.findAllByFirebaseUidIn(any())).thenReturn(List.of(user(UUID.randomUUID(), null)));

        assertThat(MessageSenders.resolve(userRepo, List.of(UUID.randomUUID().toString(), "uidA"))).isEmpty();
    }
}
