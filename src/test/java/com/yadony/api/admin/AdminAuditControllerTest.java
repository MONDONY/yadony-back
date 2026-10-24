package com.yadony.api.admin;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditControllerTest {

    @Mock AuditLogRepository auditRepo;
    @Mock UserRepository userRepo;

    private AdminAuditController controller() {
        return new AdminAuditController(auditRepo, userRepo);
    }

    @Test
    void list_returnsPage() {
        AuditLogEntity entity = new AuditLogEntity();
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entity));
        when(auditRepo.findFiltered(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(page);

        ResponseEntity<?> resp = controller().list(null, null, null, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void list_withActorId_resolvesActorName() {
        UUID actorId = UUID.randomUUID();
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActorId(actorId);
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entity));
        when(auditRepo.findFiltered(isNull(), isNull(), isNull(), eq(actorId.toString()), isNull(), isNull(), any()))
            .thenReturn(page);
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setFirstName("Alice");
        user.setLastName("D.");
        when(userRepo.findAllById(anyIterable())).thenReturn(List.of(user));

        ResponseEntity<?> resp = controller().list(null, null, null, actorId.toString(), null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(userRepo, times(1)).findAllById(any());
    }

    @Test
    void list_withActorHavingNullLastName_doesNotThrow() {
        UUID actorId = UUID.randomUUID();
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActorId(actorId);
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entity));
        when(auditRepo.findFiltered(isNull(), isNull(), isNull(), eq(actorId.toString()), isNull(), isNull(), any()))
            .thenReturn(page);
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setFirstName("Alice");
        user.setLastName(null);
        when(userRepo.findAllById(anyIterable())).thenReturn(List.of(user));

        ResponseEntity<?> resp = controller().list(null, null, null, actorId.toString(), null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void list_withEntityId_passesItToTheFilter() {
        UUID entityId = UUID.randomUUID();
        when(auditRepo.findFiltered(isNull(), eq("PAYMENT"), eq(entityId.toString()), isNull(), isNull(), isNull(),
                any())).thenReturn(new PageImpl<>(List.of(new AuditLogEntity())));

        ResponseEntity<?> resp = controller().list(null, "PAYMENT", entityId, null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(auditRepo).findFiltered(isNull(), eq("PAYMENT"), eq(entityId.toString()), isNull(), isNull(),
                isNull(), any());
    }
}
